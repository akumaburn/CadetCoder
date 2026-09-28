package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolving how many tokens the model can produce.
 *
 * <p>No ceiling is sent on the wire by default, so this number is not the request's limit. Two
 * callers still need it: the compactor holds room back in the input window for a reply, and the
 * Anthropic Messages wire requires a number it cannot omit.</p>
 */
public class OutputWindowTest {

    private Integer originalMaxTokens;

    @After
    public void tearDown() {
        if (originalMaxTokens != null) {
            ConfigManager.getInstance().getConfig().getAi().setMaxTokens(originalMaxTokens);
        }
    }

    private Configuration.AiConfig ai() {
        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        if (originalMaxTokens == null) {
            originalMaxTokens = ai.getMaxTokens();
        }
        return ai;
    }

    @Test
    public void aCeilingSomebodyAskedForWins() {
        ai().setMaxTokens(1234);

        assertThat(OutputWindow.tokens()).isEqualTo(1234);
    }

    @Test
    public void noCeilingResolvesToSomethingUsable() {
        ai().setMaxTokens(0);

        // Zero means no ceiling is sent. It must not mean "reserve nothing" to the callers that
        // need a number, so this always answers with a positive figure.
        assertThat(OutputWindow.tokens()).isPositive();
    }

    @Test
    public void anUncataloguedModelFallsBackRatherThanReportingNothing() {
        assertThat(OutputWindow.publishedOutput("not-a-provider", "not-a-model")).isZero();
        assertThat(OutputWindow.publishedOutput(null, null)).isZero();
        assertThat(OutputWindow.publishedOutput("", "")).isZero();

        assertThat(OutputWindow.forModel("not-a-provider", "not-a-model"))
                .isEqualTo(OutputWindow.DEFAULT_TOKENS);
    }

    @Test
    public void theAssumedLimitIsAcceptedByEveryProvider() {
        // Sent verbatim by the Anthropic wire for a model the catalog does not describe, so it has
        // to be a number no model refuses. 4,096 is the whole allowance of the smallest Claude
        // models and well under what every other provider permits.
        assertThat(OutputWindow.DEFAULT_TOKENS).isEqualTo(4096);
    }

    @Test
    public void aCataloguedModelUsesItsPublishedLimit() {
        int published = OutputWindow.publishedOutput(ai().getProvider(), ai().getModel());
        if (published <= 0) {
            return; // no catalogued model configured in this environment
        }
        ai().setMaxTokens(0);

        assertThat(OutputWindow.tokens()).isEqualTo(published);
    }

    /**
     * A ceiling nobody asked for and nobody was told about is indistinguishable, from the outside,
     * from a model that simply stops writing. The wire requires a number, so one is sent -- but the
     * line names the model and the setting that replaces the guess.
     */
    @Test
    public void anAssumedCeilingIsSaidOutLoud() {
        ai().setMaxTokens(0);
        OutputWindow.resetForTesting();
        TestOutputCapture console = new TestOutputCapture();
        try {
            OutputWindow.forRequiredCeiling("acme", "a-model-no-catalog-knows", "anthropic");
        } finally {
            console.restore();
        }

        assertThat(console.getAllOutput())
                .contains("a-model-no-catalog-knows")
                .contains("ai.maxTokens");
    }

    @Test
    public void theSameAssumptionIsNotAnnouncedOnEveryRequest() {
        ai().setMaxTokens(0);
        OutputWindow.resetForTesting();
        OutputWindow.forRequiredCeiling("acme", "a-model-no-catalog-knows", "anthropic");

        TestOutputCapture console = new TestOutputCapture();
        try {
            OutputWindow.forRequiredCeiling("acme", "a-model-no-catalog-knows", "anthropic");
        } finally {
            console.restore();
        }

        assertThat(console.getAllOutput())
                .as("one line about the model, not one per turn of a long run")
                .doesNotContain("a-model-no-catalog-knows");
    }

    @Test
    public void theOutputLimitIsNotTheInputWindow() {
        ai().setMaxTokens(0);

        // The two are unrelated numbers. A model that accepts 200,000 tokens and returns 8,000 is
        // ordinary, so one field cannot answer both questions.
        assertThat(OutputWindow.tokens()).isLessThanOrEqualTo(ContextWindow.tokens());
    }
}
