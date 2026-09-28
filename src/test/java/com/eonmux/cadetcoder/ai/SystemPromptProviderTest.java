package com.eonmux.cadetcoder.ai;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The operating principles that lead every system prompt, and how they are composed with it. */
public class SystemPromptProviderTest {

    @Test
    public void theShippedPrinciplesAreLoaded() {
        String principles = SystemPromptProvider.principles();

        assertThat(principles).contains("# Operating Principles");
        assertThat(principles).contains("Treat the repository as the primary source of truth");
        assertThat(principles).contains("Keep documentation clean and purposeful");
    }

    @Test
    public void aCommandWithNoSystemPromptOfItsOwnStillGetsThePrinciples() {
        // Six commands pass an empty system prompt. Composing at the send point rather than in each
        // command is what gives those the principles as well.
        assertThat(SystemPromptProvider.compose("")).isEqualTo(SystemPromptProvider.principles());
        assertThat(SystemPromptProvider.compose("   ")).isEqualTo(SystemPromptProvider.principles());
        assertThat(SystemPromptProvider.compose(null)).isEqualTo(SystemPromptProvider.principles());
    }

    @Test
    public void thePrinciplesLeadAndTheCommandsOwnPromptFollows() {
        String composed = SystemPromptProvider.compose("Respond with ACTION_START ... ACTION_END.");

        // Order matters twice over: a model weights format instructions nearest the user turn most
        // heavily, so the action contract has to come last; and the principles are constant, so
        // leading with them keeps the system prompt's opening bytes stable for prompt caching.
        assertThat(composed).startsWith("# Operating Principles");
        assertThat(composed).endsWith("Respond with ACTION_START ... ACTION_END.");
        assertThat(composed.indexOf("# Operating Principles"))
                .isLessThan(composed.indexOf("ACTION_START"));
    }

    @Test
    public void composingIsStableSoTheSystemPromptStaysCacheable() {
        String first  = SystemPromptProvider.compose("contract");
        String second = SystemPromptProvider.compose("contract");

        assertThat(first).isEqualTo(second);
    }

    @Test
    public void composingTwiceWouldBeVisible() {
        // Guards against a second composition creeping in: the principles must appear exactly once
        // in what is sent, or every request pays for them twice.
        String composed = SystemPromptProvider.compose("contract");

        assertThat(composed.split("# Operating Principles", -1).length - 1).isEqualTo(1);
    }

    @Test
    public void theTemplateEnginesLoadFailureMessagesAreNotTreatedAsPrinciples() {
        // The engine reports a failure by RETURNING the failure message as though it were the
        // template. Passed through, that would put "Failed to load prompt content from: ..." in
        // front of every request the tool makes.
        assertThat(SystemPromptProvider.looksLikeLoadFailure("No template found for prompt: system")).isTrue();
        assertThat(SystemPromptProvider.looksLikeLoadFailure(
                "Failed to load prompt content from: content/system.md")).isTrue();
        assertThat(SystemPromptProvider.looksLikeLoadFailure(
                "Error loading prompt content: boom")).isTrue();
    }

    @Test
    public void realPrinciplesAreNotMistakenForALoadFailure() {
        assertThat(SystemPromptProvider.looksLikeLoadFailure(SystemPromptProvider.principles())).isFalse();
        assertThat(SystemPromptProvider.looksLikeLoadFailure("# Operating Principles")).isFalse();
    }
}
