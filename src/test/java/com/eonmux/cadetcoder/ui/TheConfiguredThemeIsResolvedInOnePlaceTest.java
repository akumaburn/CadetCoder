package com.eonmux.cadetcoder.ui;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a configured theme name means, and where that is decided.
 *
 * <h2>The defect</h2>
 *
 * <p>It was decided twice. The theme manager read the setting through four {@code getMethod} lookups
 * against {@code ConfigManager} by string name, inside a catch-all that discarded whatever came
 * back -- so renaming any of the four would have gone on compiling while silently ceasing to honour
 * the user's theme. The interactive shell then resolved the same setting again on startup, in its
 * own words, with its own idea of what an unknown name means. Which answer applied depended on
 * whether the process had opened a shell, so a misspelled theme name behaved differently for
 * {@code cadet ls} than for {@code cadet -i}.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That a name that is a theme is applied; that one that is not falls back to the default rather
 * than leaving whatever happened to be in force; that the same is true of an absent or empty name,
 * which is what a fresh configuration has; that the caller is told which of the two happened; and
 * that there is a theme in force at all times, since every render asks for one.</p>
 */
public class TheConfiguredThemeIsResolvedInOnePlaceTest {

    private static final String A_REAL_THEME = "nord";

    private String original;

    @Before
    public void rememberTheme() {
        original = TuiThemeManager.getCurrentTheme().getName();
    }

    @After
    public void restoreTheme() {
        TuiThemeManager.setTheme(original);
    }

    @Test
    public void anameThatIsAThemeIsApplied() {
        assertThat(TuiThemeManager.apply(A_REAL_THEME)).isTrue();
        assertThat(TuiThemeManager.getCurrentTheme().getName()).isEqualTo(A_REAL_THEME);
    }

    @Test
    public void anameThatIsNotAThemeFallsBackToTheDefault() {
        TuiThemeManager.setTheme(A_REAL_THEME);

        assertThat(TuiThemeManager.apply("no-such-theme")).isFalse();
        assertThat(TuiThemeManager.getCurrentTheme().getName())
                .isEqualTo(TuiThemeManager.DEFAULT_THEME);
    }

    @Test
    public void anabsentNameFallsBackToTheDefault() {
        // What a configuration that has never had a theme set looks like.
        TuiThemeManager.setTheme(A_REAL_THEME);

        assertThat(TuiThemeManager.apply(null)).isFalse();
        assertThat(TuiThemeManager.getCurrentTheme().getName())
                .isEqualTo(TuiThemeManager.DEFAULT_THEME);
    }

    @Test
    public void anemptyNameFallsBackToTheDefault() {
        TuiThemeManager.setTheme(A_REAL_THEME);

        assertThat(TuiThemeManager.apply("")).isFalse();
        assertThat(TuiThemeManager.getCurrentTheme().getName())
                .isEqualTo(TuiThemeManager.DEFAULT_THEME);
    }

    @Test
    public void theDefaultIsItselfATheme() {
        assertThat(TuiThemeManager.getAvailableThemes())
                .contains(TuiThemeManager.DEFAULT_THEME);
    }

    @Test
    public void readingTheConfigurationLeavesAThemeInForce() {
        // It also runs while this class is initialising, so it must answer rather than raise
        // whatever the configuration turns out to be.
        TuiThemeManager.applyConfiguredTheme();

        assertThat(TuiThemeManager.getCurrentTheme()).isNotNull();
        assertThat(TuiThemeManager.getCurrentTheme().getName()).isNotEmpty();
    }

    @Test
    public void thereIsAThemeInForceBeforeAnybodyChoosesOne() {
        assertThat(TuiThemeManager.getCurrentTheme()).isNotNull();
    }
}
