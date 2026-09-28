package com.eonmux.cadetcoder.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A model running a command is a participant in the session, not its owner.
 *
 * <h2>The defects these lock out</h2>
 *
 * <p>{@code quit} ends the process, which is right when a person types it and never right when a
 * model emits it. The original guard keyed on {@code cadet.interactive}, which reads {@code true}
 * inside the interactive shell — exactly where an agent loop runs — so {@code COMMAND: quit} from a
 * chat or agent run closed the user's shell. The flag answers "is anyone at a terminal"; the
 * question is "did they ask for this".</p>
 *
 * <p>The second defect is the one that followed: marking a dispatch and refusing a nested loop were
 * two rules kept in two places, and a third dispatch site was written with neither. Both now live
 * behind {@link ModelDispatch#run}, which is the only way to mark a thread at all, and every place
 * that runs a command for a model goes through it.</p>
 */
class ModelDrivenQuitTest {

    /**
     * The files that dispatch a command a PERSON asked for.
     *
     * <p>Everything else under {@code src/main} that reaches a command registry is running a
     * model's action and has to say so. Naming the three that are not is shorter than naming the
     * ones that are, and it does not go stale silently: a new model dispatch site anywhere fails
     * {@link #everyCommandAModelRunsIsDispatchedAsTheModelsDoing} until it is wrapped.</p>
     */
    private static final Set<String> PERSON_DRIVEN =
            Set.of("Main.java", "InteractiveShell.java", "PlanModeCommand.java");

    /** How a dispatch says the command is a model's doing. */
    private static final String MARKED = "ModelDispatch.run(";

    /** How any code reaches a command registry. */
    private static final String DISPATCH = ".executeCommand(";

    private String previousInteractive;

    @BeforeEach
    void setUp() {
        previousInteractive = System.getProperty("cadet.interactive");
        // The shell's own condition: a person IS at a terminal. Without this the old guard would
        // catch the case for the wrong reason and the test would prove nothing.
        System.setProperty("cadet.interactive", "true");
    }

    @AfterEach
    void tearDown() {
        if (previousInteractive == null) {
            System.clearProperty("cadet.interactive");
        } else {
            System.setProperty("cadet.interactive", previousInteractive);
        }
    }

    @Test
    @DisplayName("Quit dispatched by the model returns the sentinel instead of ending the process")
    void modelDrivenQuitDoesNotEndTheSession() {
        int code = ModelDispatch.run("quit", () -> new QuitCommand().execute(new String[0]));

        assertThat(code)
                .as("the caller decides what the model wanting to stop means")
                .isEqualTo(QuitCommand.EXIT_REQUESTED);
    }

    @Test
    @DisplayName("The marker is thread-scoped, so a worker cannot make the shell look model-driven")
    void theMarkerDoesNotLeakAcrossThreads() {
        AtomicBoolean seenOnOtherThread = new AtomicBoolean(true);

        ModelDispatch.run("read", () -> {
            Thread other = new Thread(() -> seenOnOtherThread.set(ModelDispatch.isModelDriven()));
            other.start();
            try {
                other.join(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return 0;
        });

        assertThat(seenOnOtherThread).isFalse();
        assertThat(ModelDispatch.isModelDriven())
                .as("the marker is cleared once the dispatch returns")
                .isFalse();
    }

    @Test
    @DisplayName("A nested dispatch does not make the rest of the outer one look user-driven")
    void nestedDispatchRestoresRatherThanClears() {
        AtomicBoolean stillModelDriven = new AtomicBoolean();

        ModelDispatch.run("read", () -> {
            ModelDispatch.run("grep", () -> 0);
            stillModelDriven.set(ModelDispatch.isModelDriven());
            return 0;
        });

        assertThat(stillModelDriven).isTrue();
        assertThat(ModelDispatch.isModelDriven()).isFalse();
    }

    @Test
    @DisplayName("Outside a model dispatch nothing is marked")
    void ordinaryDispatchIsNotMarked() {
        assertThat(ModelDispatch.isModelDriven()).isFalse();
    }

    @Test
    @DisplayName("A non-interactive host still gets the sentinel, as before")
    void theNonInteractiveContractIsUnchanged() {
        System.setProperty("cadet.interactive", "false");

        assertThat(new QuitCommand().execute(new String[0])).isEqualTo(QuitCommand.EXIT_REQUESTED);
    }

    @Test
    @DisplayName("A model cannot start a loop inside the loop it is already in")
    void aModelCannotStartAnotherLoop() {
        AtomicBoolean ran = new AtomicBoolean();

        for (String loop : new String[]{"agent", "chat", "AGENT", " chat "}) {
            int code = ModelDispatch.run(loop, () -> {
                ran.set(true);
                return 0;
            });

            assertThat(code).as(loop + " must be refused").isEqualTo(ModelDispatch.REFUSED);
        }

        assertThat(ran).as("nothing was run at all").isFalse();
    }

    @Test
    @DisplayName("A refused loop is a refusal the model can read, not silence")
    void aRefusedLoopSaysWhichCommandWasRefused() {
        assertThat(ModelDispatch.refusalFor("agent")).contains("agent");
        assertThat(ModelDispatch.startsALoop("agent")).isTrue();
        assertThat(ModelDispatch.startsALoop("read")).isFalse();
        assertThat(ModelDispatch.startsALoop(null)).isFalse();
    }

    @Test
    @DisplayName("Every command a model runs is dispatched as the model's doing")
    void everyCommandAModelRunsIsDispatchedAsTheModelsDoing() throws IOException {
        List<String> unmarked = new ArrayList<>();

        for (Path source : sourcesReachingACommandRegistry()) {
            String name = String.valueOf(source.getFileName());
            if (PERSON_DRIVEN.contains(name) || Files.readString(source).contains(MARKED)) {
                continue;
            }
            unmarked.add(name);
        }

        assertThat(unmarked)
                .as("these run a command without saying a model asked for it, so `quit` would end "
                    + "the user's session and `agent` would nest a second loop")
                .isEmpty();
    }

    private static List<Path> sourcesReachingACommandRegistry() throws IOException {
        try (Stream<Path> tree = Files.walk(Path.of("src/main/java"))) {
            List<Path> found = new ArrayList<>();
            for (Path source : tree.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (Files.readString(source).contains(DISPATCH)) {
                    found.add(source);
                }
            }
            return found;
        }
    }
}
