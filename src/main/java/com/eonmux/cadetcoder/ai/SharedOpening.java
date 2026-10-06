package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.commands.CommandCatalog;

import java.util.List;

/**
 * Where the opening that every loop's system prompt shares ends.
 *
 * <h2>Why a request needs to know</h2>
 *
 * <p>Every loop's system prompt opens with the operating principles and then the command list; see
 * {@link CommandCatalog}. A provider that caches without being asked reuses that opening by
 * itself. A provider that caches only up to a marker in the request -- Anthropic, Bedrock, the
 * Anthropic models behind OpenRouter, and OpenAI's GPT-5.6 and later -- reuses it only when a marker
 * sits exactly at its end, so the backends for those providers ask this class where to put one.</p>
 *
 * <p>The end is found in the text rather than carried beside it, so a prompt reaches the backend as
 * the one string it already is, and a prompt that does not open this way is marked as before.</p>
 */
public final class SharedOpening {

    private SharedOpening() {
    }

    /**
     * The length of the shared opening at the start of a system prompt.
     *
     * @param systemPrompt the system prompt as it will be sent, principles included
     * @return the opening's length, or {@code 0} when the prompt does not open with it or only
     *         blank text follows it, which some providers refuse as a block of its own
     */
    public static int lengthIn(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            return 0;
        }
        // The full list first: a worker's list is the full list without its last entry, so a
        // worker's opening is also the start of everyone else's.
        for (String list : List.of(CommandCatalog.coreCommands(), CommandCatalog.workerCommands())) {
            String opening = SystemPromptProvider.compose(CommandCatalog.promptBlock(list));
            if (systemPrompt.startsWith(opening)) {
                return systemPrompt.substring(opening.length()).isBlank() ? 0 : opening.length();
            }
        }
        return 0;
    }

    /**
     * A system prompt cut where the shared opening ends.
     *
     * @param systemPrompt the system prompt as it will be sent
     * @return the opening and the rest, the prompt alone when it has no shared opening, or nothing
     *         when there is no prompt
     */
    public static List<String> split(String systemPrompt) {
        if (systemPrompt == null) {
            return List.of();
        }
        int end = lengthIn(systemPrompt);
        return end == 0 ? List.of(systemPrompt)
                        : List.of(systemPrompt.substring(0, end), systemPrompt.substring(end));
    }
}
