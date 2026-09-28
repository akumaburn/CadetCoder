package com.eonmux.cadetcoder.git;

import org.junit.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every placeholder is substituted, wherever the message is rendered from.
 *
 * <p>Three call sites each substituted a different one. The shipped default is
 * {@code "Auto-commit on {date}"} and the two edit commands replaced only {@code {changeSummary}},
 * so every commit they made was titled, literally, <em>Auto-commit on {date}</em>.</p>
 */
public class CommitMessageTest {

    @Test
    public void theShippedDefaultRendersADateAndNotItsPlaceholder() {
        String message = CommitMessage.render(CommitMessage.DEFAULT_TEMPLATE, "edited Foo.java");

        assertThat(message)
                .as("this is the message the edit commands were actually producing")
                .doesNotContain("{date}")
                .contains(LocalDate.now().toString());
    }

    @Test
    public void aTemplateNamingTheChangeSummaryGetsOne() {
        assertThat(CommitMessage.render("cadet: {changeSummary}", "edited Foo.java"))
                .isEqualTo("cadet: edited Foo.java");
    }

    @Test
    public void aCallerWithNoChangeSummaryDoesNotLeaveThePlaceholderInTheHistory() {
        assertThat(CommitMessage.render("cadet {changeSummary} on {date}", null))
                .doesNotContain("{changeSummary}")
                .contains(LocalDate.now().toString());
    }

    @Test
    public void everyPlaceholderIsSubstitutedRegardlessOfWhichCallerRenders() {
        String message = CommitMessage.render("{date} {time} {changeSummary}", "work");

        assertThat(message).doesNotContain("{").doesNotContain("}");
    }

    @Test
    public void aMissingTemplateFallsBackRatherThanFailingTheCommit() {
        assertThat(CommitMessage.render(null, "work")).contains(LocalDate.now().toString());
        assertThat(CommitMessage.render("   ", "work")).contains(LocalDate.now().toString());
    }

    /** git refuses an empty message, and an edit that already succeeded should not fail for it. */
    @Test
    public void aTemplateThatRendersToNothingStillProducesAMessage() {
        assertThat(CommitMessage.render("{changeSummary}", null)).isNotBlank();
    }
}
