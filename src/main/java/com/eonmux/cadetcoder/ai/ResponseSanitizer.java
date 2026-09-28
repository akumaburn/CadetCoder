package com.eonmux.cadetcoder.ai;

import java.util.regex.Pattern;

/**
 * Strips chat-template control tokens that a model (especially a local, raw-completion model served
 * over llama.cpp / an OpenAI-compatible shim) can echo into its generated text.
 *
 * <p>When the prompt is rendered with an explicit chat template (ChatML, Llama, Mistral, ...), a
 * continuation model frequently emits the very delimiters it was trained on — {@code
 * <|im_start|>assistant}, a trailing {@code <|im_end|>}, {@code <|eot_id|>}, {@code [INST]}, {@code
 * <s>}, and so on — because the backend either did not register them as stop sequences or did not
 * strip them from the completion. Those tokens are control structure, never content: if they reach
 * the multi-tier {@code ResponseParsingEngine} they wrap the model's real payload (e.g. a JSON
 * action block) in noise, defeat JSON/ACTION detection, and end up captured verbatim as an action's
 * "reasoning" — which is then printed as {@code Executing: <|im_start|>assistant ...} and executed
 * as a bogus command.</p>
 *
 * <p>This sanitizer removes those tokens centrally (see {@link AIManager#complete}) so every
 * consumer — the chat/agent harness, the parsing engine, and the session history — sees clean text.
 * It is deliberately conservative: it removes only well-known template control tokens and a single
 * leading role label, leaving all ordinary prose, code, and markdown untouched.</p>
 */
public final class ResponseSanitizer {

    private ResponseSanitizer() {
    }

    /**
     * Any ChatML / GPT-style pipe-delimited special token: {@code <|...|>}. This covers
     * {@code <|im_start|>}, {@code <|im_end|>}, {@code <|im_sep|>}, {@code <|endoftext|>},
     * {@code <|eot_id|>}, {@code <|eom_id|>}, {@code <|start_header_id|>}, {@code <|end_header_id|>},
     * and any future variant, in one rule. The inner class {@code [^|>]*} forbids {@code |} and
     * {@code >} so the match cannot run past a single token.
     */
    private static final Pattern PIPE_SPECIAL_TOKEN = Pattern.compile("<\\|[^|>]*\\|>");

    /** Llama-2 / Mistral instruction markers: {@code [INST]} and {@code [/INST]}. */
    private static final Pattern INST_MARKER = Pattern.compile("\\[/?INST\\]", Pattern.CASE_INSENSITIVE);

    /** Llama-2 system-prompt markers: {@code <<SYS>>} and {@code <</SYS>>}. */
    private static final Pattern SYS_MARKER = Pattern.compile("<</?SYS>>", Pattern.CASE_INSENSITIVE);

    /**
     * A leading BOS sentinel ({@code <s>}) and a trailing EOS sentinel ({@code </s>}). These are
     * anchored to the string ends so that a legitimate {@code <s>...</s>} appearing inside content
     * (e.g. discussing HTML) is left alone — only a stray opening BOS at the very start or a stray
     * closing EOS at the very end (the shapes a continuation model actually leaks) is removed.
     */
    private static final Pattern LEADING_BOS  = Pattern.compile("^\\s*<s>\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_EOS  = Pattern.compile("\\s*</s>\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * A dangling chat role label left at the very start once an opening {@code <|im_start|>} token has
     * been removed, e.g. {@code "assistant\n{json}"} or {@code "assistant: hello"}. To avoid corrupting
     * ordinary prose that merely begins with one of these words (e.g. "User authentication is..."), the
     * role is stripped ONLY when it is a genuine chat delimiter — immediately followed by a colon or a
     * line break (optionally after trailing spaces), never when followed by more words on the same line.
     */
    private static final Pattern LEADING_ROLE = Pattern.compile(
            "^\\s*(?:assistant|system|user|tool)[ \\t]*(?::[ \\t]*|\\r?\\n)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Returns {@code response} with chat-template control tokens removed and surrounding whitespace
     * trimmed. {@code null} in yields {@code null} out; a response consisting solely of control
     * tokens collapses to an empty string (which the caller treats as an empty response).
     *
     * @param response the raw model completion (may be {@code null})
     * @return the cleaned text, or {@code null} if {@code response} was {@code null}
     */
    public static String sanitize(String response) {
        if (response == null) {
            return null;
        }

        String cleaned = PIPE_SPECIAL_TOKEN.matcher(response).replaceAll("");
        cleaned = INST_MARKER.matcher(cleaned).replaceAll("");
        cleaned = SYS_MARKER.matcher(cleaned).replaceAll("");
        cleaned = LEADING_BOS.matcher(cleaned).replaceFirst("");
        cleaned = TRAILING_EOS.matcher(cleaned).replaceFirst("");
        // Remove a role label only after the opening token is gone, and only once at the start.
        cleaned = LEADING_ROLE.matcher(cleaned).replaceFirst("");

        return cleaned.trim();
    }
}
