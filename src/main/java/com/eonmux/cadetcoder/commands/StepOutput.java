package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ui.CollapsedOutput;
import com.eonmux.cadetcoder.ui.CommandOutputVisibility;

import java.util.regex.Pattern;

/**
 * What a step's output looks like on the console and in the transcript.
 *
 * <h2>Why the two shapes are decided in one place</h2>
 *
 * <p>A step builds one string for the model and it is then shown to a person and recorded for the
 * next turn. The three uses want different things from it -- the model wants all of it, the console
 * wants the diagnostic without the payload, the transcript wants nothing the next prompt already
 * carries -- and every one of them is a decision about the same text. Spread across the run loop
 * they drifted: the same command output reached the console twice, the prompt twice, and the final
 * answer three times.</p>
 */
final class StepOutput {

    /** Longest answer still folded into the success line itself rather than printed beneath it. */
    private static final int INLINE_ANSWER_CHARS = 100;

    /** Longest output still printed in full when command output is hidden. */
    private static final int HIDDEN_OUTPUT_HEAD_LINES = 4;

    /** A {@code SUCCESS:} protocol marker at the start of a line, with any spacing after it. */
    private static final Pattern SUCCESS_MARKER_LINE = Pattern.compile("(?m)^SUCCESS:[ \\t]*");

    private StepOutput() {
    }

    /**
     * Removes the {@code SUCCESS:} protocol marker from a completing step's output.
     *
     * <p>The marker is <em>deleted where it appears</em>, at the start of a line; nothing around it
     * is discarded. This branch is the only place a completing step reaches the console, so cutting
     * the text at the marker silently threw away whatever the model wrote before it -- and a model
     * asked to end with a {@code SUCCESS:} summary routinely writes its actual answer first. The
     * same cut could also land inside a fenced block and emit an unbalanced fence into a shell that
     * renders results as Markdown.</p>
     *
     * <p>A {@code SUCCESS:} in the middle of a line is left alone: it is prose, not the marker.</p>
     *
     * @param output the completing step's output
     * @return the answer text with the marker removed, never {@code null}
     */
    static String extractAnswer(String output) {
        if (output == null) {
            return "";
        }
        return SUCCESS_MARKER_LINE.matcher(output.trim()).replaceAll("").trim();
    }

    /**
     * Prints the completion of a run: a success line, plus the answer when it needs its own space.
     *
     * @param answer the answer text, already stripped of any {@code SUCCESS:} lead-in
     */
    static void reportCompletion(String answer) {
        if (answer.isEmpty()) {
            OutputFormatter.printSuccess("Done");
            return;
        }
        if (!answer.contains("\n") && answer.length() <= INLINE_ANSWER_CHARS) {
            OutputFormatter.printSuccess(answer);
            return;
        }
        // A blank line between the status and the answer, and the answer set to a readable measure.
        // Run together at the full width of a wide terminal, the two read as one paragraph that
        // happened to start with the word "Done".
        OutputFormatter.printSuccess("Done");
        OutputFormatter.println("");
        OutputFormatter.printProse(answer);
    }

    /**
     * Reports a run that stopped because it was no longer making progress.
     *
     * @param guard the guard that detected it
     */
    static void reportNoProgress(IterationProgressGuard guard) {
        OutputFormatter.printError("Stopping: " + guard.stopReason() + ".");
        OutputFormatter.printInfo(
                "Nothing further was run. Re-run with a more specific request, or check the last "
                + "output above for what blocked progress.");
    }

    /**
     * Shows a step's output on the console, collapsed unless the setting says otherwise.
     *
     * <h2>What "collapsed" means where the shell is drawing the screen</h2>
     *
     * <p>Nothing of the body is printed. The text is still sent, marked for
     * {@link CollapsedOutput}, so clicking the result opens it and shows everything it holds. What
     * stays on screen is the announcement -- the command and the model's reason for running it --
     * which is the line that explains why the output exists, and the line that is clicked to read
     * it.</p>
     *
     * <h2>What it means where nothing can open it</h2>
     *
     * <p>On the plain command line, and inside a worker whose transcript is read back as text, the
     * head of the output is kept and the rest is replaced by a count. That is worse to look at and
     * the only honest option: a step reports its own diagnostics through this channel ("File not
     * found", "Did you mean one of these?"), they lead the output, and hiding them where they
     * cannot be recovered would leave a failed step explained by nothing but a number.</p>
     *
     * <p>Short output is printed as it is either way. Collapsing two lines would put a step's whole
     * account of itself behind a click.</p>
     *
     * @param output the step's full output
     */
    static void printForConsole(String output) {
        // Strip FIRST, and on every path. The preamble is boilerplate in all of them -- output
        // visible, output short enough to show whole, output collapsed -- and stripping it only on
        // one meant the commonest cases still showed it.
        String body = stripStepPreamble(output);
        if (body == null || body.isBlank()) {
            return;
        }
        if (CommandOutputVisibility.isVisible() || lineCount(body) <= HIDDEN_OUTPUT_HEAD_LINES) {
            OutputFormatter.printOutputBlock(body);
            return;
        }
        if (!CollapsedOutput.isSupported()) {
            OutputFormatter.printOutputBlock(abbreviateForConsole(output));
            return;
        }
        OutputFormatter.println(CollapsedOutput.OPEN);
        OutputFormatter.printOutputBlock(body);
        OutputFormatter.println(CollapsedOutput.CLOSE);
    }

    /**
     * A step's output cut down to its head and a count of what was left out.
     *
     * <p>For the consoles that cannot open a collapsed result. Returns the text unchanged when
     * output is visible or when there is nothing to leave out.</p>
     *
     * @param output the step's full output
     * @return the text to display
     */
    static String abbreviateForConsole(String output) {
        String body = stripStepPreamble(output);
        if (CommandOutputVisibility.isVisible()) {
            return body;
        }
        String[] lines = body.split("\n", -1);
        int      said  = lineCount(body);
        if (said <= HIDDEN_OUTPUT_HEAD_LINES) {
            return body;
        }
        StringBuilder abbreviated = new StringBuilder();
        for (int i = 0; i < HIDDEN_OUTPUT_HEAD_LINES; i++) {
            abbreviated.append(lines[i]).append('\n');
        }
        int hidden = said - HIDDEN_OUTPUT_HEAD_LINES;
        abbreviated.append("... ").append(hidden).append(hidden == 1 ? " more line" : " more lines")
                   .append(" hidden (").append(CommandUsage.prefix())
                   .append("config ui.showCommandOutput true)");
        return abbreviated.toString();
    }

    /**
     * How many lines of text a block holds.
     *
     * <p>A trailing newline terminates the last line rather than starting another: every line a
     * process writes is followed by a separator, and counting the empty field after it put every
     * count one too high.</p>
     *
     * @param body the text
     * @return the number of lines
     */
    static int lineCount(String body) {
        String[] lines = body.split("\n", -1);
        return lines.length > 0 && lines[lines.length - 1].isEmpty() ? lines.length - 1
                                                                     : lines.length;
    }

    /**
     * Removes the framing a step wraps around a command's output before displaying it.
     *
     * <p>A step's output is built for the MODEL, and opens with {@code "Command executed: <cmd>"}, a
     * blank line and {@code "Command Output:"}. On the console every one of those three lines is
     * already known -- the command was announced a moment earlier and its ordered record names it
     * again -- so they were pure boilerplate repeated once per command. The model still receives the
     * text unchanged; only what is shown is trimmed.</p>
     *
     * <h2>Why a command that said nothing comes back with nothing</h2>
     *
     * <p>An empty result used to be answered with the input unchanged, which handed back the whole
     * of the boilerplate this method exists to remove: a command that printed nothing -- a
     * {@code write} that worked, a {@code grep} with no matches -- was displayed as "Command
     * executed: ..." followed by "Command Output:" and a blank space where its output would have
     * been. That is the one case where the preamble is the entire message, so it is the one case
     * where leaving it in is least defensible. Callers already treat a blank body as nothing to
     * show, so the honest answer is the empty one.</p>
     *
     * @param output the step's output
     * @return the output with the preamble removed, or unchanged when it has none
     */
    static String stripStepPreamble(String output) {
        if (output == null) {
            return "";
        }
        String remaining = output;
        boolean stripped = false;
        if (remaining.startsWith("Command executed: ")) {
            int newline = remaining.indexOf('\n');
            remaining = newline < 0 ? "" : remaining.substring(newline + 1);
            stripped  = true;
        }
        remaining = remaining.stripLeading();
        if (remaining.startsWith("Command Output:")) {
            int newline = remaining.indexOf('\n');
            remaining = newline < 0 ? "" : remaining.substring(newline + 1);
            stripped  = true;
        }
        // Whether a preamble was there decides this, not whether anything survived it: an output
        // that is empty because it was ALL preamble has been stripped correctly, while one that is
        // empty because the step said nothing at all has nothing to give back either.
        return stripped ? remaining : output;
    }

    /**
     * Drops a step's output from the transcript entry when the next prompt already carries it.
     *
     * <p>Deduplication, not truncation: the test is whether the following entry provably contains
     * this one's body, so the information in the prompt is unchanged and only the second copy goes.
     * Because it removes text that has not been sent yet rather than rewriting text that has, the
     * prompt stays append-only and the provider-side cache is untouched.</p>
     *
     * @param output     the step's output
     * @param nextPrompt the prompt that will be appended immediately after it
     * @return the descriptor alone when the body is already carried, otherwise {@code output}
     */
    static String condenseForTranscript(String output, String nextPrompt) {
        if (output == null || output.isEmpty() || nextPrompt == null) {
            return output == null ? "" : output;
        }
        String body = stripStepPreamble(output);
        // stripStepPreamble returns the input unchanged when there was no preamble to strip; there
        // is no descriptor to keep in that case, so the entry stays as it is.
        if (body.equals(output) || body.isBlank() || !nextPrompt.contains(body)) {
            return output;
        }
        int firstLine = output.indexOf('\n');
        return firstLine < 0 ? output : output.substring(0, firstLine);
    }
}
