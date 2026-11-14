package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The thinking tokens a reasoning model produced, and the toggle over seeing them.
 *
 * <p><b>The defect</b>: both forms of thinking reached every reader but the person watching. A
 * provider that reports the reasoning in a field beside the answer ({@code reasoning_content}) had
 * that field thrown away when the reply carried content; a model that wrote its thought into the
 * reply inside a {@code <think>} block had it stripped by the parsers before anything displayed
 * it. A user watching a reasoning model work saw the answer and never the thought it cost.</p>
 *
 * <p><b>What is locked here</b>: the side channel's lifecycle (reported per attempt, last wins,
 * drained once, cleared between requests), the inline blocks read out of the reply without
 * touching the reply, the toggle -- collapsed where a console can open it, inline when asked,
 * silent on a plain console that did not ask -- and the strictness of the override.</p>
 */
public class ThinkingViewTest {

    private String previousProperty;
    private String previousTui;
    private TestOutputCapture output;

    @Before
    public void setUp() {
        previousProperty = System.getProperty(ThinkingView.PROPERTY);
        previousTui      = System.getProperty(TuiMode.OVERRIDE_PROPERTY);
        System.clearProperty(ThinkingView.PROPERTY);
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
        ThinkingView.clear();
        restore(ThinkingView.PROPERTY, previousProperty);
        restore(TuiMode.OVERRIDE_PROPERTY, previousTui);
    }

    /* -- the side channel -------------------------------------------------- */

    @Test
    public void reasoningReportedBesideTheReplyIsDrainedOnce() {
        ThinkingView.reported("the model's thought");

        assertThat(ThinkingView.drain()).isEqualTo("the model's thought");
        assertThat(ThinkingView.drain()).as("drained, not left for the next request").isNull();
    }

    @Test
    public void theLastAttemptToReportWins() {
        ThinkingView.reported("the first attempt's thought");
        ThinkingView.reported("the second attempt's thought");

        assertThat(ThinkingView.drain()).isEqualTo("the second attempt's thought");
    }

    @Test
    public void nothingReportedMeansNothingToShowAndNothingLeaking() {
        ThinkingView.reported(null);
        ThinkingView.reported("   ");

        assertThat(ThinkingView.drain()).isNull();
    }

    @Test
    public void aRequestThatClearsLeavesNothingForTheNextOne() {
        ThinkingView.reported("a thought whose request failed");
        ThinkingView.clear();

        assertThat(ThinkingView.drain()).isNull();
    }

    /* -- the inline blocks ------------------------------------------------- */

    @Test
    public void theBlocksAreReadOutOfTheReplyInOrder() {
        List<String> blocks = ThinkingView.blocksIn(
                "<think>first thought</think>the answer <think>second thought</think>");

        assertThat(blocks).containsExactly("first thought", "second thought");
    }

    @Test
    public void anUnterminatedBlockIsNotAThought() {
        assertThat(ThinkingView.blocksIn("<think>a thought cut off by the output limit"))
                .isEmpty();
    }

    @Test
    public void anEmptyBlockIsNotAThought() {
        assertThat(ThinkingView.blocksIn("<think></think>the answer")).isEmpty();
    }

    /* -- the toggle -------------------------------------------------------- */

    @Test
    public void hiddenOnAPlainConsoleByDefault() {
        // No TUI override: the console cannot open a collapsed run, and nothing asked for inline.
        ThinkingView.reported("the thought");
        ThinkingView.renderAlongside("the answer");

        assertThat(output.getAllOutput())
                .as("the default is silence where there is nothing to click")
                .doesNotContain("the thought");
    }

    @Test
    public void theSystemPropertyShowsItInline() {
        System.setProperty(ThinkingView.PROPERTY, "true");

        ThinkingView.reported("the thought");
        ThinkingView.renderAlongside("the answer");

        assertThat(output.getAllOutput()).contains("the thought");
    }

    @Test
    public void thePropertySaysFalseMeansNotInlineAndTheShellStillCollapsesIt() {
        // The same convention as showCommandOutput: false is the ordinary view, not suppression.
        // In the shell that is the collapsed run.
        System.setProperty(ThinkingView.PROPERTY, "false");
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");

        ThinkingView.reported("the thought");
        ThinkingView.renderAlongside("the answer");

        String shown = output.getAllOutput();
        assertThat(shown).as("the collapsed run, not inline text")
                .contains(CollapsedOutput.OPEN, CollapsedOutput.CLOSE);
    }

    @Test
    public void aValueThatIsNotAnAnswerLeavesThePreferenceInCharge() {
        System.setProperty(ThinkingView.PROPERTY, "yes");

        // The default configuration has it off; the point is that a nonsensical override neither
        // throws nor accidentally reads as "true".
        assertThat(ThinkingView.isShownInline()).isFalse();
    }

    @Test
    public void inTheShellItPrintsAsACollapsedRunThatOpens() {
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");

        ThinkingView.reported("the provider's reasoning field");
        ThinkingView.renderAlongside("the answer");

        String shown = output.getAllOutput();
        assertThat(shown).contains(CollapsedOutput.OPEN);
        assertThat(shown).contains("the provider's reasoning field");
        assertThat(shown).contains(CollapsedOutput.CLOSE);
    }

    @Test
    public void theInlineBlocksAreShownTheSameWay() {
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");

        ThinkingView.renderAlongside("<think>a thought written into the reply</think>the answer");

        String shown = output.getAllOutput();
        assertThat(shown).contains("a thought written into the reply");
        assertThat(shown).contains(CollapsedOutput.CLOSE);
    }

    @Test
    public void nothingToSayPrintsNothing() {
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");

        ThinkingView.renderAlongside("the answer, with no thinking anywhere");

        assertThat(output.getAllOutput()).doesNotContain(CollapsedOutput.OPEN);
    }

    @Test
    public void theReplyItselfIsNeverChanged() {
        // The reply is passed in for its inline blocks and handed back by no one: the contract is
        // that showing the thinking does not alter what the callers parse and record.
        String reply = "<think>a thought</think>the answer";

        ThinkingView.renderAlongside(reply);

        assertThat(reply).isEqualTo("<think>a thought</think>the answer");
    }

    @Test
    public void theConfiguredPreferenceIsReadWhenNoOverrideIsSet() {
        Configuration config = ConfigManager.getInstance().getConfig();
        try {
            config.getUi().setShowThinking(true);
            assertThat(ThinkingView.isShownInline()).isTrue();
            config.getUi().setShowThinking(false);
            assertThat(ThinkingView.isShownInline()).isFalse();
        } finally {
            config.getUi().setShowThinking(false);
        }
    }

    /* -- plumbing ---------------------------------------------------------- */

    private static void restore(String property, String previous) {
        if (previous == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, previous);
        }
    }
}
