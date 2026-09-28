package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.commands.CommandUsage;
import com.eonmux.cadetcoder.harness.loop.RunWatch;
import com.eonmux.cadetcoder.harness.loop.ToolRequest;

import java.util.List;

/**
 * A run, as it looks to the person who started it.
 *
 * <h2>Why a quiet run still says something every turn</h2>
 *
 * <p>A run is minutes in which the terminal is otherwise still, and an agent that prints nothing is
 * indistinguishable from one that has hung on a request that will never answer. The honest response
 * to that is to kill it, which throws away the ledger's last write and everything the run had
 * established. So every reply and every tool call marks the terminal even when nothing else is
 * shown.</p>
 *
 * <h2>Why the answers are cut down</h2>
 *
 * <p>The two tools the agent uses most answer with a whole observation, which for a real workspace
 * is thousands of characters. Printed in full, a round of thinking fills the screen and buries the
 * one line that says what happened. The first line of an answer is where every tool in this system
 * puts its verdict, so that is what a quiet run shows; asking for verbose is asking for the rest.</p>
 *
 * <h2>Why the ending is not announced here</h2>
 *
 * <p>Whoever started the run reports the outcome, because the outcome is more than the result: it is
 * also where the record was kept, and the exit code the shell is about to be given. A watch that also
 * announced the ending would print half of that, immediately before the other half.</p>
 */
public final class TerminalWatch implements RunWatch {

    /** How much of a tool's answer a quiet run shows before it starts saying "and more". */
    public static final int ANSWER_SHOWN = 160;

    /** What a shortened answer ends with, so nobody reads a cut line as the whole of it. */
    private static final String CUT = "...";

    private final boolean verbose;

    private TerminalWatch(boolean verbose) {
        this.verbose = verbose;
    }

    /** A run that shows what happened and not what was said about it. */
    public static TerminalWatch quiet() {
        return new TerminalWatch(false);
    }

    /** A run that shows the agent's reasoning and every answer in full. */
    public static TerminalWatch verbose() {
        return new TerminalWatch(true);
    }

    @Override
    public void thought(String reasoner, String text, List<ToolRequest> requested) {
        OutputFormatter.printIteration(reasoner);
        if (verbose && text != null && !text.isBlank()) {
            OutputFormatter.println(text.strip());
        }
    }

    @Override
    public void called(ToolRequest request, String answer) {
        if (verbose) {
            OutputFormatter.printSubheader(request.tool());
            if (answer != null && !answer.isBlank()) {
                OutputFormatter.println(answer.strip());
            }
            return;
        }
        String said = shortened(answer);
        OutputFormatter.printSubheader(said.isEmpty() ? request.tool()
                                                      : request.tool() + ": " + said);
    }

    @Override
    public void complained(String complaint) {
        OutputFormatter.printWarning(complaint);
    }

    @Override
    public void escalated(String reasoner, String reason) {
        OutputFormatter.printWarning("Handing this over to " + reasoner + ": " + reason);
    }

    @Override
    public void stalled(String reason) {
        OutputFormatter.printWarning(
                "This run has stopped getting anywhere: " + reason + ".");
        OutputFormatter.printInfo(
                "There is no stronger model to hand it to. Name one with '"
                + CommandUsage.prefix() + "config ai.escalateTo <model>' and a run that stalls"
                + " will hand itself over.");
    }

    @Override
    public void compacted(int squashed) {
        if (verbose) {
            OutputFormatter.println("The transcript was compacted; " + squashed
                                    + " answers given up. They are still in the record.");
        }
    }

    /** The first line of an answer, short enough to sit on one terminal line. */
    private static String shortened(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        String first = answer.strip().lines().findFirst().orElse("").strip();
        return first.length() <= ANSWER_SHOWN ? first
                                              : first.substring(0, ANSWER_SHOWN) + CUT;
    }
}
