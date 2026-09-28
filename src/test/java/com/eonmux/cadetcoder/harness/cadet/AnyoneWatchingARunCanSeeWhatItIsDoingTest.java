package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.harness.loop.RunResult;
import com.eonmux.cadetcoder.harness.loop.RunStatus;
import com.eonmux.cadetcoder.harness.loop.ToolRequest;
import com.eonmux.cadetcoder.harness.tools.ToolName;
import com.eonmux.cadetcoder.ui.CapturedRun;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run is minutes of somebody's time in which nothing else happens, so what it is doing has to
 * reach the terminal as it does it.
 *
 * <p>Two failures are being prevented at once. A run that prints nothing looks hung, and the honest
 * response to a hung agent is to kill it -- which throws away everything it had established. A run
 * that prints everything is worse: one observation of a large workspace is thousands of characters,
 * and a screen of them per round buries the two lines that say what actually happened. These tests
 * fix what is worth seeing at each verbosity, and keep the ending out of the watch entirely so it is
 * not announced twice by two different parts of the same command.</p>
 */
class AnyoneWatchingARunCanSeeWhatItIsDoingTest {

    private static final ToolRequest OBSERVING =
            new ToolRequest(ToolName.OBSERVE, Map.of());

    private static final ToolRequest COMMITTING =
            new ToolRequest(ToolName.COMMIT, Map.of("actions", "[{\"command\": \"read pom.xml\"}]"));

    /** Longer than any single line the terminal should be given for one answer. */
    private static final String FLOOD = "x".repeat(TerminalWatch.ANSWER_SHOWN * 4);

    private static String quietly(Consumer<TerminalWatch> shown) {
        return printed(TerminalWatch.quiet(), shown);
    }

    private static String verbosely(Consumer<TerminalWatch> shown) {
        return printed(TerminalWatch.verbose(), shown);
    }

    private static String printed(TerminalWatch watch, Consumer<TerminalWatch> shown) {
        return CapturedRun.of(() -> {
            shown.accept(watch);
            return 0;
        }).output();
    }

    @Test
    void everyToolTheAgentRunsIsAnnouncedAsItHappens() {
        String seen = quietly(watch -> watch.called(COMMITTING, "1 action, goal not reached"));

        assertThat(seen).contains(ToolName.COMMIT);
    }

    @Test
    void aQuietRunDoesNotRepeatTheWholeOfWhatTheAgentSaid() {
        String seen = quietly(watch -> watch.thought("a model", "I will read the build file first.",
                                                     List.of(OBSERVING)));

        assertThat(seen).doesNotContain("I will read the build file first.");
    }

    @Test
    void aVerboseRunShowsTheAgentsReasoningInTheAgentsOwnWords() {
        String seen = verbosely(watch -> watch.thought("a model", "I will read the build file first.",
                                                       List.of(OBSERVING)));

        assertThat(seen).contains("I will read the build file first.").contains("a model");
    }

    @Test
    void aLongAnswerIsShortenedRatherThanFloodingTheTerminal() {
        String seen = quietly(watch -> watch.called(OBSERVING, FLOOD));

        assertThat(seen.length()).isLessThan(FLOOD.length());
        assertThat(seen).contains(ToolName.OBSERVE);
    }

    @Test
    void anAnswerOfManyLinesIsShownAsTheOneThatSaysWhatHappened() {
        String seen = quietly(watch -> watch.called(OBSERVING, "committed 3 actions"
                                                              + System.lineSeparator()
                                                              + "tree_hash: 91a2"
                                                              + System.lineSeparator()
                                                              + "exit: 0"));

        assertThat(seen).contains("committed 3 actions").doesNotContain("tree_hash");
    }

    @Test
    void aVerboseRunShowsTheWholeAnswerBecauseThatIsWhatVerboseIsFor() {
        String seen = verbosely(watch -> watch.called(OBSERVING, "committed 3 actions"
                                                                 + System.lineSeparator()
                                                                 + "tree_hash: 91a2"));

        assertThat(seen).contains("tree_hash");
    }

    @Test
    void whatTheHarnessCouldNotReadIsShownAsAWarningEvenInAQuietRun() {
        String seen = quietly(watch -> watch.complained("no tool called 'reed'"));

        assertThat(seen).contains("reed");
    }

    @Test
    void aHandOverToAStrongerReasonerIsAnnouncedBecauseItChangesWhatTheRunCosts() {
        String seen = quietly(watch -> watch.escalated("the bigger model", "no progress in 4 rounds"));

        assertThat(seen).contains("the bigger model").contains("no progress in 4 rounds");
    }

    @Test
    void aRunThatStalledWithNobodyToHandToIsToldWhatWouldGiveItSomebody() {
        String seen = quietly(watch -> watch.stalled("no progress in 4 rounds"));

        assertThat(seen)
                .as("a stalled run the watcher cannot act on is a warning with no remedy in it")
                .contains("no progress in 4 rounds")
                .contains("ai.escalateTo");
    }

    @Test
    void aCompactionIsOnlyWorthMentioningToSomebodyWatchingClosely() {
        assertThat(quietly(watch -> watch.compacted(12))).isEmpty();
        assertThat(verbosely(watch -> watch.compacted(12))).contains("12");
    }

    @Test
    void theEndingIsLeftToWhoeverStartedTheRunSoItIsNotSaidTwice() {
        String seen = verbosely(
                watch -> watch.ended(new RunResult(RunStatus.DONE, 3, 1, 4, 0, "finished")));

        assertThat(seen).isEmpty();
    }

    @Test
    void aToolThatAnsweredNothingIsStillAnnouncedSoTheRunDoesNotLookHung() {
        String seen = quietly(watch -> watch.called(OBSERVING, ""));

        assertThat(seen).contains(ToolName.OBSERVE);
    }
}
