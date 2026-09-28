package com.eonmux.cadetcoder.ai;

import java.util.Map;

/**
 * Interface for AI model clients
 */
public interface AIClient {
    /**
     * Sends a prompt to the AI model and returns the response
     *
     * @param promptData The prompt to send
     * @param parameters Additional parameters for the model
     * @return The AI model's response
     */
    String complete(PromptData promptData, Map<String, Object> parameters) throws Exception;

    /**
     * Checks if the AI client is available
     *
     * @return true if the client is available, false otherwise
     */
    boolean isAvailable();

    /**
     * Gets the name of the model being used
     *
     * @return The model name
     */
    String getModelName();

    /**
     * Whether the images on a request would actually reach the model.
     *
     * <h2>Why the caller has to be able to ask</h2>
     *
     * <p>Every client here drops images the wire cannot carry or the model cannot read, inside
     * {@link #complete}, because that is where the backend and the model id are known. The decision
     * was therefore invisible to the caller, and {@code AIManager} marked the turn's attachment
     * delivered on the strength of having attached it -- so a picture dropped by
     * {@link ImageChannel} was recorded as shown to a model that never saw it, and
     * {@link PromptAttachments}, whose whole contract is that an attachment is spent once delivered
     * rather than once read, had nothing left to send on the next request.</p>
     *
     * <h2>Why a client that says nothing answers no</h2>
     *
     * <p>The answer decides whether the turn stops carrying its attachment. "I do not know" and
     * "yes" must not be the same answer to that question: an image sent twice costs tokens, and an
     * image silently never sent costs the user their question. A client that does not implement
     * this has not said the images arrived.</p>
     *
     * @param promptData the request, which may carry images
     * @return whether the images it carries would be sent as they are
     */
    default boolean deliversImages(PromptData promptData) {
        return false;
    }
}
