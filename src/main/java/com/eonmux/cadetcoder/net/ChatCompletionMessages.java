package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.PromptImage;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The {@code messages} array a Chat Completions request carries.
 *
 * <h2>Why no chat template is applied</h2>
 *
 * <p>On this protocol the roles are the template. The server reads {@code system} and {@code user}
 * and renders them with the control tokens the model was trained on, which is the one rendering
 * that can be right: OpenAI knows its own models, and llama-server reads the template out of the
 * GGUF it loaded.</p>
 *
 * <p>A chat template belongs to a wire that sends one string and no roles, and there is no such
 * wire here -- all three of these backends post a {@code messages} array. This class rendered
 * {@code ai.chatTemplate} into a single {@code user} message anyway, and that setting defaults to
 * {@code chatml}, so by default every request went out as one message reading
 * {@code <|im_start|>system ... <|im_end|><|im_start|>user ... }. The model was handed its own
 * control tokens as literal text, the system prompt arrived with the user's role, and the server
 * then templated the whole thing a second time.</p>
 *
 * <p>It also broke the one request that could not hide it. Collapsed to a single message, an
 * attached image made that message's content a parts array -- so the first message's content was
 * an array, and a provider that joins message contents as strings answered
 * {@code sequence item 0: expected str instance, list found} and refused the request.</p>
 *
 * <h2>Why both backends build it here</h2>
 *
 * <p>Two classes post to {@code /v1/chat/completions}: {@link OpenAICompatibleBackend}, which every
 * connector-configured provider goes through, and {@link OpenAIBackend}, which the legacy
 * {@code APIClient} uses when no connector is configured. They had a message shape each, and the
 * second one built its JSON by hand, so it could not carry an image at all and its escaping was its
 * own. One implementation means an addition to the protocol reaches both.</p>
 *
 * <h2>The shape an image takes</h2>
 *
 * <p>A message whose content is a plain string cannot hold one, so an image turns the content into
 * an array of parts: one {@code image_url} part per image, then the text. {@code image_url} is an
 * object with a {@code url}, not a bare string -- the bare string belongs to the newer Responses
 * API, which is a different endpoint. The URL is a {@code data:} URL because the file is on this
 * machine, and a link to it is a link the provider cannot follow.</p>
 *
 * <h2>Why the pictures come before the question</h2>
 *
 * <p>Because they do on the other three wires, and a cache breakpoint means something. The text
 * part is where this protocol's breakpoint lives, and a breakpoint marks everything ahead of it, so
 * with the text first every image sat outside the cached prefix and was paid for again on every
 * turn of a run -- images being the most expensive thing a request carries. {@code AnthropicBackend}
 * and {@code AmazonBedrockBackend} order the blocks this way deliberately and say so; this one said
 * the opposite, so the same conversation was laid out two different ways depending on which
 * provider answered it. Anthropic's and Google's own guidance is also that a model reads a picture
 * better when the question about it follows.</p>
 *
 * <p>The optional {@code detail} field is left out. Omitting it means {@code auto}, which is what
 * would be asked for anyway, and these bodies also go to a long tail of OpenAI-compatible servers
 * that reject fields they do not recognise.</p>
 */
final class ChatCompletionMessages {

    /**
     * What stands in for an image's bytes in a logged copy of the body.
     *
     * <p>A logged request is redacted, measured and written to a file that outlives the session.
     * Several megabytes of base64 per image would be scanned by the redactor and kept on disk, for
     * a record nobody can read. The elision keeps the shape of the request intact, which is what
     * the log is for.</p>
     */
    private static final String ELIDED = "<image bytes elided>";

    private ChatCompletionMessages() {
    }

    /**
     * The messages to send.
     *
     * @param promptData       the request
     * @param cacheBreakpoints whether the text carries an ephemeral cache breakpoint
     * @return the {@code messages} array
     */
    static List<Map<String, Object>> of(PromptData promptData, boolean cacheBreakpoints) {
        return build(promptData, cacheBreakpoints, PromptImage::dataUrl);
    }

    /**
     * The same messages, with each image's bytes replaced by a note.
     *
     * @param promptData       the request
     * @param cacheBreakpoints whether the text carries an ephemeral cache breakpoint
     * @return the {@code messages} array, safe to write to a log
     */
    static List<Map<String, Object>> elided(PromptData promptData, boolean cacheBreakpoints) {
        return build(promptData, cacheBreakpoints,
                     image -> "data:" + image.mediaType() + ";base64," + ELIDED);
    }

    /**
     * @param promptData       the request
     * @param cacheBreakpoints whether the text carries an ephemeral cache breakpoint
     * @param urlOf            how an image renders as a URL
     * @return the {@code messages} array
     */
    private static List<Map<String, Object>> build(PromptData promptData, boolean cacheBreakpoints,
                                                   Function<PromptImage, String> urlOf) {
        List<Map<String, Object>> messages = new ArrayList<>();
        List<PromptImage>         images   = promptData.getImages();
        String system = promptData.getSystemPrompt();
        if (system != null && !system.isEmpty()) {
            messages.add(message("system", system, cacheBreakpoints));
        }
        messages.add(userMessage(promptData.getUserPrompt(), images, cacheBreakpoints, urlOf));
        return messages;
    }

    /** The user message, which is the only one an image can be attached to. */
    private static Map<String, Object> userMessage(String content, List<PromptImage> images,
                                                   boolean cacheBreakpoint,
                                                   Function<PromptImage, String> urlOf) {
        if (images == null || images.isEmpty()) {
            return message("user", content, cacheBreakpoint);
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        for (PromptImage image : images) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "image_url");
            part.put("image_url", Map.of("url", urlOf.apply(image)));
            parts.add(part);
        }
        // A picture dropped on an empty prompt line is a whole request on its own -- "look at this"
        // with nothing typed after it. An empty text part is not how to say that: every one of
        // these endpoints rejects a content part whose text is the empty string, so the request
        // that carried only a picture was the one request that could never be sent.
        if (content != null && !content.isEmpty()) {
            parts.add(textPart(content, cacheBreakpoint));
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", parts);
        return msg;
    }

    /** One message, with content as a plain string or as a marked parts array. */
    private static Map<String, Object> message(String role, String content, boolean cacheBreakpoint) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", role);
        if (!cacheBreakpoint) {
            msg.put("content", content == null ? "" : content);
            return msg;
        }
        msg.put("content", List.of(textPart(content, true)));
        return msg;
    }

    /** A text part, optionally carrying an ephemeral cache breakpoint. */
    private static Map<String, Object> textPart(String content, boolean cacheBreakpoint) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "text");
        part.put("text", content == null ? "" : content);
        if (cacheBreakpoint) {
            part.put("cache_control", Map.of("type", "ephemeral"));
        }
        return part;
    }
}
