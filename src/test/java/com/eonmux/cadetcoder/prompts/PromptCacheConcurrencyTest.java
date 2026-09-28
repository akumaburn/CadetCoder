package com.eonmux.cadetcoder.prompts;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The system prompt must reach the provider, on every thread, every time.
 *
 * <p>{@link PromptTemplateEngine} is a process-wide singleton whose cache is read on every LLM
 * completion -- and {@code WorkerPool} runs up to eight agent loops at once, while {@code prompt
 * reload} clears the cache from the shell thread. The cache was a plain {@code HashMap} read with
 * {@code containsKey} followed by {@code get}, so a racing write could satisfy the first and return
 * null from the second. A null template is passed straight through variable substitution, so that
 * request went out with no system prompt at all: no operating principles, and a cacheable prefix
 * that no longer matched.</p>
 *
 * <p>A race cannot be reproduced on demand, so this hammers the real access pattern instead. It is
 * a regression guard, not a proof.</p>
 */
public class PromptCacheConcurrencyTest {

    private static final int READERS = 8;
    private static final int READS_PER_THREAD = 300;

    @Test
    public void aPromptIsNeverEmptyNoMatterWhoIsReadingOrClearing() throws Exception {
        PromptTemplateEngine engine = PromptTemplateEngine.getInstance();
        ConcurrentLinkedQueue<String> problems = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);

        Thread clearer = new Thread(() -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                while (running.get()) {
                    engine.clearCache();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                problems.add("clearCache threw " + e);
            }
        });
        clearer.setDaemon(true);
        clearer.start();

        List<Thread> readers = new java.util.ArrayList<>();
        for (int i = 0; i < READERS; i++) {
            Thread reader = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    for (int n = 0; n < READS_PER_THREAD; n++) {
                        String prompt = engine.getPrompt("system", Map.of());
                        if (prompt == null || prompt.isBlank()) {
                            problems.add("a request would have been sent with no system prompt");
                            return;
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    problems.add("getPrompt threw " + e);
                }
            });
            readers.add(reader);
            reader.start();
        }

        start.countDown();
        for (Thread reader : readers) {
            reader.join(30_000);
        }
        running.set(false);
        clearer.join(5_000);

        assertThat(problems).isEmpty();
    }

    @Test
    public void aCachedPromptIsTheSameOneOnEveryRead() {
        PromptTemplateEngine engine = PromptTemplateEngine.getInstance();

        String first = engine.getPrompt("system", Map.of());
        String second = engine.getPrompt("system", Map.of());

        assertThat(first).isNotBlank();
        assertThat(second).isEqualTo(first);
    }

    @Test
    public void clearingTheCacheDoesNotLeaveItEmptyForTheNextReader() {
        PromptTemplateEngine engine = PromptTemplateEngine.getInstance();

        String before = engine.getPrompt("system", Map.of());
        engine.clearCache();
        String after = engine.getPrompt("system", Map.of());

        assertThat(after).isNotBlank().isEqualTo(before);
    }
}
