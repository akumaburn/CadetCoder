package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.InterruptSignal;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.test.StubbedProvider;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What an iteration of a loop is called, and what it says about where it sits.
 *
 * <h2>The defect</h2>
 *
 * <p>A loop of a hundred passes runs a whole model run per pass, and each run counts its own
 * iterations from one. So the transcript reads {@code Iteration 1}, {@code Iteration 2} ...
 * {@code Iteration 31}, {@code Iteration 1} -- and that last line is the only sign that a pass
 * ended and another began. Scrolling back through a run of several hours, there is nothing in an
 * iteration line to say which pass it belongs to, and no way to tell how much work the loop has
 * done in total short of counting the lines.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That an iteration inside a pass names the pass and gives the count across the whole loop,
 * that the count carries from one pass to the next, and that a run which is not part of a loop is
 * still called what it always was.</p>
 */
public class AniterationSaysWhichPassItIsPartOfTest {

    private TestOutputCapture output;

    @Before
    public void captureOutput() {
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void releaseOutput() {
        output.stopCapture();
        InterruptSignal.clear();
    }

    @Test
    public void aniterationOutsideAloopIsCalledWhatItAlwaysWas() {
        assertThat(LoopPass.opening(3)).isEqualTo("Iteration 3");
    }

    @Test
    public void aniterationInsideApassNamesThePassItBelongsTo() {
        String[] label = new String[1];

        LoopPass.inPass(2, 100, 0, () -> label[0] = LoopPass.opening(3));

        assertThat(label[0]).contains("Pass 2 of 100").contains("iteration 3");
    }

    /** The whole point of the count: how much work the loop has done, not just this pass. */
    @Test
    public void thecountRunsAcrossEveryPassBeforeIt() {
        String[] label = new String[1];

        LoopPass.inPass(2, 100, 111, () -> label[0] = LoopPass.opening(3));

        assertThat(label[0]).contains("114 overall");
    }

    @Test
    public void apassReportsHowManyIterationsItRan() {
        int ran = LoopPass.inPass(1, 10, 0, () -> {
            LoopPass.opening(1);
            LoopPass.opening(2);
            LoopPass.opening(3);
        });

        assertThat(ran).isEqualTo(3);
    }

    @Test
    public void apassThatRanNothingRanNothing() {
        assertThat(LoopPass.inPass(1, 10, 0, () -> { })).isZero();
    }

    /** A pass is over when it is over: nothing of it is left on the thread for the next caller. */
    @Test
    public void apassLeavesNothingBehindIt() {
        LoopPass.inPass(2, 100, 111, () -> LoopPass.opening(3));

        assertThat(LoopPass.opening(1)).isEqualTo("Iteration 1");
    }

    /**
     * The same, through the executor that prints it.
     *
     * <p>One turn: the run opens on its initial prompt, the model answers, and the next step
     * completes it. That turn is the iteration, and it is opened under the pass it belongs to.</p>
     */
    @Test
    public void theexecutorOpensItsIterationUnderThePassItIsRunningIn() {
        IterativeCommand command = mock(IterativeCommand.class);
        String[]         args    = {"frobnicate"};
        when(command.supportsIterativeExecution(args)).thenReturn(true);
        when(command.getInitialPrompt(args)).thenReturn("Frobnicate the widget");
        when(command.executeStep(any(), any(), any()))
                .thenReturn(new IterativeCommand.StepResult(true, "done", new HashMap<>(), null));

        System.setProperty("cadet.interactive", "false");
        try (StubbedProvider model = StubbedProvider.answering("SUCCESS: done")) {
            int ran = LoopPass.inPass(2, 100, 111,
                                      () -> new IterativeExecutor().execute(command, args));

            assertThat(model.asked()).hasSize(1);
            assertThat(ran).as("the pass ran one turn of its own").isEqualTo(1);
            assertThat(output.getAllOutput()).contains("Pass 2 of 100, iteration 1 (112 overall)");
        } finally {
            System.clearProperty("cadet.interactive");
        }
    }

    /**
     * What a finished loop adds up to, said once at the end.
     *
     * <p>Zero here is the honest figure and not an empty one: the model answers each pass's opening
     * request with a completing reply, so no pass ever takes a turn of its own, and no iteration is
     * opened. The count is of iterations run, which is the same count the lines above carry.</p>
     */
    @Test
    public void afinishedLoopSaysHowManyIterationsItTook() {
        boolean wasUber = ConfigManager.getInstance().getConfig().getAi().isUberMode();
        ConfigManager.getInstance().getConfig().getAi().setUberMode(false);
        System.setProperty("cadet.interactive", "false");
        try (StubbedProvider model = StubbedProvider.answering("SUCCESS: nothing left to do")) {
            new LoopCommand().execute(new String[] {"--times=2", "make it faster"});

            assertThat(model.asked()).hasSize(2);
            assertThat(output.getAllOutput())
                    .contains("Finished 2 passes and 0 iterations at: make it faster");
        } finally {
            System.clearProperty("cadet.interactive");
            ConfigManager.getInstance().getConfig().getAi().setUberMode(wasUber);
        }
    }
}
