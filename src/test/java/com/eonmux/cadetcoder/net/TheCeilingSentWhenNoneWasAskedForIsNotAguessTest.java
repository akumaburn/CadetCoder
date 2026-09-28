package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.OutputWindow;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the one wire that cannot be told "no ceiling" sends instead.
 *
 * <h2>The defect</h2>
 *
 * <p>Anthropic's Messages API requires {@code max_tokens}, so this is the one place a number has to
 * be chosen for a user who asked for no limit. The number came from a models.dev lookup keyed on the
 * connector id -- and the connector id is deliberately not always {@code anthropic}: a gateway that
 * resells Claude is its own provider, so that every failure names the provider the user actually
 * configured. models.dev does not list the gateway, so the lookup missed on every single request
 * such a gateway made, and the fallback of 4,096 went out instead. Claude Sonnet 4.5 can write
 * 64,000: answers stopped at a sixteenth of what the model would have produced, with nothing on
 * screen to say a ceiling had been imposed at all.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That a gateway's Claude gets the ceiling Claude has; that a ceiling the user configured beats
 * anything the catalog says; and that when genuinely nothing is known, the assumption is stated
 * rather than applied in silence.</p>
 */
class TheCeilingSentWhenNoneWasAskedForIsNotAguessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A model the catalog lists under {@code anthropic}, and the ceiling it publishes. */
    private static final String CATALOGUED_MODEL   = "claude-sonnet-4-5";
    private static final String UNCATALOGUED_MODEL = "a-model-no-catalog-knows";

    private int originalMaxTokens;

    @BeforeEach
    void rememberWhatWasConfigured() {
        originalMaxTokens = ai().getMaxTokens();
        ai().setMaxTokens(OutputBudget.UNLIMITED);
    }

    @AfterEach
    void restoreWhatWasConfigured() {
        ai().setMaxTokens(originalMaxTokens);
    }

    private static Configuration.AiConfig ai() {
        return ConfigManager.getInstance().getConfig().getAi();
    }

    /**
     * What the Anthropic connector itself is sent for the catalogued model.
     *
     * <p>Read through the public entry point rather than the catalog, because the claim every test
     * below makes is that a gateway is treated the same as the vendor -- and that is a comparison
     * between two answers of the same method, not between an answer and a number copied out of a
     * snapshot that will be refreshed.</p>
     */
    private static int ceilingForTheVendorsOwnDoor() {
        return OutputWindow.forRequiredCeiling(
                AnthropicBackend.DEFAULT_PROVIDER_ID, CATALOGUED_MODEL,
                AnthropicBackend.DEFAULT_PROVIDER_ID);
    }

    /** The {@code max_tokens} the Messages wire would send for a provider and model. */
    private static int ceilingSentBy(String providerId, String modelId) throws Exception {
        AnthropicBackend backend =
                new AnthropicBackend(modelId, "https://example.invalid", "key", providerId);
        String json = MAPPER.writeValueAsString(
                backend.buildBody("system", "user", 0.7f, OutputBudget.UNLIMITED, false));
        return MAPPER.readTree(json).get("max_tokens").asInt();
    }

    @Test
    void agatewaysClaudeGetsTheCeilingClaudeHas() throws Exception {
        int published = ceilingForTheVendorsOwnDoor();
        assertThat(published)
                .as("the bundled catalog snapshot is what makes this answerable offline")
                .isGreaterThan(OutputWindow.DEFAULT_TOKENS);

        assertThat(ceilingSentBy("commandcode", CATALOGUED_MODEL))
                .as("the same model reached through a different door is the same model")
                .isEqualTo(published);
    }

    @Test
    void agatewayThatQualifiesTheModelIdIsStillUnderstood() throws Exception {
        assertThat(ceilingSentBy("openrouter", "anthropic/" + CATALOGUED_MODEL))
                .isEqualTo(ceilingForTheVendorsOwnDoor());
    }

    @Test
    void theAnthropicConnectorItselfIsUnaffected() throws Exception {
        assertThat(ceilingSentBy("anthropic", CATALOGUED_MODEL))
                .isEqualTo(ceilingForTheVendorsOwnDoor());
    }

    @Test
    void aCeilingTheUserConfiguredBeatsAnythingTheCatalogSays() throws Exception {
        ai().setMaxTokens(1234);

        assertThat(ceilingSentBy("commandcode", CATALOGUED_MODEL)).isEqualTo(1234);
    }

    @Test
    void aModelNothingKnowsAnythingAboutStillGetsAusableNumber() throws Exception {
        assertThat(ceilingSentBy("acme", UNCATALOGUED_MODEL))
                .as("the field is required, so some number has to go out")
                .isEqualTo(OutputWindow.DEFAULT_TOKENS);
    }
}
