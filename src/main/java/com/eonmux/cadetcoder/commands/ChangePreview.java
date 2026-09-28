package com.eonmux.cadetcoder.commands;

import java.util.ArrayList;
import java.util.List;

/**
 * What {@code edit} and {@code refactor} show of a generated change before asking to apply it.
 *
 * <h2>Why nothing is cut</h2>
 *
 * <p>The preview is what the approval is given on. It showed the first fenced block, cut at 500
 * characters, so a person approved a change from part of it, and in auto mode the model answering
 * for the person declined good changes because it could not see them.</p>
 */
final class ChangePreview {

    private static final String FENCE = "```";

    private ChangePreview() {
    }

    /**
     * The change in a model's reply.
     *
     * @param reply the reply that holds the change
     * @return every fenced block in it, whole and in order; the whole reply when it has no
     *         complete block
     */
    static String of(String reply) {
        if (reply == null) {
            return "";
        }
        List<String> blocks = new ArrayList<>();
        int          from   = reply.indexOf(FENCE);
        while (from >= 0) {
            int end = reply.indexOf(FENCE, from + FENCE.length());
            if (end < 0) {
                // An unclosed block cannot be told apart from the text after it.
                return reply;
            }
            blocks.add(reply.substring(from, end + FENCE.length()));
            from = reply.indexOf(FENCE, end + FENCE.length());
        }
        return blocks.isEmpty() ? reply : String.join("\n\n", blocks);
    }
}
