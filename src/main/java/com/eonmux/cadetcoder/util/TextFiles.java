package com.eonmux.cadetcoder.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a file holds text worth reading, searching or indexing.
 *
 * <h2>Why it is one class</h2>
 *
 * <p>{@code grep} kept this rule to itself -- 26 text extensions, 34 binary ones and a content
 * sniff, all private to {@code GrepCommand} -- while {@code ContextEngine} asked nothing at all and
 * indexed whatever was under half a megabyte. The same {@code target/classes/Main.class} was
 * therefore skipped by one command and stored whole by the other, and what the index holds is what
 * {@code search} prints and what {@code edit} and {@code agent} put into the prompt they send to
 * the provider. A rule that depends on which command you go through is one rule pretending to be
 * two.</p>
 *
 * <h2>What it decides on</h2>
 *
 * <p>Extension first, because it is free and it is right almost always. When the name says nothing
 * -- {@code a.out}, {@code Makefile}, a stripped executable, a serialized cache -- the leading
 * bytes decide: more than one NUL, or more than a tenth of the sample outside printable ASCII, and
 * it is not text.</p>
 *
 * <p>The sample is a fixed size, so how big the file is never enters the answer. A ceiling above
 * which a large file was called binary without being opened saved nothing -- the sniff reads the
 * same few kilobytes either way -- and cost {@code grep} every multi-megabyte log, dump or export
 * whose name does not settle the question: it was skipped without a word. Callers that cannot
 * afford a large file, such as the index, bound it themselves, where the budget is theirs.</p>
 *
 * <p>UTF-16 is deliberately not text here. It is half NUL bytes, and nothing downstream decodes it:
 * everything that reads a file's content reads it as UTF-8, so calling a UTF-16 file text would
 * produce garbled matches and garbled context rather than none.</p>
 *
 * <h2>How that content is decoded</h2>
 *
 * <p>{@link #readText} and {@link #lossyUtf8Decoder} are here for the same reason the rest of the
 * class is. {@code grep} decoded as UTF-8 and said which files it had to replace characters in,
 * while {@code ContextEngine} wrote {@code new String(bytes)} -- the platform default charset, which
 * on a JVM started under {@code LANG=C} is US-ASCII. Every accented letter, symbol and CJK character
 * in the project was replaced before it reached the index, so {@code search} returned garbled
 * snippets and {@code edit} and {@code agent} sent them to the provider as the user's own code.</p>
 */
public final class TextFiles {

    /** Extensions whose content is text whatever the bytes look like. */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "java", "kt", "py", "js", "html", "css", "xml", "json",
            "properties", "yml", "yaml", "sh", "c", "cpp", "h", "cs",
            "go", "rs", "ts", "rb", "php", "pl", "sql", "log");

    /** Extensions whose content is not text whatever the bytes look like. */
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            // compiled and packaged output
            "class", "jar", "exe", "dll", "so", "dylib", "o", "obj", "lib", "a",
            "pyc", "pyd", "pyo", "bin", "dat",
            // images
            "png", "jpg", "jpeg", "gif", "bmp", "tiff", "ico",
            // documents and archives
            "pdf", "zip", "tar", "gz", "7z", "rar",
            // media
            "mp3", "mp4", "avi", "mov", "wav", "ogg");

    /** Below this a file has too few bytes for the sniff to mean anything. */
    private static final long TOO_SMALL_TO_JUDGE_BYTES = 10L;

    /** How much of the file the sniff looks at. */
    private static final int SAMPLE_BYTES = 4096;

    /** The share of the sample that may sit outside printable ASCII before it is not text. */
    private static final double MAX_NON_TEXT_RATIO = 0.10;

    private TextFiles() {
    }

    /**
     * Reads a text file's content.
     *
     * <p>UTF-8, always, whatever charset the JVM happened to start with -- a project's files do not
     * change encoding because a service started under a different {@code LANG}. A byte that is not
     * valid UTF-8 becomes U+FFFD rather than an exception, so a file with one bad byte is still
     * searchable and still readable to the end.</p>
     *
     * @param file the file to read
     * @return its content
     * @throws IOException if the file cannot be read
     */
    public static String readText(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /**
     * The decoder for reading a text file a piece at a time, for callers that cannot hold it whole.
     *
     * <p>The same rule {@link #readText} applies, in the form a {@code Reader} takes.</p>
     *
     * @return a fresh UTF-8 decoder that replaces what it cannot decode
     */
    public static CharsetDecoder lossyUtf8Decoder() {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    /**
     * Returns a file name's extension, lowercased and without the dot.
     *
     * @param fileName a file name or path; {@code null} yields the empty string
     * @return the extension, or the empty string when there is none
     */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name      = fileName.toLowerCase(Locale.ROOT);
        int    separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    /** Whether the name alone settles that the content is text. */
    public static boolean isKnownTextExtension(String fileName) {
        return TEXT_EXTENSIONS.contains(extensionOf(fileName));
    }

    /** Whether the name alone settles that the content is not text. */
    public static boolean isKnownBinaryExtension(String fileName) {
        return BINARY_EXTENSIONS.contains(extensionOf(fileName));
    }

    /** The extensions {@link #isKnownBinaryExtension} refuses, for callers that enumerate them. */
    public static Set<String> binaryExtensions() {
        return BINARY_EXTENSIONS;
    }

    /** The extensions {@link #isKnownTextExtension} accepts, for callers that enumerate them. */
    public static Set<String> textExtensions() {
        return TEXT_EXTENSIONS;
    }

    /**
     * Whether a file's content may be read as text.
     *
     * <p>A read failure is reported rather than answered. The callers differ in what they owe the
     * user when a file cannot be read -- {@code grep} counts it among the files it could not search,
     * {@code index} among the files it could not read -- and swallowing the failure here turned
     * both of those counts into silence.</p>
     *
     * @param file the file to judge
     * @return whether its content is text
     * @throws IOException if the file cannot be read
     */
    public static boolean isTextFile(Path file) throws IOException {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (isKnownTextExtension(name)) {
            return true;
        }
        if (isKnownBinaryExtension(name)) {
            return false;
        }

        long size = Files.size(file);
        if (size < TOO_SMALL_TO_JUDGE_BYTES) {
            return true;
        }
        return looksLikeText(file, (int) Math.min(SAMPLE_BYTES, size));
    }

    /**
     * Reads the leading bytes and decides whether they are text.
     *
     * @param file       the file to sample
     * @param sampleSize how many bytes to read
     * @return whether the sample reads as text
     * @throws IOException if the file cannot be read
     */
    private static boolean looksLikeText(Path file, int sampleSize) throws IOException {
        byte[] sample;
        int    read;
        try (InputStream in = Files.newInputStream(file)) {
            sample = new byte[sampleSize];
            read   = in.read(sample);
        }
        if (read <= 0) {
            return true;
        }

        int nulls   = 0;
        int nonText = 0;
        for (int i = 0; i < read; i++) {
            byte b = sample[i];
            if (b == 0 && ++nulls > 1) {
                return false;
            }
            boolean printable = (b >= 32 && b <= 126) || b == '\n' || b == '\r' || b == '\t';
            if (!printable) {
                nonText++;
            }
        }
        if ((double) nonText / read <= MAX_NON_TEXT_RATIO) {
            return true;
        }
        // The ratio is a guess; a byte-order mark is the file saying what it is. A short UTF-8 file
        // that opens with one spends three of its handful of bytes on the mark, which fails the
        // ratio on its own, and the declaration is the better evidence.
        return hasUtf8ByteOrderMark(sample, read);
    }

    /**
     * Whether the sample opens with a UTF-8 byte-order mark.
     *
     * @param sample the leading bytes
     * @param read   how many of them were actually read
     * @return whether the file declares itself as UTF-8 text
     */
    private static boolean hasUtf8ByteOrderMark(byte[] sample, int read) {
        return read >= 3 && sample[0] == (byte) 0xEF && sample[1] == (byte) 0xBB
               && sample[2] == (byte) 0xBF;
    }
}
