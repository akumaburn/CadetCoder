package com.eonmux.cadetcoder;

import com.eonmux.cadetcoder.commands.ModelDispatch;
import com.eonmux.cadetcoder.timers.AgentTimer;
import com.eonmux.cadetcoder.timers.TimerRegistry;
import com.eonmux.cadetcoder.timers.TimerScope;
import com.eonmux.cadetcoder.ui.CapturedRun;
import com.eonmux.cadetcoder.ui.InteractivePrompts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a command is run inside must still be true of the thread it actually runs on.
 *
 * <p><b>The defect</b>: every {@link CommandRegistry.InterruptibleCommand} is dispatched on a
 * thread of its own so that an interrupt can be delivered to it. Nothing the caller had established
 * for its own thread went with it, and four separate things are kept per thread: which output sink
 * is collecting, whose timers this is, whether a model asked for this, and whether this may stop
 * and ask the user a question. So a captured run collected nothing, a timer a worker set was filed
 * under the session instead of under the worker, and {@code commit} asked a question that reached
 * nobody and was answered no on their behalf.</p>
 */
class AcommandKeepsTheThreadItWasCalledOnTest {

    private CommandRegistry registry;

    @BeforeEach
    void setUp() {
        TimerRegistry.clearAll();
        registry = new CommandRegistry();
    }

    @AfterEach
    void tearDown() {
        TimerRegistry.clearAll();
    }

    /** A capture around a command that is interruptible collects what it printed. */
    @Test
    void whatAnInterruptibleCommandPrintsReachesTheCaptureAroundIt() {
        assertThat(registry.getCommands().get("ls"))
                .as("this test is only about the dispatch that runs on its own thread")
                .isInstanceOf(CommandRegistry.InterruptibleCommand.class);

        CapturedRun.Result run = CapturedRun.of(() -> registry.executeCommand("ls", new String[]{"."}));

        assertThat(run.output())
                .as("the listing went to the terminal instead of to whoever asked for it")
                .isNotBlank();
    }

    /** A timer set inside a worker is the worker's, not the session's. */
    @Test
    void atimerSetInsideAnamedScopeBelongsToThatScope() {
        List<AgentTimer> seenByTheWorker = new ArrayList<>();

        TimerScope.in("Worker 1", () -> {
            registry.executeCommand("timer", new String[]{"create", "--every", "5m", "check the build"});
            seenByTheWorker.addAll(TimerRegistry.active());
        });

        assertThat(seenByTheWorker)
                .as("the worker cannot be reminded of a timer filed under somebody else")
                .hasSize(1);
        assertThat(seenByTheWorker.get(0).instruction()).contains("check the build");
    }

    /** And nothing of the worker's is left behind in the session once the worker is gone. */
    @Test
    void anamedScopeLeavesNothingInTheSession() {
        TimerScope.in("Worker 1", () ->
                registry.executeCommand("timer", new String[]{"create", "--every", "5m", "check the build"}));

        assertThat(TimerScope.current()).isEqualTo(TimerScope.SESSION);
        assertThat(TimerRegistry.active())
                .as("a worker's reminder must not outlive the worker in the user's own list")
                .isEmpty();
    }

    /**
     * A command a model asked for may not stop and ask, wherever it ends up running.
     *
     * <p><b>The defect</b>: the scope that says "a model asked for this, so do not ask anybody
     * anything" was not carried across the handover. On the new thread the question fell back to
     * the {@code cadet.interactive} property, which reads true inside the interactive shell -- and
     * that is exactly where an agent loop runs. {@code commit}, {@code edit} and {@code refactor}
     * are all interruptible and all ask for confirmation, so each of them put its question to a
     * user who never saw it; nobody could answer, the safe default is no, and the model was told
     * its commit had been declined. It then fell back to running {@code git commit} through the
     * shell, which skips every check {@code commit} performs.</p>
     */
    @Test
    void acommandTheModelAskedForDoesNotStopToAskOnItsOwnThread() {
        String wasInteractive = System.setProperty(InteractivePrompts.PROPERTY, "true");
        List<Boolean> mayAskOverThere = new ArrayList<>();
        try {
            ModelDispatch.run("read", () -> {
                Thread onItsOwnThread = new Thread(ThreadHandover.carrying(
                        () -> mayAskOverThere.add(InteractivePrompts.isOn())));
                onItsOwnThread.start();
                try {
                    onItsOwnThread.join();
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
                return 0;
            });
        } finally {
            if (wasInteractive == null) {
                System.clearProperty(InteractivePrompts.PROPERTY);
            } else {
                System.setProperty(InteractivePrompts.PROPERTY, wasInteractive);
            }
        }

        assertThat(mayAskOverThere)
                .as("a question asked there is collected into the transcript, not shown to anyone")
                .containsExactly(false);
    }

    /** And the scope is the caller's, so a thread handed nothing is still nobody's model run. */
    @Test
    void athreadHandedWorkNobodyAskedForIsStillNobodys() {
        List<Boolean> modelDrivenOverThere = new ArrayList<>();

        Thread onItsOwnThread = new Thread(ThreadHandover.carrying(
                () -> modelDrivenOverThere.add(InteractivePrompts.isModelDrivenWork())));
        onItsOwnThread.start();
        try {
            onItsOwnThread.join();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }

        assertThat(modelDrivenOverThere).containsExactly(false);
    }
}
