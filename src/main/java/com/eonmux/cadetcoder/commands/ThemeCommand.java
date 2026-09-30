package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.logging.DebugLogger;
import com.eonmux.cadetcoder.ui.*;
import picocli.CommandLine.*;

import java.util.*;
import java.util.concurrent.Callable;

/**
 * Theme management command optimized for TUI interface.
 * Provides comprehensive theme operations with TUI-specific previews and validation.
 */
@Command(name = "theme", description = "Show, preview or change the color theme")
public class ThemeCommand implements CommandRegistry.Command, Callable<Integer> {

    /** The shared glyph set, so this listing degrades with the rest of the console. */
    private static final com.eonmux.cadetcoder.ui.Glyphs GLYPHS =
            com.eonmux.cadetcoder.ui.Glyphs.system();

    /** The actions that change the theme in the settings, which only the user may do. */
    private static final Set<String> CHANGES_THE_THEME = Set.of("set", "apply", "reset");

    @Parameters(index = "0",
            description = "Action: list, set, preview, current, validate, reset "
                        + "(aliases: ls, apply, info, check)",
            defaultValue = "list")
    private String action = "list";

    @Parameters(index = "1", description = "Theme name (for set/preview actions)", defaultValue = "")
    private String themeName = "";

    @Option(names = {"-s", "--save"}, description = "Save theme selection to configuration")
    private boolean save = false;

    @Option(names = {"-v", "--verbose"}, description = "Show detailed information")
    private boolean verbose = false;

    /**
     * Runs one {@code theme} invocation, on an instance that has never run one before.
     *
     * <p>The registry builds a single {@code ThemeCommand} and hands it every {@code theme} for the
     * life of the session, so the flags one invocation typed are otherwise still set for the next.
     * That used to be answered by putting all four fields back by hand before parsing -- a second
     * statement of the defaults the declarations already make, and one that says nothing when a
     * field is added and nobody remembers it.</p>
     *
     * @param args the argument vector
     * @return the exit code
     */
    @Override
    public int execute(String[] args) {
        return new ThemeCommand().executeOnce(args);
    }

    private int executeOnce(String[] args) {
        try {
            // Parse command line arguments manually to handle edge cases
            parseArguments(args);
            // Refused with or without --save: the theme is kept in the settings held in memory,
            // and the next command that saves them writes it to the file.
            if (CHANGES_THE_THEME.contains(action.toLowerCase(Locale.ROOT))) {
                Integer refused = ModelDispatch.refuseSetupChange("theme " + action);
                if (refused != null) {
                    return refused;
                }
            }
            return call();
        } catch (Exception e) {
            OutputFormatter.printError("Theme command failed: " + e.getMessage());
            if (verbose) {
                // Route stack-trace detail through the logging layer instead of writing a
                // raw trace to System.err, so TUI output routing/ANSI-stripping is honored.
                DebugLogger.getInstance().error("theme", "Theme command failed", e);
            }
            return 1;
        }
    }

    private void parseArguments(String[] args) {
        // First separate option flags from positional arguments so that a leading
        // flag (e.g. "theme -s set dark") does not get misinterpreted as the action.
        List<String> positionals = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("-s") || arg.equals("--save")) {
                save = true;
            } else if (arg.equals("-v") || arg.equals("--verbose")) {
                verbose = true;
            } else {
                positionals.add(arg);
            }
        }

        if (!positionals.isEmpty()) {
            action = positionals.get(0);
        }
        if (positionals.size() > 1) {
            themeName = positionals.get(1);
        }
    }

    @Override
    public Integer call() throws Exception {
        // An action typed as an empty string names nothing, and listing is what "theme" alone
        // means. Read into a local: writing it back would make this run's fallback the next run's
        // starting point.
        String requested = action == null || action.isEmpty() ? "list" : action;

        return switch (requested.toLowerCase()) {
            case "list", "ls" -> listThemes();
            case "set", "apply" -> setTheme();
            case "preview" -> previewTheme();
            case "current", "info" -> showCurrentTheme();
            case "validate", "check" -> validateThemes();
            case "reset" -> resetToDefault();
            default -> {
                OutputFormatter.printError("Unknown action: " + requested);
                showUsage();
                yield 1;
            }
        };
    }

    /**
     * List all available TUI themes with descriptions
     */
    private int listThemes() {
        OutputFormatter.printHeader("Available TUI Themes");

        Map<String, String> descriptions = TuiThemeManager.getThemeDescriptions();
        List<String> themeNames = new ArrayList<>(descriptions.keySet());
        Collections.sort(themeNames);

        TuiTheme currentTheme = TuiThemeManager.getCurrentTheme();
        String activeThemeName = currentTheme != null ? currentTheme.getName() : "unknown";

        // The name column is only padded when something follows it. Padding every row to a fixed
        // width wrote trailing spaces to the end of each line, invisible in the terminal and carried
        // along by a copy-paste.
        int nameWidth = 0;
        for (String name : themeNames) {
            nameWidth = Math.max(nameWidth, name.length());
        }

        // ONE message, not one per row. printInfo marks the first line and indents the rest, so a
        // listing rendered as a block reads as a block; a call per row put an information glyph in
        // front of every theme name, as though each were a separate notice.
        StringBuilder listing = new StringBuilder("Found ").append(themeNames.size())
                .append(themeNames.size() == 1 ? " theme:" : " themes:");
        for (String name : themeNames) {
            boolean active      = name.equals(activeThemeName);
            // Through the glyph set, so a terminal that cannot render the star gets "*" rather than
            // the replacement character.
            String  marker      = active ? GLYPHS.star() + " active" : "";
            String  description = descriptions.get(name);

            String row = verbose
                    ? "  " + pad(name, nameWidth) + "  " + description + (active ? "  " + marker : "")
                    : "  " + (active ? pad(name, nameWidth) + "  " + marker : name);
            listing.append('\n').append(row.stripTrailing());
        }
        OutputFormatter.printInfo(listing.toString());

        OutputFormatter.println();
        OutputFormatter.printInfo("Usage:\n"
                + "  cadet theme set <name>     - Apply a theme\n"
                + "  cadet theme current        - Show current theme info");

        return 0;
    }

    /** Left-justifies {@code text} in a {@code width}-wide field. */
    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    /**
     * Set the active TUI theme with validation and persistence
     */
    private int setTheme() {
        if (themeName == null || themeName.isEmpty()) {
            OutputFormatter.printError("Theme name is required");
            OutputFormatter.printInfo("Available themes: " + String.join(", ", TuiThemeManager.getAvailableThemes()));
            return 1;
        }

        // Validate theme exists
        if (!TuiThemeManager.getAvailableThemes().contains(themeName)) {
            OutputFormatter.printError("Theme '" + themeName + "' not found");
            OutputFormatter.printInfo("Available themes: " + String.join(", ", TuiThemeManager.getAvailableThemes()));
            return 1;
        }

        // Apply the theme to TUI theme manager
        boolean success = TuiThemeManager.setTheme(themeName);
        if (!success) {
            OutputFormatter.printError("Failed to apply theme '" + themeName + "'");
            return 1;
        }

        // Always apply the theme immediately to both systems for immediate effect
        try {
            // Apply to legacy ColorThemeManager for console compatibility 
            if (ColorThemeManager.getAvailableThemes().contains(themeName)) {
                ColorThemeManager.setTheme(themeName);
            }
            
            // CRITICAL: Temporarily update the config so that when Main.java calls
            // ThemedOutputFormatter.initializeTheme() it picks up our theme change
            if (!save) {
                // For temporary theme changes, update the config in memory only
                Configuration.UiConfig uiConfig = ConfigManager.getInstance().getConfig().getUi();
                uiConfig.setColorTheme(themeName);
                // Don't save to disk - this is in-memory only for this session
            }
            
            // Apply theme immediately
            applyThemeImmediately(themeName);
            
        } catch (Exception e) {
            OutputFormatter.printWarning("Could not sync with console theme system: " + e.getMessage());
        }

        OutputFormatter.printSuccess("Applied theme: " + themeName);

        // Show theme information
        TuiTheme theme = TuiThemeManager.getTheme(themeName);
        if (theme != null) {
            OutputFormatter.printInfo("Description: " + theme.getDescription());
        }

        // Handle persistence
        if (save) {
            if (saveThemeToConfig(themeName)) {
                OutputFormatter.printSuccess("Theme saved to configuration");
            } else {
                OutputFormatter.printWarning("Theme applied but not saved to configuration");
            }
        } else {
            OutputFormatter.printInfo("Theme applied for this session only");
            OutputFormatter.printInfo("Use --save flag to persist this theme selection");
        }

        // Show sample output
        if (verbose) {
            showThemeSample();
        }

        return 0;
    }

    /**
     * Preview one theme (when a name is supplied) or all available themes by
     * applying each for the session and rendering a sample of themed output.
     */
    private int previewTheme() {
        // Capture the active console theme so 'preview' is read-only: applyThemeImmediately()
        // mutates the global ColorThemeManager state, so we must restore the prior theme in a
        // finally block once the preview rendering completes.
        ColorTheme priorTheme = ColorThemeManager.getCurrentTheme();
        String priorThemeName = priorTheme != null ? priorTheme.getName() : null;

        if (themeName == null || themeName.isEmpty()) {
            OutputFormatter.printHeader("TUI Theme Previews");

            List<String> themeNames = new ArrayList<>(TuiThemeManager.getAvailableThemes());
            Collections.sort(themeNames);

            try {
                for (String name : themeNames) {
                    OutputFormatter.printSubheader("Theme: " + name);
                    applyThemeImmediately(name);
                    showThemeSample();
                }
            } finally {
                restorePreviewTheme(priorThemeName);
            }

            return 0;
        }

        // Validate theme exists before previewing
        if (!TuiThemeManager.getAvailableThemes().contains(themeName)) {
            OutputFormatter.printError("Theme '" + themeName + "' not found");
            OutputFormatter.printInfo("Available themes: " + String.join(", ", TuiThemeManager.getAvailableThemes()));
            return 1;
        }

        OutputFormatter.printHeader("Theme Preview: " + themeName);

        TuiTheme theme = TuiThemeManager.getTheme(themeName);
        if (theme != null) {
            OutputFormatter.printInfo("Description: " + theme.getDescription());
        }

        try {
            applyThemeImmediately(themeName);
            showThemeSample();
        } finally {
            restorePreviewTheme(priorThemeName);
        }

        return 0;
    }

    /**
     * Restore the active console theme captured before a preview so previewing does not
     * permanently mutate global theme state. Clears the themed-output color cache on restore.
     */
    private void restorePreviewTheme(String priorThemeName) {
        if (priorThemeName != null && ColorThemeManager.getAvailableThemes().contains(priorThemeName)) {
            ColorThemeManager.setTheme(priorThemeName);
        }
        ThemedOutputFormatter.clearColorCache();
    }

    /**
     * Show current theme information and sample output
     */
    private int showCurrentTheme() {
        TuiTheme current = TuiThemeManager.getCurrentTheme();
        
        OutputFormatter.printHeader("Current TUI Theme");
        
        if (current == null) {
            OutputFormatter.printWarning("No theme currently active");
            return 1;
        }

        OutputFormatter.printInfo("Name: " + current.getName());
        OutputFormatter.printInfo("Description: " + current.getDescription());
        
        // Show configuration status
        try {
            Configuration.UiConfig uiConfig = ConfigManager.getInstance().getConfig().getUi();
            String configTheme = uiConfig.getColorTheme();
            boolean isPersisted = current.getName().equals(configTheme);
            
            OutputFormatter.printInfo("Persisted: " + (isPersisted ? "Yes" : "No"));
            if (!isPersisted && configTheme != null) {
                OutputFormatter.printInfo("Config theme: " + configTheme);
            }
        } catch (Exception e) {
            OutputFormatter.printWarning("Could not read theme configuration");
        }

        // Show sample output
        OutputFormatter.printSubheader("Theme Sample");
        showThemeSample();

        return 0;
    }

    /**
     * Validate all themes for TUI compatibility
     */
    private int validateThemes() {
        OutputFormatter.printHeader("TUI Theme Validation");
        
        List<String> issues = TuiThemeManager.validateThemes();
        
        if (issues.isEmpty()) {
            OutputFormatter.printSuccess("All " + TuiThemeManager.getAvailableThemes().size() + 
                " themes are properly configured for TUI usage");
            
            if (verbose) {
                OutputFormatter.printInfo("Validated themes: " + 
                    String.join(", ", TuiThemeManager.getAvailableThemes()));
                
                TuiTheme current = TuiThemeManager.getCurrentTheme();
                if (current != null) {
                    OutputFormatter.printInfo("Current theme: " + current.getName());
                }
            }
            
            return 0;
        } else {
            OutputFormatter.printWarning("Found " + issues.size() + " theme validation issues:");
            for (String issue : issues) {
                OutputFormatter.printError("  • " + issue);
            }
            return 1;
        }
    }

    /**
     * Reset to the default theme.
     */
    private int resetToDefault() {
        String defaultTheme = TuiThemeManager.DEFAULT_THEME;
        
        OutputFormatter.printInfo("Resetting to default theme: " + defaultTheme);
        
        boolean success = TuiThemeManager.setTheme(defaultTheme);
        if (!success) {
            OutputFormatter.printError("Failed to reset to default theme");
            return 1;
        }

        // Sync console/in-memory theme systems so reset is fully applied for the
        // session, mirroring the set/apply path behavior.
        if (!save) {
            // For session-only reset, update the config in memory only
            Configuration.UiConfig uiConfig = ConfigManager.getInstance().getConfig().getUi();
            uiConfig.setColorTheme(defaultTheme);
        }
        applyThemeImmediately(defaultTheme);

        boolean saved = save && saveThemeToConfig(defaultTheme);
        if (save && !saved) {
            OutputFormatter.printWarning("Theme reset but not saved to configuration");
        } else {
            OutputFormatter.printSuccess("Reset to default theme: " + defaultTheme);
        }
        return 0;
    }

    /**
     * Show a sample of themed output
     */
    private void showThemeSample() {
        UnifiedOutput.println();
        ThemedOutputFormatter.printSuccess("Theme successfully applied");
        ThemedOutputFormatter.printWarning("Configuration may need adjustment");
        ThemedOutputFormatter.printError("Sample error message");
        ThemedOutputFormatter.printInfo("Additional information available");
        ThemedOutputFormatter.printPath("/project/src/main.java");
        ThemedOutputFormatter.printString("\"Hello, TUI World!\"");
        UnifiedOutput.println();
    }

    /**
     * Apply theme immediately for session-only changes
     */
    private void applyThemeImmediately(String themeName) {
        try {
            // CRITICAL: Force ColorThemeManager to use the new theme
            // This is what ThemedOutputFormatter uses for console output coloring
            if (ColorThemeManager.getAvailableThemes().contains(themeName)) {
                ColorThemeManager.setTheme(themeName);
                
                // Clear any cached color settings AFTER setting the theme
                ThemedOutputFormatter.clearColorCache();
                
                // DON'T call ThemedOutputFormatter.initializeTheme() here as it 
                // reloads from config and overrides our theme change!
                // The theme is already set in ColorThemeManager and cache is cleared
                
            } else {
                OutputFormatter.printWarning("Theme '" + themeName + "' not available in ColorThemeManager");
            }
            
        } catch (Exception e) {
            OutputFormatter.printWarning("Could not apply theme immediately: " + e.getMessage());
        }
    }

    /**
     * Writes the theme choice to the configuration file.
     *
     * <p>Returns what actually happened. It used to discard {@code saveConfig()}'s result and
     * return {@code true} unconditionally, which made both callers' failure branches -- "Theme
     * applied but not saved to configuration" and "Theme reset but not saved to configuration" --
     * unreachable for the I/O failure they were written for.</p>
     *
     * @param themeName the theme to record
     * @return whether the configuration file was written
     */
    private boolean saveThemeToConfig(String themeName) {
        try {
            Configuration.UiConfig uiConfig = ConfigManager.getInstance().getConfig().getUi();
            uiConfig.setColorTheme(themeName);
            boolean saved = ConfigManager.getInstance().saveConfig();

            // The session gets the theme either way: the colours are already on screen, and
            // withholding them because the file could not be written helps nobody. What the write
            // decides is what the caller may say about the next run.
            ThemedOutputFormatter.clearColorCache();
            ThemedOutputFormatter.initializeTheme();

            return saved;
        } catch (Exception e) {
            OutputFormatter.printError("Failed to save theme configuration: " + e.getMessage());
            return false;
        }
    }

    /**
     * Show command usage information
     */
    private void showUsage() {
        OutputFormatter.printInfo("Usage: cadet theme <action> [theme-name] [options]");
        OutputFormatter.printInfo("");
        OutputFormatter.printInfo("Actions:");
        OutputFormatter.printInfo("  list                    - List all available themes");
        OutputFormatter.printInfo("  set <name>             - Apply a specific theme");
        OutputFormatter.printInfo("  preview [name]         - Preview theme(s)");
        OutputFormatter.printInfo("  current                - Show current theme info");
        OutputFormatter.printInfo("  validate               - Validate theme configurations");
        OutputFormatter.printInfo("  reset                  - Reset to default theme");
        OutputFormatter.printInfo("");
        OutputFormatter.printInfo("Options:");
        OutputFormatter.printInfo("  --save, -s             - Save theme to configuration");
        OutputFormatter.printInfo("  --verbose, -v          - Show detailed information");
    }


    @Override
    public String getUsage() {
        return "theme <list|set|preview|current|validate|reset> [theme-name] [-s|--save] [-v|--verbose]\n"
             + "  Aliases: ls=list, apply=set, info=current, check=validate";
    }
}