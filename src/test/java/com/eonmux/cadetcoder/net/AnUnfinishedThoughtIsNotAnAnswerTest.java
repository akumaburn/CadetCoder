package com.eonmux.cadetcoder.net;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A turn cut off at the output limit is reported, not salvaged.
 *
 * <p><b>What was observed</b>: {@code deepseek/deepseek-v4-flash} through Command Code answers the
 * same question differently depending on how much output budget it is given. Measured against the
 * live gateway with one short question:</p>
 *
 * <ul>
 *   <li>{@code max_tokens: 64} returned {@code finish_reason: length}, empty {@code content}, and
 *       250 characters of {@code reasoning}.</li>
 *   <li>{@code max_tokens: 256} returned {@code finish_reason: length}, empty {@code content}, and
 *       1,013 characters of {@code reasoning}.</li>
 *   <li>{@code max_tokens: 4096} returned {@code finish_reason: stop} and a 947-character answer.</li>
 * </ul>
 *
 * <p>A reasoning model charges its thinking to the same budget as its answer, so a long turn can
 * spend all of it before writing anything. That is why the same request works and then does not.
 * Handing the unfinished thought back as the reply hid it: the text does not parse as an action, the
 * loop asks for the format again, and the next turn truncates in the same place.</p>
 */
public class AnUnfinishedThoughtIsNotAnAnswerTest {

    private static Map<String, Object> reply(String content, String reasoning, String finishReason) {
        return Map.of("choices", List.of(Map.of(
                "message", reasoning == null
                           ? Map.of("role", "assistant", "content", content)
                           : Map.of("role", "assistant", "content", content, "reasoning", reasoning),
                "finish_reason", finishReason)));
    }

    @Test
    public void aTruncatedThoughtIsNotOfferedAsTheReply() {
        Map<String, Object> response =
                reply("", "The user just said \"Say OK\". I should respond with", "length");

        assertThat(ChatCompletionContent.firstChoiceText(response))
                .as("acting on half an idea is worse than reporting that the budget ran out")
                .isEmpty();
    }

    @Test
    public void theReportSaysTheBudgetWentOnThinking() {
        Map<String, Object> response = reply("", "still working it out", "length");

        assertThat(ChatCompletionContent.absenceDetail(response))
                .contains("finish_reason: length")
                .contains("ai.maxTokens")
                .contains("thinking");
    }

    @Test
    public void aTruncationWithNoThinkingReportsTheLimitPlainly() {
        Map<String, Object> response = reply("", null, "length");

        assertThat(ChatCompletionContent.absenceDetail(response))
                .contains("finish_reason: length")
                .contains("ai.maxTokens")
                .doesNotContain("thinking");
    }

    @Test
    public void afinishedTurnStillHasItsReasoningRead() {
        // The case this salvage was written for: the model finished, and the gateway put the text
        // under a reasoning field instead of under content. Nothing was cut off, so what is there
        // is the whole of what the model said.
        Map<String, Object> response = reply("", "The answer is 42.", "stop");

        assertThat(ChatCompletionContent.firstChoiceText(response)).contains("The answer is 42.");
    }

    @Test
    public void aReplyWithNoFinishReasonIsStillSalvaged() {
        // Only "length" withholds the salvage. A gateway that reports no finish reason at all must
        // not lose a reply it did send.
        Map<String, Object> response = Map.of("choices", List.of(Map.of(
                "message", Map.of("role", "assistant", "content", "",
                                  "reasoning_content", "The answer is 42."))));

        assertThat(ChatCompletionContent.firstChoiceText(response)).contains("The answer is 42.");
    }

    @Test
    public void contentStillWinsOverReasoningWhenTheTurnWasCutOff() {
        // A truncated ANSWER is still the answer, and the loop can act on the part that arrived.
        // What is withheld is thinking passed off as an answer, not text the model wrote as one.
        Map<String, Object> response = reply("Three sorting algorithms are quick", "thinking", "length");

        assertThat(ChatCompletionContent.firstChoiceText(response))
                .contains("Three sorting algorithms are quick");
    }
}
