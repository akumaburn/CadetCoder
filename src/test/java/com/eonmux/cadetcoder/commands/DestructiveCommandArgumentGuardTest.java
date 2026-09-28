package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two commands that act destructively while taking no positional arguments must refuse stray
 * words rather than ignoring them.
 *
 * <p>Both {@code undo} and {@code push} used to drop every positional silently, so a sentence typed
 * at the CLI ran the destructive action anyway: {@code cadet undo the last thing I did} discarded the
 * whole working tree and never mentioned the five words it had thrown away, and
 * {@code cadet push to origin} performed a real push while discarding the apparent target. The guard
 * runs before any git work, so these tests do not need a repository.</p>
 */
public class DestructiveCommandArgumentGuardTest {

    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        outputCapture = new TestOutputCapture();
    }

    @After
    public void tearDown() {
        outputCapture.restore();
    }

    @Test
    public void undoRefusesStrayWordsInsteadOfDiscardingTheWorkingTree() {
        int exitCode = new UndoCommand().execute(
                new String[] {"the", "last", "thing", "I", "did"});

        assertThat(exitCode).isEqualTo(1);
        String output = outputCapture.getAllOutput();
        assertThat(output).contains("undo takes no positional arguments");
        assertThat(output).contains("the last thing I did");
        assertThat(output)
                .as("the user must be told how to reach the AI instead")
                .contains("cadet chat");
    }

    @Test
    public void pushRefusesStrayWordsInsteadOfPushing() {
        int exitCode = new PushCommand().execute(new String[] {"to", "origin"});

        assertThat(exitCode).isEqualTo(1);
        String output = outputCapture.getAllOutput();
        assertThat(output).contains("push takes no positional arguments");
        assertThat(output).contains("to origin");
    }

    // The cases below ask what the guard does with a VALID invocation, which is the one question
    // that cannot be answered by running the command: passing the guard means proceeding, and
    // proceeding means discarding the working tree or pushing to the tracked remote. Running them
    // through execute() made the suite attempt a real network push on every run. They test the
    // parsing directly instead, which is where the answer actually lives.

    @Test
    public void undoStillAcceptsItsOwnFlags() {
        assertThat(UndoCommand.strayPositionals(new String[] {"-c", "-f"})).isEmpty();
        assertThat(UndoCommand.strayPositionals(new String[] {"--commit", "--edit", "--force"})).isEmpty();
    }

    @Test
    public void pushStillAcceptsItsOwnFlags() {
        assertThat(PushCommand.strayPositionals(new String[] {"-f"})).isEmpty();
        assertThat(PushCommand.strayPositionals(new String[] {"-u", "--force"})).isEmpty();
    }

    @Test
    public void aBranchNameIsTheFlagsValueNotAStrayWord() {
        // "-b main" is two tokens; treating "main" as a positional would refuse a valid push.
        assertThat(PushCommand.strayPositionals(new String[] {"-b", "main"})).isEmpty();
        assertThat(PushCommand.strayPositionals(new String[] {"--branch", "release"})).isEmpty();
    }

    @Test
    public void bareInvocationsAreUnaffected() {
        assertThat(UndoCommand.strayPositionals(new String[0])).isEmpty();
        assertThat(PushCommand.strayPositionals(new String[0])).isEmpty();
    }

    @Test
    public void strayWordsAreReportedInTheOrderTheyWereTyped() {
        assertThat(UndoCommand.strayPositionals(new String[] {"the", "last", "thing"}))
                .containsExactly("the", "last", "thing");
        assertThat(PushCommand.strayPositionals(new String[] {"to", "origin"}))
                .containsExactly("to", "origin");
    }

    @Test
    public void aStrayWordAmongValidFlagsIsStillCaught() {
        // The dangerous shape: enough of the invocation looks right that the rest slips through.
        assertThat(PushCommand.strayPositionals(new String[] {"-f", "origin"}))
                .containsExactly("origin");
        assertThat(UndoCommand.strayPositionals(new String[] {"-c", "everything"}))
                .containsExactly("everything");
    }

    @Test
    public void nullArgumentsAreToleratedRatherThanCounted() {
        assertThat(UndoCommand.strayPositionals(null)).isEmpty();
        assertThat(PushCommand.strayPositionals(new String[] {null, "-f", null})).isEmpty();
    }
}
