package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A chat run is rebuilt from a plain map between every step, and what comes back is either the right
 * type or nothing.
 *
 * <h2>The defect</h2>
 *
 * <p>The rebuild guarded its two collections with {@code catch (ClassCastException)}, which caught
 * nothing: generics are erased, so {@code (List<String>) map.get("actionHistory")} succeeds over a
 * list of anything at all. The failure it was written for still happened, just somewhere else -- at
 * whichever later line first read an element, in a message naming neither the key nor this class.
 * The handler that looked like the safety was the reason there was none.</p>
 *
 * <p>The rebuild also restored a {@code lastBadResponse} that nothing ever read: it was written on
 * every format failure, cleared on every recovery, and carried across every step boundary for no
 * reader at all.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>Everything a step sets survives a round trip through the map; a value of the wrong type is
 * dropped rather than carried into a later step; an element of the wrong type is dropped without
 * taking the rest of the collection with it; the loop guard is the same object on the other side,
 * because a guard rebuilt from nothing has observed nothing; and a null map yields a usable context
 * rather than an exception.</p>
 */
public class TheContextSurvivesTheStepBoundaryTest {

    private final ChatCommand log = new ChatCommand();

    @Test
    public void everythingAStepSetsComesBack() {
        ChatContext before = new ChatContext();
        before.setStep("execute_single_action");
        before.setUserRequest("find the parser");
        before.setLastCommandOutput("3 matches");
        before.setLastCommandError("none");
        before.setLastErrorType("FILE_NOT_FOUND");
        before.setLastErrorDetails("no such file");
        before.setFormatRetryCount(2);
        before.setActionRetryCount(1);
        before.setUberChecksPassed(1);
        before.setCurrentAction(new ChatCommand.AIAction("read", new String[] {"a.java"}, "look"));
        before.setFailedAction(new ChatCommand.AIAction("grep", new String[] {"x"}, "search"));
        before.setActionHistory(new ArrayList<>(List.of("read a.java")));
        before.setFoundFiles(new HashMap<>(Map.of("a.java", "src/a.java")));

        ChatContext after = ChatContext.fromMap(before.toMap(), log);

        assertThat(after.getStep()).isEqualTo("execute_single_action");
        assertThat(after.getUserRequest()).isEqualTo("find the parser");
        assertThat(after.getLastCommandOutput()).isEqualTo("3 matches");
        assertThat(after.getLastCommandError()).isEqualTo("none");
        assertThat(after.getLastErrorType()).isEqualTo("FILE_NOT_FOUND");
        assertThat(after.getLastErrorDetails()).isEqualTo("no such file");
        assertThat(after.getFormatRetryCount()).isEqualTo(2);
        assertThat(after.getActionRetryCount()).isEqualTo(1);
        assertThat(after.getUberChecksPassed()).isEqualTo(1);
        assertThat(after.getCurrentAction().command).isEqualTo("read");
        assertThat(after.getFailedAction().command).isEqualTo("grep");
        assertThat(after.getActionHistory()).containsExactly("read a.java");
        assertThat(after.getFoundFiles()).containsEntry("a.java", "src/a.java");
    }

    @Test
    public void theLoopGuardIsTheSameObjectOnTheOtherSide() {
        ChatContext before = new ChatContext();
        ActionLoopGuard guard = before.getLoopGuard();

        assertThat(ChatContext.fromMap(before.toMap(), log).getLoopGuard()).isSameAs(guard);
    }

    @Test
    public void aValueOfTheWrongTypeIsDroppedRatherThanCarried() {
        Map<String, Object> map = new HashMap<>();
        map.put("step", 7);
        map.put("userRequest", new Object());
        map.put("formatRetryCount", "two");
        map.put("currentAction", "read a.java");
        map.put("actionHistory", "read a.java");
        map.put("foundFiles", List.of("a.java"));

        ChatContext context = ChatContext.fromMap(map, log);

        assertThat(context.getStep()).isNull();
        assertThat(context.getUserRequest()).isNull();
        assertThat(context.getFormatRetryCount()).isZero();
        assertThat(context.getCurrentAction()).isNull();
        assertThat(context.getActionHistory()).isEmpty();
        assertThat(context.getFoundFiles()).isEmpty();
    }

    @Test
    public void oneBadElementDoesNotTakeTheRestOfTheCollectionWithIt() {
        List<Object> history = new ArrayList<>();
        history.add("read a.java");
        history.add(42);
        history.add("grep x");

        Map<String, Object> entries = new HashMap<>();
        entries.put("a.java", "src/a.java");
        entries.put("b.java", 42);

        Map<String, Object> map = new HashMap<>();
        map.put("actionHistory", history);
        map.put("foundFiles", entries);

        ChatContext context = ChatContext.fromMap(map, log);

        assertThat(context.getActionHistory()).containsExactly("read a.java", "grep x");
        assertThat(context.getFoundFiles()).containsExactly(Map.entry("a.java", "src/a.java"));
    }

    @Test
    public void theListThatComesBackIsSafeToReadAsText() {
        List<Object> history = new ArrayList<>(List.of("read a.java", 42));
        Map<String, Object> map = new HashMap<>();
        map.put("actionHistory", history);

        // The point of the copy: joining it must not throw, which is exactly what a straight cast of
        // the caller's list would have done at this line rather than at the boundary.
        assertThat(String.join(", ", ChatContext.fromMap(map, log).getActionHistory()))
                .isEqualTo("read a.java");
    }

    @Test
    public void aRunThatHasNotStartedStillGetsAUsableContext() {
        ChatContext context = ChatContext.fromMap(null, log);

        assertThat(context.getActionHistory()).isEmpty();
        assertThat(context.getFoundFiles()).isEmpty();
        assertThat(context.getLoopGuard()).isNotNull();
        assertThat(context.getFormatRetryCount()).isZero();
    }
}
