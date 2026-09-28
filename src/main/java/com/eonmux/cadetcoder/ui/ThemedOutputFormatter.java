package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.logging.CadetLogger;
import com.eonmux.cadetcoder.security.SecretRedactor;

import java.util.List;

/**
 * Enhanced output formatter that uses color themes.
 * This class extends the functionality of OutputFormatter to support themes.
 */
public class ThemedOutputFormatter {
    private static CadetLogger logger            = null;
    private static Boolean     colorEnabledCache = null;

    public static void clearColorCache() {
        colorEnabledCache = null;
    }

    /**
     * Initialize theme from configuration
     */
    public static void initializeTheme() {
        try {
            Configuration.UiConfig uiConfig  = ConfigManager.getInstance().getConfig().getUi();
            String                 themeName = uiConfig.getColorTheme();
            if (themeName != null && !themeName.isEmpty()) {
                // Set theme in the TUI theme manager
                boolean success = TuiThemeManager.setTheme(themeName);
                if (success) {
                    // Also ensure old theme manager is synchronized for any legacy code
                    if (ColorThemeManager.getAvailableThemes().contains(themeName)) {
                        ColorThemeManager.setTheme(themeName);
                    }
                } else {
                    // Fallback to old theme manager if theme not found in new system
                    ColorThemeManager.setTheme(themeName);
                }
            }
        } catch (Exception e) {
            // Use default theme if config not available
        }
    }

    /**
     * The glyph set the console marks lines with.
     *
     * <p>Resolved once from the environment. On a Unicode terminal the markers are single-column
     * glyphs, which is what lets every kind of line start its message in the same column; on a
     * terminal that cannot render them they fall back to the bracketed words this console used
     * before ({@code [OK]}, {@code [WARN]}, {@code [ERR]}, {@code === … ===}, {@code -- … --}), so
     * nothing turns into mojibake and {@link OutputLineStyler} still classifies the line.</p>
     */
    private static final Glyphs GLYPHS = Glyphs.system();

    /**
     * The character a table's header separator is drawn with.
     *
     * <p>Deliberately not {@code Glyphs.rule()}: that falls back to {@code -} on an ASCII terminal,
     * which is Markdown's thematic break. Both forms here are in {@code OutputLineStyler}'s rule
     * character set instead, so the separator classifies the same way whichever terminal drew it.</p>
     */
    private static final String TABLE_RULE = GLYPHS.isUnicode() ? "\u2500" : "=";

    public static void printHeader(String text) {
        routeOutput(marked(GLYPHS.headerMarker(), text, GLYPHS.headerCloser(),
                           ColorTheme.ColorType.HEADER));
    }

    /**
     * Renders one marked console line (or block).
     *
     * <p>A message is not always a single line -- {@code /theme list} prints its whole listing
     * through one {@code printInfo} call, and a model's answer arrives as a paragraph. Prefixing
     * only the first physical line left every following line hanging in column 0, unmarked and
     * unclassifiable. Each continuation line is therefore indented to the column where the first
     * line's text began, so the block reads as one message and {@code OutputLineStyler} sees a
     * consistent shape.</p>
     *
     * @param marker the opening marker glyph (or bracketed word)
     * @param text   the message, which may contain newlines
     * @param closer the closing delimiter, empty unless the ASCII fallback needs one
     * @param type   the colour role
     * @return the rendered, colourised block
     */
    private static String marked(String marker, String text, String closer,
                                 ColorTheme.ColorType type) {
        // Masked here, at the one point every marked line is composed.
        //
        // Every file sink was taught to mask secrets and the console was not, so the one place a
        // key is most likely to appear -- a provider's own 401 body, quoted back in the error that
        // reports it -- was printed in full, and under the interactive shell went on into the
        // transcript that is saved to session.json. A message worth showing a person is worth
        // showing them without their credential in it.
        String body = text == null ? "" : SecretRedactor.redact(text);

        // Nothing to mark. A blank message is how callers ask for a blank line between two blocks,
        // and marking it produced a line holding a marker, a space and nothing else -- a stray
        // bullet in the output and a line of trailing whitespace in anything that copies it.
        if (body.isEmpty()) {
            return "";
        }

        // An empty marker is the unmarked form: no opening glyph, and therefore no indent to line
        // continuation lines up under. See printInfo for why a notice is printed that way.
        String   opening = marker.isEmpty() ? "" : marker + " ";
        String   indent  = " ".repeat(opening.length());
        String[] lines   = body.split("\n", -1);

        // The closer belongs to the FIRST line, not the block. A header is a single line by
        // convention, but nothing enforces that, and closing the block on its last line rendered a
        // two-line ASCII header as "=== line one" / "    line two ===" -- delimiters that no longer
        // wrap anything and that OutputLineStyler would not classify.
        // A message that ends in a newline has an empty final element after the split. Emitting it
        // would append a line consisting of nothing but the continuation indent -- invisible in the
        // terminal, but a line of trailing whitespace in anything that copies or logs the output.
        int last = lines.length;
        while (last > 1 && lines[last - 1].isEmpty()) {
            last--;
        }

        // A message containing a Markdown fence gets the marker on EVERY line instead of an indent.
        //
        // Indenting is the better look, but it is unsound here. The marker on the first line hides a
        // fence that opens the message, while the fence that CLOSES it survives the indent and is
        // then read as an opening fence -- so everything printed afterwards, including other
        // commands' output, is swallowed into a code block. Marking every line makes all of them
        // inert (a fence must start its line to count), which cannot unbalance anything.
        boolean fenced       = containsFence(lines, last);
        String  continuation = fenced && !opening.isEmpty() ? opening : indent;

        // Soft wrapping is off for a fenced block (its content is code, and a break inside a line of
        // it is a change to the code) and for a closed one (the ASCII header's trailing delimiter
        // belongs at the end of the text it encloses, which a wrap would move into the middle of the
        // message). Everything else is prose, and prose that runs past the edge is wrapped by the
        // terminal wherever the edge falls -- mid-word, and back at column zero.
        int width = fenced || !closer.isEmpty() ? TerminalWidth.UNKNOWN
                                                : ProseMeasure.fit(TerminalWidth.columns());

        StringBuilder out = new StringBuilder();
        appendWrapped(out, opening, lines[0] + closer, width);
        for (int i = 1; i < last; i++) {
            out.append('\n');
            appendWrapped(out, continuation, lines[i], width);
        }
        return colorize(out.toString(), type);
    }

    /**
     * Appends one logical line under {@code prefix}, broken to {@code width} if there is one.
     *
     * <p>The prefix is written once and its width is reserved on every piece, so a wrapped message
     * lines up under the first character of its own text rather than under its marker.</p>
     */
    private static void appendWrapped(StringBuilder out, String prefix, String line, int width) {
        List<String> pieces = ProseWrap.wrap(line, width - prefix.length());
        out.append(prefix).append(pieces.get(0));
        String hang = " ".repeat(prefix.length());
        for (int at = 1; at < pieces.size(); at++) {
            out.append('\n').append(hang).append(pieces.get(at));
        }
    }

    /** Whether a message holds a Markdown code fence on any of its lines. */
    private static boolean carriesFence(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String[] lines = text.split("\n", -1);
        return containsFence(lines, lines.length);
    }

    /** Whether any of the first {@code count} lines opens or closes a Markdown code fence. */
    private static boolean containsFence(String[] lines, int count) {
        for (int i = 0; i < count; i++) {
            String stripped = lines[i].stripLeading();
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Apply theme color to text
     * Note: in immediate-mode TUI rendering the theme is applied per-frame,
     * not via string colorization. For console output, we fall back to ColorThemeManager.
     */
    private static String colorize(String text, ColorTheme.ColorType type) {
        if (!isColorEnabled() || TuiMode.isActive()) {
            return text;
        }
        
        // Use the existing ColorThemeManager for console string colorization
        // TuiThemeManager handles TUI component colors directly
        ColorTheme theme = ColorThemeManager.getCurrentTheme();
        if (theme != null) {
            return theme.colorize(text, type);
        }
        
        return text;
    }
    

    /**
     * Route output to appropriate destination (TUI or console)
     */
    public static void routeOutput(String text) {
        // Ensure clean text for TUI mode
        String cleanText = TuiMode.isActive() ? stripAnsiCodes(text) : text;
        // Use UnifiedOutput for automatic routing
        UnifiedOutput.println(cleanText);
    }

    /**
     * Whether output may carry colour.
     *
     * <p>Remembered once the answer is settled, and only then. While the configuration is still
     * being read the answer comes from the defaults in hand, because those are what is in force at
     * that moment -- but it is not kept, or the whole run would be coloured by a file nobody had
     * read yet.</p>
     */
    private static boolean isColorEnabled() {
        if (colorEnabledCache != null) {
            return colorEnabledCache;
        }

        try {
            boolean enabled = ConfigManager.getInstance().getConfig().getUi().isColorEnabled();
            if (ConfigManager.isSettled()) {
                colorEnabledCache = enabled;
            }
            return enabled;
        } catch (Exception e) {
            // Default to true if config is not available
            return true;
        }
    }

    /**
     * Opens a new iteration of an agentic run.
     *
     * <p>Rendered as a sub-header, so the shell opens a navigable section for it exactly as it does
     * for a sub-step, but marked distinctly so a turn boundary is visible while scrolling a long
     * run.</p>
     *
     * <h2>Why it is preceded by a blank line</h2>
     *
     * <p>The marker alone did not separate one turn from the next. A run printed its telemetry, its
     * steps and their output as one unbroken column, and the turn boundary this marker exists to
     * show was the least visible thing on the screen. The blank line is what does the separating;
     * the marker says what the separation is.</p>
     *
     * @param text the turn's label, e.g. {@code Iteration 3}
     */
    public static void printIteration(String text) {
        routeOutput("");
        routeOutput(marked(GLYPHS.iterationMarker(), text, "", ColorTheme.ColorType.HEADER));
    }

    public static void printSubheader(String text) {
        routeOutput(marked(GLYPHS.subheaderMarker(), text, GLYPHS.subheaderCloser(),
                           ColorTheme.ColorType.SUBHEADER));
    }

    public static void printSuccess(String text) {
        routeOutput(marked(GLYPHS.successMarker(), text, "", ColorTheme.ColorType.SUCCESS));
        // Log success messages to file only - the "[OK]" line above is the user-facing copy.
        try {
            if (getLogger() != null) {
                getLogger().infoToFile("SUCCESS: " + text);
            }
        } catch (Exception ignored) {
            // Avoid circular dependency issues during initialization
        }
    }

    private static CadetLogger getLogger() {
        if (logger == null) {
            logger = CadetLogger.getLogger(ThemedOutputFormatter.class);
        }
        return logger;
    }

    public static void printWarning(String text) {
        routeOutput(marked(GLYPHS.warningMarker(), text, "", ColorTheme.ColorType.WARNING));
        // Log warnings to file only - the "[WARN]" line above is the user-facing copy.
        try {
            if (getLogger() != null) {
                getLogger().warnToFile(text);
            }
        } catch (Exception ignored) {
            // Avoid circular dependency issues during initialization
        }
    }

    public static void printError(String text) {
        // Errors always go to stderr
        routeError(marked(GLYPHS.errorMarker(), text, "", ColorTheme.ColorType.ERROR));
        // Log errors to file only - the "[ERR]" line above is the user-facing copy;
        // CadetLogger.error() would print it again (SLF4J console appender plus its own
        // "✗" line), which is how one error used to surface as three lines.
        try {
            if (getLogger() != null) {
                getLogger().errorToFile(text);
            }
        } catch (Exception ignored) {
            // Avoid circular dependency issues during initialization
        }
    }

    /**
     * Renders and routes an error line WITHOUT recording it in the log file.
     *
     * <p>For callers that do their own file logging -- {@link CadetLogger} writes the message and
     * the whole stack trace itself -- so that the message is not written to the rotating log twice.
     * Everything else about the line is identical to {@link #printError}: same marker, same colour,
     * same stream, and it classifies the same way in the shell, which is the point. The loggers used
     * to hand-build their own {@code "✗ "} console line, which was neither themed nor classified,
     * and which sat next to a differently-formatted copy from SLF4J.</p>
     *
     * @param text the message
     */
    public static void printErrorLine(String text) {
        routeError(marked(GLYPHS.errorMarker(), text, "", ColorTheme.ColorType.ERROR));
    }

    /**
     * Renders and routes a warning line WITHOUT recording it in the log file.
     *
     * <p>The warning counterpart of {@link #printErrorLine}, for callers that do their own file
     * logging.</p>
     *
     * @param text the message
     */
    public static void printWarningLine(String text) {
        routeOutput(marked(GLYPHS.warningMarker(), text, "", ColorTheme.ColorType.WARNING));
    }

    /**
     * Route error output to appropriate destination (TUI or console)
     */
    private static void routeError(String text) {
        // Ensure clean text for TUI mode
        String cleanText = TuiMode.isActive() ? stripAnsiCodes(text) : text;
        // Use UnifiedOutput for automatic error routing
        UnifiedOutput.printlnErr(cleanText);
    }

    /**
     * Prints a notice.
     *
     * <h2>Why it carries no marker</h2>
     *
     * <p>Every other marker answers a question the text does not: whether a step worked, whether
     * something is wrong, what a heading opens. "This is information" answers none -- it was on the
     * majority of the lines the program printed, including whole listings and help text, where it
     * added a column of repeated glyphs down the left of output that was plainly prose already.</p>
     *
     * <p>The exception is a message carrying a Markdown fence, which keeps the marker on every
     * line. A fence that starts a line is live: an odd number of them in one message would pair
     * with the next fence printed and swallow everything between the two. A marker in front makes
     * each of them inert, and a message that brings its own code block is rare enough that the
     * glyph is not the noise this removed.</p>
     *
     * <p>In the interactive shell a line's colour comes from its marker, because the shell strips
     * the escape codes and classifies what is left. So a notice is drawn there in the ordinary text
     * colour. On a plain console it still carries the theme's info colour, which survives in the
     * escape codes.</p>
     *
     * @param text the message, which may contain newlines
     */
    public static void printInfo(String text) {
        // Only print to console if verbosity level allows it
        if (isAtLeastNormalVerbosity()) {
            String marker = carriesFence(text) ? GLYPHS.infoMarker() : "";
            routeOutput(marked(marker, text, "", ColorTheme.ColorType.INFO));
        }
        // Always log info messages to file only - the console line above, when verbosity
        // allows it, is the user-facing copy.
        try {
            if (getLogger() != null) {
                getLogger().infoToFile(text);
            }
        } catch (Exception ignored) {
            // Avoid circular dependency issues during initialization
        }
    }

    /**
     * Whether informational output is wanted, defaulting to yes when the configuration cannot be read.
     *
     * <p>Reading the verbosity level walks {@code ConfigManager.getInstance().getConfig().getUi()},
     * any link of which can be absent while the application is still coming up or under a partial
     * test double. Unguarded, that made <em>printing a line</em> able to throw into its caller: a
     * NullPointerException raised here propagated out of the code that was merely reporting which AI
     * client it had chosen, and the selection was abandoned half-done.</p>
     *
     * <p>The fallback is to print. Information is the channel commands use to say what they did, and
     * losing that silently is worse than printing a line someone had configured away.</p>
     */
    private static boolean isAtLeastNormalVerbosity() {
        try {
            Configuration config = ConfigManager.getInstance().getConfig();
            Configuration.UiConfig ui = config == null ? null : config.getUi();
            return ui == null
                   || ui.getVerbosityLevel() >= Configuration.UiConfig.VERBOSITY.NORMAL.ordinal();
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static void printCommand(String text) {
        routeOutput(colorize(text, ColorTheme.ColorType.COMMAND));
    }

    public static void printPath(String text) {
        routeOutput(colorize(text, ColorTheme.ColorType.PATH));
    }

    public static void printString(String text) {
        routeOutput(colorize(text, ColorTheme.ColorType.STRING));
    }

    public static void printAccent1(String text) {
        routeOutput(colorize(text, ColorTheme.ColorType.ACCENT1));
    }

    public static void printAccent2(String text) {
        routeOutput(colorize(text, ColorTheme.ColorType.ACCENT2));
    }

    public static void printDim(String text) {
        routeOutput(colorize(text, ColorTheme.ColorType.DIM));
    }

    public static void printBold(String text) {
        routeOutput(colorize(text, ColorTheme.ColorType.BOLD));
    }

    /**
     * Print themed code block
     */
    /**
     * Print themed code block.
     *
     * <p>Routed through {@link #routeOutput}, like every other marker. It used to call
     * {@link UnifiedOutput} directly and gate only on {@code isColorEnabled()}, so it was the one
     * output path with no TUI check -- three independent TUI detections and three independent ANSI
     * strippers had to agree for it to come out right.</p>
     */
    /**
     * Prints the tool's own prose at a readable measure, with no marker in front of it.
     *
     * <p>For a block that is the message rather than a remark about one -- a model's final answer is
     * the case this exists for. It carries no marker because there is nothing to classify it
     * against, and it is wrapped because a paragraph drawn across a wide terminal is hard to read.
     * See {@link ProseMeasure}.</p>
     *
     * <p>A block holding a Markdown fence is printed as it stands. Its content is code, and a break
     * inside a line of code is a change to the code.</p>
     *
     * @param text the prose
     */
    public static void printProse(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String[] lines = SecretRedactor.redact(text).split("\n", -1);
        if (containsFence(lines, lines.length)) {
            routeOutput(String.join("\n", lines));
            return;
        }
        int           width = ProseMeasure.fit(TerminalWidth.columns());
        StringBuilder out   = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(String.join("\n", ProseWrap.wrap(lines[i], width)));
        }
        routeOutput(out.toString());
    }

    /** Columns a nested command's output is set in from, to show what it belongs to. */
    private static final String NESTED_INDENT = "  ";

    /**
     * Prints what a command the model ran produced, indented under the step that ran it.
     *
     * <h2>Why it carries no marker of its own</h2>
     *
     * <p>This text was printed by a command, so it already carries that command's markers. Putting
     * another one in front of it produced lines that opened {@code "ℹ ▎ File: ..."} -- two markers
     * for one line -- and buried whatever structure the command had given its own output under a
     * marker that said only "this is output", which is the one thing its position on screen already
     * said. An indent says the same thing and leaves the command's headings, ticks and warnings
     * legible as themselves.</p>
     *
     * <h2>Why not {@link #printCodeBlock}</h2>
     *
     * <p>Captured console output is not source code. A bare fence renders in the interactive shell
     * as a block labelled {@code code} -- a label that says nothing, above text that is not code --
     * and the fence itself is copied along with the text.</p>
     *
     * <h2>Why not {@link #printInfo}</h2>
     *
     * <p>That one is suppressed below normal verbosity. A step's own diagnosis of what went wrong
     * leads this block, so dropping it at low verbosity leaves a failed step reported by nothing but
     * its line count.</p>
     *
     * <h2>Why it is marked as program output</h2>
     *
     * <p>The text is what a command printed, so the shell must not read it as Markdown. Where the
     * shell reads the markers of {@link ProgramOutput}, the block is sent between them.</p>
     *
     * @param text what the step or command produced
     */
    public static void printOutputBlock(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        boolean marked = ProgramOutput.isSupported();
        if (marked) {
            routeOutput(ProgramOutput.OPEN);
        }
        try {
            routeOutput(colorize(indented(text, marked), ColorTheme.ColorType.INFO));
        } finally {
            // Closed even when the printing threw, or the rest of the result would be shown as
            // program output.
            if (marked) {
                routeOutput(ProgramOutput.CLOSE);
            }
        }
    }

    /**
     * Sets a captured block in from the left margin.
     *
     * <p>Where the block is not marked as {@link ProgramOutput}, a block holding a Markdown fence
     * is marked on every line instead. An indent does not stop a fence counting -- a renderer strips
     * leading space before it looks -- so an odd number of fences in captured output would pair with
     * the next one printed and swallow everything between them. A marker at the start of the line
     * makes each of them inert. Inside program output no fence counts, so the indent is enough.</p>
     *
     * @param text   the captured output
     * @param marked whether the block is sent between the markers of {@link ProgramOutput}
     * @return the block to print, with every line prefixed
     */
    private static String indented(String text, boolean marked) {
        String[] lines = SecretRedactor.redact(text).split("\n", -1);

        // A block ending in a newline splits to an empty last element. Prefixing it would emit a
        // line of nothing but whitespace, invisible on screen and carried along by a copy.
        int last = lines.length;
        while (last > 1 && lines[last - 1].isEmpty()) {
            last--;
        }

        String        prefix = !marked && containsFence(lines, last)
                               ? GLYPHS.infoMarker() + " " : NESTED_INDENT;
        StringBuilder out    = new StringBuilder();
        for (int i = 0; i < last; i++) {
            if (i > 0) {
                out.append('\n');
            }
            // An empty line stays empty: prefixing it would add trailing whitespace and nothing else.
            out.append(lines[i].isEmpty() ? "" : prefix + lines[i]);
        }
        return out.toString();
    }

    public static void printCodeBlock(String code) {
        ColorTheme theme = ColorThemeManager.getCurrentTheme();
        if (isColorEnabled() && !TuiMode.isActive() && theme != null) {
            routeOutput(theme.getDim() + "```" + theme.getReset());
            routeOutput(theme.getAccent1() + code + theme.getReset());
            routeOutput(theme.getDim() + "```" + theme.getReset());
        } else {
            routeOutput("```");
            routeOutput(code);
            routeOutput("```");
        }
    }

    /**
     * Print themed table
     */
    public static void printTable(List<String[]> rows, boolean hasHeader) {
        if (rows.isEmpty()) {
            return;
        }

        ColorTheme theme = ColorThemeManager.getCurrentTheme();

        // Calculate column widths
        int[] colWidths = new int[rows.get(0).length];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                colWidths[i] = Math.max(colWidths[i], row[i].length());
            }
        }

        // Print rows
        for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
            String[]      row       = rows.get(rowIdx);
            StringBuilder rowOutput = new StringBuilder();

            for (int i = 0; i < row.length; i++) {
                // The LAST column is not padded: padding it wrote trailing spaces to the end of
                // every row, which a terminal shows as nothing and a copy-paste carries along.
                String cell = i == row.length - 1 ? row[i] : padRight(row[i], colWidths[i] + 2);
                if (rowIdx == 0 && hasHeader && isColorEnabled() && !TuiMode.isActive() && theme != null) {
                    rowOutput.append(theme.getBold()).append(theme.getHeader()).append(cell).append(theme.getReset());
                } else {
                    rowOutput.append(cell);
                }
            }
            routeOutput(rowOutput.toString());

            // Print header separator
            if (rowIdx == 0 && hasHeader) {
                StringBuilder separatorOutput = new StringBuilder();
                for (int i = 0; i < colWidths.length; i++) {
                    // Not a run of hyphens: a hyphen run is Markdown's thematic break AND its list
                    // bullet, so the shell's Markdown renderer replaced this line with a full-width
                    // rule of its own. Glyphs.rule() falls back to hyphens on an ASCII terminal, so
                    // the separator has its own character on both sides of that fallback.
                    int width = i == colWidths.length - 1 ? colWidths[i] : colWidths[i] + 2;
                    separatorOutput.append(TABLE_RULE.repeat(Math.max(0, width)));
                }
                String separator = separatorOutput.toString();
                if (isColorEnabled() && !TuiMode.isActive() && theme != null) {
                    separator = theme.getDim() + separator + theme.getReset();
                }
                routeOutput(separator);
            }
        }
    }

    private static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }
    
    /**
     * Strip ANSI color codes from text for TUI display
     */
    private static String stripAnsiCodes(String text) {
        if (text == null) {
            return null;
        }
        // Remove all ANSI escape sequences
        return text.replaceAll("\u001B\\[[;\\d]*[mK]", "")
                  .replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "")
                  .replaceAll("\\x1B\\[[0-9;]*[mK]", "")
                  .replaceAll("\\x1B\\[[0-9;]*[a-zA-Z]", "")
                  .replaceAll("\\e\\[[0-9;]*[mK]", "")
                  .replaceAll("\\e\\[[0-9;]*[a-zA-Z]", "");
    }
}