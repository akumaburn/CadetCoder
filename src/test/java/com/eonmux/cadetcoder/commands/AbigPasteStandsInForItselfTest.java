package com.eonmux.cadetcoder.commands;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a paste puts in the line, and what is sent in its place.
 *
 * <h2>Why this is tested</h2>
 *
 * <p>A pasted block used to go in as text with its line breaks turned into spaces, so forty lines
 * of a stack trace became one line of four thousand characters. The field scrolls to keep the caret
 * visible, so what was on screen was the tail of the paste: the prompt, the question and anything
 * typed after it were all somewhere off to the left.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That a marker stands for the paste and is put back on the way out; that a short paste is still
 * typed in, because a path is more useful in the line than behind a marker; that a dropped file is
 * kept as its path, quotes and all taken off; that a line with no marker in it is sent exactly as
 * it reads; and that a marker whose paste is gone is left alone rather than dropped, because a line
 * is sent as it reads unless this knows otherwise.</p>
 */
public class AbigPasteStandsInForItselfTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final ShellPastes pastes = new ShellPastes();

    private static String lines(int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            out.append("line ").append(i).append('\n');
        }
        return out.toString();
    }

    @Test
    public void ablockOfOutputIsTooBigToType() {
        assertThat(ShellPastes.standsInFor(lines(40))).isTrue();
        assertThat(ShellPastes.standsInFor("x".repeat(ShellPastes.MAX_INLINE_CHARS + 1))).isTrue();
    }

    @Test
    public void apathOrAcommandLineIsNot() {
        // Short and single-line: worth having in the line, where it can be edited.
        assertThat(ShellPastes.standsInFor("src/main/java/Foo.java")).isFalse();
        assertThat(ShellPastes.standsInFor("mvn -o test -Dtest=Foo")).isFalse();
        assertThat(ShellPastes.standsInFor("")).isFalse();
        assertThat(ShellPastes.standsInFor(null)).isFalse();
    }

    @Test
    public void themarkerSaysHowMuchItStandsFor() {
        assertThat(pastes.keep(lines(40))).isEqualTo("[#1: 40 lines]");
        assertThat(pastes.keep("x".repeat(1203))).isEqualTo("[#2: 1,203 characters]");
    }

    @Test
    public void whatWasPastedIsPutBackOnTheWayOut() {
        String block  = lines(40);
        String marker = pastes.keep(block);

        assertThat(pastes.expand("explain " + marker + " please"))
                .isEqualTo("explain " + block + " please");
    }

    @Test
    public void whatIsTypedAroundAmarkerSurvivesIt() {
        // The point of a marker: the line stays short enough to type in.
        String marker = pastes.keep(lines(40));

        assertThat(pastes.expand(marker).strip()).isEqualTo(lines(40).strip());
        assertThat(pastes.holdsMarker("why does " + marker + " fail")).isTrue();
    }

    @Test
    public void ashortPasteIsStillTypedIn() {
        // One line and short: a path or a command line, which is worth having in the line itself.
        assertThat(pastes.insertionFor("mvn -o test")).isEqualTo("mvn -o test");
        assertThat(pastes.insertionFor("")).isEmpty();
        assertThat(pastes.insertionFor(null)).isEmpty();
    }

    @Test
    public void abigPasteGoesInAsAmarkerAndAdroppedFileAsItsName() throws IOException {
        Path file = folder.newFile("Bar.java").toPath();

        assertThat(pastes.insertionFor(lines(12))).isEqualTo("[#1: 12 lines]");
        assertThat(pastes.insertionFor("'" + file + "' ")).isEqualTo("[#2: Bar.java]");
    }

    @Test
    public void apasteWithAlineBreakInItIsAlwaysAmarker() {
        // Which is why nothing is flattened on the way in: a line break means the paste is behind
        // a marker, and what it stands for keeps its breaks until it is sent.
        assertThat(pastes.insertionFor("ls\nsrc")).isEqualTo("[#1: 2 lines]");
        assertThat(pastes.expand("[#1: 2 lines]")).isEqualTo("ls\nsrc");
    }

    @Test
    public void alineWithNoMarkerIsSentAsItReads() {
        pastes.keep(lines(40));

        assertThat(pastes.expand("ls src")).isEqualTo("ls src");
        assertThat(pastes.holdsMarker("ls src")).isFalse();
    }

    @Test
    public void amarkerNobodyIssuedIsLeftAsItWasTyped() {
        assertThat(pastes.expand("[#7: 40 lines]")).isEqualTo("[#7: 40 lines]");
    }

    @Test
    public void adroppedFileIsKeptAsItsPath() throws IOException {
        Path file = folder.newFile("Foo.java").toPath();

        // A terminal quotes a dropped path and adds a trailing space.
        Path found = ShellPastes.droppedFile("'" + file + "' ");

        assertThat(found).isNotNull();
        assertThat(found).isEqualTo(file.toAbsolutePath());
        assertThat(pastes.keepFile(found)).isEqualTo("[#1: Foo.java]");
        assertThat(pastes.expand("read [#1: Foo.java]"))
                .isEqualTo("read " + file.toAbsolutePath());
    }

    @Test
    public void apathWithAspaceInItIsQuotedWhenItGoesBack() throws IOException {
        Path file = folder.newFolder("my project").toPath().resolve("Foo.java");
        Files.writeString(file, "class Foo {}");

        String marker = pastes.keepFile(ShellPastes.droppedFile(file.toString()));

        assertThat(pastes.expand(marker)).startsWith("'").endsWith("'");
    }

    @Test
    public void textThatIsNotApathIsNotAfile() {
        assertThat(ShellPastes.droppedFile("explain this")).isNull();
        assertThat(ShellPastes.droppedFile(lines(3))).isNull();
        assertThat(ShellPastes.droppedFile("")).isNull();
        assertThat(ShellPastes.droppedFile(null)).isNull();
        assertThat(ShellPastes.droppedFile("/no/such/file/anywhere.txt")).isNull();
    }

    @Test
    public void adirectoryIsNotAfileEither() throws IOException {
        // Dropping a folder pastes its path too, and there is nothing to open at the end of it.
        assertThat(ShellPastes.droppedFile(folder.newFolder("somewhere").toString())).isNull();
    }
}
