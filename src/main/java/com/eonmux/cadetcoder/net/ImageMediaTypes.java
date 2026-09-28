package com.eonmux.cadetcoder.net;

import java.util.Set;

/**
 * The image types each API documents that it takes.
 *
 * <h2>Why the lists are not the same list</h2>
 *
 * <p>Three of the four agree and one does not. The Chat Completions, Anthropic Messages and Bedrock
 * Converse APIs each document PNG, JPEG, GIF and WebP. Gemini documents PNG, JPEG, WebP, HEIC and
 * HEIF -- no GIF, and two formats none of the others take.</p>
 *
 * <p>Sending a type an API does not take is not a degraded answer, it is a rejected request: the
 * question that came with the picture is lost along with it. A GIF was read, attached and sent to
 * Gemini, which refused the whole call, so dropping an animation into a Gemini session ended the
 * turn with a protocol error rather than an answer. Asking the wire first lets the picture be
 * dropped and the question asked, which is what {@code ai/ImageChannel} does with every other
 * reason a picture cannot be sent.</p>
 *
 * <h2>Why the lists live here</h2>
 *
 * <p>One home, so the four cannot drift apart, and so a list is stated once beside the reason it
 * says what it says. Three of the wires share a list; written out per backend, the three copies
 * would be free to disagree about a format none of them had reason to think about.</p>
 */
public final class ImageMediaTypes {

    /** A wire with no place to put an image at all. */
    public static final Set<String> NONE = Set.of();

    /**
     * PNG, JPEG, GIF and WebP.
     *
     * <p>What the OpenAI Chat Completions API takes in an {@code image_url} part, what Anthropic's
     * Messages API takes in an {@code image} block, and the {@code png | jpeg | gif | webp} that
     * Bedrock Converse lists as the valid values of an image block's {@code format}. Animations are
     * not read by any of them; the first frame is.</p>
     */
    public static final Set<String> PNG_JPEG_GIF_WEBP =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    /**
     * PNG, JPEG, WebP, HEIC and HEIF.
     *
     * <p>What Gemini documents for inline image data. It is the one list here without GIF, and the
     * one with the two formats an iPhone photograph arrives in.</p>
     */
    public static final Set<String> GEMINI =
            Set.of("image/png", "image/jpeg", "image/webp", "image/heic", "image/heif");

    private ImageMediaTypes() {
    }
}
