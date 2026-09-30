package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run's own entries are told apart from the session's, before and after the transcript is
 * folded.
 *
 * <p>A fold keeps a head of the transcript, replaces the middle with one summary entry, and keeps a
 * tail. Counted by index, the run's own entries started after as many entries as the session had,
 * so a session longer than the head cut the run's own entries off the saved point.</p>
 */
public class ArunsOwnTranscriptSurvivesAfoldTest {

    private static List<String> session(int size) {
        List<String> entries = new ArrayList<>();
        for (int at = 1; at <= size; at++) {
            entries.add("User: session question " + at);
        }
        return entries;
    }

    private static Map<String, Object> context(List<String> session, List<String> history) {
        return Map.of(IterativeExecutor.SESSION_TRANSCRIPT, session,
                      "conversationHistory", history);
    }

    @Test
    public void theEntriesAfterTheSessionAreTheRunsOwn() {
        List<String> session = session(3);
        List<String> history = new ArrayList<>(session);
        history.addAll(List.of("System: ran ls", "Assistant: read it"));

        assertThat(IterativeExecutor.ownTranscript(context(session, history)))
                .containsExactly("System: ran ls", "Assistant: read it");
    }

    @Test
    public void aFoldThatKeepsSessionEntriesOnBothSidesKeepsOnlyTheRunsOwn() {
        List<String> session = session(10);
        List<String> history = new ArrayList<>(session.subList(0, 6));
        history.add("[12 earlier entries folded]");
        history.addAll(session.subList(9, 10));
        history.addAll(List.of("System: ran ls", "Assistant: read it"));

        assertThat(IterativeExecutor.ownTranscript(context(session, history)))
                .containsExactly("[12 earlier entries folded]", "System: ran ls",
                                 "Assistant: read it");
    }

    @Test
    public void aRunWithNoSessionKeepsEveryEntry() {
        List<String> history = List.of("System: ran ls");

        assertThat(IterativeExecutor.ownTranscript(context(List.of(), history)))
                .containsExactly("System: ran ls");
    }
}
