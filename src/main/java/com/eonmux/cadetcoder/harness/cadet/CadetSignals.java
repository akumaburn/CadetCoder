package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.ai.UberMode;
import com.eonmux.cadetcoder.harness.loop.RunSignals;
import com.eonmux.cadetcoder.harness.loop.SystemPrompt;
import com.eonmux.cadetcoder.jobs.JobNotice;
import com.eonmux.cadetcoder.timers.TimerNotice;

/**
 * What this tool has to say to a run while it is going: check-ins that have come due, and a question
 * about a completion it has just claimed.
 *
 * <h2>Why this is here and not in the harness</h2>
 *
 * <p>Both are about how this tool is used rather than about how a run works. Timers are set by a
 * command the agent ran, through this tool's registry; whether a claim of completion is worth
 * questioning is a setting the person at the terminal chose. The harness is the part that knows
 * about ledgers and evidence and nothing about either of those, so it asks and this answers.</p>
 *
 * <h2>Why one of these belongs to one run</h2>
 *
 * <p>The count of claims questioned is per run. Shared between runs, a long first run would spend
 * the budget and every run after it would have its first claim believed -- so one is built for each
 * run and discarded with it.</p>
 */
public final class CadetSignals implements RunSignals {

    /** How the agent is told to go on, and how to end, when a claim of completion is sent back. */
    private static final String HOW_TO_FINISH =
            "If anything remains, carry on as usual. If everything really is done and verified, "
            + "reply " + SystemPrompt.DONE + " again and the run will end.";

    /** What this run was asked for, which is the thing a claim of completion is checked against. */
    private final String task;

    private int questionsAsked;

    /**
     * @param task what the run was asked to do
     */
    public CadetSignals(String task) {
        this.task = task;
    }

    @Override
    public String standingInstruction() {
        return UberMode.directive();
    }

    @Override
    public String beforeRound() {
        String notice = TimerNotice.dueNow() + JobNotice.dueNow();
        // The harness writes whatever this returns straight into the transcript, and the transcript
        // is what every later prompt is built from -- so by the time this returns the reminder has
        // arrived somewhere the model will read it, with nothing in between that can lose it.
        TimerNotice.delivered();
        JobNotice.delivered();
        return notice;
    }

    @Override
    public String questionDone(String declaration) {
        if (!UberMode.shouldQuestion(questionsAsked)) {
            return "";
        }
        questionsAsked++;
        return UberMode.challenge(questionsAsked, task, declaration, HOW_TO_FINISH);
    }

    /** @return how many of this run's claims of completion have been questioned */
    public int questionsAsked() {
        return questionsAsked;
    }
}
