package com.eonmux.cadetcoder.harness.loop;

import java.util.ArrayList;
import java.util.List;

/**
 * A watcher that writes down everything it is told.
 *
 * <p>Whoever drives a run -- a command, a log, a progress line -- sees it only through the watch, so
 * a run that does the right thing while telling nobody is a run nothing can be shown of. Keeping
 * every notice is what lets a test say the two are the same run.</p>
 */
public final class RecordingWatch implements RunWatch {

    private final List<String>    thoughts   = new ArrayList<>();
    private final List<String>    calls      = new ArrayList<>();
    private final List<String>    complaints = new ArrayList<>();
    private final List<String>    escalations = new ArrayList<>();
    private final List<String>    stalls      = new ArrayList<>();
    private final List<Integer>   compactions = new ArrayList<>();
    private final List<RunResult> endings    = new ArrayList<>();

    @Override
    public void thought(String reasoner, String text, List<ToolRequest> requested) {
        thoughts.add(reasoner + ": " + text);
    }

    @Override
    public void called(ToolRequest request, String answer) {
        calls.add(request.tool());
    }

    @Override
    public void complained(String complaint) {
        complaints.add(complaint);
    }

    @Override
    public void escalated(String reasoner, String reason) {
        escalations.add(reasoner + ": " + reason);
    }

    @Override
    public void stalled(String reason) {
        stalls.add(reason);
    }

    @Override
    public void compacted(int squashed) {
        compactions.add(squashed);
    }

    @Override
    public void ended(RunResult result) {
        endings.add(result);
    }

    /** Every reply, as the reasoner that gave it and what it said. */
    public List<String> thoughts() {
        return List.copyOf(thoughts);
    }

    /** The name of every tool that was run, in order. */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    /** Everything the harness could not read. */
    public List<String> complaints() {
        return List.copyOf(complaints);
    }

    /** Every hand-over to a stronger reasoner. */
    public List<String> escalations() {
        return List.copyOf(escalations);
    }

    public List<String> stalls() {
        return List.copyOf(stalls);
    }

    /** How many answers each compaction gave up. */
    public List<Integer> compactions() {
        return List.copyOf(compactions);
    }

    /** The result the run ended with, or {@code null} if it was never told of one. */
    public RunResult ending() {
        return endings.isEmpty() ? null : endings.get(endings.size() - 1);
    }

    /** How many times it was told a run ended. */
    public int endings() {
        return endings.size();
    }
}
