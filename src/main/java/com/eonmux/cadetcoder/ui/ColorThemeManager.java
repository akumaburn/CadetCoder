package com.eonmux.cadetcoder.ui;

import java.util.*;

/**
 * Manages available color themes for the CLI.
 * Provides predefined themes and theme selection functionality.
 */
public class ColorThemeManager {
    private static final Map<String, ColorTheme> themes = new HashMap<>();
    // ANSI color codes
    private static final String RESET = "\u001B[0m";
    private static final String BOLD  = "\u001B[1m";
    private static final String DIM   = "\u001B[2m";
    // Foreground colors
    private static final String BLACK   = "\u001B[30m";
    private static final String RED     = "\u001B[31m";
    private static final String GREEN   = "\u001B[32m";
    private static final String YELLOW  = "\u001B[33m";
    private static final String BLUE    = "\u001B[34m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String CYAN    = "\u001B[36m";
    private static final String WHITE   = "\u001B[37m";
    // Bright foreground colors
    private static final String BRIGHT_BLACK   = "\u001B[90m";
    private static final String BRIGHT_RED     = "\u001B[91m";
    private static final String BRIGHT_GREEN   = "\u001B[92m";
    private static final String BRIGHT_YELLOW  = "\u001B[93m";
    private static final String BRIGHT_BLUE    = "\u001B[94m";
    private static final String BRIGHT_MAGENTA = "\u001B[95m";
    private static final String BRIGHT_CYAN    = "\u001B[96m";
    private static final String BRIGHT_WHITE   = "\u001B[97m";
    private static       ColorTheme              currentTheme;

    /**
     * Build a 24-bit truecolor foreground SGR escape ({@code ESC[38;2;R;G;Bm}) from
     * an {@code "#RRGGBB"} hex string. Used by truecolor themes so they render with
     * their authentic published palettes rather than collapsing onto the 16 basic
     * ANSI colours.
     */
    private static String fg(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return "\u001B[38;2;" + r + ";" + g + ";" + b + "m";
    }

    static {
        // Initialize predefined themes
        createMatrixTheme();
        createDefaultTheme();
        createSolarizedDarkTheme();
        createSolarizedLightTheme();
        createMonokaiTheme();
        createDraculaTheme();
        createNordTheme();
        createGruvboxDarkTheme();
        createOneDarkTheme();
        createModernTheme();

        // Set default theme to matrix
        currentTheme = themes.get("matrix");
    }

    private static void createModernTheme() {
        // Modern dark theme (graphite background + calm teal/blue accent), expressed
        // in authentic 24-bit truecolor to match the TUI 'modern' theme.
        themes.put("modern", new ColorTheme(
                "modern",
                "Modern dark theme with a graphite background and a calm accent",
                fg("#56b6c2"),  // header  - teal accent
                fg("#d7dae0"),  // subheader - bright graphite text
                fg("#98c379"),  // success - green
                fg("#e5c07b"),  // warning - amber
                fg("#e06c75"),  // error   - red
                fg("#61afef"),  // info    - blue
                fg("#c678dd"),  // command - purple accent
                fg("#e5c07b"),  // string  - amber
                fg("#56b6c2"),  // path    - teal
                fg("#56b6c2"),  // accent1 - teal (primary)
                fg("#61afef"),  // accent2 - blue (secondary)
                fg("#5c6370")   // dim     - muted comment grey
        ));
    }

    private static void createMatrixTheme() {
        themes.put("matrix", new ColorTheme(
                "matrix",
                "Matrix-style green on black theme with digital aesthetics",
                BRIGHT_GREEN,   // header - bright green for prominence
                GREEN,          // subheader - normal green
                BRIGHT_GREEN,   // success - bright green
                BRIGHT_YELLOW,  // warning - yellow for visibility against black
                BRIGHT_RED,     // error - bright red for visibility
                GREEN,          // info - normal green
                BRIGHT_CYAN,    // command - cyan for command differentiation
                GREEN,          // string - green strings
                BRIGHT_GREEN,   // path - bright green paths
                BRIGHT_WHITE,   // accent1 - white for special highlights
                CYAN,           // accent2 - cyan for secondary highlights  
                DIM + GREEN     // dim - dimmed green for less important text
        ));
    }

    private static void createDefaultTheme() {
        themes.put("default", new ColorTheme(
                "default",
                "Classic terminal colors with good contrast",
                BLUE,           // header
                WHITE,          // subheader
                GREEN,          // success
                YELLOW,         // warning
                RED,            // error
                BLUE,           // info
                CYAN,           // command
                GREEN,          // string
                YELLOW,         // path
                MAGENTA,        // accent1
                BRIGHT_CYAN,    // accent2
                BRIGHT_BLACK    // dim
        ));
    }

    private static void createSolarizedDarkTheme() {
        // Solarized color values approximated with ANSI
        themes.put("solarized-dark", new ColorTheme(
                "solarized-dark",
                "Solarized dark theme with reduced brightness",
                CYAN,           // header
                BRIGHT_WHITE,   // subheader
                GREEN,          // success
                YELLOW,         // warning
                RED,            // error
                BLUE,           // info
                BRIGHT_BLUE,    // command
                CYAN,           // string
                BRIGHT_YELLOW,  // path
                MAGENTA,        // accent1
                BRIGHT_CYAN,    // accent2
                BRIGHT_BLACK    // dim
        ));
    }

    private static void createSolarizedLightTheme() {
        themes.put("solarized-light", new ColorTheme(
                "solarized-light",
                "Solarized light theme for bright terminals",
                BLUE,           // header
                BLACK,          // subheader
                GREEN,          // success
                YELLOW,         // warning
                RED,            // error
                CYAN,           // info
                BLUE,           // command
                CYAN,           // string
                MAGENTA,        // path
                BRIGHT_MAGENTA, // accent1
                BRIGHT_BLUE,    // accent2
                BRIGHT_BLACK    // dim
        ));
    }

    private static void createMonokaiTheme() {
        themes.put("monokai", new ColorTheme(
                "monokai",
                "Monokai theme with vibrant colors",
                BRIGHT_MAGENTA, // header
                BRIGHT_WHITE,   // subheader
                BRIGHT_GREEN,   // success
                BRIGHT_YELLOW,  // warning
                BRIGHT_RED,     // error
                BRIGHT_BLUE,    // info
                BRIGHT_CYAN,    // command
                YELLOW,         // string
                GREEN,          // path
                MAGENTA,        // accent1
                BRIGHT_BLUE,    // accent2
                BRIGHT_BLACK    // dim
        ));
    }

    private static void createDraculaTheme() {
        themes.put("dracula", new ColorTheme(
                "dracula",
                "Dracula theme with purple accents",
                BRIGHT_MAGENTA, // header
                BRIGHT_WHITE,   // subheader
                BRIGHT_GREEN,   // success
                BRIGHT_YELLOW,  // warning
                BRIGHT_RED,     // error
                BRIGHT_CYAN,    // info
                BRIGHT_MAGENTA, // command
                BRIGHT_YELLOW,  // string
                BRIGHT_BLUE,    // path
                MAGENTA,        // accent1
                CYAN,           // accent2
                BRIGHT_BLACK    // dim
        ));
    }

    private static void createNordTheme() {
        themes.put("nord", new ColorTheme(
                "nord",
                "Nord theme with cool blue tones",
                BRIGHT_BLUE,    // header
                BRIGHT_WHITE,   // subheader
                BRIGHT_GREEN,   // success
                YELLOW,         // warning
                BRIGHT_RED,     // error
                BRIGHT_CYAN,    // info
                BRIGHT_BLUE,    // command
                GREEN,          // string
                BRIGHT_YELLOW,  // path
                BRIGHT_MAGENTA, // accent1
                CYAN,           // accent2
                BRIGHT_BLACK    // dim
        ));
    }

    private static void createGruvboxDarkTheme() {
        themes.put("gruvbox-dark", new ColorTheme(
                "gruvbox-dark",
                "Gruvbox dark theme with warm colors",
                BRIGHT_YELLOW,  // header
                BRIGHT_WHITE,   // subheader
                BRIGHT_GREEN,   // success
                YELLOW,         // warning
                BRIGHT_RED,     // error
                BRIGHT_BLUE,    // info
                BRIGHT_CYAN,    // command
                GREEN,          // string
                BRIGHT_YELLOW,  // path
                BRIGHT_MAGENTA, // accent1
                CYAN,           // accent2
                BRIGHT_BLACK    // dim
        ));
    }

    private static void createOneDarkTheme() {
        themes.put("one-dark", new ColorTheme(
                "one-dark",
                "One Dark theme from Atom editor",
                BRIGHT_BLUE,    // header
                BRIGHT_WHITE,   // subheader
                BRIGHT_GREEN,   // success
                BRIGHT_YELLOW,  // warning
                BRIGHT_RED,     // error
                BRIGHT_CYAN,    // info
                BRIGHT_MAGENTA, // command
                GREEN,          // string
                YELLOW,         // path
                BRIGHT_MAGENTA, // accent1
                BRIGHT_BLUE,    // accent2
                BRIGHT_BLACK    // dim
        ));
    }

    /**
     * Get current active theme
     */
    public static ColorTheme getCurrentTheme() {
        return currentTheme;
    }

    /**
     * Set active theme by name
     */
    public static boolean setTheme(String themeName) {
        if (themes.containsKey(themeName)) {
            currentTheme = themes.get(themeName);
            return true;
        }
        return false;
    }

    /**
     * Get all available theme names
     */
    public static Set<String> getAvailableThemes() {
        return themes.keySet();
    }

    /**
     * Get theme by name
     */
    public static ColorTheme getTheme(String themeName) {
        return themes.get(themeName);
    }

    /**
     * Add a custom theme
     */
    public static void addTheme(ColorTheme theme) {
        themes.put(theme.getName(), theme);
    }

    /**
     * Get theme descriptions for display
     */
    public static Map<String, String> getThemeDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        for (Map.Entry<String, ColorTheme> entry : themes.entrySet()) {
            descriptions.put(entry.getKey(), entry.getValue().getDescription());
        }
        return descriptions;
    }
}