package com.eonmux.cadetcoder.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stream that stands in for {@code System.out} while the shell is running.
 *
 * <p>It was a {@link PrintStream} subclass overriding the handful of methods this code base happens
 * to call. Everything else — {@code print(Object)}, {@code print(char[])},
 * {@link Throwable#printStackTrace(PrintStream)}, anything going through a {@link PrintWriter} —
 * reached it as raw bytes through {@code write(byte[], int, int)}, which decoded each chunk on its
 * own. A character whose bytes straddled two chunks became replacement characters, and
 * {@code write(int)} cast a single byte to a {@code char}, so every byte above 0x7F was mojibake by
 * construction.</p>
 */
class CapturingStreamTest {

    private final List<String> received = new ArrayList<>();

    private PrintStream stream() {
        return OutputRouter.capturingStream(received::add);
    }

    private String all() {
        return String.join("", received);
    }

    @Test
    @DisplayName("A character whose bytes arrive in two writes is not torn in half")
    void aSplitCharacterSurvives() {
        byte[] tick = "✓".getBytes(StandardCharsets.UTF_8);
        assertThat(tick).hasSizeGreaterThan(1);

        PrintStream out = stream();
        out.write(tick, 0, 1);            // the encoder can flush mid-character on a buffer edge
        out.write(tick, 1, tick.length - 1);
        out.flush();

        assertThat(all())
                .as("the marker every status line begins with must not decode as two '?'s")
                .isEqualTo("✓");
    }

    @Test
    @DisplayName("A single byte above 0x7F is a fragment of a character, not a character")
    void singleByteWritesAreNotTreatedAsCharacters() {
        byte[] arrow = "▸".getBytes(StandardCharsets.UTF_8);

        PrintStream out = stream();
        for (byte b : arrow) {
            out.write(b);
        }
        out.flush();

        assertThat(all()).isEqualTo("▸");
    }

    @Test
    @DisplayName("Every way of printing arrives, not only the overridden ones")
    void everyPrintStreamEntryPointIsCarried() {
        PrintStream out = stream();
        out.print("plain ");
        out.print(42);                 // print(int)
        out.print(new char[]{' ', 'c'});
        out.println((Object) " obj");  // println(Object)
        out.printf(" %s%n", "fmt");
        out.flush();

        assertThat(all()).isEqualTo("plain 42 c obj\n fmt" + System.lineSeparator());
    }

    @Test
    @DisplayName("A stack trace reaches the transcript whole")
    void aStackTraceIsCarried() {
        PrintStream out = stream();
        new IllegalStateException("boom").printStackTrace(out);
        out.flush();

        assertThat(all())
                .contains("java.lang.IllegalStateException: boom")
                .contains("aStackTraceIsCarried");
    }

    @Test
    @DisplayName("Text written through a PrintWriter keeps its non-ASCII characters")
    void printWriterOutputIsCarried() {
        PrintWriter writer = new PrintWriter(stream());
        writer.println("─── ▎ heading ───");
        writer.flush();

        assertThat(all()).contains("─── ▎ heading ───");
    }

    @Test
    @DisplayName("Output larger than the encoder's own buffer is carried intact")
    void aLargeMultiByteBodySurvivesTheBufferBoundary() {
        // The encoder hands its bytes over in ~8KB blocks, so a body this size is guaranteed to
        // split at least one character across two writes -- which is the case that used to corrupt.
        String block = "✓ ▸ ─ ⚠ ".repeat(4_000);

        PrintStream out = stream();
        out.print(block);
        out.flush();

        assertThat(all()).isEqualTo(block);
        assertThat(all()).doesNotContain("�");
    }

    @Test
    @DisplayName("Bytes that are not valid UTF-8 degrade to a replacement rather than derailing")
    void invalidBytesDoNotBreakTheStream() {
        PrintStream out = stream();
        out.write(new byte[]{(byte) 0xC3, (byte) 0x28}, 0, 2); // a truncated two-byte sequence
        out.print("after");
        out.flush();

        assertThat(all()).endsWith("after");
    }
}
