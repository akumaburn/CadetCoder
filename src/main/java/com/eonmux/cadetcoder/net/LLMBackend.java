package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;

import java.util.Map;
import java.util.Set;

/**
 * Interface for interacting with various LLM backends.
 */
public interface LLMBackend {
    /**
     * Sends a prompt to the LLM backend and returns the completion.
     *
     * @param promptData An object containing both the system and user prompts.
     * @param parameters Additional parameters.
     * @return The completion text.
     * @throws Exception if the operation fails.
     */
    String complete(PromptData promptData, Map<String, Object> parameters) throws Exception;

    /**
     * Checks whether the backend is available.
     *
     * @return true if available, false otherwise.
     */
    boolean isAvailable();

    /**
     * Returns the model name used by this backend.
     *
     * @return The model name.
     */
    String getModelName();

    /**
     * The image types this wire documents that it takes.
     *
     * <p>Every backend shipped here takes some, each in its own shape, and each says which by
     * overriding this with one of the lists in {@link ImageMediaTypes}. The default is empty
     * because that is the safe answer for a wire added later: a request that quietly dropped an
     * image would have the model answering about a picture it was never shown, which is a wrong
     * answer with nothing in it to say so.</p>
     *
     * <p>The answer is a list rather than a yes or no because the wires disagree about formats,
     * not only about images. Gemini takes HEIC and HEIF and does not take GIF; the other three
     * take GIF and neither of the first two. A type the wire does not take is refused with the
     * whole request, so {@code ai/ImageChannel} asks this before a request goes out and drops the
     * images it names rather than losing the question they came with.</p>
     *
     * @return the media types this wire carries, empty when it has no place to put an image
     */
    default Set<String> imageMediaTypes() {
        return ImageMediaTypes.NONE;
    }

    /**
     * Returns the base URL every request from this backend is sent to.
     *
     * <p>Where a backend talks to is as much a part of its identity as which model it asks for,
     * and it is not always the provider's public host: {@code baseURL} is a per-provider option,
     * so the same connector can be pointed at a gateway, a private endpoint, or a local server.
     * Without an accessor there was no way to establish, from outside, that a configured
     * {@code baseURL} had actually been honoured.</p>
     *
     * @return the base URL, without a trailing slash or a request path
     */
    String getApiEndpoint();
}
