package com.eonmux.cadetcoder.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Reads a response body into memory and stops at a byte ceiling.
 *
 * <h2>Why the ceiling counts bytes and not characters</h2>
 *
 * <p>A character ceiling bounds nothing: the reader has to decode before it can count, so the
 * decoder has already consumed however much the server chose to send. The raw stream is the only
 * place a limit actually holds.</p>
 *
 * <h2>Why a line-at-a-time ceiling is not one either</h2>
 *
 * <p>Checking the accumulated length after each {@code readLine} bounds nothing, because the line
 * has to be complete before the check can run. A server that sends a gigabyte with no newline in it
 * is read whole, and the check that was meant to stop it never gets a turn.</p>
 *
 * <h2>Why the cut is fed to the decoder as "more may follow"</h2>
 *
 * <p>Byte counting bounds memory but says nothing about where characters begin, so a ceiling of 64
 * lands one byte inside the 22nd three-byte character. Told that is the end of the input, the
 * decoder is obliged to call those bytes malformed and emit a replacement mark, so every truncated
 * page ends in a character the server never sent. Telling it more may follow instead leaves the
 * incomplete tail unconsumed, and it is simply dropped -- which is what truncation means.</p>
 */
public final class BoundedBody {

    /** How much is taken from the socket at a time. */
    private static final int CHUNK_BYTES = 8192;

    private BoundedBody() {
    }

    /**
     * The charset a {@code Content-Type} header declares, e.g.
     * {@code text/html; charset=ISO-8859-1}.
     *
     * @param contentType the header value, or {@code null} when the server sent none
     * @return the declared charset, or UTF-8 when the header is absent, carries no charset, or names
     *         one this JVM does not have
     */
    public static Charset charsetOf(String contentType) {
        if (contentType == null) {
            return StandardCharsets.UTF_8;
        }
        for (String part : contentType.split(";")) {
            String token = part.trim();
            if (token.regionMatches(true, 0, "charset=", 0, "charset=".length())) {
                return named(token.substring("charset=".length()).trim());
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** One charset name, unquoted, falling back to UTF-8 rather than failing the fetch. */
    private static Charset named(String name) {
        String unquoted = name;
        if (unquoted.length() >= 2 && unquoted.charAt(0) == '"'
            && unquoted.charAt(unquoted.length() - 1) == '"') {
            unquoted = unquoted.substring(1, unquoted.length() - 1);
        }
        try {
            return Charset.forName(unquoted);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * Reads {@code raw} into {@code out}, decoding through {@code charset} and stopping once
     * {@code limitBytes} bytes have been taken from the stream.
     *
     * @param raw        the response body
     * @param charset    what to decode it as
     * @param limitBytes how much of it to take
     * @param out        where the decoded text goes
     * @return {@code true} when the body was cut at the ceiling, {@code false} when it fitted
     * @throws IOException if the body cannot be read
     */
    public static boolean readInto(InputStream raw, Charset charset, long limitBytes,
                                   StringBuilder out) throws IOException {
        if (raw == null || charset == null || out == null) {
            throw new IllegalArgumentException("a bounded read needs a stream, a charset and "
                                               + "somewhere to put the result");
        }
        CharsetDecoder decoder = charset.newDecoder()
                                        .onMalformedInput(CodingErrorAction.REPLACE)
                                        .onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer pending = ByteBuffer.allocate(CHUNK_BYTES);
        CharBuffer decoded = CharBuffer.allocate(CHUNK_BYTES);
        byte[]     chunk   = new byte[CHUNK_BYTES];

        long taken = 0;
        while (taken < limitBytes && pending.hasRemaining()) {
            int wanted = (int) Math.min(pending.remaining(), limitBytes - taken);
            int read   = raw.read(chunk, 0, wanted);
            if (read == -1) {
                return finish(decoder, pending, decoded, out);
            }
            taken += read;
            pending.put(chunk, 0, read);
            pending.flip();
            drain(decoder, pending, decoded, false, out);
            // Anything the decoder could not yet make sense of is the start of a character whose
            // remaining bytes are in the next chunk, so it is kept for that chunk to complete.
            pending.compact();
        }

        // One byte past the ceiling tells a truncated body from one that happened to end there.
        if (raw.read() == -1) {
            return finish(decoder, pending, decoded, out);
        }
        return true;
    }

    /** The body ended on its own, so whatever is left really is the end of it. */
    private static boolean finish(CharsetDecoder decoder, ByteBuffer pending, CharBuffer decoded,
                                  StringBuilder out) {
        pending.flip();
        drain(decoder, pending, decoded, true, out);
        while (!decoder.flush(decoded).isUnderflow()) {
            emit(decoded, out);
        }
        emit(decoded, out);
        return false;
    }

    /** Decodes everything currently readable, making room whenever the character buffer fills. */
    private static void drain(CharsetDecoder decoder, ByteBuffer pending, CharBuffer decoded,
                              boolean endOfInput, StringBuilder out) {
        CoderResult result;
        do {
            result = decoder.decode(pending, decoded, endOfInput);
            emit(decoded, out);
        } while (result.isOverflow());
    }

    /** Moves what has been decoded so far into the caller's buffer and clears the staging one. */
    private static void emit(CharBuffer decoded, StringBuilder out) {
        decoded.flip();
        out.append(decoded);
        decoded.clear();
    }
}
