package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prompt must be APPEND-ONLY across turns, because prompt caching matches on a shared leading
 * prefix.
 *
 * <p>What this prevents coming back: the prompt used to be rendered from a sliding window of the last
 * five history entries under a {@code "Previous context:"} heading, with the current prompt then
 * repeated under {@code "Current request:"}. Three entries are recorded per iteration, so from the
 * second iteration onwards the window advanced every turn, the prompt's opening bytes changed every
 * turn, and no provider could ever serve any of it from cache.</p>
 */
public class PromptCachePrefixTest {

    /** Mirrors what {@code IterativeExecutor} appends per iteration. */
    private static void recordIteration(List<String> transcript, String llmResponse,
                                        String stepOutput, String nextPrompt) {
        if (llmResponse != null) {
            transcript.add("LLM: " + llmResponse);
        }
        transcript.add("System: " + stepOutput);
        transcript.add("Next prompt: " + nextPrompt);
    }

    @Test
    public void eachTurnsPromptIsALiteralExtensionOfThePreviousTurns() {
        IterativeExecutor executor   = new IterativeExecutor();
        List<String>      transcript = new ArrayList<>();
        List<String>      prompts    = new ArrayList<>();

        // Twenty turns is well past the old five-entry window, which is where the sliding behaviour
        // used to start corrupting the prefix.
        for (int turn = 1; turn <= 20; turn++) {
            recordIteration(transcript,
                    turn == 1 ? null : "response " + turn,
                    "output " + turn,
                    "prompt " + turn);
            prompts.add(executor.buildPromptWithHistory("prompt " + turn, transcript));
        }

        for (int turn = 1; turn < prompts.size(); turn++) {
            assertThat(prompts.get(turn))
                    .as("turn %d must extend turn %d, not rewrite it", turn + 1, turn)
                    .startsWith(prompts.get(turn - 1));
        }
    }

    @Test
    public void theSharedPrefixKeepsGrowingRatherThanStayingConstant() {
        IterativeExecutor executor   = new IterativeExecutor();
        List<String>      transcript = new ArrayList<>();

        recordIteration(transcript, null, "output 1", "prompt 1");
        String first = executor.buildPromptWithHistory("prompt 1", transcript);

        for (int turn = 2; turn <= 10; turn++) {
            recordIteration(transcript, "response " + turn, "output " + turn, "prompt " + turn);
        }
        String later = executor.buildPromptWithHistory("prompt 10", transcript);

        assertThat(later).startsWith(first);
        assertThat(later.length())
                .as("later turns carry more context, not a window of the same size")
                .isGreaterThan(first.length());
    }

    @Test
    public void nothingOlderThanTheWindowIsDroppedAnyMore() {
        IterativeExecutor executor   = new IterativeExecutor();
        List<String>      transcript = new ArrayList<>();

        recordIteration(transcript, null, "THE-EARLIEST-FACT", "prompt 1");
        for (int turn = 2; turn <= 12; turn++) {
            recordIteration(transcript, "response " + turn, "output " + turn, "prompt " + turn);
        }

        String prompt = executor.buildPromptWithHistory("prompt 12", transcript);

        assertThat(prompt)
                .as("the old five-entry window discarded everything older than ~1.5 turns")
                .contains("THE-EARLIEST-FACT");
    }

    @Test
    public void theCurrentPromptIsNotSentTwice() {
        IterativeExecutor executor   = new IterativeExecutor();
        List<String>      transcript = new ArrayList<>();

        recordIteration(transcript, null, "output 1", "the-current-request");
        String prompt = executor.buildPromptWithHistory("the-current-request", transcript);

        // It used to appear once inside the window and again under "Current request:", so every turn
        // paid for the newest entry twice.
        assertThat(prompt.split("the-current-request", -1).length - 1).isEqualTo(1);
    }

    @Test
    public void anEmptyTranscriptFallsBackToTheCurrentPrompt() {
        IterativeExecutor executor = new IterativeExecutor();

        assertThat(executor.buildPromptWithHistory("only-this", new ArrayList<>())).isEqualTo("only-this");
        assertThat(executor.buildPromptWithHistory("only-this", null)).isEqualTo("only-this");
    }

    @Test
    public void aNullEntryDoesNotBreakTheRendering() {
        IterativeExecutor executor   = new IterativeExecutor();
        List<String>      transcript = new ArrayList<>();
        transcript.add("first");
        transcript.add(null);
        transcript.add("second");

        assertThat(executor.buildPromptWithHistory("second", transcript))
                .contains("first")
                .contains("second")
                .doesNotContain("null");
    }
}
