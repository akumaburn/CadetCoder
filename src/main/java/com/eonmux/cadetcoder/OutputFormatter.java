package com.eonmux.cadetcoder;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.ui.ThemedOutputFormatter;

import java.util.List;

/**
 * What the rest of the tool prints through.
 *
 * <h2>Why it only forwards</h2>
 *
 * <p>Every call here goes to {@link ThemedOutputFormatter}, which owns the theme, the glyphs, the
 * colour cache and the routing to a terminal or to the shell's transcript. This class used to carry
 * a second copy of some of that -- its own colour cache, its own logger, and an
 * {@code isColorEnabled} that walked the current stack trace looking for {@link ConfigManager} to
 * decide whether it was inside configuration loading. Nothing called any of it. A private cache that
 * no printing consults is not a cache, and a colour decision taken in two places is a decision that
 * will eventually be taken two ways.</p>
 */
public class OutputFormatter {

    /**
     * Drops the cached "is colour enabled" answer so the next line re-reads the configuration.
     *
     * <p>The live cache belongs to {@link ThemedOutputFormatter}, which is where every {@code printX}
     * here actually goes. This used to clear only a field of this class's own, which nothing read, so
     * a caller changing the colour setting and calling this to apply it saw no effect at all.</p>
     */
    public static void clearColorCache() {
        ThemedOutputFormatter.clearColorCache();
    }

    public static void printHeader(String text) {
        ThemedOutputFormatter.printHeader(text);
    }

    /** Opens a new iteration of an agentic run; see {@link ThemedOutputFormatter#printIteration}. */
    public static void printIteration(String text) {
        ThemedOutputFormatter.printIteration(text);
    }

    public static void printSubheader(String text) {
        ThemedOutputFormatter.printSubheader(text);
    }

    public static void printSuccess(String text) {
        ThemedOutputFormatter.printSuccess(text);
    }

    public static void printWarning(String text) {
        ThemedOutputFormatter.printWarning(text);
    }

    public static void printError(String text) {
        ThemedOutputFormatter.printError(text);
    }

    public static void printCodeBlock(String code) {
        ThemedOutputFormatter.printCodeBlock(code);
    }

    /** Text a command produced: marked as information, never suppressed, never fenced. */
    public static void printOutputBlock(String text) {
        ThemedOutputFormatter.printOutputBlock(text);
    }

    public static void printTable(List<String[]> rows, boolean hasHeader) {
        ThemedOutputFormatter.printTable(rows, hasHeader);
    }

    public static void printInfo(String snippet) {
        ThemedOutputFormatter.printInfo(snippet);
    }

    public static void disableColor() {
        Configuration.UiConfig uiConfig = ConfigManager.getInstance().getConfig().getUi();
        uiConfig.setColorEnabled(false);
        ThemedOutputFormatter.clearColorCache();
    }

    public static void enableVerbose() {
        Configuration.UiConfig uiConfig = ConfigManager.getInstance().getConfig().getUi();
        uiConfig.setVerbosityLevel(Configuration.UiConfig.VERBOSITY.VERBOSE.ordinal());
    }

    /** Prints a line with no marker, colour or wrapping: content the caller has already shaped. */
    public static void println(String text) {
        ThemedOutputFormatter.routeOutput(text);
    }

    /** Prints prose at a readable measure; see {@link ThemedOutputFormatter#printProse}. */
    public static void printProse(String text) {
        ThemedOutputFormatter.printProse(text);
    }

    /** Prints without a trailing newline. */
    public static void print(String text) {
        com.eonmux.cadetcoder.ui.UnifiedOutput.print(text);
    }

    /** Prints an empty line. */
    public static void println() {
        com.eonmux.cadetcoder.ui.UnifiedOutput.println();
    }

    /** Prints {@code String.format}-style. */
    public static void printf(String format, Object... args) {
        com.eonmux.cadetcoder.ui.UnifiedOutput.printf(format, args);
    }
}
