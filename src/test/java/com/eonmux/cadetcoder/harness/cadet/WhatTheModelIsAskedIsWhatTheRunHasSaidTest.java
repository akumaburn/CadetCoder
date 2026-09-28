package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.ai.AIClient;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.Completion;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.harness.loop.Reply;
import com.eonmux.cadetcoder.harness.loop.Transcript;
import com.eonmux.cadetcoder.harness.loop.Turn;
import com.eonmux.cadetcoder.net.LLMException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What reaches the backend has to be the run, whole and in order.
 *
 * <h2>Why the attribution is asserted rather than the wording</h2>
 *
 * <p>A run is a conversation, and {@link PromptData} carries one system prompt and one user prompt.
 * Flattening a conversation into a single turn is only safe while the agent can still tell its own
 * past words from the harness's instructions and from what a tool answered: a model that reads its
 * own last reply as an instruction acts on its own guess as though the world had confirmed it. The
 * exact labels are this class's business; that every turn carries one, and that they differ by who
 * spoke, is the harness's.</p>
 */
class WhatTheModelIsAskedIsWhatTheRunHasSaidTest {

    /** A transcript with one turn of each kind, each with text nothing else would produce. */
    private static final Transcript SAID =
            Transcript.empty()
                      .plus(Turn.harness("where the world is"))
                      .plus(Turn.agent("what I think is happening"))
                      .plus(Turn.answer("what the tool answered"));

    /** What the backend hands back when the test does not care. */
    private static final Completion ANSWERED = new Completion("go on then", 1_000L, 40L, false);

    /** Stands a reasoner on a backend that always answers {@code said}. */
    private ModelReasoner reasonerAnswering(Completion said) {
        AIManager models = mock(AIManager.class);
        when(models.completeMeasured(ArgumentMatchers.<AIClient>any(), any(PromptData.class)))
                .thenReturn(said);
        return new ModelReasoner("test-model", models);
    }

    /** Runs one question and hands back what the backend was actually sent. */
    private PromptData askedFor(AIManager models, Transcript transcript) {
        new ModelReasoner("test-model", models).think("the standing instructions", transcript);
        ArgumentCaptor<PromptData> sent = ArgumentCaptor.forClass(PromptData.class);
        org.mockito.Mockito.verify(models)
                   .completeMeasured(ArgumentMatchers.<AIClient>any(), sent.capture());
        return sent.getValue();
    }

    @Test
    void theTranscriptReachesTheModelInTheOrderItWasSaid() {
        AIManager models = mock(AIManager.class);
        when(models.completeMeasured(ArgumentMatchers.<AIClient>any(), any(PromptData.class)))
                .thenReturn(ANSWERED);

        String asked = askedFor(models, SAID).getUserPrompt();

        assertThat(asked).contains("where the world is")
                         .contains("what I think is happening")
                         .contains("what the tool answered");
        assertThat(asked.indexOf("where the world is"))
                .isLessThan(asked.indexOf("what I think is happening"));
        assertThat(asked.indexOf("what I think is happening"))
                .isLessThan(asked.indexOf("what the tool answered"));
    }

    @Test
    void everyTurnSaysWhoSaidItSoTheAgentDoesNotReadItsOwnGuessAsAnInstruction() {
        AIManager models = mock(AIManager.class);
        when(models.completeMeasured(ArgumentMatchers.<AIClient>any(), any(PromptData.class)))
                .thenReturn(ANSWERED);

        String asked = askedFor(models, SAID).getUserPrompt();
        String[] lines = asked.split("\\R");

        String beforeHarness = attribution(lines, "where the world is");
        String beforeAgent   = attribution(lines, "what I think is happening");
        String beforeAnswer  = attribution(lines, "what the tool answered");

        assertThat(beforeHarness).isNotBlank();
        assertThat(beforeAgent).isNotBlank();
        assertThat(beforeAnswer).isNotBlank();
        assertThat(List.of(beforeHarness, beforeAgent, beforeAnswer)).doesNotHaveDuplicates();
    }

    @Test
    void theStandingInstructionsAreSentAsTheSystemPromptRatherThanBuriedInTheConversation() {
        AIManager models = mock(AIManager.class);
        when(models.completeMeasured(ArgumentMatchers.<AIClient>any(), any(PromptData.class)))
                .thenReturn(ANSWERED);

        PromptData sent = askedFor(models, SAID);

        assertThat(sent.getSystemPrompt()).isEqualTo("the standing instructions");
        assertThat(sent.getUserPrompt()).doesNotContain("the standing instructions");
    }

    @Test
    void whatTheAnswerCostComesBackWithIt() {
        Reply reply = reasonerAnswering(new Completion("go on then", 1_234L, 56L, true))
                .think("the standing instructions", SAID);

        assertThat(reply.text()).isEqualTo("go on then");
        assertThat(reply.tokensIn()).isEqualTo(1_234L);
        assertThat(reply.tokensOut()).isEqualTo(56L);
    }

    @Test
    void aBackendThatFailsEndsTheRunRatherThanAnsweringForIt() {
        AIManager models = mock(AIManager.class);
        when(models.completeMeasured(ArgumentMatchers.<AIClient>any(), any(PromptData.class)))
                .thenThrow(new LLMException(LLMException.Kind.AUTH, "the key was revoked",
                                            "test", null, null, 401, null, null, null));
        ModelReasoner reasoner = new ModelReasoner("test-model", models);

        assertThatThrownBy(() -> reasoner.think("the standing instructions", SAID))
                .isInstanceOf(LLMException.class)
                .hasMessageContaining("the key was revoked");
    }

    @Test
    void aReasonerBuiltForOneModelAsksThatModelAndNotWhicheverIsActive() {
        AIManager models = mock(AIManager.class);
        AIClient  asked  = mock(AIClient.class);
        when(models.completeMeasured(ArgumentMatchers.<AIClient>any(), any(PromptData.class)))
                .thenReturn(ANSWERED);

        new ModelReasoner("the-stronger-model", models, asked)
                .think("the standing instructions", SAID);

        ArgumentCaptor<AIClient> sent = ArgumentCaptor.forClass(AIClient.class);
        org.mockito.Mockito.verify(models).completeMeasured(sent.capture(), any(PromptData.class));
        assertThat(sent.getValue())
                .as("a run that escalated and was answered by the model it gave up on has not "
                    + "escalated")
                .isSameAs(asked);
    }

    @Test
    void aReasonerIsNamedAfterWhateverIsDoingTheThinking() {
        assertThat(reasonerAnswering(ANSWERED).name()).isEqualTo("test-model");
    }

    @Test
    void thereHasToBeSomethingForTheModelToAnswer() {
        ModelReasoner reasoner = reasonerAnswering(ANSWERED);

        assertThatThrownBy(() -> reasoner.think("the standing instructions", Transcript.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The last non-blank line before the one holding {@code text}. */
    private static String attribution(String[] lines, String text) {
        for (int at = 0; at < lines.length; at++) {
            if (lines[at].contains(text)) {
                for (int back = at - 1; back >= 0; back--) {
                    if (!lines[back].isBlank()) {
                        return lines[back].strip();
                    }
                }
                return "";
            }
        }
        return "";
    }
}
