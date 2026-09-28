package com.eonmux.cadetcoder.prompts;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading an optional prompt block, and what happens when there is none.
 *
 * <p><b>The defect</b>: the template engine reports a template it could not load by returning the
 * failure message as though it were the template. A caller composing a system prompt out of a named
 * resource would then put "Failed to load prompt content from: ..." in front of every request the
 * tool makes, where the model reads it as an instruction. The blocks read this way are optional by
 * design -- principles a user may delete, a directive that applies in one mode -- so an absence is
 * not an error, and a failure has to look like one.</p>
 *
 * <p><b>What is locked here</b>: that each of the engine's failure messages is recognised wherever
 * it appears at the front of what came back; that an ordinary template is not mistaken for one; and
 * that a prompt nobody ships reads as nothing at all rather than as a sentence about not finding
 * it.</p>
 */
public class AnAbsentPromptIsAnAbsenceTest {

    @Test
    public void everyWayTheEngineReportsAFailureIsRecognised() {
        assertThat(PromptResource.looksLikeLoadFailure("No template found for prompt: ubermode"))
                .isTrue();
        assertThat(PromptResource.looksLikeLoadFailure(
                "Failed to load prompt content from: content/ubermode.md")).isTrue();
        assertThat(PromptResource.looksLikeLoadFailure("Error loading prompt content: boom"))
                .isTrue();
        assertThat(PromptResource.looksLikeLoadFailure("\n  No template found for prompt: x"))
                .as("the engine's message can arrive with the leading whitespace of a rendered block")
                .isTrue();
    }

    @Test
    public void arealTemplateIsNotMistakenForAFailure() {
        assertThat(PromptResource.looksLikeLoadFailure("# Finishing, Not Stopping")).isFalse();
        assertThat(PromptResource.looksLikeLoadFailure("")).isFalse();
        assertThat(PromptResource.looksLikeLoadFailure(null)).isFalse();
    }

    @Test
    public void apromptNobodyShipsReadsAsNothingAtAll() {
        assertThat(PromptResource.text("a-prompt-that-does-not-exist")).isEmpty();
        assertThat(PromptResource.text(null)).isEmpty();
    }

    @Test
    public void apromptThatIsShippedComesBackAsItsText() {
        assertThat(PromptResource.text("ubermode"))
                .isNotEmpty()
                .doesNotContain("No template found");
    }
}
