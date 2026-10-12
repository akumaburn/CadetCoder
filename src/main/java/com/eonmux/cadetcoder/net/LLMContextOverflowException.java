package com.eonmux.cadetcoder.net;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The provider refused the prompt because it is longer than the model's context window.
 *
 * <h2>Why it is a kind of its own</h2>
 *
 * <p>It is still a rejected request, and the same prompt is refused again, so it is never retried
 * as it stands. Unlike any other rejected request it has a remedy the tool can apply itself: a
 * loop that keeps a transcript can fold it and ask again. A plain {@link LLMBadRequestException}
 * cannot be told apart from a wrong model name, which no fold mends.</p>
 */
public class LLMContextOverflowException extends LLMBadRequestException {

    private static final long serialVersionUID = 1L;

    /** What each provider says, lower case. Matched on the whole body, never the excerpt. */
    private static final List<String> WORDINGS = List.of(
            "context_length_exceeded",              // OpenAI's code
            "maximum context length",               // OpenAI and vLLM
            "exceed_context_size_error",            // llama.cpp's type
            "exceeds the available context size",   // llama.cpp
            "prompt is too long",                   // Anthropic
            "input is too long",                    // Bedrock
            "exceeds the maximum number of tokens"  // Gemini
    );

    /** Where each provider puts the tokens it counted. The first that matches is taken. */
    private static final List<Pattern> COUNTS = List.of(
            Pattern.compile("prompt contains at least (\\d+)"),
            Pattern.compile("resulted in (\\d+) tokens"),
            Pattern.compile("prompt is too long: (\\d+) tokens"),
            Pattern.compile("input token count \\((\\d+)\\)"),
            Pattern.compile("\"n_prompt_tokens\"\\s*:\\s*(\\d+)"));

    /** HTTP 413: the server will not take a body that large. */
    private static final int TOO_LARGE = 413;

    private final int promptTokens;

    LLMContextOverflowException(String provider, String model, String endpoint, int statusCode,
                                String bodyExcerpt, int promptTokens) {
        super(withExcerpt(buildMessage(provider, model, statusCode, promptTokens), bodyExcerpt),
              provider, model, endpoint, statusCode, bodyExcerpt);
        this.promptTokens = Math.max(0, promptTokens);
    }

    /**
     * Reads a refusal as one over the prompt's length, when it is one.
     *
     * @param provider   the connector that refused
     * @param model      the model asked
     * @param endpoint   the endpoint asked
     * @param statusCode the HTTP status
     * @param body       the whole response body
     * @param excerpt    the part of it the message quotes
     * @return the failure, or {@code null} when the body names some other reason
     */
    static LLMContextOverflowException readFrom(String provider, String model, String endpoint,
                                                int statusCode, String body, String excerpt) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (statusCode != TOO_LARGE && WORDINGS.stream().noneMatch(lower::contains)) {
            return null;
        }
        return new LLMContextOverflowException(provider, model, endpoint, statusCode, excerpt,
                                               countIn(lower));
    }

    /** @return the tokens the provider said the prompt has at least; {@code 0} when it gave none */
    public int promptTokens() {
        return promptTokens;
    }

    private static int countIn(String body) {
        for (Pattern pattern : COUNTS) {
            Matcher found = pattern.matcher(body);
            if (found.find()) {
                try {
                    return Integer.parseInt(found.group(1));
                } catch (NumberFormatException tooLarge) {
                    // No prompt has that many tokens, so the figure says nothing about this one.
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String buildMessage(String provider, String model, int statusCode,
                                       int promptTokens) {
        return "Provider '" + providerLabel(provider) + "' refused the request for"
                + (modelPhrase(model).isEmpty() ? " the configured model" : modelPhrase(model))
                + " (HTTP " + statusCode + ") because the prompt"
                + (promptTokens > 0 ? String.format(Locale.ROOT, " (%,d tokens)", promptTokens)
                                    : "")
                + " is longer than the model's context window.";
    }
}
