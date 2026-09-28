package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.After;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolving how many tokens the model can take as input.
 *
 * <p>Distinct from {@code ai.maxTokens}, which is how many it may generate. One field answered both
 * questions, so the space available for a prompt was whatever the completion limit happened to be.</p>
 *
 * <h2>Why a gateway's model is looked up three ways</h2>
 *
 * <p>The catalog is keyed by the provider that serves a model, and it does not list gateways. A
 * model reached through one was therefore unknown, and every prompt was measured against the
 * fallback: a model with a million-token window was worked to eight thousand, and the first
 * request of a fresh session came back "cannot make this request fit" by about four thousand
 * tokens. The vendor named in the model id publishes the real figure, so it is asked. When the
 * prefix is a brand rather than a provider id, as {@code z-ai/glm-5.3-flash} is, that misses too,
 * and the model's own name is looked up across the catalog instead.</p>
 */
public class ContextWindowTest {

    private Integer originalContextTokens;

    @After
    public void tearDown() {
        if (originalContextTokens != null) {
            ConfigManager.getInstance().getConfig().getAi().setContextTokens(originalContextTokens);
        }
    }

    private Configuration.AiConfig ai() {
        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        if (originalContextTokens == null) {
            originalContextTokens = ai.getContextTokens();
        }
        return ai;
    }

    @Test
    public void anExplicitSettingWins() {
        ai().setContextTokens(123_456);

        assertThat(ContextWindow.tokens()).isEqualTo(123_456);
    }

    @Test
    public void zeroMeansResolveItAutomatically() {
        ai().setContextTokens(0);

        // Whatever it resolves to, it must be usable: positive, and not the old completion limit
        // masquerading as an input budget.
        assertThat(ContextWindow.tokens()).isPositive();
        assertThat(ContextWindow.tokens()).isGreaterThanOrEqualTo(ContextWindow.DEFAULT_TOKENS);
    }

    @Test
    public void anUncataloguedEndpointFallsBackRatherThanReportingNothing() {
        assertThat(ContextWindow.publishedContext("not-a-provider", "not-a-model")).isZero();
        assertThat(ContextWindow.publishedContext(null, null)).isZero();
        assertThat(ContextWindow.publishedContext("", "")).isZero();
    }

    @Test
    public void theInputBudgetIsNotTheCompletionLimit() {
        ai().setContextTokens(0);
        int completionLimit = ai().getMaxTokens();

        // The defect this replaced: both numbers came from ai.maxTokens, so a model accepting
        // 200,000 tokens of input was given a prompt budget of 4,096.
        assertThat(ContextWindow.tokens()).isNotEqualTo(completionLimit);
    }

    @Test
    public void amodelReachedThroughAgatewayKeepsItsOwnWindow() {
        int direct = ContextWindow.publishedContext("deepseek", "deepseek-v4-flash");
        assertThat(direct).as("the catalog publishes this model's window").isGreaterThan(100_000);

        assertThat(ContextWindow.publishedContext("commandcode", "deepseek/deepseek-v4-flash"))
                .as("the same model, reached through a gateway the catalog has never heard of")
                .isEqualTo(direct);
    }

    @Test
    public void thespellingOfTheProviderAndModelIsNotWhatDecidesIt() {
        assertThat(ContextWindow.publishedContext("DeepSeek", "DeepSeek-V4-Flash"))
                .isEqualTo(ContextWindow.publishedContext("deepseek", "deepseek-v4-flash"));
    }

    @Test
    public void amodelNobodyPublishesStillFallsBack() {
        assertThat(ContextWindow.publishedContext("commandcode", "acme/private-model-9"))
                .as("a gateway's own model is not in any catalog, and that is not an error")
                .isZero();
    }

    @Test
    public void aCataloguedModelUsesItsPublishedWindow() {
        // Every model the catalog describes carries a context length; whichever one is configured,
        // the published figure is what a zero setting resolves to.
        int published = ContextWindow.publishedContext(ai().getProvider(), ai().getModel());
        if (published <= 0) {
            return; // no catalogued model configured in this environment
        }
        ai().setContextTokens(0);
        assertThat(ContextWindow.tokens()).isEqualTo(published);
    }

    /**
     * A pair the catalog publishes nothing for still gets the model's real window.
     *
     * <p>{@code commandcode} serving {@code z-ai/glm-5.3-flash} is the case this covers.
     * {@code commandcode} is a gateway, so it is not in the catalog, and {@code z-ai} is a
     * vendor's brand rather than a provider id, so asking the vendor misses too. The model's own
     * name is in the catalog once per provider serving it, which is where the window comes from.
     * {@code glm-4.6} stands in for it here because the bundled snapshot is what this runs
     * against.</p>
     */
    @Test
    public void awindowNoProviderOfThisPairPublishesIsFoundUnderTheModelsName() {
        assertThat(ContextWindow.publishedContext("commandcode", "z-ai/glm-4.6"))
                .as("neither the gateway nor a vendor called z-ai is in the catalog")
                .isZero();

        ContextWindow.Window resolved = ContextWindow.resolvedFor("commandcode", "z-ai/glm-4.6");

        assertThat(resolved.tokens())
                .as("the window every provider of this model publishes, not the assumption")
                .isGreaterThan(100_000);
        assertThat(resolved.source()).contains("glm-4.6").contains("providers that list it");
    }

    @Test
    public void amodelNoProviderListsAtAllIsStillAssumed() {
        ContextWindow.Window resolved =
                ContextWindow.resolvedFor("commandcode", "acme/private-model-9");

        assertThat(resolved.tokens()).isEqualTo(ContextWindow.DEFAULT_TOKENS);
        assertThat(resolved.source()).startsWith("assumed");
    }
}
