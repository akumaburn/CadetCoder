package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reasoning a reasoning model produces, shown while it is being thought.
 *
 * <p><b>The defect</b>: the reasoning reached the screen only after the whole reply was generated,
 * and a reasoning model spends most of its turn thinking before it writes -- fifty seconds of a
 * silent screen after every command, twice in one real session, both times ended by the user
 * giving up on a model that was working the whole while.</p>
 *
 * <p><b>What is locked here</b>: the live span's lifecycle (opened per call, closed in a
 * {@code finally}, silent when no thought arrives), whole-line buffering (a delta is not a line),
 * the soft flush that keeps a run-on thought moving, and the once-only rule -- thinking that
 * streamed live is not printed again beside the reply.</p>
 */
public class TheThinkingStreamsLiveTest {

    private String previousTui;
    private TestOutputCapture output;

    @Before
    public void setUp() {
        previousTui = System.getProperty(TuiMode.OVERRIDE_PROPERTY);
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
        ThinkingView.liveEnd(); // no-op when no span is open; closes one a test left behind
        ThinkingView.clear();
        restore(TuiMode.OVERRIDE_PROPERTY, previousTui);
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }

    @Test
    public void theFirstThoughtOpensTheRunAndNamesIt() {
        ThinkingView.liveBegin();
        ThinkingView.liveDelta("the first thought\n");

        String shown = output.getAllOutput();
        assertThat(shown).contains("thinking");
        assertThat(shown).contains("the first thought");
        assertThat(shown).contains(CollapsedOutput.OPEN);
    }

    @Test
    public void aDeltaIsNotALine() {
        // Deltas arrive a fragment at a time; printing each one as it came would put every word on
        // its own line. A line prints when it is whole.
        ThinkingView.liveBegin();
        ThinkingView.liveDelta("half a");
        ThinkingView.liveDelta(" thought\n");

        assertThat(output.getAllOutput()).containsOnlyOnce("half a thought");
    }

    @Test
    public void theRemainderFlushesAtTheEnd() {
        ThinkingView.liveBegin();
        ThinkingView.liveDelta("a thought with no newline at the end of it");
        assertThat(output.getAllOutput()).doesNotContain("a thought with no newline");

        ThinkingView.liveEnd();
        assertThat(output.getAllOutput()).contains("a thought with no newline at the end of it");
    }

    @Test
    public void theRunClosesEvenWhenTheThoughtWasCutShort() {
        ThinkingView.liveBegin();
        ThinkingView.liveDelta("interrupted mid-thought\n");
        ThinkingView.liveEnd();

        assertThat(output.getAllOutput()).contains(CollapsedOutput.CLOSE);
    }

    @Test
    public void aModelThatProducesNoReasoningPrintsNothing() {
        ThinkingView.liveBegin();
        ThinkingView.liveEnd();

        assertThat(output.getAllOutput()).doesNotContain(CollapsedOutput.OPEN);
        assertThat(ThinkingView.wasStreamedLive()).isFalse();
    }

    @Test
    public void aDeltaWithNoSpanOpenCostsNothing() {
        // A backend that streams while nobody is listening -- the judges' path, or any caller
        // that did not open a span -- must not print, and must not throw.
        ThinkingView.liveDelta("orphaned thought\n");

        assertThat(output.getAllOutput()).isEmpty();
    }

    @Test
    public void thinkingThatStreamedLiveIsNotPrintedAgainBesideTheReply() {
        ThinkingView.liveBegin();
        ThinkingView.liveDelta("the live thought\n");
        ThinkingView.liveEnd();
        ThinkingView.reported("the live thought");

        // The end-of-turn render has nothing left to say: the reasoning was the display already,
        // and the reply -- which this method never prints anyway -- carries no inline blocks.
        ThinkingView.renderAlongside("the answer");

        assertThat(output.getAllOutput()).containsOnlyOnce("the live thought");
    }

    @Test
    public void aNewSpanStreamAgain() {
        ThinkingView.liveBegin();
        ThinkingView.liveDelta("first request's thought\n");
        ThinkingView.liveEnd();
        ThinkingView.renderAlongside("first answer");

        ThinkingView.liveBegin(); // the next request: its thinking is its own again
        ThinkingView.liveDelta("second request's thought\n");
        ThinkingView.liveEnd();

        assertThat(ThinkingView.wasStreamedLive()).isTrue();
        assertThat(output.getAllOutput()).contains("second request's thought");
    }

    @Test
    public void aRunOnThoughtKeepsMovingAtTheSoftWidth() {
        // Some providers punctuate sparingly. A line that never ends would hold the whole thought
        // until the end of the request, which is the silence this exists to end.
        StringBuilder runOn = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            runOn.append("word "); // 150 characters, past the soft-flush width
        }
        ThinkingView.liveBegin();
        ThinkingView.liveDelta(runOn.toString());

        assertThat(output.getAllOutput()).isNotEmpty();
    }
}
