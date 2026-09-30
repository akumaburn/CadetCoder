package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.harness.cadet.RecordedRun;
import com.eonmux.cadetcoder.harness.ledger.Transition;
import com.eonmux.cadetcoder.session.ResumePoint;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a resumed agent is told about the run it carries on.
 *
 * <h2>Why it is part of the task</h2>
 *
 * <p>The harness builds every request from the task and from what the new run establishes. The
 * interrupted run's record is in a directory of its own, which the new run does not read. So what
 * that run wrote down for itself and the last things it did are added to the task, where every
 * request of the new run shows them. The classic loop keeps no record, so its actions are saved in
 * the resume point, and those are added instead.</p>
 *
 * <p>A resumed run that is interrupted again writes a record of its own. What the attempts before
 * it did is kept beside that record, as text, so each resume shows the work of every attempt.</p>
 */
final class AgentResumeNote {

    /** How much of the earlier run's notes is shown. */
    static final int NOTES_CHARS = 8_000;

    /** How many of the earlier run's last transitions are shown. */
    static final int TRANSITIONS = 20;

    /** How much of one transition or one action's output is shown. */
    static final int ENTRY_CHARS = 400;

    /** How many of the classic loop's actions are kept. */
    static final int ACTIONS_KEPT = 30;

    /** How much of the earlier attempts' work is kept, from the latest end. */
    static final int EARLIER_CHARS = 16_000;

    private AgentResumeNote() {
    }

    /**
     * @param agent    what the interrupted run left
     * @param briefing what else the run is told about the interrupt, or empty
     * @return the text to add to the task
     */
    static String of(ResumePoint.Agent agent, String briefing) {
        StringBuilder note = new StringBuilder("\n\nThe user interrupted an earlier run at this ")
                .append("task, and has now resumed it.\n");
        if (briefing != null && !briefing.isBlank()) {
            note.append(briefing.strip()).append('\n');
        }
        if (agent.earlier() != null && !agent.earlier().isBlank()) {
            note.append("What the attempts before that run did:\n").append(agent.earlier().strip())
                .append('\n');
        }
        note.append(workOf(agent));
        return note.append("Carry on from where it stopped. Do not repeat work that is already ")
                   .append("done; check the project where you are not sure.").toString();
    }

    /**
     * What every attempt up to and including this one did, for the attempt after it.
     *
     * @param agent what the attempt this run carries on left
     * @return the text, the latest {@link #EARLIER_CHARS} characters of it
     */
    static String earlierWork(ResumePoint.Agent agent) {
        StringBuilder work = new StringBuilder();
        if (agent.earlier() != null && !agent.earlier().isBlank()) {
            work.append(agent.earlier().strip()).append('\n');
        }
        work.append(workOf(agent));
        String text = work.toString().strip();
        return text.length() > EARLIER_CHARS
               ? "[...]\n" + text.substring(text.length() - EARLIER_CHARS) : text;
    }

    /** @return what one attempt did, from its record or from its actions */
    private static String workOf(ResumePoint.Agent agent) {
        StringBuilder work = new StringBuilder();
        if (agent.record() != null) {
            fromRecord(agent.record(), work);
        } else if (!agent.actions().isEmpty()) {
            work.append("What that run did, oldest first:\n");
            agent.actions().forEach(action -> work.append("- ").append(action).append('\n'));
        }
        return work.toString();
    }

    /**
     * The classic loop's actions, as the resume point keeps them.
     *
     * @param state the run so far, or {@code null}
     * @return one line per action, the latest {@link #ACTIONS_KEPT} of them
     */
    static List<String> actionsOf(AgentState state) {
        if (state == null) {
            return List.of();
        }
        List<AgentState.ActionResult> done = state.getExecutedActions();
        List<String>                  kept = new ArrayList<>();
        for (AgentState.ActionResult result
                : done.subList(Math.max(0, done.size() - ACTIONS_KEPT), done.size())) {
            String args = result.action.args == null ? "" : String.join(" ", result.action.args);
            kept.add(oneLine(result.action.command + " " + args) + " -> exit " + result.exitCode
                     + ": " + oneLine(result.output == null ? "" : result.output));
        }
        return kept;
    }

    private static void fromRecord(String directory, StringBuilder note) {
        note.append("That run kept its record in ").append(directory).append(".\n");
        try {
            RecordedRun run = RecordedRun.at(Paths.get(directory));
            Optional<String> notes = run.notes();
            if (notes.isPresent() && !notes.get().isBlank()) {
                String text = notes.get().strip();
                note.append("What it wrote down for itself:\n")
                    .append(text.length() > NOTES_CHARS ? text.substring(0, NOTES_CHARS) + "\n[...]"
                                                        : text)
                    .append('\n');
            }
            List<Transition> last = run.lastTransitions(TRANSITIONS);
            if (!last.isEmpty()) {
                note.append("The last things it did, oldest first:\n");
                last.forEach(step -> note.append(oneLine(step.toString())).append('\n'));
            }
        } catch (RuntimeException unreadable) {
            note.append("Its record could not be read: ").append(unreadable.getMessage())
                .append('\n');
        }
    }

    private static String oneLine(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > ENTRY_CHARS ? flat.substring(0, ENTRY_CHARS) + " [...]" : flat;
    }
}
