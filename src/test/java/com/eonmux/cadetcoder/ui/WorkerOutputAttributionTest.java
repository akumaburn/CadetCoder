package com.eonmux.cadetcoder.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Everything a worker prints belongs to that worker.
 *
 * <p>Workers run several agent loops at once, so their output is collected per thread rather than
 * streamed — that is what keeps concurrent runs attributable. Only {@code println} took part in it.
 * A worker's errors went out through {@code printlnErr}, its partial lines through {@code print},
 * its formatted output through {@code printf}, and picocli's usage text through a
 * {@link PrintWriter} — and all four escaped into the shell's own transcript, where they appeared
 * with nothing saying which worker had said them.</p>
 */
class WorkerOutputAttributionTest {

    @Test
    @DisplayName("An error line is collected with the rest of the worker's output")
    void errorsAreAttributedToTheWorkerThatPrintedThem() {
        List<String> lines = OutputCapture.collect(() -> {
            UnifiedOutput.println("checking");
            UnifiedOutput.printlnErr("could not read pom.xml");
        });

        assertThat(lines).containsExactly("checking", "could not read pom.xml");
    }

    @Test
    @DisplayName("Formatted output is collected too")
    void printfIsAttributed() {
        List<String> lines = OutputCapture.collect(() -> UnifiedOutput.printf("%d of %d%n", 2, 5));

        assertThat(lines).containsExactly("2 of 5");
    }

    @Test
    @DisplayName("Fragments printed without a newline become one line, not one line each")
    void partialLinesAreAssembledRatherThanSplit() {
        List<String> lines = OutputCapture.collect(() -> {
            UnifiedOutput.print("Worker ");
            UnifiedOutput.print("2 ");
            UnifiedOutput.println("finished");
        });

        assertThat(lines).containsExactly("Worker 2 finished");
    }

    @Test
    @DisplayName("A last fragment with no newline is still something the worker said")
    void anUnterminatedTailIsNotDropped() {
        List<String> lines = OutputCapture.collect(() -> UnifiedOutput.print("no newline here"));

        assertThat(lines).containsExactly("no newline here");
    }

    @Test
    @DisplayName("Usage text rendered into a writer is collected")
    void writerOutputIsAttributed() {
        List<String> lines = OutputCapture.collect(() -> {
            PrintWriter out = UnifiedOutput.getPrintWriter();
            out.println("Usage: grep [-i] PATTERN");
            out.flush();
        });

        assertThat(lines).containsExactly("Usage: grep [-i] PATTERN");
    }

    @Test
    @DisplayName("An error writer is collected as well")
    void errorWriterOutputIsAttributed() {
        List<String> lines = OutputCapture.collect(() -> {
            PrintWriter err = UnifiedOutput.getErrorWriter();
            err.println("Unknown option: --nope");
            err.flush();
        });

        assertThat(lines).containsExactly("Unknown option: --nope");
    }

    @Test
    @DisplayName("Output that never went through UnifiedOutput is attributed too")
    void theRouterAttributesWhateverReachesIt() {
        // The router replaces System.out for the whole process while the shell runs, so this is the
        // point where anything printed by any means at all -- a bare System.out.print in a command,
        // a library writing to the stream -- meets the per-thread capture.
        List<String> lines = OutputCapture.collect(() -> {
            OutputRouter.getInstance().appendOutputPublic("raw line one\nraw line ");
            OutputRouter.getInstance().appendOutputPublic("two\n");
        });

        assertThat(lines).containsExactly("raw line one", "raw line two");
    }

    @Test
    @DisplayName("Nothing is collected outside a capture")
    void anUncapturedThreadIsUntouched() {
        assertThat(OutputCapture.isCapturing()).isFalse();
        assertThat(OutputCapture.offerChunk("goes to the console")).isFalse();
    }

    @Test
    @DisplayName("A chunk spanning several lines becomes several lines")
    void multiLineChunksAreSplit() {
        List<String> lines = OutputCapture.collect(() -> UnifiedOutput.print("a\nb\nc\n"));

        assertThat(lines).containsExactly("a", "b", "c");
    }
}
