package com.eonmux.cadetcoder;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An interrupt must reach the command wherever the command is running.
 *
 * <p>{@code CommandRegistry} decided whether the user had asked to stop by walking the CALLING
 * thread's stack for a frame whose class name contained {@code InteractiveShell}, and reading a
 * system property only if it found one. That is true of the shell's own command thread and of
 * nothing else. A sub-command dispatched by an agent runs on a {@code cadet-worker} thread whose
 * stack starts at {@code Thread.run}, so the check found no such frame, answered "not interrupted"
 * every time it was asked, and F2 did nothing -- for the part of a run that is most likely to be
 * long enough to need stopping.</p>
 */
public class InterruptReachesEveryThreadTest {

    /**
     * A command that keeps working until something tells it not to.
     *
     * <p>Reports {@code 0} when it reaches its own deadline, which is what "nobody ever asked me to
     * stop" looks like from the outside.</p>
     */
    private static final class SpinningCommand implements CommandRegistry.InterruptibleCommand {

        private final long spinMillis;

        private SpinningCommand(long spinMillis) {
            this.spinMillis = spinMillis;
        }

        @Override
        public int execute(String[] args) {
            long deadline = System.currentTimeMillis() + spinMillis;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 130;
                }
            }
            return 0;
        }

        @Override
        public String getUsage() {
            return "spin";
        }

        @Override
        public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        }
    }

    private ExecutorService   workers;
    private TestOutputCapture output;

    @Before
    public void setUp() {
        // Named and shaped like the pool an agent's sub-commands actually run on: a plain thread
        // with no shell frame anywhere beneath it.
        workers = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cadet-worker-1");
            thread.setDaemon(true);
            return thread;
        });
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
        workers.shutdownNow();
        InterruptSignal.clear();
    }

    private int runOnAWorkerThread(CommandRegistry.InterruptibleCommand command) throws Exception {
        return workers.submit(
                () -> CommandRegistry.executeInterruptibleCommand(command, new String[0]))
                      .get(15, TimeUnit.SECONDS);
    }

    @Test
    public void aCommandRunningFarFromTheShellStillStops() throws Exception {
        InterruptSignal.request();

        assertThat(runOnAWorkerThread(new SpinningCommand(10_000)))
                .as("the user asked for this to stop; it does not matter which thread it is on")
                .isEqualTo(130);
    }

    @Test
    public void aCommandNobodyInterruptedRunsToCompletion() throws Exception {
        assertThat(runOnAWorkerThread(new SpinningCommand(150)))
                .as("nothing asked this to stop")
                .isEqualTo(0);
    }

    @Test
    public void aWithdrawnRequestDoesNotStopTheNextCommand() throws Exception {
        InterruptSignal.request();
        InterruptSignal.clear();

        assertThat(runOnAWorkerThread(new SpinningCommand(150)))
                .as("the request was withdrawn before this command started")
                .isEqualTo(0);
    }

    /**
     * The stack is not where the answer lives.
     *
     * <p>Stated as a source rule because the defect is invisible from any single call site: the
     * check worked from the shell, which is where it was written and tried, and silently answered
     * "no" everywhere else.</p>
     */
    @Test
    public void nothingDecidesAnInterruptByLookingAtTheStack() throws java.io.IOException {
        java.util.List<String> offenders = new java.util.ArrayList<>();
        java.nio.file.Path     sources   = java.nio.file.Paths.get("src", "main", "java");

        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(sources)) {
            for (java.nio.file.Path file
                    : (Iterable<java.nio.file.Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String source = java.nio.file.Files.readString(file);
                if (source.contains("cadet.interrupt.requested")) {
                    offenders.add(file + " passes the interrupt through a system property");
                }
                if (file.getFileName().toString().equals("CommandRegistry.java")
                    && source.contains("getStackTrace()")) {
                    offenders.add(file + " decides something by sniffing the stack");
                }
            }
        }

        assertThat(offenders).isEmpty();
    }
}
