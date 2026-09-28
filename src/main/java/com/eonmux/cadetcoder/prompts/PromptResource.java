package com.eonmux.cadetcoder.prompts;

import com.eonmux.cadetcoder.logging.CadetLogger;

/**
 * A prompt read for its text alone, with a load failure treated as an absence.
 *
 * <h2>Why a failure has to be recognised rather than passed on</h2>
 *
 * <p>{@link PromptTemplateEngine} reports a template it could not load by returning the failure
 * message as though it were the template. Whatever asked for it then puts "Failed to load prompt
 * content from: ..." into a system prompt, where the model reads it as an instruction. Every caller
 * that composes a prompt out of a named resource therefore has to know the engine's failure
 * messages -- which is one caller too many for that knowledge to live in.</p>
 *
 * <p>The blocks composed this way are all optional by design: operating principles a user may delete,
 * a directive that only applies in one mode. An absent one is not an error, so a failure to read one
 * is answered the same way as a user who removed it -- with nothing.</p>
 */
public final class PromptResource {

    /** Strings the engine returns in place of a template it could not load. */
    private static final String[] LOAD_FAILURE_SENTINELS = {
            "No template found for prompt:",
            "Failed to load prompt content from:",
            "Error loading prompt content:"
    };

    private PromptResource() {
    }

    /**
     * The text of a named prompt.
     *
     * @param name the prompt's name, as {@code ~/.cadet/prompts/<name>.json} would override it
     * @return the text, stripped, or an empty string when there is none to be had
     */
    public static String text(String name) {
        try {
            String loaded = PromptTemplateEngine.getInstance().getRawPrompt(name);
            if (loaded == null || loaded.isBlank() || looksLikeLoadFailure(loaded)) {
                return "";
            }
            return loaded.strip();
        } catch (RuntimeException e) {
            // Being unable to read one optional block must not stop the tool from answering.
            CadetLogger.getLogger(PromptResource.class)
                       .errorToFile("Could not load the prompt '" + name + "': " + e);
            return "";
        }
    }

    /**
     * @param text the text the engine returned
     * @return whether it reports a failure to load rather than being a template
     */
    public static boolean looksLikeLoadFailure(String text) {
        if (text == null) {
            return false;
        }
        String head = text.stripLeading();
        for (String sentinel : LOAD_FAILURE_SENTINELS) {
            if (head.startsWith(sentinel)) {
                return true;
            }
        }
        return false;
    }
}
