package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ui.InteractivePrompts;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A command a model dispatched never takes the user's input line.
 *
 * <p><b>The defect</b>: an agent run stopped dead on a bare {@code >>>} prompt with no question
 * above it, and stayed there. The chain was four steps. {@code ActionRun.dispatch} collected a
 * dispatched command's output by swapping {@code System.out} for the whole process rather than
 * through {@link com.eonmux.cadetcoder.ui.OutputCapture}. Every "can a person be asked?" check in
 * the application reads {@code OutputCapture.isCapturing()}, so all of them answered yes.
 * {@code EditCommand} then reached its own confirmation step and asked "Do you want to apply these
 * changes? (yes/no/modify)" -- which went into the swallowed buffer. {@code UserAsk} asked for the
 * answer with the bare string {@code ">>> "}, which reaches the shell by a different route, so that
 * is all the user saw.</p>
 *
 * <p>The fix is in three parts, and this pins all three: a dispatched command runs with prompts off,
 * its output is collected per thread, and no prompt is ever shown without its question.</p>
 */
public class AnestedCommandDoesNotStopTheRunToAskTest {

    private String savedInteractive;

    @Before
    public void setUp() {
        savedInteractive = System.getProperty(InteractivePrompts.PROPERTY);
        System.setProperty(InteractivePrompts.PROPERTY, "true");
    }

    @After
    public void tearDown() {
        if (savedInteractive == null) {
            System.clearProperty(InteractivePrompts.PROPERTY);
        } else {
            System.setProperty(InteractivePrompts.PROPERTY, savedInteractive);
        }
    }

    @Test
    public void aPersonAtTheTerminalIsStillAskedOrdinarily() {
        assertThat(InteractivePrompts.isOn())
                .as("typing `cadet edit` yourself must still confirm the changes")
                .isTrue();
    }

    @Test
    public void workAModelAskedForDoesNotPrompt() {
        boolean insideDispatch = InteractivePrompts.asModelDrivenWork(InteractivePrompts::isOn);

        assertThat(insideDispatch)
                .as("the question would be collected into the transcript, so it must not be asked")
                .isFalse();
    }

    @Test
    public void theScopeIsReleasedWhenTheDispatchEnds() {
        InteractivePrompts.asModelDrivenWork(() -> "done");

        assertThat(InteractivePrompts.isOn())
                .as("the shell's own next command must be able to ask again")
                .isTrue();
    }

    @Test
    public void aNestedDispatchDoesNotReleaseItsCallersScope() {
        // edit dispatched by chat can itself dispatch; an inner scope that cleared the flag on exit
        // would hand the outer command back a prompt it must not show.
        boolean afterInner = InteractivePrompts.asModelDrivenWork(() -> {
            InteractivePrompts.asModelDrivenWork(() -> "inner");
            return InteractivePrompts.isOn();
        });

        assertThat(afterInner).isFalse();
    }

    @Test
    public void theScopeIsReleasedEvenWhenTheCommandThrows() {
        try {
            InteractivePrompts.asModelDrivenWork(() -> {
                throw new IllegalStateException("the command failed");
            });
        } catch (IllegalStateException expected) {
            // The point of the test is what the scope does next.
        }

        assertThat(InteractivePrompts.isOn())
                .as("a failed action must not silence every prompt for the rest of the session")
                .isTrue();
    }

    @Test
    public void theScopeIsScopedToOneThread() throws Exception {
        boolean[] otherThreadSaw = {false};

        InteractivePrompts.asModelDrivenWork(() -> {
            Thread other = new Thread(() -> otherThreadSaw[0] = InteractivePrompts.isOn());
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });

        assertThat(otherThreadSaw[0])
                .as("a worker's dispatch must not silence the shell's own thread")
                .isTrue();
    }

    @Test
    public void aQuestionIsAlwaysCarriedOnTheInputLine() {
        // What the user actually saw was ">>> " and nothing else. Whatever route the question takes
        // to the screen, the line they type on has to say what is being asked.
        assertThat(UserAsk.inputPrompt("Do you want to apply these changes? (yes/no/modify)"))
                .isEqualTo("Do you want to apply these changes? (yes/no/modify) >>> ");
    }

    @Test
    public void aLongQuestionIsShortenedRatherThanDropped() {
        String question = "Please choose which of the following files you meant, by number, from "
                          + "the list printed above this prompt, and press Enter to continue";

        String line = UserAsk.inputPrompt(question);

        assertThat(line).endsWith(">>> ");
        assertThat(line).startsWith("Please choose which of the following files");
        assertThat(line).contains("…");
        assertThat(line.length()).isLessThan(question.length());
    }

    @Test
    public void aQuestionSpanningLinesIsFlattenedOntoTheInputLine() {
        assertThat(UserAsk.inputPrompt("Apply these changes?\n(yes/no/modify)"))
                .isEqualTo("Apply these changes? (yes/no/modify) >>> ");
    }

    @Test
    public void onlyAnAbsentQuestionLeavesTheBarePrompt() {
        assertThat(UserAsk.inputPrompt(null)).isEqualTo(">>> ");
        assertThat(UserAsk.inputPrompt("   ")).isEqualTo(">>> ");
    }
}
