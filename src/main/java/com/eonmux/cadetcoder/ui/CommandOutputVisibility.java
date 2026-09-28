package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

/**
 * Whether the console shows the full output of commands the AI runs, or just a record that they ran.
 *
 * <h2>Why hidden by default</h2>
 *
 * <p>An agentic turn can run a dozen commands, and each one's raw output was printed in full. A
 * single {@code read} of a large file, or a {@code grep} with many hits, buried the reasoning, the
 * actions and the result under thousands of lines the user did not ask to see. What a user actually
 * wants while watching an agent work is <em>what it is doing and in what order</em>; the output
 * matters when something goes wrong, and then it should be available on demand rather than always.</p>
 *
 * <p>This is a <b>console</b> setting only. Hiding output never changes what the model receives: the
 * captured text is still fed into the next prompt, and still written to the debug and session logs.
 * A hidden command is displayed, executed and recorded exactly as before -- only the echo to the
 * terminal is replaced by a one-line summary.</p>
 *
 * <h2>Hidden is not gone</h2>
 *
 * <p>In the shell the text is still sent, between the markers {@link CollapsedOutput} defines, and
 * clicking a result or focusing it with {@code Tab} shows all of it. The run's bookkeeping goes the
 * same way -- the iteration line, and the record of a step that worked -- so what stays on screen
 * is what the run is doing. On a plain console, where there is nothing to click, the head of the
 * output is kept and the rest is counted. Setting this to {@code true} prints everything inline in
 * both places.</p>
 *
 * <h2>Turning it on</h2>
 *
 * <ul>
 *   <li>For one run: {@code -Dcadet.showCommandOutput=true}</li>
 *   <li>Persistently: {@code cadet config ui.showCommandOutput true}</li>
 * </ul>
 *
 * <p>The system property wins when set, so a single run can override the saved preference either
 * way. It has to be spelled {@code true} or {@code false}; anything else is not an answer to the
 * question and the configured preference stands.</p>
 */
public final class CommandOutputVisibility {

    /** System property that overrides the configured preference for one run. */
    public static final String PROPERTY = "cadet.showCommandOutput";

    private CommandOutputVisibility() {
    }

    /**
     * @return {@code true} when a command's full output should be echoed to the console
     */
    public static boolean isVisible() {
        Boolean override = asBoolean(System.getProperty(PROPERTY));
        if (override != null) {
            return override;
        }
        try {
            Configuration.UiConfig ui = ConfigManager.getInstance().getConfig().getUi();
            return ui != null && ui.isShowCommandOutput();
        } catch (RuntimeException e) {
            // A missing or half-initialised configuration must not decide policy by crashing.
            return false;
        }
    }

    /**
     * Reads the override, refusing anything that is not one of the two answers.
     *
     * <h2>Why {@code Boolean.parseBoolean} was the wrong reader</h2>
     *
     * <p>It maps everything that is not "true" to false, so {@code -Dcadet.showCommandOutput=yes}
     * turned output OFF -- the opposite of what was asked for, on a run somebody was explicitly
     * configuring. {@code ConfigOverrides.asBoolean} refuses exactly this input for exactly this
     * reason, and the same setting must not mean one thing typed at the prompt and another handed
     * to the JVM.</p>
     *
     * <p>It is refused rather than raised: this is read on every rendering path, so a value nobody
     * can act on before the next command must not end the run. A value that says nothing leaves
     * the configured preference in charge, which is where it would have been had the property not
     * been set at all.</p>
     *
     * @param value the property as it was given; {@code null}, blank and unrecognised all mean
     *              "not an answer"
     * @return what it says, or {@code null} when it says nothing
     */
    private static Boolean asBoolean(String value) {
        if (value == null) {
            return null;
        }
        String said = value.trim();
        if ("true".equalsIgnoreCase(said)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(said)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Renders the one-line record shown in place of hidden output.
     *
     * <p>Carries the four things needed to follow an agent without reading its output: the position
     * in the sequence, what ran, whether it worked, and how much output there was -- the last so a
     * suspiciously empty or enormous result is still visible as a fact.</p>
     *
     * @param order     1-based position of this command in the run
     * @param command   the command name
     * @param arguments its arguments, already rendered
     * @param succeeded whether it reported success
     * @param output    the captured output, used only for its size (may be {@code null})
     * @return the summary line
     */
    public static String summarize(int order, String command, String arguments,
                                   boolean succeeded, String output) {
        StringBuilder line = new StringBuilder();
        line.append('[').append(order).append("] ");
        line.append(describe(command, arguments));
        line.append(succeeded ? "  ok" : "  FAILED");
        line.append("  (").append(describeSize(output)).append(')');
        return line.toString();
    }

    /**
     * Renders the outcome record for a command that was announced immediately above it.
     *
     * <p>Hidden with the output it reports on when the step worked, and left on screen when it did
     * not; see {@link CollapsedOutput#hiding}.</p>
     *
     * <p>The same four facts as {@link #summarize}, minus the command name and arguments: with the
     * announcement two lines up, repeating a seventy-character invocation says nothing new. When a
     * command's full output stands between the announcement and its outcome -- that is, when output
     * is visible -- use {@link #summarize} instead, so the record is still self-contained.</p>
     *
     * @param order     1-based position of this command in the run
     * @param succeeded whether it reported success
     * @param output    the captured output, used only for its size (may be {@code null})
     * @return the outcome line
     */
    public static String summarizeOutcome(int order, boolean succeeded, String output) {
        return "[" + order + "] " + (succeeded ? "ok" : "FAILED")
               + "  (" + describeSize(output) + ')';
    }

    /**
     * Renders {@code command args} as one line, with every argument.
     *
     * <p>Whitespace inside the arguments is collapsed, so an argument containing a newline cannot
     * break the line it is placed on. Nothing is cut: the line is the record of what the model ran,
     * and a command cut at seventy characters hid the flags that told one run from the next. The
     * console wraps a long line, and the one-row bars fit it to their own width.</p>
     *
     * @param command   the command name
     * @param arguments its arguments, already joined (may be {@code null} or blank)
     * @return e.g. {@code "read src/Foo.java"}
     */
    public static String describe(String command, String arguments) {
        String name = command == null || command.isBlank() ? "?" : command.trim();
        String args = compactArguments(arguments);
        return args.isEmpty() ? name : name + " " + args;
    }

    /** Collapses whitespace in an argument string onto one line. */
    static String compactArguments(String arguments) {
        return arguments == null ? "" : arguments.strip().replaceAll("\\s+", " ");
    }

    /** Describes how much output a command produced, so an empty or huge result is still evident. */
    static String describeSize(String output) {
        if (output == null || output.isEmpty()) {
            return "no output";
        }
        // Drop ONE trailing newline (the terminator of the last line, not a line of its own) and then
        // count. Counting separators without this reported a lone "\n" as one line of output.
        String body = output.endsWith("\n") ? output.substring(0, output.length() - 1) : output;
        if (body.isEmpty()) {
            return "no output";
        }
        int lines = 1;
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines + (lines == 1 ? " line" : " lines");
    }
}
