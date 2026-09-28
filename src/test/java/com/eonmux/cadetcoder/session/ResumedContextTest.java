package com.eonmux.cadetcoder.session;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Reopening the conversation a resumed session was told to continue. */
public class ResumedContextTest {

    private static List<String> history(int turns, int sizeEach) {
        List<String> history = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            history.add("User: turn " + i + " " + "x".repeat(sizeEach));
        }
        return history;
    }

    @Test
    public void afreshSessionRestoresNothingForAnyCaller() {
        // The gate every caller shares. Without it, an unrelated command would drag the previous
        // invocation's conversation into its prompt.
        assertThat(ResumedContext.forCurrentSession()).isEmpty();
    }

    @Test
    public void afreshSessionSeedsNothing() {
        assertThat(ResumedContext.seed(null, 8192)).isEmpty();
        assertThat(ResumedContext.seed(List.of(), 8192)).isEmpty();
    }

    @Test
    public void ashortConversationComesBackWhole() {
        List<String> seed = ResumedContext.seed(List.of("User: hello", "AI: hi"), 8192);

        assertThat(String.join("\n", seed)).contains("User: hello").contains("AI: hi");
    }

    @Test
    public void whatComesBackIsLabelledAsHavingHappenedBefore() {
        // Unmarked, the model reads the last thing it said before the break as the thing it just
        // said, and continues mid-thought from a turn the user may have abandoned hours ago.
        List<String> seed = ResumedContext.seed(List.of("AI: I will now edit Foo.java"), 8192);

        assertThat(seed.get(0)).contains("Resumed session").contains("BEFORE");
        assertThat(seed.get(seed.size() - 1)).contains("End of the resumed conversation");
    }

    @Test
    public void alongConversationKeepsItsMostRecentTurns() {
        // A resumed session continues from where it stopped, so the newest turns are the ones that
        // matter; dropping from the front is the same choice compaction makes.
        List<String> seed = ResumedContext.seed(history(200, 400), 8192);
        String       text = String.join("\n", seed);

        assertThat(text).contains("turn 199");
        assertThat(text).doesNotContain("turn 0 ");
    }

    @Test
    public void restoredHistoryLeavesRoomForTheWorkBeingResumed() {
        int budget = 8192;
        List<String> seed = ResumedContext.seed(history(200, 400), budget);

        int cost = 0;
        for (String entry : seed) {
            cost += com.eonmux.cadetcoder.ai.metrics.TokenEstimator.estimate(entry);
        }
        // History that crowds out the current task has defeated the point of restoring it.
        assertThat(cost).isLessThan(budget / 2);
    }

    @Test
    public void itSaysHowMuchItCouldNotBringBack() {
        List<String> seed = ResumedContext.seed(history(200, 400), 8192);

        assertThat(seed.get(0)).contains("earlier turns are not shown");
    }

    @Test
    public void abudgetTooSmallToSayAnythingUsefulSeedsNothing() {
        // Half a turn plus two markers is worse than a clean start: it reads as a truncated thought.
        assertThat(ResumedContext.seed(history(10, 400), 64)).isEmpty();
    }

    @Test
    public void blankTurnsAreNotRestoredAsEmptyLines() {
        List<String> seed = ResumedContext.seed(java.util.Arrays.asList("User: real", "", null), 8192);

        assertThat(seed).noneMatch(entry -> entry == null || entry.isBlank());
    }
}
