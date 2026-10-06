package com.eonmux.cadetcoder.net;

/**
 * How a Chat Completions request marks where a cacheable prefix ends.
 *
 * <p>The protocol has no marker of its own. Two spellings are documented by providers that speak
 * it, and they mean different things, so a request carries at most one of them.</p>
 */
enum InlineCacheMarker {

    /** Nothing is marked; the provider caches a shared prefix by itself, or not at all. */
    NONE,

    /**
     * {@code "cache_control": {"type": "ephemeral"}} on every text part: the end of the shared
     * opening, the end of the system prompt and the end of the user message. OpenRouter forwards it
     * to the Anthropic models it serves, which cache nothing without it.
     */
    CACHE_CONTROL,

    /**
     * {@code "prompt_cache_breakpoint": {"mode": "explicit"}} at the end of the shared opening only.
     * OpenAI's GPT-5.6 and later families place a breakpoint on the latest message by themselves,
     * and otherwise reuse a cached prefix only up to a breakpoint, so the shared opening inside the
     * system message is reused only when it is marked. Models before GPT-5.6 reject the field.
     */
    OPENING_BREAKPOINT
}
