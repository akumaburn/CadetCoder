package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class ShellOutputBufferTest {

    @Test
    public void appendLine_addsCompleteLines() {
        ShellOutputBuffer buf = new ShellOutputBuffer(100);
        buf.appendLine("alpha");
        buf.appendLine("beta");
        assertThat(buf.snapshot()).containsExactly("alpha", "beta");
        assertThat(buf.size()).isEqualTo(2);
    }

    @Test
    public void append_splitsEmbeddedNewlines() {
        ShellOutputBuffer buf = new ShellOutputBuffer(100);
        buf.append("one\ntwo\nthree\n");
        assertThat(buf.snapshot()).containsExactly("one", "two", "three");
    }

    @Test
    public void append_holdsPartialLineUntilCompleted() {
        ShellOutputBuffer buf = new ShellOutputBuffer(100);
        buf.append("par");
        // Partial line is visible in the snapshot but not yet a committed line.
        assertThat(buf.snapshot()).containsExactly("par");
        buf.append("tial\n");
        assertThat(buf.snapshot()).containsExactly("partial");
    }

    @Test
    public void append_preservesBlankLines() {
        ShellOutputBuffer buf = new ShellOutputBuffer(100);
        buf.append("a\n\nb\n");
        assertThat(buf.snapshot()).containsExactly("a", "", "b");
    }

    @Test
    public void buffer_capsToMaxLinesDroppingOldest() {
        ShellOutputBuffer buf = new ShellOutputBuffer(3);
        for (int i = 1; i <= 6; i++) {
            buf.appendLine("line" + i);
        }
        assertThat(buf.snapshot()).containsExactly("line4", "line5", "line6");
        assertThat(buf.size()).isEqualTo(3);
    }

    @Test
    public void clear_removesEverythingIncludingPending() {
        ShellOutputBuffer buf = new ShellOutputBuffer(100);
        buf.appendLine("kept");
        buf.append("partial");
        buf.clear();
        assertThat(buf.snapshot()).isEmpty();
        assertThat(buf.size()).isZero();
    }

    @Test
    public void append_nullIsIgnored() {
        ShellOutputBuffer buf = new ShellOutputBuffer(100);
        buf.appendLine("x");
        buf.append(null);
        assertThat(buf.snapshot()).containsExactly("x");
    }

    @Test
    public void concurrentAppends_doNotLoseLinesOrThrow() throws Exception {
        ShellOutputBuffer buf     = new ShellOutputBuffer(100_000);
        int               writers = 8;
        int               perWriter = 500;
        CountDownLatch    start   = new CountDownLatch(1);
        CountDownLatch    done    = new CountDownLatch(writers);

        for (int w = 0; w < writers; w++) {
            final int id = w;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        buf.appendLine("w" + id + "-" + i);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        List<String> snapshot = buf.snapshot();
        assertThat(snapshot).hasSize(writers * perWriter);
    }
}
