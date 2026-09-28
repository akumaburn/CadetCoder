package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.harness.budget.BudgetLimits;
import com.eonmux.cadetcoder.harness.loop.RunStatus;
import com.eonmux.cadetcoder.harness.loop.RunStop;
import com.eonmux.cadetcoder.harness.loop.RunWatch;
import com.eonmux.cadetcoder.harness.loop.ScriptedReasoner;
import com.eonmux.cadetcoder.harness.tools.ToolSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole binding, end to end: an agent run whose world is this project and whose actions are this
 * tool's own commands.
 *
 * <p>Everything underneath has its own tests, and each of them passes against a world that is not
 * this one. What has never been checked until here is that the pieces meet: that the system prompt
 * is built from the workspace the run was pointed at, that the ledger opens inside the run's own
 * record rather than the project, that a committed action reaches the command registry it was given,
 * and that the record a run leaves behind is still readable once the run is over. A scripted reasoner
 * stands in for the model, so the binding is tested without a backend -- the seam being tested is
 * between the harness and this project, not between the harness and a provider.</p>
 */
class ARunIsBoundToThisToolsOwnCommandsTest {

    /** A blind probe that runs one command, which is what a scripted agent commits to act. */
    private static final String TOUCH =
            "<call tool=\"commit\"><arg name=\"actions\">"
            + "[{\"command\": \"scratch made.txt\"}]</arg></call>";

    /** What a scripted agent says when it has nothing to do. */
    private static final String FINISHED = "DONE: there was nothing to do";

    /** What one test double is registered as. */
    private static final String SCRATCH = "scratch";

    private static HarnessRun running(CommandRegistry commands, ScriptedReasoner agent) {
        return new HarnessRun(commands, List.of(agent), RunWatch.silent(), RunStop.never());
    }

    /** A registry whose only command writes the file it is named after, under {@code project}. */
    private static CommandRegistry writingInto(Path project) {
        CommandRegistry commands = new CommandRegistry();
        commands.register(SCRATCH, new CommandRegistry.Command() {
            @Override
            public int execute(String[] args) {
                try {
                    Files.writeString(project.resolve(args[0]), "made by the agent");
                    return 0;
                } catch (IOException failure) {
                    return 1;
                }
            }

            @Override
            public String getUsage() {
                return "scratch <name>";
            }
        });
        return commands;
    }

    @Test
    void aRunThatFinishesAtOnceStillLeavesTheRecordThatProvesItRan(@TempDir Path project) {
        RunOutcome outcome = running(new CommandRegistry(), ScriptedReasoner.saying(FINISHED))
                .on(RunRequest.of("do nothing at all", project));

        assertThat(Files.exists(outcome.record().ledger())).isTrue();
        assertThat(outcome.result().ledgerLength()).isEqualTo(1);
        assertThat(outcome.result().status()).isEqualTo(RunStatus.DONE);
        assertThat(outcome.exitCode()).isEqualTo(RunOutcome.FINISHED);
    }

    @Test
    void theAgentIsToldTheTaskItWasGivenAndTheCommandsItHas(@TempDir Path project) {
        ScriptedReasoner agent = ScriptedReasoner.saying(FINISHED);

        running(new CommandRegistry(), agent).on(RunRequest.of("tidy the imports", project));

        assertThat(agent.systems()).hasSize(1);
        assertThat(agent.systems().get(0)).contains("tidy the imports").contains("read");
    }

    @Test
    void whatTheAgentCommitsReachesTheCommandRegistryItWasGiven(@TempDir Path project) {
        running(writingInto(project), ScriptedReasoner.saying(TOUCH, FINISHED))
                .on(RunRequest.of("make a scratch file", project));

        assertThat(Files.exists(project.resolve("made.txt"))).isTrue();
    }

    @Test
    void whatReallyHappenedIsWrittenIntoTheRunsOwnRecordAndNotTheProject(@TempDir Path project) {
        RunOutcome outcome = running(writingInto(project), ScriptedReasoner.saying(TOUCH, FINISHED))
                .on(RunRequest.of("make a scratch file", project));

        assertThat(outcome.record().ledger())
                .startsWith(project.resolve(RunRecord.RUNS))
                .isEqualTo(outcome.record().directory().resolve(ToolSession.LEDGER_NAME));
        assertThat(outcome.result().ledgerLength()).isEqualTo(2);
    }

    @Test
    void theRecordARunKeepsIsNoPartOfTheWorkspaceItObserves(@TempDir Path project)
            throws IOException {
        Files.writeString(project.resolve("pom.xml"), "<project/>");

        RunOutcome outcome = running(writingInto(project), ScriptedReasoner.saying(TOUCH, FINISHED))
                .on(RunRequest.of("make a scratch file", project));

        assertThat(Files.exists(outcome.record().ledger())).isTrue();
        assertThat(WorkspaceTree.of(project).paths())
                .containsExactlyInAnyOrder("pom.xml", "made.txt");
    }

    @Test
    void twoRunsAgainstTheSameProjectDoNotWriteOverEachOther(@TempDir Path project) {
        HarnessRun runs = running(new CommandRegistry(), ScriptedReasoner.saying(FINISHED));

        RunOutcome first  = runs.on(RunRequest.of("the first attempt", project));
        RunOutcome second = new HarnessRun(new CommandRegistry(),
                                          List.of(ScriptedReasoner.saying(FINISHED)),
                                          RunWatch.silent(), RunStop.never())
                .on(RunRequest.of("the second attempt", project));

        assertThat(first.record().directory()).isNotEqualTo(second.record().directory());
        assertThat(Files.exists(first.record().ledger())).isTrue();
        assertThat(Files.exists(second.record().ledger())).isTrue();
    }

    @Test
    void anAllowanceTheRunWasGivenIsTheAllowanceTheRunStopsAt(@TempDir Path project) {
        BudgetLimits oneTurn = new BudgetLimits(BudgetLimits.UNLIMITED, BudgetLimits.UNLIMITED,
                                                BudgetLimits.UNLIMITED, 1);

        RunOutcome outcome = running(writingInto(project),
                                     ScriptedReasoner.saying(TOUCH, TOUCH, TOUCH, TOUCH))
                .on(RunRequest.of("keep going", project).spending(oneTurn));

        assertThat(outcome.result().status()).isEqualTo(RunStatus.BUDGET);
        assertThat(outcome.exitCode()).isEqualTo(RunOutcome.UNFINISHED);
    }

    @Test
    void aRunSomebodyCallsOffStopsAndSaysSo(@TempDir Path project) {
        HarnessRun runs = new HarnessRun(writingInto(project),
                                       List.of(ScriptedReasoner.saying(TOUCH, TOUCH)),
                                       RunWatch.silent(), () -> true);

        RunOutcome outcome = runs.on(RunRequest.of("keep going", project));

        assertThat(outcome.result().status()).isEqualTo(RunStatus.STOPPED);
        assertThat(Files.exists(project.resolve("made.txt"))).isFalse();
    }

    @Test
    void aRunWithNothingToThinkWithIsRefusedBeforeAnyRecordIsMade(@TempDir Path project) {
        assertThatThrownBy(() -> new HarnessRun(new CommandRegistry(), List.of(), RunWatch.silent(),
                                               RunStop.never()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(Files.exists(project.resolve(RunRecord.RUNS))).isFalse();
    }

    @Test
    void aRunWithNoCommandsToActThroughIsRefused() {
        assertThatThrownBy(() -> new HarnessRun(null, List.of(ScriptedReasoner.saying(FINISHED)),
                                               RunWatch.silent(), RunStop.never()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
