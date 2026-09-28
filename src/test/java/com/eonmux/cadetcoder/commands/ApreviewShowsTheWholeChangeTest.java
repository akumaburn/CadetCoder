package com.eonmux.cadetcoder.commands;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The preview of a change shows all of the change it asks approval for.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code edit} and {@code refactor} showed the first fenced block of the change, cut at 500
 * characters, and {@code multiedit} showed each replacement cut at 50. A person approved a change
 * from part of it. In auto mode the model that answers for the person declined good edits twice in
 * one run, saying "the edit preview is truncated and does not show the actual change".</p>
 */
class ApreviewShowsTheWholeChangeTest {

    private static String fence(String body) {
        return "```\n" + body + "\n```";
    }

    @Test
    void alongBlockIsShownWhole() {
        String block = fence("x".repeat(5_000));

        assertThat(ChangePreview.of("Here is the change:\n" + block)).isEqualTo(block);
    }

    @Test
    void everyBlockIsShownAndNotOnlyTheFirst() {
        String first  = fence("first change");
        String second = fence("second change");

        assertThat(ChangePreview.of(first + "\nand then\n" + second))
                .contains("first change")
                .contains("second change");
    }

    @Test
    void areplyWithNoBlockIsShownWhole() {
        String reply = "y".repeat(1_000);

        assertThat(ChangePreview.of(reply)).isEqualTo(reply);
    }

    @Test
    void ablockWithNoClosingFenceIsNotLost() {
        String reply = "Intro\n```\nunclosed change";

        assertThat(ChangePreview.of(reply)).isEqualTo(reply);
    }

    @Test
    void nothingIsCalledCut() {
        assertThat(ChangePreview.of(fence("z".repeat(10_000)))).doesNotContain("truncated");
    }
}
