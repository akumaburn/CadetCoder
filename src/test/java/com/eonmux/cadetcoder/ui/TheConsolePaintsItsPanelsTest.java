package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's painted backgrounds, end to end: a marked line printed by the formatter carries
 * its theme's background escape -- and a theme that declares no backgrounds paints nothing.
 *
 * <p>These tests run against the real configuration (colour enabled, the modern theme in force),
 * the way a plain console run does. The interactive shell is not exercised here: there the ANSI
 * is stripped and the painting happens through {@link OutputLineStyler}, which
 * {@link ThemeSurfacesTest} covers.</p>
 */
public class TheConsolePaintsItsPanelsTest {

    private TestOutputCapture outputCapture;
    private String originalTheme;

    @Before
    public void setUp() {
        outputCapture  = new TestOutputCapture();
        originalTheme  = ColorThemeManager.getCurrentTheme().getName();
        ThemedOutputFormatter.clearColorCache();
        ColorThemeManager.setTheme("modern");
    }

    @After
    public void tearDown() {
        outputCapture.restore();
        ColorThemeManager.setTheme(originalTheme);
        ThemedOutputFormatter.clearColorCache();
    }

    @Test
    public void aNoticeCarriesItsTint() {
        ThemedOutputFormatter.printWarning("careful");

        String out = outputCapture.getStdout();
        assertThat(out).contains("\u001B[48;2;51;42;20m")   // the warning tint, #332a14
                       .contains("\u001B[38;2;229;192;123m") // the amber text, unchanged
                       .contains("⚠ careful");
    }

    @Test
    public void aBannerCarriesItsSurface() {
        ThemedOutputFormatter.printHeader("Update check");

        String out = outputCapture.getStdout();
        assertThat(out).contains("\u001B[48;2;43;48;59m")   // the strong surface, #2b303b
                       .contains("▎ Update check");
    }

    @Test
    public void aStepStripCarriesSofterSurfaceThanABanner() {
        ThemedOutputFormatter.printSubheader("write README.md");

        assertThat(outputCapture.getStdout()).contains("\u001B[48;2;35;40;51m"); // #232833
    }

    @Test
    public void aThemeWithoutBackgroundsPaintsNothing() {
        // one-dark's console colours are the 16 ANSI colours, defined against whatever palette
        // the terminal itself uses. Inventing a background for them is how a garish or
        // low-contrast pair gets made, so the theme declares none and the console paints none.
        ColorThemeManager.setTheme("one-dark");

        ThemedOutputFormatter.printWarning("careful");

        assertThat(outputCapture.getStdout()).doesNotContain("\u001B[48;2;")
                                             .doesNotContain("\u001B[48")
                                             .contains("⚠ careful");
    }
}
