package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.ui.OutputLineStyler.Kind;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The design contracts for the painted backgrounds.
 *
 * <p>The model is the opencode theme's: a surface is one small luminance step away from the
 * theme's own background, and a tint is a dark, desaturated wash of the semantic hue with the
 * accent carried by the text on top of it. These tests exist to keep it that way -- a background
 * that creeps toward the text's own luminance is exactly the low-contrast or garish combination
 * the model forbids, and nothing in the code stops that drift. The arithmetic here does.</p>
 */
public class ThemeSurfacesTest {

    /**
     * WCAG relative luminance, from the sRGB channels of a theme colour.
     *
     * @param color the colour; never {@code null} in the themes under test
     * @return the relative luminance, 0 (black) to 1 (white)
     */
    private static double luminance(Color color) {
        Color.Rgb rgb = color.toRgb();
        return 0.2126 * linear(rgb.r()) + 0.7152 * linear(rgb.g()) + 0.0722 * linear(rgb.b());
    }

    /** The sRGB channel value linearised per the WCAG formula. */
    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** WCAG contrast ratio between two colours; 1 (indistinguishable) to 21 (black on white). */
    private static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** Whether the theme is a light one, judged by its own window background. */
    private static boolean isLight(TuiTheme theme) {
        return luminance(theme.getWindowBackground().bg().orElse(Color.BLACK)) > 0.5;
    }

    @Test
    public void everyThemeDeclaresItsSurfacesAndTints() {
        for (String name : TuiThemeManager.getAvailableThemes()) {
            TuiTheme theme = TuiThemeManager.getTheme(name);
            assertThat(theme.getSurface()).as("%s surface", name).isNotNull();
            assertThat(theme.getSurfaceStrong()).as("%s strong surface", name).isNotNull();
            assertThat(theme.getSuccessBg()).as("%s success tint", name).isNotNull();
            assertThat(theme.getWarningBg()).as("%s warning tint", name).isNotNull();
            assertThat(theme.getErrorBg()).as("%s error tint", name).isNotNull();
        }
    }

    @Test
    public void surfacesStepAwayFromTheThemeBackgroundInTheRightDirection() {
        for (String name : TuiThemeManager.getAvailableThemes()) {
            TuiTheme theme = TuiThemeManager.getTheme(name);
            double base = luminance(theme.getWindowBackground().bg().orElse(Color.BLACK));

            if (isLight(theme)) {
                // A light theme lowers the surface below the background; raising it would paint
                // a strip brighter than the paper, which reads as a highlight, not an area.
                assertThat(luminance(theme.getSurface()))
                        .as("%s surface below its background", name).isLessThan(base);
            } else {
                // A dark theme raises it; a surface at or below the background paints nothing.
                assertThat(luminance(theme.getSurface()))
                        .as("%s surface above its background", name).isGreaterThan(base);
            }
            // And the banner layer steps further from the background than the strip layer,
            // whichever direction "away" is for this theme.
            double stripDistance  = Math.abs(luminance(theme.getSurface()) - base);
            double bannerDistance = Math.abs(luminance(theme.getSurfaceStrong()) - base);
            assertThat(bannerDistance)
                    .as("%s banner layer steps further than the strip layer", name)
                    .isGreaterThan(stripDistance);
        }
    }

    @Test
    public void darkBackgroundsStayDark() {
        // The "no strange colours" rule, as arithmetic: on a dark theme no painted background
        // may leave the bottom of the luminance range. The opencode surfaces this model comes
        // from sit between 0.005 and 0.06; a quarter of the range already forgives a great deal.
        for (String name : TuiThemeManager.getAvailableThemes()) {
            TuiTheme theme = TuiThemeManager.getTheme(name);
            if (isLight(theme)) {
                continue;
            }
            Color[] backgrounds = {theme.getSurface(), theme.getSurfaceStrong(),
                                   theme.getSuccessBg(), theme.getWarningBg(), theme.getErrorBg()};
            for (Color background : backgrounds) {
                assertThat(luminance(background))
                        .as("%s background luminance", name).isLessThan(0.25);
            }
        }
    }

    @Test
    public void lightBackgroundsStayLight() {
        // The mirror of the dark rule: on a light theme a painted background stays near the
        // paper. A tint that darkened toward the text's own luminance would be a foreground
        // wearing a background's name.
        for (String name : TuiThemeManager.getAvailableThemes()) {
            TuiTheme theme = TuiThemeManager.getTheme(name);
            if (!isLight(theme)) {
                continue;
            }
            Color[] backgrounds = {theme.getSurface(), theme.getSurfaceStrong(),
                                   theme.getSuccessBg(), theme.getWarningBg(), theme.getErrorBg()};
            for (Color background : backgrounds) {
                assertThat(luminance(background))
                        .as("%s background luminance", name).isGreaterThan(0.6);
            }
        }
    }

    @Test
    public void noticesAreReadableOnTheirTints() {
        // A notice's text keeps its semantic accent on top of its tint; the pair must stay
        // clearly readable. On a dark theme the accents are light and the tints near-black, so
        // three to one is the floor. On a light theme the accents are the palette's own darker
        // variants -- Solarized's green on its cream is under three to one on the PLAIN
        // background -- so the floor is the palette's own convention, not a stricter one
        // invented here.
        for (String name : TuiThemeManager.getAvailableThemes()) {
            TuiTheme theme = TuiThemeManager.getTheme(name);
            double floor = isLight(theme) ? 2.3 : 3.0;

            assertThat(contrast(theme.getSuccess().fg().orElse(Color.BLACK), theme.getSuccessBg()))
                    .as("%s success text on its tint", name).isGreaterThanOrEqualTo(floor);
            assertThat(contrast(theme.getWarning().fg().orElse(Color.BLACK), theme.getWarningBg()))
                    .as("%s warning text on its tint", name).isGreaterThanOrEqualTo(floor);
            assertThat(contrast(theme.getError().fg().orElse(Color.BLACK), theme.getErrorBg()))
                    .as("%s error text on its tint", name).isGreaterThanOrEqualTo(floor);
        }
    }

    @Test
    public void bannersAreReadableOnTheirSurfaces() {
        // A banner's accent must survive the surface it sits on. The floor is set just under
        // what the dimmest accent in the set manages (Solarized's magenta, which scores 3.6 on
        // its own plain background and 2.3 on the banner layer); anything below that is a
        // banner fading into its strip.
        for (String name : TuiThemeManager.getAvailableThemes()) {
            TuiTheme theme = TuiThemeManager.getTheme(name);
            double floor = isLight(theme) ? 2.4 : 2.2;

            assertThat(contrast(theme.getAccent1().fg().orElse(Color.BLACK), theme.getSurfaceStrong()))
                    .as("%s header accent on the banner surface", name).isGreaterThanOrEqualTo(floor);
            assertThat(contrast(theme.getAccent2().fg().orElse(Color.BLACK), theme.getSurface()))
                    .as("%s step accent on the strip surface", name).isGreaterThanOrEqualTo(floor);
        }
    }

    @Test
    public void theStylerPaintsTheKindItsLineWasClassifiedAs() {
        TuiTheme theme = TuiThemeManager.getCurrentTheme();

        assertThat(bg(OutputLineStyler.styleFor(Kind.HEADER, theme))).isEqualTo(theme.getSurfaceStrong());
        assertThat(bg(OutputLineStyler.styleFor(Kind.ITERATION, theme))).isEqualTo(theme.getSurfaceStrong());
        assertThat(bg(OutputLineStyler.styleFor(Kind.SUBHEADER, theme))).isEqualTo(theme.getSurface());
        assertThat(bg(OutputLineStyler.styleFor(Kind.USER, theme))).isEqualTo(theme.getSurface());
        assertThat(bg(OutputLineStyler.styleFor(Kind.SUCCESS, theme))).isEqualTo(theme.getSuccessBg());
        assertThat(bg(OutputLineStyler.styleFor(Kind.WARNING, theme))).isEqualTo(theme.getWarningBg());
        assertThat(bg(OutputLineStyler.styleFor(Kind.ERROR, theme))).isEqualTo(theme.getErrorBg());

        // Prose and system remarks keep the terminal's own colours: painting them would repaint
        // whole listings the tool did not draw. Ordinary text still carries the window
        // background it always had; code fences carry only their foreground.
        assertThat(bg(OutputLineStyler.styleFor(Kind.NORMAL, theme)))
                .isEqualTo(theme.getWindowBackground().bg().orElse(null));
        assertThat(OutputLineStyler.styleFor(Kind.INFO, theme).bg()).isEmpty();
        assertThat(OutputLineStyler.styleFor(Kind.SYSTEM, theme).bg()).isEmpty();
        assertThat(OutputLineStyler.styleFor(Kind.CODE, theme).bg()).isEmpty();
    }

    @Test
    public void theStylerKeepsTheTextColourItHad() {
        // The backgrounds change where a line sits, not what its text says: every kind keeps the
        // foreground it was mapped to before surfaces existed.
        TuiTheme theme = TuiThemeManager.getCurrentTheme();
        assertThat(fg(OutputLineStyler.styleFor(Kind.ERROR, theme))).isEqualTo(fg(theme.getError()));
        assertThat(fg(OutputLineStyler.styleFor(Kind.SUCCESS, theme))).isEqualTo(fg(theme.getSuccess()));
        assertThat(fg(OutputLineStyler.styleFor(Kind.WARNING, theme))).isEqualTo(fg(theme.getWarning()));
        assertThat(fg(OutputLineStyler.styleFor(Kind.USER, theme))).isEqualTo(fg(theme.getCommand()));
        assertThat(fg(OutputLineStyler.styleFor(Kind.HEADER, theme))).isEqualTo(fg(theme.getAccent1()));
        assertThat(fg(OutputLineStyler.styleFor(Kind.SUBHEADER, theme))).isEqualTo(fg(theme.getAccent2()));
        assertThat(fg(OutputLineStyler.styleFor(Kind.ITERATION, theme))).isEqualTo(fg(theme.getAccent2()));
    }

    @Test
    public void aFencedCodeBlockSitsOnTheRaisedSurface() {
        // opencode sets its code blocks on a panel one step above the background; the shell's
        // renderer does the same, so a block's extent is visible without a rule or a gutter.
        TuiTheme theme = TuiThemeManager.getCurrentTheme();
        MarkdownRenderer renderer = new MarkdownRenderer(theme, Glyphs.system());

        List<Line> lines = renderer.render(List.of("```", "int x = 1;", "```"), 80);

        boolean codePainted = false;
        for (Line line : lines) {
            for (Span span : line.spans()) {
                if ("int x = 1;".equals(span.content())
                        && span.style().bg().orElse(null) != null) {
                    assertThat(span.style().bg().orElse(null)).isEqualTo(theme.getSurface());
                    codePainted = true;
                }
            }
        }
        assertThat(codePainted).as("the code line painted on the surface").isTrue();
    }

    private static Color bg(Style style) {
        return style.bg().orElse(null);
    }

    private static Color fg(Style style) {
        return style.fg().orElse(null);
    }
}
