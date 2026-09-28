package com.eonmux.cadetcoder.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/**
 * One image on its way to a model.
 *
 * <h2>Why the bytes are kept encoded</h2>
 *
 * <p>Every wire that takes an image takes it as base64 in a JSON body -- Anthropic under
 * {@code source.data}, Chat Completions inside a {@code data:} URL, Google under
 * {@code inlineData.data}, Bedrock Converse under {@code source.bytes}. Encoding once here means
 * the four backends differ only in where they put the string, and it makes this value class
 * immutable, which an array of bytes handed round four call sites is not.</p>
 *
 * <h2>Why the type is sniffed rather than read off the name</h2>
 *
 * <p>The provider rejects a body whose declared media type does not match the bytes, and the name
 * is whatever the file was called. A screenshot saved as {@code shot.jpg} that is really a PNG is
 * ordinary, and costs a 400 from every provider here. The first bytes of the file say what it is,
 * and they are what the four formats each provider accepts can be told apart by.</p>
 */
public final class PromptImage {

    /**
     * The most encoded bytes an image may carry before the providers start refusing it.
     *
     * <h2>Why the limit is stated on the encoded size</h2>
     *
     * <p>Every provider here states its image limit on the base64 string rather than on the file.
     * Anthropic documents 10 MB per image on its own API and 5 MB on Amazon Bedrock and on Google
     * Cloud, and 5 MB is the lowest of those. A request that costs a round trip to be told it was
     * too big is worse than one that was never sent, so the floor is the number checked.</p>
     */
    public static final int MAX_ENCODED_BYTES = 5 * 1024 * 1024;

    /**
     * The most bytes an image file may hold to stay inside {@link #MAX_ENCODED_BYTES}.
     *
     * <h2>Why the file is measured against a smaller number than the limit</h2>
     *
     * <p>Base64 writes four characters for every three bytes, so a file encodes to a third more
     * than it holds. The limit was checked against the file size while the providers check it
     * against the encoding, so a 5 MB screenshot passed here and arrived as 6.7 MB -- over the 5 MB
     * that Anthropic on Bedrock and on Google Cloud takes. The provider refused it, and the
     * question that came with it was lost. Three quarters of the encoded limit is the largest
     * file that cannot do that.</p>
     */
    public static final int MAX_BYTES = MAX_ENCODED_BYTES / 4 * 3;

    private final String name;
    private final String mediaType;
    private final String base64;

    private PromptImage(String name, String mediaType, String base64) {
        this.name      = name;
        this.mediaType = mediaType;
        this.base64    = base64;
    }

    /**
     * Reads an image file.
     *
     * @param file the file to read
     * @return the image
     * @throws IOException              when the file cannot be read
     * @throws IllegalArgumentException when it is not an image any provider here takes, or is
     *                                  larger than {@link #MAX_BYTES}
     */
    public static PromptImage of(Path file) throws IOException {
        // Asked before the file is read, not after. Dropping a disk image or a video on the prompt
        // is an easy mistake, and reading one into a byte array to measure it ends the session with
        // an OutOfMemoryError rather than a message about the size.
        long size = Files.size(file);
        if (size > MAX_BYTES) {
            throw new IllegalArgumentException(
                    file.getFileName() + " is " + megabytes(size)
                    + " MB; the limit is " + megabytes(MAX_BYTES) + " MB");
        }
        byte[] bytes = Files.readAllBytes(file);
        String type  = mediaTypeOf(bytes);
        if (type == null) {
            throw new IllegalArgumentException(
                    file.getFileName() + " is not a PNG, JPEG, GIF or WebP image");
        }
        Path fileName = file.getFileName();
        return new PromptImage(fileName == null ? "image" : fileName.toString(), type,
                               Base64.getEncoder().encodeToString(bytes));
    }

    /**
     * A size in megabytes, for a message a person has to act on.
     *
     * <p>Rounded to one place rather than divided as integers. Integer division told the user that
     * a 5.4 MB file "is 5 MB; the limit is 5 MB" -- a sentence that reads as a bug in the tool
     * rather than as a file to shrink, and that gives no idea how much to shrink it by.</p>
     *
     * @param bytes the size in bytes
     * @return the size in megabytes, to one decimal place
     */
    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }

    /**
     * Whether a file's first bytes are an image one of these providers takes.
     *
     * @param bytes the head of the file, or all of it
     * @return whether it is a PNG, JPEG, GIF or WebP
     */
    public static boolean looksLikeImage(byte[] bytes) {
        return mediaTypeOf(bytes) != null;
    }

    /**
     * What an image's first bytes say it is.
     *
     * @param bytes the head of the file, or all of it
     * @return the media type, or {@code null} when these bytes are not one of the four
     */
    static String mediaTypeOf(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        if (startsWith(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(bytes, 'G', 'I', 'F', '8')) {
            return "image/gif";
        }
        // RIFF....WEBP: the four bytes between are the file size, so they are stepped over.
        if (startsWith(bytes, 'R', 'I', 'F', 'F')
            && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, int... head) {
        if (bytes.length < head.length) {
            return false;
        }
        for (int i = 0; i < head.length; i++) {
            if ((bytes[i] & 0xFF) != (head[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /** The file this came from, for saying which image a message is about. */
    public String name() {
        return name;
    }

    /** The media type the bytes say it is, e.g. {@code image/png}. */
    public String mediaType() {
        return mediaType;
    }

    /** The bytes, base64-encoded, which is how every wire here carries them. */
    public String base64() {
        return base64;
    }

    /** The {@code data:} URL Chat Completions puts in {@code image_url}. */
    public String dataUrl() {
        return "data:" + mediaType + ";base64," + base64;
    }

    /**
     * What Bedrock Converse calls this type.
     *
     * <p>Converse names the format on its own, without the {@code image/} part, and takes only
     * these four.</p>
     *
     * @return {@code png}, {@code jpeg}, {@code gif} or {@code webp}
     */
    public String bedrockFormat() {
        return mediaType.substring("image/".length()).toLowerCase(Locale.ROOT);
    }

    /** Roughly how much was read, for a message about size. */
    public int byteCount() {
        // Base64 is four characters per three bytes, less whatever padding the tail carries.
        int padding = base64.endsWith("==") ? 2 : base64.endsWith("=") ? 1 : 0;
        return base64.length() / 4 * 3 - padding;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof PromptImage that
               && mediaType.equals(that.mediaType)
               && base64.equals(that.base64)
               && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, mediaType, base64);
    }

    @Override
    public String toString() {
        return name + " (" + mediaType + ", " + byteCount() + " bytes)";
    }
}
