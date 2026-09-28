package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ui.OutputCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker must never put a question on the shell's input line.
 *
 * <p>The failure this pins down was visible as an unexplained {@code ? >>>} prompt that ignored
 * whatever was typed and asked again. A worker runs a full agent loop on a background thread with
 * its output collected, so the "User Input Required" heading and the question itself went into that
 * worker's transcript while only the bare input prompt reached the shell — the user was asked to
 * answer a question they could not read. With several workers running, each seized the same single
 * prompt slot, so an answer released whichever had installed itself last and stranded the others.</p>
 */
class WorkerPromptIsolationTest {

    /** An interactive process: the case where prompting would otherwise be allowed. */
    private final UserAsk ask = new UserAsk(true);

    @Test
    @DisplayName("A thread whose output is collected may not ask the user anything")
    void aCapturedThreadCannotPrompt() {
        // Outside a capture the terminal is reachable, so prompting stays allowed.
        assertThat(ask.canAskUser()).isTrue();

        boolean[]    insideCapture = {true};
        List<String> collected     = OutputCapture.collect(() -> insideCapture[0] = ask.canAskUser());

        assertThat(insideCapture[0])
                .as("a worker's question would land in its own transcript, not on screen")
                .isFalse();
        assertThat(collected).isEmpty();
    }

    @Test
    @DisplayName("The restriction lifts again once the capture ends")
    void promptingIsAllowedAgainAfterTheCapture() {
        OutputCapture.collect(() -> { });

        assertThat(ask.canAskUser())
                .as("the foreground loop must still be able to ask")
                .isTrue();
    }

    @Test
    @DisplayName("Interactivity alone no longer decides: capture overrides it")
    void captureOverridesTheInteractiveProperty() {
        boolean[] asked = {true};

        OutputCapture.collect(() -> asked[0] = ask.canAskUser());

        assertThat(asked[0]).isFalse();
    }
}
