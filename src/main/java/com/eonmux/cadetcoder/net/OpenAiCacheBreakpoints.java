package com.eonmux.cadetcoder.net;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which OpenAI models take an explicit {@code prompt_cache_breakpoint}.
 *
 * <h2>Why the model is checked before the field is sent</h2>
 *
 * <p>OpenAI documents the breakpoint for GPT-5.6 and later families, and Azure OpenAI documents it
 * for the same models on Chat Completions; see
 * https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/prompt-caching. Earlier models answer
 * a request that carries it with HTTP 400. {@link PromptCachePolicy} recovers from that rejection,
 * but the earlier models are the ones most requests go to, so each of them would cost a refused
 * request before the latch turned the marker off.</p>
 *
 * <p>The family is read from the model id. An Azure deployment is named by whoever created it, so a
 * deployment whose name does not say which model it serves is not marked.</p>
 */
final class OpenAiCacheBreakpoints {

    /** The connectors whose documentation describes the breakpoint. */
    private static final Set<String> PROVIDERS = Set.of("openai", "azure");

    /**
     * {@code gpt-} and a family version, after a provider prefix or a fine-tune prefix if there is
     * one. The major version is one digit, so Azure's {@code gpt-35-turbo} reads as no family
     * rather than as family 35.
     */
    private static final Pattern FAMILY = Pattern.compile("(?:^|[/:])gpt-(\\d)(?!\\d)(?:\\.(\\d+))?");

    private OpenAiCacheBreakpoints() {
    }

    /**
     * @param providerId the connector the request goes to
     * @param modelId    the model or Azure deployment name the request names
     * @return whether that model takes an explicit breakpoint
     */
    static boolean accepts(String providerId, String modelId) {
        if (providerId == null || modelId == null
                || !PROVIDERS.contains(providerId.toLowerCase(Locale.ROOT))) {
            return false;
        }
        Matcher family = FAMILY.matcher(modelId.toLowerCase(Locale.ROOT));
        if (!family.find()) {
            return false;
        }
        int major = Integer.parseInt(family.group(1));
        int minor = family.group(2) == null ? 0 : Integer.parseInt(family.group(2));
        return major > 5 || (major == 5 && minor >= 6);
    }
}
