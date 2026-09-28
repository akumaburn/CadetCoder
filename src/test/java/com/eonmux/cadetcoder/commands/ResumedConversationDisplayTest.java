package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ui.Glyphs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A resumed session has to show what was said before it.
 *
 * <p>{@code --continue} restored the conversation into the model's context and nowhere else, so the
 * shell opened on an empty transcript: the model knew what had been discussed and the person it was
 * talking to had nothing to scroll back through. Everything needed was already loaded and simply
 * never put on screen.</p>
 */
class ResumedConversationDisplayTest {

    private static final Glyphs GLYPHS = Glyphs.ASCII;

    private static List<String> render(List<String> history, int max) {
        return ShellWelcome.resumedConversation(history, max, GLYPHS);
    }

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    @Test
    @DisplayName("Both halves of each exchange are replayed, in order")
    void theExchangeIsReplayed() {
        List<String> lines = render(List.of(
                "User: remember the secret code",
                "AI: MOONBEAM-ALPHA-7731 is the secret code.",
                "User: what was it again?",
                "AI: MOONBEAM-ALPHA-7731."), 200);

        String text = joined(lines);
        assertThat(text).contains("remember the secret code");
        assertThat(text).contains("MOONBEAM-ALPHA-7731 is the secret code.");
        assertThat(text.indexOf("remember the secret code"))
                .as("the conversation reads forwards, oldest first")
                .isLessThan(text.indexOf("what was it again?"));
    }

    @Test
    @DisplayName("A restored user turn carries the same marker a live one does")
    void aRestoredUserTurnLooksLikeALiveOne() {
        List<String> lines = render(List.of("User: hello", "AI: hi"), 200);

        // "> " is what the styler classifies as a user line, so a restored turn is coloured like a
        // typed one instead of reading as a quoted log.
        assertThat(lines).contains("> hello");
        assertThat(lines).contains("hi");
        assertThat(lines).noneSatisfy(line -> assertThat(line).startsWith("User: "));
        assertThat(lines).noneSatisfy(line -> assertThat(line).startsWith("AI: "));
    }

    @Test
    @DisplayName("It says where the old conversation ends and the session picks up")
    void theResumePointIsMarked() {
        List<String> lines = render(List.of("User: a", "AI: b"), 200);

        assertThat(joined(lines)).contains("Resumed conversation");
        assertThat(lines.get(lines.size() - 1).isEmpty() ? lines.get(lines.size() - 2)
                                                         : lines.get(lines.size() - 1))
                .contains("Session resumes here");
    }

    @Test
    @DisplayName("A long history is trimmed from the front, and says how much it dropped")
    void anOverlongHistoryIsTrimmedAndSaysSo() {
        List<String> history = new ArrayList<>();
        for (int i = 1; i <= 250; i++) {
            history.add("User: turn " + i);
        }

        List<String> lines = render(history, 200);
        String text = joined(lines);

        // Newest kept, oldest dropped: pouring all of it in would push the banner out of reach.
        assertThat(text).contains("turn 250");
        assertThat(text).doesNotContain("turn 49\n");
        assertThat(text).contains("50 earlier turns not shown");
        assertThat(text).contains("the model still has them");
    }

    @Test
    @DisplayName("Exactly one dropped turn is described in the singular")
    void theCountReadsNaturallyAtOne() {
        List<String> history = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            history.add("User: turn " + i);
        }

        assertThat(joined(render(history, 2))).contains("1 earlier turn not shown");
    }

    @Test
    @DisplayName("Nothing to replay produces nothing at all, not an empty heading")
    void anEmptyHistoryRendersNothing() {
        assertThat(render(List.of(), 200)).isEmpty();
        assertThat(render(null, 200)).isEmpty();
    }

    @Test
    @DisplayName("Blank entries are skipped rather than becoming stray gaps")
    void blankEntriesAreSkipped() {
        List<String> lines = render(java.util.Arrays.asList("User: a", "", null, "AI: b"), 200);

        assertThat(lines).contains("> a").contains("b");
        assertThat(lines).doesNotContainNull();
    }

    @Test
    @DisplayName("An entry with no speaker prefix is shown as it is")
    void unprefixedEntriesSurvive() {
        assertThat(render(List.of("System: compacted 12 turns"), 200))
                .contains("System: compacted 12 turns");
    }
}
