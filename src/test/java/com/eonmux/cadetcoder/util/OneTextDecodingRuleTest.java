package com.eonmux.cadetcoder.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What charset a project's files are read in is a fact about the files, not about the JVM.
 *
 * <p>{@code grep} decoded as UTF-8 and told the user which files it had to substitute characters in.
 * {@code ContextEngine} wrote {@code new String(bytes)} in both of the methods that index one, and
 * so did {@code edit} when it loaded the README into the prompt -- and that constructor decodes with
 * whatever charset the JVM started with. Under {@code LANG=C} or {@code LANG=POSIX}, which is what a
 * cron job, a container and most CI runners give a process, that is US-ASCII: every accented letter,
 * every symbol and every CJK character in the project became U+FFFD before it reached the index. So
 * {@code search} returned garbled snippets, and {@code edit} and {@code agent} sent those snippets
 * to the provider as the user's own code.</p>
 *
 * <p>The rule is now stated once, in {@link TextFiles}, beside the rule for what counts as text.</p>
 */
public class OneTextDecodingRuleTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    /** Text no non-Unicode charset can carry. */
    private static final String NON_ASCII = "café — 日本語 — ✓";

    @Test
    public void aFilesTextIsItsUtf8Text() throws IOException {
        Path file = tempFolder.newFile("notes.md").toPath();
        Files.write(file, NON_ASCII.getBytes(StandardCharsets.UTF_8));

        assertThat(TextFiles.readText(file))
                .as("what the file holds, whatever charset this JVM started with")
                .isEqualTo(NON_ASCII);
    }

    /**
     * A file that is not valid UTF-8 is still read.
     *
     * <p>A single Latin-1 byte is not a reason to fail an index run or a search. The bad byte is
     * replaced and everything around it survives, which is what {@code grep} already did.</p>
     */
    @Test
    public void aByteThatIsNotUtf8IsReplacedRatherThanThrown() throws IOException {
        Path file = tempFolder.newFile("latin1.txt").toPath();
        Files.write(file, new byte[] {'c', 'a', 'f', (byte) 0xE9, ' ', 'a', 'u', ' ', 'l', 'a', 'i', 't'});

        assertThat(TextFiles.readText(file))
                .isEqualTo("caf� au lait");
    }

    @Test
    public void anEmptyFileReadsAsEmptyText() throws IOException {
        assertThat(TextFiles.readText(tempFolder.newFile("empty.txt").toPath())).isEmpty();
    }

    /** The streaming form decodes exactly as the whole-file form does. */
    @Test
    public void theStreamingDecoderAnswersTheSameWay() throws IOException {
        Path file = tempFolder.newFile("streamed.txt").toPath();
        Files.write(file, NON_ASCII.getBytes(StandardCharsets.UTF_8));

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(Files.newInputStream(file), TextFiles.lossyUtf8Decoder()))) {
            assertThat(reader.readLine()).isEqualTo(NON_ASCII);
        }
    }

    /** A decoder is stateful, so each caller must get its own. */
    @Test
    public void everyCallerGetsItsOwnDecoder() {
        assertThat(TextFiles.lossyUtf8Decoder()).isNotSameAs(TextFiles.lossyUtf8Decoder());
    }

    /**
     * Nothing decides for itself what charset a file is in.
     *
     * <p>{@code new String(bytes)} is the shape this went wrong in three times, and it is invisible
     * on a developer machine whose JVM already starts in UTF-8 -- the same code is right there and
     * wrong everywhere else. So it is looked for rather than waited for.</p>
     */
    @Test
    public void nothingBuildsTextFromFileBytesWithoutNamingTheCharset() throws IOException {
        try (Stream<Path> sources = Files.walk(Paths.get("src", "main", "java"))) {
            List<String> offenders = new ArrayList<>();
            sources.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> decodesWithoutACharset(path, offenders));

            assertThat(offenders)
                    .as("UTF-8 is the rule; the platform default is whatever the caller was started with")
                    .isEmpty();
        }
    }

    private static final Pattern STRING_CONSTRUCTION = Pattern.compile("\\bnew\\s+String\\s*\\(");

    private static void decodesWithoutACharset(Path source, List<String> offenders) {
        String text = withoutComments(read(source));
        Matcher constructions = STRING_CONSTRUCTION.matcher(text);
        while (constructions.find()) {
            int    closed    = matching(text, constructions.end() - 1);
            String arguments = closed < 0 ? "" : text.substring(constructions.end(), closed);
            // Bytes going in, and nothing saying what they mean.
            if (arguments.contains("ytes") && !arguments.contains("Charset")
                && !arguments.contains("UTF")) {
                offenders.add(source + ": new String(" + arguments.trim() + ")");
            }
        }
    }

    /** The index of the parenthesis closing the one at {@code start}, or -1 when it is unbalanced. */
    private static int matching(String text, int start) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            if (text.charAt(i) == '(') {
                depth++;
            } else if (text.charAt(i) == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Java source with its comments blanked, so prose about the rule is not read as code. */
    private static String withoutComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + source, e);
        }
    }
}
