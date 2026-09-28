package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.OutputWindow;
import com.eonmux.cadetcoder.config.Configuration;

import org.junit.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing guesses how long an answer should be.
 *
 * <p><b>Why this changed</b>: every wire sent {@code max_tokens: 4096}, a number nobody chose for
 * the question being asked. How long an answer needs to be is not knowable before it is written, and
 * a guess is only ever wrong in the direction that truncates. The cost rose with reasoning models,
 * which charge their thinking to the same budget: {@code deepseek/deepseek-v4-flash} through Command
 * Code returned {@code finish_reason: length} and no answer at 64 and at 256 tokens, and answered at
 * 4,096. How much a model thinks varies with what it is asked, so one fixed budget serves some turns
 * and not others.</p>
 *
 * <p>Both halves were confirmed against the live gateway. Omitting the field on
 * {@code /chat/completions} returns 200 and a complete answer. Omitting it on {@code /messages}
 * returns {@code 400 invalid_request_error: "Invalid input: expected number, received undefined"},
 * which is why that one wire still sends a number.</p>
 */
public class NoCeilingIsAskedForUnlessSomebodyAsksTest {

    private static final Map<String, Object> NOTHING_ASKED = Map.of();

    // ---------------------------------------------------------------- the rule

    @Test
    public void zeroMeansNoCeiling() {
        assertThat(OutputBudget.isLimited(OutputBudget.UNLIMITED)).isFalse();
        assertThat(OutputBudget.UNLIMITED).isZero();
    }

    @Test
    public void aNumberSomebodyAskedForIsALimit() {
        assertThat(OutputBudget.isLimited(4096)).isTrue();
        assertThat(OutputBudget.isLimited(1)).isTrue();
    }

    @Test
    public void nonsenseIsNotALimitEither() {
        // A negative budget cannot be sent to anything, and reading it as "unlimited" is the only
        // interpretation that does not produce a request no provider will accept.
        assertThat(OutputBudget.isLimited(-1)).isFalse();
    }

    // ---------------------------------------------------------------- the default

    @Test
    public void theShippedConfigurationAsksForNoCeiling() {
        assertThat(new Configuration.AiConfig().getMaxTokens())
                .isEqualTo(OutputBudget.UNLIMITED);
    }

    // ---------------------------------------------------------------- the wires

    @Test
    public void theAnthropicWireStillSendsANumber() throws Exception {
        // Its API rejects a request without the field, so "no limit" cannot be expressed there. The
        // number sent is the model's own published ceiling, which for an uncatalogued model is the
        // assumed default.
        assertThat(messagesBody(OutputBudget.UNLIMITED))
                .contains("\"max_tokens\":" + OutputWindow.DEFAULT_TOKENS);
    }

    @Test
    public void theAssumedOutputLimitIsValidForEveryClaudeModel() {
        // 4,096 is the whole allowance of the smallest Claude models, so it cannot be refused by any
        // of them. A larger assumption would be a 400 on some, which is worse than a smaller budget.
        assertThat(OutputWindow.DEFAULT_TOKENS).isEqualTo(4096);
    }

    @Test
    public void anAskedForBudgetReachesTheAnthropicWireUnchanged() throws Exception {
        assertThat(messagesBody(64000)).contains("\"max_tokens\":64000");
    }

    @Test
    public void theOpenAiCompatibleWireOmitsTheFieldWhenNoneWasAsked() throws Exception {
        assertThat(chatCompletionsBody(NOTHING_ASKED)).doesNotContain("max_tokens");
    }

    @Test
    public void theOpenAiCompatibleWireSendsABudgetSomebodyAskedFor() throws Exception {
        assertThat(chatCompletionsBody(Map.of("maxTokens", 1234)))
                .contains("\"max_tokens\":1234");
    }

    /**
     * The request body {@link OpenAICompatibleBackend} would send.
     *
     * <p>Built rather than sent: what is being asked is what goes on the wire, and a test that had
     * to reach a provider to find out would be testing the provider.</p>
     */
    private static String chatCompletionsBody(Map<String, Object> parameters) throws Exception {
        OpenAICompatibleBackend backend = new OpenAICompatibleBackend(
                "deepseek/deepseek-v4-flash", "https://example.invalid/v1", "a-key",
                com.eonmux.cadetcoder.ai.providers.AuthScheme.BEARER, Map.of(), Map.of());
        int budget = OpenAICompatibleBackend.numberParam(parameters, "maxTokens",
                                                         OutputBudget.UNLIMITED).intValue();
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                backend.buildBody(new com.eonmux.cadetcoder.ai.PromptData("system", "user"),
                                  0.7f, budget, false));
    }

    /** The request body {@link AnthropicBackend} would send for a given budget. */
    private static String messagesBody(int maxTokens) throws Exception {
        AnthropicBackend backend = new AnthropicBackend(
                "a-model-no-catalog-knows", "https://example.invalid", "a-key");
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                backend.buildBody("system", "user", 0.7f, maxTokens, false));
    }
}
