package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.harness.budget.BudgetLimits;
import com.eonmux.cadetcoder.harness.cadet.RunOutcome;
import com.eonmux.cadetcoder.harness.cadet.RunRecord;
import com.eonmux.cadetcoder.harness.cadet.RunRequest;
import com.eonmux.cadetcoder.harness.loop.RunResult;
import com.eonmux.cadetcoder.harness.loop.RunStatus;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An {@code agent} run is driven by the agent harness, and the caller says when it is not.
 *
 * <h2>The defect these lock out</h2>
 *
 * <p>The previous loop asked the model for one action at a time and kept nothing but a transcript of
 * what it had done, so nothing it believed about the workspace was ever written down, replayed or
 * contradicted. The harness keeps a hash-chained ledger of what really happened and refuses a
 * committed action the model's own theory cannot account for. Which of the two runs is therefore
 * not a detail: it is the difference between a run whose claims can be checked and one whose cannot,
 * and it has to be settled by the invocation rather than left to whichever branch was written last.
 * </p>
 *
 * <p>The fallback is kept and named, because a backend too small to maintain an executable world
 * model can still do useful work under the old loop. It is asked for, never fallen into.</p>
 */
class WhichHarnessARunGetsIsTheCallersChoiceTest {

    /** A task with no options in it, so a test that asserts on the task is asserting on the task. */
    private static final String TASK = "tidy the imports";

    /** A goal check that names a real command, which is all the environment requires of one. */
    private static final String CHECK = "bash mvn -q test";

    /** Long enough to be told apart from any default. */
    private static final int STEPS = 5;

    /** Likewise, and in seconds, which is what the option takes. */
    private static final int SECONDS = 30;

    private static final long MILLIS_PER_SECOND = 1000L;

    private final Map<String, Object> runContext = new HashMap<>();

    private TestOutputCapture outputCapture;

    @BeforeEach
    void setUp() {
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();
    }

    @AfterEach
    void tearDown() {
        outputCapture.stopCapture();
    }

    @Test
    void anAgentNobodySaidOtherwiseAboutIsDrivenByTheHarness(@TempDir Path record) {
        AtomicReference<RunRequest> asked = new AtomicReference<>();

        agentRecording(asked, finished(record)).execute(new String[] {"-y", TASK});

        assertThat(asked.get()).isNotNull();
        assertThat(asked.get().task()).isEqualTo(TASK);
    }

    @Test
    void theOldLoopIsStillThereForWhoeverAsksForIt(@TempDir Path record) {
        AtomicReference<RunRequest> asked = new AtomicReference<>();

        agentRecording(asked, finished(record)).execute(new String[] {"-y", "--classic", TASK});

        assertThat(asked.get()).as("--classic is the loop that keeps no ledger").isNull();
        assertThat(runContext.get("step")).isEqualTo("execute_agent_step");
    }

    @Test
    void askingForTheOldLoopIsNotPartOfTheTask(@TempDir Path record) {
        agentRecording(new AtomicReference<>(), finished(record))
                .execute(new String[] {"-y", "--classic", TASK});

        assertThat(runContext.get("task")).isEqualTo(TASK);
    }

    @Test
    void whatMakesTheRunDoneIsWhatTheCallerSaidMakesItDone(@TempDir Path record) {
        AtomicReference<RunRequest> asked = new AtomicReference<>();

        agentRecording(asked, finished(record))
                .execute(new String[] {"-y", "--check", CHECK, TASK});

        assertThat(asked.get().goalCheck()).isEqualTo(CHECK);
        assertThat(asked.get().task()).as("the check is not part of the task").isEqualTo(TASK);
    }

    @Test
    void aRunWithNothingToCheckItSaysSoRatherThanCheckingSomethingElse(@TempDir Path record) {
        AtomicReference<RunRequest> asked = new AtomicReference<>();

        agentRecording(asked, finished(record)).execute(new String[] {"-y", TASK});

        assertThat(asked.get().goalCheck()).isNull();
    }

    @Test
    void theBudgetsOnTheCommandLineAreTheBudgetsTheRunIsGiven(@TempDir Path record) {
        AtomicReference<RunRequest> asked = new AtomicReference<>();

        agentRecording(asked, finished(record)).execute(
                new String[] {"-y", "-m", String.valueOf(STEPS), "-t", String.valueOf(SECONDS), TASK});

        BudgetLimits limits = asked.get().limits();
        assertThat(limits.maxDeliberations()).isEqualTo(STEPS);
        assertThat(limits.maxMillis()).isEqualTo(SECONDS * MILLIS_PER_SECOND);
        assertThat(limits.maxActions())
                .as("nobody asked for a bound on how much the run may change")
                .isEqualTo(BudgetLimits.UNLIMITED);
        assertThat(limits.maxTokens()).isEqualTo(BudgetLimits.UNLIMITED);
    }

    @Test
    void aRunNobodyBoundedIsNotSecretlyBounded(@TempDir Path record) {
        AtomicReference<RunRequest> asked = new AtomicReference<>();

        agentRecording(asked, finished(record)).execute(new String[] {"-y", TASK});

        assertThat(asked.get().limits()).isEqualTo(BudgetLimits.unlimited());
    }

    @Test
    void aRunThatFinishedLeavesWithNothingToReport(@TempDir Path record) {
        int code = agentRecording(new AtomicReference<>(), finished(record))
                .execute(new String[] {"-y", TASK});

        assertThat(code).isZero();
    }

    @Test
    void aRunThatSpentItsAllowanceWithoutFinishingSaysSoInItsExitCode(@TempDir Path record) {
        RunOutcome unfinished = new RunOutcome(
                new RunResult(RunStatus.BUDGET, 5, 2, 7, 0, "out of deliberations"),
                new RunRecord(record));

        int code = agentRecording(new AtomicReference<>(), unfinished).execute(new String[] {"-y", TASK});

        assertThat(code)
                .as("a caller that reads 0 as success would read this as work that got done")
                .isEqualTo(RunOutcome.UNFINISHED);
    }

    @Test
    void whereTheRunKeptItsRecordIsPartOfWhatIsReported(@TempDir Path record) {
        agentRecording(new AtomicReference<>(), finished(record)).execute(new String[] {"-y", TASK});

        assertThat(outputCapture.getOutput()).contains(record.toString());
    }

    @Test
    void whatToTypeToReadTheRecordIsPartOfWhatIsReported(@TempDir Path record) {
        agentRecording(new AtomicReference<>(), finished(record)).execute(new String[] {"-y", TASK});

        assertThat(outputCapture.getOutput())
                .as("a path on its own is not an invitation to open it")
                .contains("runs show " + record.getFileName());
    }

    /** An outcome that says the run finished, with its record wherever the test put it. */
    private static RunOutcome finished(Path record) {
        return new RunOutcome(new RunResult(RunStatus.DONE, 3, 1, 4, 0, "the imports are tidy"),
                              new RunRecord(record));
    }

    /**
     * An agent whose harness is a double.
     *
     * <p>Both paths are stopped where the question under test is already settled: the harness at the
     * point it would be handed a request, and the old loop at its first step. Neither needs a model
     * behind it, which is the point -- the choice of harness is made before either could call
     * one.</p>
     */
    private AgentCommand agentRecording(AtomicReference<RunRequest> asked, RunOutcome answer) {
        return new AgentCommand() {
            @Override
            protected RunOutcome underTheHarness(RunRequest request, boolean verbose) {
                asked.set(request);
                return answer;
            }

            @Override
            public IterativeCommand.StepResult executeStep(String[] args, Map<String, Object> ctx,
                                                           String llmResponse) {
                if ("execute_agent_step".equals(ctx.get("step"))) {
                    runContext.clear();
                    runContext.putAll(ctx);
                    return IterativeCommand.StepResult.success("intercepted", ctx);
                }
                IterativeCommand.StepResult stepped = super.executeStep(args, ctx, llmResponse);
                runContext.clear();
                runContext.putAll(stepped.getContext());
                return stepped;
            }
        };
    }

    /** Kept honest: a verbose run and a quiet one differ only in what the watch prints. */
    @Test
    void whetherTheRunIsVerboseIsPassedOnRatherThanDecidedHere(@TempDir Path record) {
        AtomicBoolean loud = new AtomicBoolean();
        AgentCommand agent = new AgentCommand() {
            @Override
            protected RunOutcome underTheHarness(RunRequest request, boolean verbose) {
                loud.set(verbose);
                return finished(record);
            }
        };

        agent.execute(new String[] {"-y", "-v", TASK});

        assertThat(loud).isTrue();
    }
}
