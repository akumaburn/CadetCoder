package com.eonmux.cadetcoder.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A value of the right type can still be one nothing can work with.
 *
 * <p><b>The defect</b>: every numeric setting was checked for type and nothing else, so
 * {@code context.maxFiles 0} was accepted, saved, echoed back by {@code /config} -- and then refused
 * by Lucene on every request that consults the index, for the rest of that install's life.
 * {@code compaction.trigger 0} folded the transcript on every single turn, destroying the prompt
 * cache the setting exists to protect, and said nothing. {@code ui.verbosityLevel 99} switched on
 * the branch that prints whole prompts to the terminal while leaving the branches that compare for
 * equality switched off.</p>
 */
class AsettingIsRefusedWhileTheUserCanStillSeeItTest {

    private static void set(Configuration config, String key, String value) {
        ConfigOverrides.apply(config, key, value);
    }

    private static void refuses(String key, String value, String saying) {
        Configuration config = new Configuration();
        assertThatThrownBy(() -> set(config, key, value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(saying);
    }

    @Test
    void acountOfFilesToSearchCannotBeNone() {
        refuses("context.maxFiles", "0", "at least 1");
        refuses("context.maxFiles", "-3", "at least 1");
    }

    @Test
    void afractionOfTheBudgetStaysAfraction() {
        refuses("compaction.trigger", "0", "between");
        refuses("compaction.trigger", "2.0", "between");
        refuses("compaction.target", "0", "between");
    }

    @Test
    void thereAreThreeLevelsOfVerbosityAndNoOthers() {
        refuses("ui.verbosityLevel", "99", "0 (minimal)");
        refuses("ui.verbosityLevel", "-1", "0 (minimal)");
    }

    @Test
    void amodelCannotBeAskedForNoTimeOrAnImpossibleTemperature() {
        refuses("ai.completionTimeoutSeconds", "0", "at least 1");
        refuses("ai.temperature", "99", "between");
    }

    @Test
    void anOutputCeilingCanBeRemovedButNotMadeNegative() {
        // Zero is how "no ceiling at all" is written and it is the shipped default, so refusing it
        // would leave the default unreachable through the command that sets it.
        Configuration config = new Configuration();
        set(config, "ai.maxTokens", "0");
        assertThat(config.getAi().getMaxTokens()).isZero();

        refuses("ai.maxTokens", "-1", "at least 0");
    }

    @Test
    void anAutoCommitIntervalIsRefusedWhenItIsTypedRatherThanWhenItIsDue() {
        refuses("git.autoCommitIntervalMinutes", "0", "at least 1");
    }

    /**
     * Reading a setting has always ignored case; writing one now does too.
     *
     * <p><b>The defect</b>: {@code config ui.colortheme} printed the value and
     * {@code config ui.colortheme matrix} answered "Unknown property ui.colortheme" -- the tool
     * refusing a name it had just printed.</p>
     */
    @Test
    void asettingCanBeWrittenTheWayItCanBeRead() {
        Configuration config = new Configuration();

        set(config, "UI.colorTheme", "matrix");
        set(config, "ui.colortheme", "dracula");
        set(config, "ai.MAXTOKENS", "2048");

        assertThat(config.getUi().getColorTheme()).isEqualTo("dracula");
        assertThat(config.getAi().getMaxTokens()).isEqualTo(2048);
    }

    /** A name that is wrong in more than its case is still refused, and still named. */
    @Test
    void anameThatIsActuallyWrongIsStillRefused() {
        refuses("ui.colourTheme", "matrix", "Unknown property");
    }

    @Test
    void whatIsInRangeIsStillAccepted() {
        Configuration config = new Configuration();
        set(config, "context.maxFiles", "25");
        set(config, "compaction.trigger", "0.8");
        set(config, "ui.verbosityLevel", "2");
        set(config, "ai.temperature", "0.3");

        assertThat(config.getContext().getMaxFiles()).isEqualTo(25);
        assertThat(config.getCompaction().getTrigger()).isEqualTo(0.8);
        assertThat(config.getUi().getVerbosityLevel()).isEqualTo(2);
        assertThat(config.getAi().getTemperature()).isEqualTo(0.3f);
    }
}
