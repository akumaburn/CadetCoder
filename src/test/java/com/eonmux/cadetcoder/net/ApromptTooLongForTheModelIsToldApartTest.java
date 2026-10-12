package com.eonmux.cadetcoder.net;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A provider that refuses a prompt for being longer than the model's context window.
 *
 * <h2>The defect</h2>
 *
 * <p>The refusal was an ordinary rejected request. Nothing in the tool could tell it from a wrong
 * model name, so the run ended where folding the transcript and asking again would have worked.
 * Its message also sent the reader to check the model name and the request settings, and neither
 * was at fault.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That each provider's wording of the refusal maps to {@link LLMContextOverflowException}, that
 * the token count the provider gives is read from the whole body, and that any other rejected
 * request is still the plain kind.</p>
 */
public class ApromptTooLongForTheModelIsToldApartTest {

    private static LLMException mapped(int status, String body) {
        return LLMErrorMapper.fromStatus("local", "qwen", "http://localhost:8000/v1", status, body,
                                         null);
    }

    /** The body the user's vLLM server sent, word for word. */
    @Test
    public void vllmSaysTheMaximumContextLength() {
        LLMException failure = mapped(400,
                "{\"error\":{\"message\":\"This model's maximum context length is 393216 tokens."
                + " However, you requested 0 output tokens and your prompt contains at least"
                + " 393217 input tokens, for a total of at least 393217 tokens. Please reduce the"
                + " length of the input prompt or the number of requested output tokens.\","
                + "\"type\":\"BadRequestError\",\"param\":null,\"code\":400}}");

        assertThat(failure).isInstanceOf(LLMContextOverflowException.class);
        assertThat(((LLMContextOverflowException) failure).promptTokens()).isEqualTo(393_217);
        assertThat(failure.getKind()).isEqualTo(LLMException.Kind.BAD_REQUEST);
        assertThat(failure.isRetryable()).as("the same prompt is refused again").isFalse();
        assertThat(failure.getMessage())
                .contains("Provider 'local'")
                .contains("longer than the model's context window")
                .contains("393,217 tokens")
                .doesNotContain("check the model name");
    }

    @Test
    public void openAiNamesTheCode() {
        LLMException failure = mapped(400,
                "{\"error\":{\"message\":\"This model's maximum context length is 128000 tokens."
                + " However, your messages resulted in 130543 tokens. Please reduce the length of"
                + " the messages.\",\"type\":\"invalid_request_error\",\"param\":\"messages\","
                + "\"code\":\"context_length_exceeded\"}}");

        assertThat(((LLMContextOverflowException) failure).promptTokens()).isEqualTo(130_543);
    }

    @Test
    public void llamaServerGivesTheCountAsAField() {
        LLMException failure = mapped(400,
                "{\"error\":{\"code\":400,\"message\":\"the request exceeds the available context"
                + " size, try increasing it\",\"type\":\"exceed_context_size_error\","
                + "\"n_prompt_tokens\":40312,\"n_ctx\":32768}}");

        assertThat(((LLMContextOverflowException) failure).promptTokens()).isEqualTo(40_312);
    }

    @Test
    public void anthropicSaysThePromptIsTooLong() {
        LLMException failure = mapped(400,
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"prompt is too long: 212345 tokens > 200000 maximum\"}}");

        assertThat(((LLMContextOverflowException) failure).promptTokens()).isEqualTo(212_345);
    }

    @Test
    public void geminiCountsTheInputTokens() {
        LLMException failure = mapped(400,
                "{\"error\":{\"code\":400,\"message\":\"The input token count (1205000) exceeds"
                + " the maximum number of tokens allowed (1048576).\","
                + "\"status\":\"INVALID_ARGUMENT\"}}");

        assertThat(((LLMContextOverflowException) failure).promptTokens()).isEqualTo(1_205_000);
    }

    @Test
    public void bedrockGivesNoCount() {
        LLMException failure = mapped(400,
                "{\"message\":\"Input is too long for requested model.\"}");

        assertThat(failure).isInstanceOf(LLMContextOverflowException.class);
        assertThat(((LLMContextOverflowException) failure).promptTokens()).isZero();
        assertThat(failure.getMessage()).contains("longer than the model's context window")
                                        .doesNotContain("0 tokens");
    }

    /** A server that will not take a body that large is answered the same way: a smaller one. */
    @Test
    public void aBodyTooLargeForTheServerIsTheSameRefusal() {
        assertThat(mapped(413, "Request Entity Too Large"))
                .isInstanceOf(LLMContextOverflowException.class);
    }

    /** The excerpt is cut at 200 characters, and the count can sit past that. */
    @Test
    public void theCountIsReadFromTheWholeBody() {
        LLMException failure = mapped(400,
                "{\"error\":{\"message\":\"" + "padding ".repeat(40) + "This model's maximum"
                + " context length is 8192 tokens. However, your messages resulted in 9000"
                + " tokens.\",\"code\":\"context_length_exceeded\"}}");

        assertThat(((LLMContextOverflowException) failure).promptTokens()).isEqualTo(9_000);
    }

    /**
     * A count no prompt can have says nothing about the prompt. Taken as one, it would set the
     * model's characters per token near nothing, and every later prompt would measure as larger
     * than any window.
     */
    @Test
    public void aCountTooLargeToReadIsNoCount() {
        LLMException failure = mapped(400,
                "{\"error\":{\"message\":\"This model's maximum context length is 8192 tokens."
                + " However, your prompt contains at least 99999999999999 input tokens.\"}}");

        assertThat(((LLMContextOverflowException) failure).promptTokens()).isZero();
    }

    @Test
    public void anyOtherRejectionIsStillThePlainKind() {
        LLMException failure = mapped(400,
                "{\"error\":{\"message\":\"Unknown model: qwen\",\"code\":400}}");

        assertThat(failure).isExactlyInstanceOf(LLMBadRequestException.class);
    }
}
