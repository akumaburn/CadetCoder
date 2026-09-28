package com.eonmux.cadetcoder.ai;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the current turn brought with it besides its text.
 *
 * <h2>Why the images wait here instead of travelling with the request</h2>
 *
 * <p>An image arrives at the shell, where a file was dropped on the prompt, and it is needed in a
 * request built several layers down by whichever command the line turned out to name. Every command
 * that talks to a model builds its own {@link PromptData} from the prompts it composed, so carrying
 * an image from the prompt line to the request would mean a new parameter on each of those
 * commands, and on everything between -- and a command added later would carry nothing, silently.
 * One place the turn can leave its attachment, and one place the request picks it up, is the
 * smaller change and the one a new command gets for free.</p>
 *
 * <h2>Why it is attached once and not to every request in the turn</h2>
 *
 * <p>A turn is rarely one request. The agent loop asks again after every command it runs, each time
 * with the whole conversation, and an image re-sent on each of those iterations is paid for on each
 * of them -- images are the most expensive thing in a request, and a long run would multiply one
 * dropped screenshot by twenty. The first request of the turn is the one that carries the user's
 * question, so it is the one the image belongs to. What the model saw is in the conversation from
 * then on.</p>
 *
 * <p>"Once" means once delivered, not once read: a request that failed, and is about to be retried,
 * has not shown the model anything. {@link #delivered()} is called after a completion comes back,
 * so a retry still carries the image.</p>
 */
public final class PromptAttachments {

    /** The images this turn is waiting to send; never null, empty when there are none. */
    private static final AtomicReference<List<PromptImage>> PENDING =
            new AtomicReference<>(List.of());

    private PromptAttachments() {
    }

    /**
     * Holds images for the turn that is about to run.
     *
     * @param images what the turn brought; {@code null} or empty clears whatever was held
     */
    public static void attach(List<PromptImage> images) {
        PENDING.set(images == null || images.isEmpty() ? List.of() : List.copyOf(images));
    }

    /**
     * What is waiting to be sent.
     *
     * @return the images, in the order they were attached; empty when there are none
     */
    public static List<PromptImage> pending() {
        return PENDING.get();
    }

    /** A request carrying the pending images reached the model, so the turn owes nothing more. */
    public static void delivered() {
        PENDING.set(List.of());
    }

    /** Drops whatever is held, for the end of a turn and for tests. */
    public static void clear() {
        PENDING.set(List.of());
    }
}
