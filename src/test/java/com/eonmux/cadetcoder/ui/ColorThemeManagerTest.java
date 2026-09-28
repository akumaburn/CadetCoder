package com.eonmux.cadetcoder.ui;

import org.junit.*;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for ColorThemeManager
 */
public class ColorThemeManagerTest {

    private ColorTheme originalTheme;

    @Before
    public void setUp() {
        originalTheme = ColorThemeManager.getCurrentTheme();
    }

    @After
    public void tearDown() {
        // Restore original theme
        if (originalTheme != null) {
            ColorThemeManager.setTheme(originalTheme.getName());
        }
    }

    @Test
    public void testGetCurrentTheme_ReturnsDefaultInitially() {
        // Reset to default
        ColorThemeManager.setTheme("default");

        ColorTheme current = ColorThemeManager.getCurrentTheme();

        assertThat(current).isNotNull();
        assertThat(current.getName()).isEqualTo("default");
    }

    @Test
    public void testSetTheme_ValidTheme() {
        boolean result = ColorThemeManager.setTheme("solarized-dark");

        assertThat(result).isTrue();
        assertThat(ColorThemeManager.getCurrentTheme().getName()).isEqualTo("solarized-dark");
    }

    @Test
    public void testSetTheme_InvalidTheme() {
        boolean result = ColorThemeManager.setTheme("nonexistent-theme");

        assertThat(result).isFalse();
        // Current theme should remain unchanged
        assertThat(ColorThemeManager.getCurrentTheme()).isEqualTo(originalTheme);
    }

    @Test
    public void testGetAvailableThemes_ContainsExpectedThemes() {
        Set<String> themes = ColorThemeManager.getAvailableThemes();

        assertThat(themes).contains(
                "modern",
                "matrix",
                "default",
                "solarized-dark",
                "solarized-light",
                "monokai",
                "dracula",
                "nord",
                "gruvbox-dark",
                "one-dark"
                                   );
        assertThat(themes).hasSize(10);
    }

    @Test
    public void testGetTheme_ValidTheme() {
        ColorTheme theme = ColorThemeManager.getTheme("default");

        assertThat(theme).isNotNull();
        assertThat(theme.getName()).isEqualTo("default");
        assertThat(theme.getDescription()).contains("Classic terminal colors");
    }

    @Test
    public void testGetTheme_InvalidTheme() {
        ColorTheme theme = ColorThemeManager.getTheme("nonexistent");

        assertThat(theme).isNull();
    }

    @Test
    public void testAddTheme_CustomTheme() {
        ColorTheme customTheme = new ColorTheme(
                "custom-test", "Custom test theme",
                "\u001B[31m", "\u001B[32m", "\u001B[33m",     // header, subheader, success
                "\u001B[34m", "\u001B[35m", "\u001B[36m",     // warning, error, info
                "\u001B[37m", "\u001B[90m", "\u001B[91m",     // command, string, path
                "\u001B[92m", "\u001B[93m", "\u001B[94m"      // accent1, accent2, dim
        );

        ColorThemeManager.addTheme(customTheme);

        assertThat(ColorThemeManager.getAvailableThemes()).contains("custom-test");
        assertThat(ColorThemeManager.getTheme("custom-test")).isEqualTo(customTheme);

        // Test setting the custom theme
        boolean result = ColorThemeManager.setTheme("custom-test");
        assertThat(result).isTrue();
        assertThat(ColorThemeManager.getCurrentTheme()).isEqualTo(customTheme);
    }

    @Test
    public void testGetThemeDescriptions_ContainsAllThemes() {
        Map<String, String> descriptions = ColorThemeManager.getThemeDescriptions();

        assertThat(descriptions).hasSize(10);
        assertThat(descriptions).containsKey("modern");
        assertThat(descriptions).containsKey("matrix");
        assertThat(descriptions).containsKey("default");
        assertThat(descriptions).containsKey("solarized-dark");
        assertThat(descriptions).containsKey("solarized-light");
        assertThat(descriptions).containsKey("monokai");
        assertThat(descriptions).containsKey("dracula");
        assertThat(descriptions).containsKey("nord");
        assertThat(descriptions).containsKey("gruvbox-dark");
        assertThat(descriptions).containsKey("one-dark");

        assertThat(descriptions.get("matrix")).contains("Matrix-style green on black");
        assertThat(descriptions.get("default")).contains("Classic terminal colors");
        assertThat(descriptions.get("solarized-dark")).contains("Solarized dark theme");
        assertThat(descriptions.get("monokai")).contains("Monokai theme");
    }

    @Test
    public void testPredefinedThemes_MatrixTheme() {
        ColorTheme theme = ColorThemeManager.getTheme("matrix");

        assertThat(theme.getName()).isEqualTo("matrix");
        assertThat(theme.getDescription()).contains("Matrix-style green on black theme with digital aesthetics");
        assertThat(theme.getHeader()).isEqualTo("\u001B[92m");    // BRIGHT_GREEN
        assertThat(theme.getSuccess()).isEqualTo("\u001B[92m");   // BRIGHT_GREEN
        assertThat(theme.getError()).isEqualTo("\u001B[91m");     // BRIGHT_RED
        assertThat(theme.getWarning()).isEqualTo("\u001B[93m");   // BRIGHT_YELLOW
    }

    @Test
    public void testPredefinedThemes_DefaultTheme() {
        ColorTheme theme = ColorThemeManager.getTheme("default");

        assertThat(theme.getName()).isEqualTo("default");
        assertThat(theme.getDescription()).contains("Classic terminal colors with good contrast");
        assertThat(theme.getSuccess()).isEqualTo("\u001B[32m"); // GREEN
        assertThat(theme.getError()).isEqualTo("\u001B[31m");   // RED
        assertThat(theme.getWarning()).isEqualTo("\u001B[33m"); // YELLOW
    }

    @Test
    public void testPredefinedThemes_SolarizedDark() {
        ColorTheme theme = ColorThemeManager.getTheme("solarized-dark");

        assertThat(theme.getName()).isEqualTo("solarized-dark");
        assertThat(theme.getDescription()).contains("Solarized dark theme with reduced brightness");
        assertThat(theme.getHeader()).isEqualTo("\u001B[36m");    // CYAN
        assertThat(theme.getSuccess()).isEqualTo("\u001B[32m");   // GREEN
    }

    @Test
    public void testPredefinedThemes_Monokai() {
        ColorTheme theme = ColorThemeManager.getTheme("monokai");

        assertThat(theme.getName()).isEqualTo("monokai");
        assertThat(theme.getDescription()).contains("Monokai theme with vibrant colors");
        assertThat(theme.getHeader()).isEqualTo("\u001B[95m");    // BRIGHT_MAGENTA
        assertThat(theme.getSuccess()).isEqualTo("\u001B[92m");   // BRIGHT_GREEN
    }

    @Test
    public void testPredefinedThemes_Dracula() {
        ColorTheme theme = ColorThemeManager.getTheme("dracula");

        assertThat(theme.getName()).isEqualTo("dracula");
        assertThat(theme.getDescription()).contains("Dracula theme with purple accents");
        assertThat(theme.getCommand()).isEqualTo("\u001B[95m");   // BRIGHT_MAGENTA
    }

    @Test
    public void testPredefinedThemes_Nord() {
        ColorTheme theme = ColorThemeManager.getTheme("nord");

        assertThat(theme.getName()).isEqualTo("nord");
        assertThat(theme.getDescription()).contains("Nord theme with cool blue tones");
        assertThat(theme.getHeader()).isEqualTo("\u001B[94m");    // BRIGHT_BLUE
    }

    @Test
    public void testPredefinedThemes_GruvboxDark() {
        ColorTheme theme = ColorThemeManager.getTheme("gruvbox-dark");

        assertThat(theme.getName()).isEqualTo("gruvbox-dark");
        assertThat(theme.getDescription()).contains("Gruvbox dark theme with warm colors");
        assertThat(theme.getHeader()).isEqualTo("\u001B[93m");    // BRIGHT_YELLOW
    }

    @Test
    public void testPredefinedThemes_OneDark() {
        ColorTheme theme = ColorThemeManager.getTheme("one-dark");

        assertThat(theme.getName()).isEqualTo("one-dark");
        assertThat(theme.getDescription()).contains("One Dark theme from Atom editor");
        assertThat(theme.getHeader()).isEqualTo("\u001B[94m");    // BRIGHT_BLUE
    }

    @Test
    public void testThemeProperties_AllThemesHaveRequiredFields() {
        Set<String> themeNames = ColorThemeManager.getAvailableThemes();

        for (String themeName : themeNames) {
            ColorTheme theme = ColorThemeManager.getTheme(themeName);

            assertThat(theme.getName()).isNotNull().isNotEmpty();
            assertThat(theme.getDescription()).isNotNull().isNotEmpty();
            assertThat(theme.getHeader()).isNotNull();
            assertThat(theme.getSuccess()).isNotNull();
            assertThat(theme.getError()).isNotNull();
            assertThat(theme.getWarning()).isNotNull();
            assertThat(theme.getInfo()).isNotNull();
            assertThat(theme.getReset()).isEqualTo("\u001B[0m");
            assertThat(theme.getBold()).isEqualTo("\u001B[1m");
        }
    }

    @Test
    public void testThemeColorization_AllPredefinedThemes() {
        Set<String> themeNames = ColorThemeManager.getAvailableThemes();

        for (String themeName : themeNames) {
            ColorTheme theme = ColorThemeManager.getTheme(themeName);

            // Test colorization works without throwing exceptions
            String result = theme.colorize("test", ColorTheme.ColorType.SUCCESS);
            assertThat(result).contains("test");
            assertThat(result).endsWith("\u001B[0m");

            result = theme.colorize("error", ColorTheme.ColorType.ERROR);
            assertThat(result).contains("error");
            assertThat(result).endsWith("\u001B[0m");
        }
    }
}