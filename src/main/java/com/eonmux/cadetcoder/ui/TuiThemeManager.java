package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.logging.DebugLogger;

import dev.tamboui.style.Color;
import java.util.*;

/**
 * TamboUI-only theme manager.
 *
 * <p>Manages the built-in themes used by the immediate-mode interactive shell. Every
 * theme is expressed in true 24-bit colour (via {@link dev.tamboui.style.Color#hex})
 * using the authentic, published palette for each scheme (see {@link ThemePalettes}),
 * so themes such as Dracula, Nord, Gruvbox, Monokai and One Dark are visually distinct
 * rather than collapsing onto the 16 basic ANSI colours.</p>
 *
 * <p>Slot order of the {@link TuiTheme} constructor (used by every {@code create*}
 * method below):</p>
 * <pre>
 *   name, description,
 *   windowBackground, windowBorder, windowTitle,
 *   textNormal, textActive, textSelected, textHighlight,
 *   fieldActive, fieldInactive, fieldSelected,
 *   success, warning, error, info, command, path, string,
 *   accent1, accent2, dim, bold,
 *   menuText, menuHighlighted, menuMnemonic, menuDisabled,
 *   label, status
 * </pre>
 */
public class TuiThemeManager {
    private static final Map<String, TuiTheme> themes = new HashMap<>();
    /** The theme in force when nothing has been chosen, or when what was chosen is not a theme. */
    public static final String DEFAULT_THEME = "modern";

    private static TuiTheme currentTheme;

    static {
        // Initialize all themes
        createModernTheme();
        createMatrixTheme();
        createDefaultTheme();
        createSolarizedDarkTheme();
        createSolarizedLightTheme();
        createMonokaiTheme();
        createNordTheme();
        createDraculaTheme();
        createGruvboxDarkTheme();
        createOneDarkTheme();

        // A theme is in force from the first lookup, whether or not the configuration can be read.
        currentTheme = themes.get(DEFAULT_THEME);
        applyConfiguredTheme();
    }

    /**
     * Get current theme
     */
    public static TuiTheme getCurrentTheme() {
        return currentTheme;
    }

    /**
     * Set theme by name
     */
    public static boolean setTheme(String themeName) {
        TuiTheme theme = themes.get(themeName);
        if (theme != null) {
            currentTheme = theme;
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
    public static TuiTheme getTheme(String themeName) {
        return themes.get(themeName);
    }

    /**
     * Add a custom theme
     */
    public static void addTheme(TuiTheme theme) {
        themes.put(theme.getName(), theme);
    }

    /**
     * Get theme descriptions
     */
    public static Map<String, String> getThemeDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        for (Map.Entry<String, TuiTheme> entry : themes.entrySet()) {
            descriptions.put(entry.getKey(), entry.getValue().getDescription());
        }
        return descriptions;
    }

    /**
     * Validate that all themes are properly configured for TUI usage.
     *
     * @return List of validation issues, empty if all themes are valid
     */
    public static List<String> validateThemes() {
        List<String> issues = new ArrayList<>();

        for (Map.Entry<String, TuiTheme> entry : themes.entrySet()) {
            String themeName = entry.getKey();
            TuiTheme theme = entry.getValue();

            if (theme == null) {
                issues.add("Theme '" + themeName + "' is null");
                continue;
            }

            if (theme.getName() == null || theme.getName().isEmpty()) {
                issues.add("Theme '" + themeName + "' has invalid name");
            }

            if (theme.getDescription() == null || theme.getDescription().isEmpty()) {
                issues.add("Theme '" + themeName + "' has invalid description");
            }

            if (theme.getWindowBackground() == null) {
                issues.add("Theme '" + themeName + "' missing window background color");
            }

            if (theme.getTextNormal() == null) {
                issues.add("Theme '" + themeName + "' missing normal text color");
            }

            if (theme.getFieldActive() == null) {
                issues.add("Theme '" + themeName + "' missing active field color");
            }

            if (theme.getSuccess() == null || theme.getWarning() == null ||
                theme.getError() == null || theme.getInfo() == null) {
                issues.add("Theme '" + themeName + "' missing essential message colors");
            }
        }

        return issues;
    }

    /**
     * Applies the theme named in the user's configuration.
     *
     * <h2>Why this is not read reflectively</h2>
     *
     * <p>It was: four {@code getMethod} lookups against {@code ConfigManager} by string name, inside
     * a catch-all that discarded whatever came back. Nothing about the dependency needed avoiding --
     * the configuration ships in this jar, and this package already depends on it elsewhere -- and
     * the price was that renaming any one of the four would have gone on compiling while silently
     * ceasing to honour the user's theme.</p>
     *
     * <h2>Why the shell no longer has its own copy</h2>
     *
     * <p>The interactive shell resolved the same setting again on startup, in its own words, with
     * its own idea of what an unknown theme name means. Two answers to one question, and the one
     * that ran depended on whether the process had opened a shell -- so a misspelled theme name
     * behaved differently for {@code cadet ls} than for {@code cadet -i}.</p>
     *
     * @return whether the configured theme was applied; {@code false} when the default stands
     */
    public static boolean applyConfiguredTheme() {
        try {
            return apply(ConfigManager.getInstance().getConfig().getUi().getColorTheme());
        } catch (Exception e) {
            // Reported rather than discarded, but never raised: this also runs while the class is
            // initialising, and a theme is not worth failing the process over.
            try {
                DebugLogger.getInstance().error("TuiThemeManager",
                        "Could not read the configured theme: " + e.getMessage(), e);
            } catch (Exception loggingFailed) {
                // Nowhere left to report it; the default theme is already in force.
            }
            return false;
        }
    }

    /**
     * Applies a theme by name, falling back to {@link #DEFAULT_THEME}.
     *
     * @param themeName the theme asked for, which may be {@code null}, empty or unknown
     * @return whether that theme was applied; {@code false} when the default was used instead
     */
    public static boolean apply(String themeName) {
        if (themeName != null && !themeName.isEmpty() && setTheme(themeName)) {
            return true;
        }
        setTheme(DEFAULT_THEME);
        return false;
    }

    private static void createModernTheme() {
        // Modern dark theme: graphite background with a calm teal/blue accent.
        String bg     = ThemePalettes.Modern.BG;
        String border = ThemePalettes.Modern.SURFACE;
        String sel    = ThemePalettes.Modern.SELECTION;

        TuiTheme theme = new TuiTheme(
            "modern",
            "Modern dark theme with a graphite background and a calm accent",

            // Window elements (background, border, title)
            TuiTheme.style(Color.hex(ThemePalettes.Modern.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Modern.ACCENT_TEAL), Color.hex(border), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Modern.ACCENT_TEAL), Color.hex(bg), true, false, false, false, false),

            // Text elements (normal, active, selected, highlight)
            TuiTheme.style(Color.hex(ThemePalettes.Modern.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Modern.TEXT), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(sel), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Modern.BRIGHT), Color.hex(bg), true, false, false, false, false),

            // Field elements (active, inactive, selected)
            TuiTheme.style(Color.hex(ThemePalettes.Modern.TEXT), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Modern.DIM), Color.hex(bg), false, false, true, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(sel), false, false, false, false, false),

            // Semantic colors (success, warning, error, info, command, path, string)
            TuiTheme.style(Color.hex(ThemePalettes.Modern.SUCCESS), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Modern.WARNING), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Modern.ERROR), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Modern.INFO), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Modern.COMMAND), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Modern.PATH), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Modern.STRING), null, false, false, false, false, false),

            // Accent colors (accent1, accent2, dim, bold)
            TuiTheme.style(Color.hex(ThemePalettes.Modern.ACCENT_TEAL), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Modern.ACCENT_BLUE), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Modern.DIM), null, false, false, true, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Modern.BRIGHT), null, true, false, false, false, false),

            // Menu colors (text, highlighted, mnemonic, disabled)
            TuiTheme.style(Color.hex(ThemePalettes.Modern.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(sel), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Modern.ACCENT_TEAL), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Modern.DIM), null, false, false, true, false, false),

            // Status and labels (label, status)
            TuiTheme.style(Color.hex(ThemePalettes.Modern.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Modern.TEXT), Color.hex(bg), false, false, false, false, false)
        );

        themes.put("modern", theme);
    }

    private static void createMatrixTheme() {
        // Matrix theme: layered greens on near-black with digital aesthetics.
        String bg  = ThemePalettes.Matrix.BG;
        String sel = ThemePalettes.Matrix.SELECTION;

        TuiTheme theme = new TuiTheme(
            "matrix",
            "Matrix-style green on black theme with digital aesthetics",

            // Window elements
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.NORMAL), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.BRIGHT), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.BRIGHT), Color.hex(bg), true, false, false, false, false),

            // Text elements
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.NORMAL), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.NORMAL), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(sel), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.HIGHLIGHT), Color.hex(bg), true, true, false, false, false),

            // Field elements
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.NORMAL), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.DIM), Color.hex(bg), false, false, true, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(sel), false, false, false, false, false),

            // Semantic colors
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.SUCCESS), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.WARNING), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.ERROR), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.INFO), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.ACCENT2), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.BRIGHT), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.NORMAL), null, false, false, false, false, false),

            // Accent colors
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.ACCENT1), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.ACCENT2), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.DIM), null, false, false, true, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.BRIGHT), null, true, false, false, false, false),

            // Menu colors
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.NORMAL), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(sel), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.BRIGHT), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.DIM), null, false, false, true, false, false),

            // Status and labels
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.NORMAL), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Matrix.NORMAL), Color.hex(bg), false, false, false, false, false)
        );

        themes.put("matrix", theme);
    }

    private static void createDefaultTheme() {
        // Clean modern blue (Turbo Vision-inspired) in true colour.
        String bg     = ThemePalettes.Default.BG;
        String panel  = ThemePalettes.Default.DEEP_BLUE;
        String border = ThemePalettes.Default.SURFACE;
        String sel    = ThemePalettes.Default.SELECTION;

        TuiTheme theme = new TuiTheme(
            "default",
            "Classic blue and white theme reminiscent of Turbo Vision",

            // Window elements
            TuiTheme.style(Color.hex(ThemePalettes.Default.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Default.GOLD), Color.hex(border), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Default.GOLD), Color.hex(panel), true, false, false, false, false),

            // Text elements
            TuiTheme.style(Color.hex(ThemePalettes.Default.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Default.TEXT), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(sel), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Default.GOLD), Color.hex(bg), true, false, false, false, false),

            // Field elements
            TuiTheme.style(Color.hex(ThemePalettes.Default.BRIGHT), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Default.DIM), Color.hex(bg), false, false, true, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(sel), false, false, false, false, false),

            // Semantic colors
            TuiTheme.style(Color.hex(ThemePalettes.Default.SUCCESS), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Default.WARNING), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Default.ERROR), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Default.INFO), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Default.COMMAND), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Default.PATH), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Default.STRING), null, false, false, false, false, false),

            // Accent colors
            TuiTheme.style(Color.hex(ThemePalettes.Default.GOLD), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Default.INFO), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Default.DIM), null, false, false, true, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Default.BRIGHT), null, true, false, false, false, false),

            // Menu colors
            TuiTheme.style(Color.hex(ThemePalettes.Default.TEXT), Color.hex(panel), false, false, false, false, false),
            TuiTheme.style(Color.hex(panel), Color.hex(ThemePalettes.Default.GOLD), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Default.GOLD), Color.hex(panel), true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Default.DIM), Color.hex(panel), false, false, true, false, false),

            // Status and labels
            TuiTheme.style(Color.hex(ThemePalettes.Default.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Default.TEXT), Color.hex(panel), false, false, false, false, false)
        );

        themes.put("default", theme);
    }

    private static void createSolarizedDarkTheme() {
        // Solarized Dark (Ethan Schoonover).
        String bg  = ThemePalettes.SolarizedDark.BASE03;
        String sel = ThemePalettes.SolarizedDark.BASE02;

        TuiTheme theme = new TuiTheme(
            "solarized-dark",
            "Solarized dark theme with reduced brightness",

            // Window elements
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.BASE0), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.CYAN), Color.hex(sel), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.CYAN), Color.hex(bg), true, false, false, false, false),

            // Text elements
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.BASE0), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.BASE1), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.SolarizedDark.CYAN), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.CYAN), Color.hex(bg), true, false, false, false, false),

            // Field elements
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.BLUE), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.BASE01), Color.hex(bg), false, false, true, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.SolarizedDark.CYAN), false, false, false, false, false),

            // Semantic colors
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.GREEN), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.YELLOW), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.RED), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.BLUE), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.VIOLET), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.CYAN), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.YELLOW), null, false, false, false, false, false),

            // Accent colors
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.MAGENTA), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.CYAN), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.BASE01), null, false, false, true, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.BASE1), null, true, false, false, false, false),

            // Menu colors
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.BASE0), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.SolarizedDark.CYAN), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.YELLOW), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.BASE01), Color.hex(bg), false, false, true, false, false),

            // Status and labels
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.BASE0), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedDark.BASE0), Color.hex(bg), false, false, false, false, false)
        );

        themes.put("solarized-dark", theme);
    }

    private static void createSolarizedLightTheme() {
        // Solarized Light (Ethan Schoonover).
        String bg  = ThemePalettes.SolarizedLight.BASE3;
        String sel = ThemePalettes.SolarizedLight.BASE2;

        TuiTheme theme = new TuiTheme(
            "solarized-light",
            "Solarized light theme for bright terminals",

            // Window elements
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.BASE00), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.BLUE), Color.hex(sel), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.BLUE), Color.hex(bg), true, false, false, false, false),

            // Text elements
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.BASE00), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.BASE01), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.SolarizedLight.BLUE), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.BLUE), Color.hex(bg), true, false, false, false, false),

            // Field elements
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.BLUE), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.BASE1), Color.hex(bg), false, false, true, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.SolarizedLight.BLUE), false, false, false, false, false),

            // Semantic colors
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.GREEN), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.YELLOW), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.RED), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.CYAN), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.VIOLET), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.CYAN), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.ORANGE), null, false, false, false, false, false),

            // Accent colors
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.MAGENTA), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.BLUE), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.BASE1), null, false, false, true, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.BASE01), null, true, false, false, false, false),

            // Menu colors
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.BASE00), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.SolarizedLight.BLUE), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.BLUE), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.BASE1), Color.hex(bg), false, false, true, false, false),

            // Status and labels
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.BASE00), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.SolarizedLight.BASE00), Color.hex(bg), false, false, false, false, false)
        );

        themes.put("solarized-light", theme);
    }

    private static void createMonokaiTheme() {
        // Monokai (Wimer Hazenberg).
        String bg  = ThemePalettes.Monokai.BG;
        String sel = ThemePalettes.Monokai.SELECTION;

        TuiTheme theme = new TuiTheme(
            "monokai",
            "Monokai theme with vibrant colors",

            // Window elements
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.PINK), Color.hex(sel), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.PINK), Color.hex(bg), true, false, false, false, false),

            // Text elements
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.TEXT), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.Monokai.PINK), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.BLUE), Color.hex(bg), true, false, false, false, false),

            // Field elements
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.BLUE), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.COMMENT), Color.hex(bg), false, false, true, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.Monokai.BLUE), false, false, false, false, false),

            // Semantic colors
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.GREEN), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.ORANGE), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.PINK), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.BLUE), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.PURPLE), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.GREEN), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.YELLOW), null, false, false, false, false, false),

            // Accent colors
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.PINK), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.BLUE), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.COMMENT), null, false, false, true, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.TEXT), null, true, false, false, false, false),

            // Menu colors
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.Monokai.PINK), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.PINK), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.COMMENT), Color.hex(bg), false, false, true, false, false),

            // Status and labels
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Monokai.TEXT), Color.hex(bg), false, false, false, false, false)
        );

        themes.put("monokai", theme);
    }

    private static void createNordTheme() {
        // Nord (Arctic Ice Studio).
        String bg  = ThemePalettes.Nord.NIGHT0;
        String sel = ThemePalettes.Nord.NIGHT1;

        TuiTheme theme = new TuiTheme(
            "nord",
            "Nord theme with cool blue tones",

            // Window elements
            TuiTheme.style(Color.hex(ThemePalettes.Nord.SNOW0), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Nord.FROST2), Color.hex(ThemePalettes.Nord.NIGHT2), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Nord.FROST1), Color.hex(bg), true, false, false, false, false),

            // Text elements
            TuiTheme.style(Color.hex(ThemePalettes.Nord.SNOW0), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Nord.SNOW2), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.Nord.FROST1), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Nord.FROST1), Color.hex(bg), true, false, false, false, false),

            // Field elements
            TuiTheme.style(Color.hex(ThemePalettes.Nord.FROST2), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Nord.NIGHT3), Color.hex(bg), false, false, true, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.Nord.FROST1), false, false, false, false, false),

            // Semantic colors
            TuiTheme.style(Color.hex(ThemePalettes.Nord.GREEN), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Nord.ORANGE), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Nord.RED), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Nord.FROST1), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Nord.PURPLE), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Nord.FROST0), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Nord.YELLOW), null, false, false, false, false, false),

            // Accent colors
            TuiTheme.style(Color.hex(ThemePalettes.Nord.FROST1), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Nord.FROST0), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Nord.NIGHT3), null, false, false, true, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Nord.SNOW2), null, true, false, false, false, false),

            // Menu colors
            TuiTheme.style(Color.hex(ThemePalettes.Nord.SNOW0), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.Nord.FROST1), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Nord.FROST1), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Nord.NIGHT3), Color.hex(bg), false, false, true, false, false),

            // Status and labels
            TuiTheme.style(Color.hex(ThemePalettes.Nord.SNOW0), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Nord.SNOW0), Color.hex(bg), false, false, false, false, false)
        );

        themes.put("nord", theme);
    }

    private static void createDraculaTheme() {
        // Dracula (Zeno Rocha).
        String bg  = ThemePalettes.Dracula.BG;
        String sel = ThemePalettes.Dracula.CURRENT;

        TuiTheme theme = new TuiTheme(
            "dracula",
            "Dracula theme with purple accents",

            // Window elements
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.PURPLE), Color.hex(sel), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.PINK), Color.hex(bg), true, false, false, false, false),

            // Text elements
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.TEXT), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.Dracula.PINK), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.PURPLE), Color.hex(bg), true, false, false, false, false),

            // Field elements
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.PURPLE), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.COMMENT), Color.hex(bg), false, false, true, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.Dracula.PINK), false, false, false, false, false),

            // Semantic colors
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.GREEN), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.ORANGE), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.RED), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.CYAN), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.PINK), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.GREEN), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.YELLOW), null, false, false, false, false, false),

            // Accent colors
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.PURPLE), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.CYAN), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.COMMENT), null, false, false, true, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.TEXT), null, true, false, false, false, false),

            // Menu colors
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.Dracula.PINK), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.PINK), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.COMMENT), Color.hex(bg), false, false, true, false, false),

            // Status and labels
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.Dracula.TEXT), Color.hex(bg), false, false, false, false, false)
        );

        themes.put("dracula", theme);
    }

    private static void createGruvboxDarkTheme() {
        // Gruvbox Dark (Pavel Pertsev).
        String bg  = ThemePalettes.GruvboxDark.BG;
        String sel = ThemePalettes.GruvboxDark.BG_SOFT;

        TuiTheme theme = new TuiTheme(
            "gruvbox-dark",
            "Gruvbox dark theme with warm colors",

            // Window elements
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.FG), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.YELLOW), Color.hex(sel), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.YELLOW), Color.hex(bg), true, false, false, false, false),

            // Text elements
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.FG), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.FG), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.GruvboxDark.YELLOW), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.ORANGE), Color.hex(bg), true, false, false, false, false),

            // Field elements
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.AQUA), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.GRAY), Color.hex(bg), false, false, true, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.GruvboxDark.YELLOW), false, false, false, false, false),

            // Semantic colors
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.GREEN), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.YELLOW), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.RED), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.BLUE), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.PURPLE), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.AQUA), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.YELLOW), null, false, false, false, false, false),

            // Accent colors
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.ORANGE), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.AQUA), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.GRAY), null, false, false, true, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.FG), null, true, false, false, false, false),

            // Menu colors
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.FG), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.GruvboxDark.YELLOW), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.YELLOW), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.GRAY), Color.hex(bg), false, false, true, false, false),

            // Status and labels
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.FG), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.GruvboxDark.FG), Color.hex(bg), false, false, false, false, false)
        );

        themes.put("gruvbox-dark", theme);
    }

    private static void createOneDarkTheme() {
        // One Dark (Atom).
        String bg  = ThemePalettes.OneDark.BG;
        String sel = ThemePalettes.OneDark.SELECTION;

        TuiTheme theme = new TuiTheme(
            "one-dark",
            "One Dark theme from Atom editor",

            // Window elements
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.BLUE), Color.hex(sel), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.BLUE), Color.hex(bg), true, false, false, false, false),

            // Text elements
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.BRIGHT), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.OneDark.BLUE), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.CYAN), Color.hex(bg), true, false, false, false, false),

            // Field elements
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.MAGENTA), Color.hex(bg), true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.GUTTER), Color.hex(bg), false, false, true, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.OneDark.BLUE), false, false, false, false, false),

            // Semantic colors
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.GREEN), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.YELLOW), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.RED), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.BLUE), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.MAGENTA), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.GREEN), null, false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.YELLOW), null, false, false, false, false, false),

            // Accent colors
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.CYAN), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.BLUE), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.GUTTER), null, false, false, true, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.BRIGHT), null, true, false, false, false, false),

            // Menu colors
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(bg), Color.hex(ThemePalettes.OneDark.BLUE), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.MAGENTA), null, true, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.GUTTER), Color.hex(bg), false, false, true, false, false),

            // Status and labels
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.TEXT), Color.hex(bg), false, false, false, false, false),
            TuiTheme.style(Color.hex(ThemePalettes.OneDark.TEXT), Color.hex(bg), false, false, false, false, false)
        );

        themes.put("one-dark", theme);
    }
}
