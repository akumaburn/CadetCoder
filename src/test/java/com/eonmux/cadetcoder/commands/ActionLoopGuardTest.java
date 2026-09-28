package com.eonmux.cadetcoder.commands;

import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the specificity of loop detection.
 *
 * <p>The first group of tests are the FALSE POSITIVES the previous name-only detector produced --
 * each one silently skipped a legitimate action. The second group are the real loops that must still
 * be caught.</p>
 */
public class ActionLoopGuardTest {

    private ActionLoopGuard guard;

    @Before
    public void setUp() {
        guard = new ActionLoopGuard();
    }

    private void ran(String command, String output, String... args) {
        guard.observe(command, args, true, output);
    }

    private boolean blocked(String command, String... args) {
        return guard.check(command, args).isBlocked();
    }

    // ------------------------------------------------------- must NOT be treated as loops

    @Test
    public void threeDifferentGrepsAreNotALoop() {
        // Old rule: counted history entries starting with "grep:" and blocked at 3, whatever the
        // patterns were.
        ran("grep", "1 match", "class Foo");
        ran("grep", "4 matches", "interface Bar");
        ran("grep", "12 matches", "TODO");

        assertThat(blocked("grep", "@Override")).isFalse();
    }

    @Test
    public void readGrepAlternationOverDifferentFilesIsNotALoop() {
        // Old rule: A-B-A-B-A-B on command NAMES only.
        ran("read", "contents of A", "A.java");
        ran("grep", "hit in A", "alpha");
        ran("read", "contents of B", "B.java");
        ran("grep", "hit in B", "beta");
        ran("read", "contents of C", "C.java");
        ran("grep", "hit in C", "gamma");

        assertThat(blocked("read", "D.java")).isFalse();
    }

    @Test
    public void readingSeveralFilesFromOnePackageIsNotALoop() {
        // Old rule: "similar filename" matched any two same-extension files sharing three leading OR
        // trailing characters, so every *Command.java in a Java tree looked like the same work.
        ran("read", "contents 1", "src/ReadCommand.java");
        ran("read", "contents 2", "src/WriteCommand.java");
        ran("read", "contents 3", "src/ChatCommand.java");
        ran("read", "contents 4", "src/ChatContext.java");

        assertThat(blocked("read", "src/EditCommand.java")).isFalse();
    }

    @Test
    public void rereadingAFileAfterEditingItIsNotALoop() {
        // Old rule: "read" got a threshold of 2, so the ordinary verify-after-edit cycle was blocked
        // and the confirming read never ran.
        ran("read", "old contents", "Foo.java");
        ran("edit", "applied 1 change", "Foo.java");
        ran("read", "NEW contents", "Foo.java");

        assertThat(blocked("read", "Foo.java")).isFalse();
    }

    @Test
    public void aRepeatWhoseResultChangedIsNotALoop() {
        ran("bash", "0 files", "ls build");
        ran("bash", "0 files", "ls build");
        ran("bash", "3 files", "ls build");

        assertThat(blocked("bash", "ls build")).isFalse();
    }

    @Test
    public void anEmptyHistoryNeverBlocks() {
        assertThat(blocked("read", "Foo.java")).isFalse();
    }

    @Test
    public void aNullOrBlankCommandIsIgnoredRatherThanBlocked() {
        assertThat(guard.check(null, new String[] {"x"}).isBlocked()).isFalse();
        assertThat(guard.check("   ", new String[] {"x"}).isBlocked()).isFalse();

        guard.observe(null, new String[] {"x"}, true, "out");
        assertThat(guard.observedCount()).isZero();
    }

    // ------------------------------------------------------- must be treated as loops

    @Test
    public void theSameCommandWithTheSameResultTwiceBlocksTheThirdAttempt() {
        ran("read", "identical contents", "Foo.java");
        ran("read", "identical contents", "Foo.java");

        ActionLoopGuard.Decision decision = guard.check("read", new String[] {"Foo.java"});

        assertThat(decision.isBlocked()).isTrue();
        assertThat(decision.getReason()).contains("read Foo.java");
        assertThat(decision.getGuidance())
                .as("the model must be told what to do instead, not just that it was blocked")
                .contains("read Foo.java")
                .contains("different");
    }

    @Test
    public void differentSpellingsOfTheSamePathAreTheSameAction() {
        ran("read", "identical contents", "./src/Foo.java");
        ran("read", "identical contents", "src/Foo.java");

        assertThat(blocked("read", "src//Foo.java")).isTrue();
    }

    @Test
    public void aTwoStepCycleWithIdenticalResultsIsBlocked() {
        ran("read", "same A", "A.java");
        ran("grep", "same hit", "alpha");
        ran("read", "same A", "A.java");
        ran("grep", "same hit", "alpha");

        ActionLoopGuard.Decision decision = guard.check("read", new String[] {"A.java"});

        assertThat(decision.isBlocked()).isTrue();
        assertThat(decision.getReason()).contains("cycle");
    }

    @Test
    public void aCycleTheModelHasAlreadyBrokenIsNotBlocked() {
        ran("read", "same A", "A.java");
        ran("grep", "same hit", "alpha");
        ran("read", "same A", "A.java");
        ran("grep", "same hit", "alpha");

        assertThat(blocked("read", "B.java"))
                .as("a different next action breaks the cycle and must be allowed")
                .isFalse();
    }

    @Test
    public void failuresRepeatingIdenticallyAreAlsoALoop() {
        guard.observe("read", new String[] {"missing.java"}, false, "File not found");
        guard.observe("read", new String[] {"missing.java"}, false, "File not found");

        assertThat(blocked("read", "missing.java")).isTrue();
    }

    @Test
    public void aFailureFollowedByASuccessIsProgress() {
        guard.observe("read", new String[] {"Foo.java"}, false, "File not found");
        guard.observe("read", new String[] {"Foo.java"}, true, "contents");

        assertThat(blocked("read", "Foo.java")).isFalse();
    }

    // ------------------------------------------------------- lifecycle

    // ------------------------------------------------------- the stop condition that replaced the caps

    @Test
    public void aRunIsNotConsideredStuckUntilSeveralRefusalsInARow() {
        assertThat(guard.isStuck()).isFalse();

        guard.recordBlocked();
        assertThat(guard.isStuck()).as("one refusal is a nudge, not a dead end").isFalse();

        guard.recordBlocked();
        assertThat(guard.isStuck()).isFalse();

        guard.recordBlocked();
        assertThat(guard.isStuck())
                .as("after being told what repeated and proposing non-productive work anyway, stop")
                .isTrue();
        assertThat(guard.consecutiveBlocks()).isEqualTo(3);
    }

    @Test
    public void anySuccessfulActionClearsTheStuckCounter() {
        guard.recordBlocked();
        guard.recordBlocked();

        ran("read", "fresh contents", "New.java");

        assertThat(guard.consecutiveBlocks())
                .as("an action actually ran, so the run is making progress again")
                .isZero();
        assertThat(guard.isStuck()).isFalse();
    }

    @Test
    public void aLongProductiveRunNeverBecomesStuck() {
        // The point of removing the iteration ceiling: 200 distinct, productive actions must not be
        // stopped by anything, however many there are.
        for (int i = 0; i < 200; i++) {
            assertThat(blocked("read", "File" + i + ".java")).isFalse();
            ran("read", "contents " + i, "File" + i + ".java");
            assertThat(guard.isStuck()).isFalse();
        }
    }

    @Test
    public void resetClearsEverything() {
        ran("read", "same", "Foo.java");
        ran("read", "same", "Foo.java");
        assertThat(blocked("read", "Foo.java")).isTrue();

        guard.reset();

        assertThat(guard.observedCount()).isZero();
        assertThat(blocked("read", "Foo.java")).isFalse();
    }

    @Test
    public void historyIsBoundedSoLongRunsDoNotGrowWithoutLimit() {
        for (int i = 0; i < 200; i++) {
            ran("read", "contents " + i, "File" + i + ".java");
        }

        assertThat(guard.observedCount()).isLessThanOrEqualTo(24);
    }

    @Test
    public void signatureNormalizationIsCaseSensitiveForPathsButNotForCommands() {
        assertThat(ActionLoopGuard.signature("READ", new String[] {"Foo.java"}))
                .isEqualTo(ActionLoopGuard.signature("read", new String[] {"Foo.java"}));
        assertThat(ActionLoopGuard.signature("read", new String[] {"foo.java"}))
                .isNotEqualTo(ActionLoopGuard.signature("read", new String[] {"Foo.java"}));
    }

    @Test
    public void describeQuotesArgumentsThatContainSpaces() {
        assertThat(ActionLoopGuard.describe("grep", new String[] {"class Foo", "--path=src"}))
                .isEqualTo("grep \"class Foo\" --path=src");
    }

    @Test
    public void normalizeArgumentCanonicalizesPathSpellings() {
        assertThat(ActionLoopGuard.normalizeArgument("./src/")).isEqualTo("src");
        assertThat(ActionLoopGuard.normalizeArgument("src//main///java")).isEqualTo("src/main/java");
        assertThat(ActionLoopGuard.normalizeArgument("  a   b  ")).isEqualTo("a b");
        assertThat(ActionLoopGuard.normalizeArgument(null)).isEmpty();
        assertThat(ActionLoopGuard.normalizeArgument("/")).isEqualTo("/");
    }
}
