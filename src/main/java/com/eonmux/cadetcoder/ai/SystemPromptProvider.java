package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.prompts.PromptResource;
import com.eonmux.cadetcoder.prompts.PromptTemplateEngine;

/**
 * The operating principles that lead the system prompt of every AI request.
 *
 * <p>Commands supply the part of the system prompt that is specific to what they are doing -- the
 * command catalog, the required action format, the response contract. This supplies the part that is
 * the same whatever the command: how the agent is expected to work. It is composed in
 * {@link AIManager#complete}, the one point every completion funnels through, so a command cannot
 * omit it and a new command inherits it without doing anything.</p>
 *
 * <h2>Position</h2>
 *
 * <p>The principles lead and the command's own prompt follows, for two reasons. A model weights
 * format instructions nearest the user turn most heavily, so the action contract stays where it has
 * the most effect. And the block is a constant, so it forms a stable prefix that a provider can
 * serve from cache rather than a varying suffix that would invalidate one.</p>
 *
 * <h2>Overriding</h2>
 *
 * <p>The text is a prompt resource like any other, resolved by {@link PromptTemplateEngine}:
 * {@code ~/.cadet/prompts/system.json} wins over the shipped default, and a user override whose
 * content is empty removes the block entirely. The engine owns the caching, and with it every
 * invalidation path the tool already has -- {@code prompt edit}, {@code prompt delete} and
 * {@code prompt reload} all take effect here without this class knowing about them. Holding a second
 * copy here would have gone stale behind all three inside the long-lived interactive shell.</p>
 */
public final class SystemPromptProvider {

    /** Name of the prompt resource holding the principles. */
    public static final String PROMPT_NAME = "system";

    private SystemPromptProvider() {
    }

    /**
     * @return the operating principles, or an empty string when none are configured
     */
    public static String principles() {
        return load();
    }

    /**
     * Composes the system prompt actually sent for a request.
     *
     * @param commandPrompt the calling command's own system prompt, possibly empty
     * @return the principles followed by {@code commandPrompt}, either part omitted when blank
     */
    public static String compose(String commandPrompt) {
        String base = principles();
        String tail = commandPrompt == null ? "" : commandPrompt;
        if (base.isEmpty()) {
            return tail;
        }
        if (tail.isBlank()) {
            return base;
        }
        return base + "\n\n" + tail;
    }

    private static String load() {
        return PromptResource.text(PROMPT_NAME);
    }

    /**
     * Whether {@code text} is one of the engine's load-failure messages rather than a template.
     *
     * @param text the text the engine returned
     * @return {@code true} when it reports a failure to load
     */
    static boolean looksLikeLoadFailure(String text) {
        return PromptResource.looksLikeLoadFailure(text);
    }
}
