package com.eonmux.cadetcoder.harness.loop;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.budget.Budget;
import com.eonmux.cadetcoder.harness.budget.BudgetExceededException;
import com.eonmux.cadetcoder.harness.budget.Progress;
import com.eonmux.cadetcoder.harness.budget.Spend;
import com.eonmux.cadetcoder.harness.ledger.Transition;
import com.eonmux.cadetcoder.harness.tools.ToolSession;
import com.eonmux.cadetcoder.harness.tools.Toolbox;

import java.util.ArrayList;
import java.util.List;

/**
 * The loop: observe, deliberate, commit, observe.
 *
 * <h2>Why a deliberation ends at the first thing that happened</h2>
 *
 * <p>Acting is the one moment at which everything the agent believes about where it is becomes a
 * claim about the past. Carrying on inside the same round of thinking means planning the next step
 * against the world as it was before the last one, and a certificate that stopped covering the ledger
 * two steps ago. Ending there costs a round of thinking and buys the next one a true starting
 * point.</p>
 *
 * <h2>Why an allowance that runs out is a result rather than a failure</h2>
 *
 * <p>A run that spends what it was given has finished doing what it could, and everything it
 * established -- the ledger, the models, the beliefs -- is still there and still worth reading. Let
 * out as an exception, that becomes indistinguishable from a crash, and whoever asked for the run is
 * handed nothing to tell them apart.</p>
 *
 * <h2>Why the agent is nudged, and why not forever</h2>
 *
 * <p>A turn that reads and concludes without acting is the failure every agent loop has: it is always
 * cheaper to look once more than to find out. Telling the agent to act usually works. Telling it
 * again and again when it does not is a whole budget spent on encouragement, so a run that will not
 * act is stopped and said to be stuck, which is what it is.</p>
 */
public final class Harness {

    /** What the ledger records as the reason for the reset that starts a run. */
    public static final String INITIAL = "the run began here";

    /** How much of the notes a compacted transcript carries forward. */
    private static final int NOTES_CARRIED = 2_000;

    /** How a turn from the driver is marked, so nothing it says reads as a tool or as the agent. */
    private static final String SPEAKING = "[harness] ";

    private static final String BREAK = System.lineSeparator() + System.lineSeparator();

    private static final String BEGIN = "Begin. Where the world is, and where the run stands:";

    /** What a result says when the run was taken back rather than finished. */
    private static final String CALLED_OFF =
            "The run was called off before its next turn. The ledger, the models and the notes "
            + "are as the run left them.";

    private static final String NUDGE =
            SPEAKING + "you ended your turn without acting. Either commit -- a plan against a "
            + "certified model, or a short blind probe -- or reply " + SystemPrompt.DONE
            + " if the work is finished, or " + SystemPrompt.STUCK
            + " with the one question that is blocking you.";

    private final ToolSession    session;
    private final Toolbox        toolbox;
    private final List<Reasoner> reasoners;
    private final HarnessLimits  limits;
    private final RunWatch       watch;
    private final RunStop        stop;
    private final RunSignals     signals;
    private final String         system;

    private Transcript transcript = Transcript.empty();
    private int        reasonerAt;
    private int        escalations;
    private boolean    saidStalled;

    /**
     * Builds a run nobody can call off, for a caller with no operator behind it.
     *
     * @param session   the world, the record, the budget and the gate
     * @param reasoners who does the thinking, weakest first
     * @param limits    what the driver may do between the agent and the world
     * @param watch     whoever is watching it happen
     */
    public Harness(ToolSession session, List<Reasoner> reasoners, HarnessLimits limits,
                   RunWatch watch) {
        this(session, reasoners, limits, watch, RunStop.never());
    }

    /**
     * Builds a run whoever started it can take back.
     *
     * @param session   the world, the record, the budget and the gate
     * @param reasoners who does the thinking, weakest first
     * @param limits    what the driver may do between the agent and the world
     * @param watch     whoever is watching it happen
     * @param stop      whether whoever started it still wants it
     */
    public Harness(ToolSession session, List<Reasoner> reasoners, HarnessLimits limits,
                   RunWatch watch, RunStop stop) {
        this(session, reasoners, limits, watch, stop, RunSignals.none());
    }

    /**
     * Builds a run that something outside it can speak into.
     *
     * @param session   the world, the record, the budget and the gate
     * @param reasoners who does the thinking, weakest first
     * @param limits    what the driver may do between the agent and the world
     * @param watch     whoever is watching it happen
     * @param stop      whether whoever started it still wants it
     * @param signals   what the world outside the run has to say to it while it is going
     */
    public Harness(ToolSession session, List<Reasoner> reasoners, HarnessLimits limits,
                   RunWatch watch, RunStop stop, RunSignals signals) {
        if (session == null || limits == null || watch == null || stop == null || signals == null) {
            throw new IllegalArgumentException("a run needs a session, its limits, a watch, a way to"
                                               + " be called off and somewhere to take signals from");
        }
        if (reasoners == null || reasoners.isEmpty()) {
            throw new IllegalArgumentException("a run needs at least one reasoner to think with");
        }
        this.session   = session;
        this.toolbox   = new Toolbox(session);
        this.reasoners = List.copyOf(reasoners);
        this.limits    = limits;
        this.watch     = watch;
        this.stop      = stop;
        this.signals   = signals;
        this.system    = withStanding(SystemPrompt.render(session.env()),
                                      signals.standingInstruction());
    }

    /**
     * The standing instructions, with whatever holds for this particular run on the end.
     *
     * <p>Appended rather than woven in: the prompt's every section is rendered by the part of the
     * harness that enforces it, and something the driver knows nothing about has no business
     * between any two of them. Read last, it also reads as qualifying what the harness has already
     * said, which is what it is.</p>
     *
     * @param prompt   what the harness tells every run
     * @param standing what holds for this one, or empty
     * @return the prompt to think against
     */
    private static String withStanding(String prompt, String standing) {
        return standing == null || standing.isBlank() ? prompt : prompt + BREAK + standing.strip();
    }

    /** A run with the standard limits that nobody is watching. */
    public static Harness of(ToolSession session, Reasoner... reasoners) {
        return new Harness(session, List.of(reasoners), HarnessLimits.standard(), RunWatch.silent());
    }

    /** Everything said so far, which is what a compaction rewrites and a transcript file holds. */
    public Transcript transcript() {
        return transcript;
    }

    /**
     * Runs until something ends it.
     *
     * @return what the run came to
     */
    public RunResult run() {
        if (session.ledger().size() == 0) {
            session.gate().reset(INITIAL);
        }
        transcript = transcript.plus(Turn.harness(BEGIN + BREAK + toolbox.observation()));
        while (true) {
            Deliberation round;
            try {
                session.budget().checkTime();
                session.budget().deliberate();
                round = deliberate();
            } catch (BudgetExceededException spent) {
                return ended(RunStatus.BUDGET, spent.getMessage());
            }
            if (round.over()) {
                return ended(round.status(), round.text());
            }
            afterDeliberation();
        }
    }

    /**
     * One round of thinking: calls until something happened to the world, a declaration, a
     * refusal to act, or the run being called off.
     *
     * @return why the round ended, and whether that ended the run
     * @throws BudgetExceededException if the run spent what it was allowed part way through
     */
    private Deliberation deliberate() {
        boolean acted  = false;
        int     calls  = 0;
        int     silent = 0;
        while (true) {
            if (stop.called()) {
                return new Deliberation(RunStatus.STOPPED, CALLED_OFF);
            }
            // Asked here as well as between rounds, because a round has no length of its own: a
            // deliberation that keeps reading costs no action and, until the call cap is reached,
            // no further deliberation either. Asked only outside, the one allowance a run cannot
            // help spending is the one it would never be stopped by.
            session.budget().checkTime();
            Reasoner reasoner = reasoner();
            Reply    reply    = reasoner.think(system, transcript);
            session.budget().chargeTokens(reply.tokensIn(), reply.tokensOut());
            transcript = transcript.plus(Turn.agent(reply.text()));

            CallReading reading = CallFormat.read(reply.text());
            watch.thought(reasoner.name(), reply.text(), reading.calls());
            if (reading.silent()) {
                Deliberation said = saidWithoutActing(reply.text(), acted, ++silent);
                if (said != null) {
                    return said;
                }
                continue;
            }
            // A reply that asked for something is not a turn that ended without acting, so the run
            // of them starts again from here. Counted straight through, a round that read, asked,
            // read and asked would be stopped as stuck over turns that were nothing of the kind,
            // and told it had refused to act a number of times in a row it never did.
            silent = 0;
            if (stop.called()) {
                // Thinking is the long part of a round and the part a person is most likely to
                // change their mind during. Asked only before it, a run taken back while the model
                // was answering would still reach the world with what came back.
                return new Deliberation(RunStatus.STOPPED, CALLED_OFF);
            }
            int before = session.ledger().size();
            calls += answer(reading);
            acted = acted || session.ledger().size() > before;

            RunStatus reached = reached(before);
            if (reached.over()) {
                return new Deliberation(reached, "");
            }
            if (acted) {
                return Deliberation.CONTINUES;
            }
            if (calls >= limits.maxCallsPerDeliberation()) {
                transcript = transcript.plus(Turn.harness(readingRatherThanActing(calls)));
                session.budget().deliberate();
                calls = 0;
            }
        }
    }

    /**
     * What a reply that asked for nothing means.
     *
     * @param text   what the agent said
     * @param acted  whether anything has already happened to the world this round
     * @param silent how many replies in a row have now ended without acting
     * @return how the round ends, or {@code null} when the agent has been nudged and asked again
     */
    private Deliberation saidWithoutActing(String text, boolean acted, int silent) {
        RunStatus declared = RunStatus.declared(text);
        if (declared == RunStatus.DONE) {
            // A run says it is finished from inside the same thinking that decided what finished
            // meant, so whoever started it gets to ask once more before that is the end. Nothing is
            // asked unless something outside the run has a question, and the questions run out --
            // see RunSignals -- so this cannot hold a run open.
            String question = signals.questionDone(text);
            if (!question.isBlank()) {
                transcript = transcript.plus(Turn.harness(SPEAKING + question));
                return null;
            }
        }
        if (declared.over()) {
            return new Deliberation(declared, text.strip());
        }
        if (acted) {
            return Deliberation.CONTINUES;
        }
        if (silent > limits.maxSilentReplies()) {
            return new Deliberation(RunStatus.STUCK, refusedToAct(silent));
        }
        transcript = transcript.plus(Turn.harness(NUDGE));
        return null;
    }

    /**
     * Runs everything one reply asked for, and reports what could not be read.
     *
     * <h2>Why a complaint costs a call</h2>
     *
     * <p>The cap is on turns spent without acting, and a reply the driver could not read is a turn
     * spent exactly as surely as one that read the ledger. Counting only the calls that parsed would
     * let a run that keeps producing malformed calls go round for as long as it has tokens.</p>
     *
     * @param reading what the reply turned out to contain
     * @return how much of the deliberation's allowance it spent
     */
    private int answer(CallReading reading) {
        if (!reading.complaints().isEmpty()) {
            transcript = transcript.plus(Turn.harness(SPEAKING + reading.complaint()));
            watch.complained(reading.complaint());
        }
        if (!reading.calls().isEmpty()) {
            List<String> answers = new ArrayList<>(reading.calls().size());
            for (ToolRequest request : reading.calls()) {
                String said = toolbox.dispatch(request.tool(), request.arguments());
                watch.called(request, said);
                answers.add(request.tool() + ": " + said);
            }
            transcript = transcript.plus(Turn.answer(String.join(BREAK, answers)));
        }
        return reading.calls().size() + reading.complaints().size();
    }

    /**
     * What the environment said about where the run stands, over everything this reply did.
     *
     * <h2>Why the ledger and not the answer the tool gave</h2>
     *
     * <p>The flags are read off the ledger because a reset is recorded there too, and because the
     * ledger is the record everything else in the system agrees on. It is also the only place that
     * distinguishes a commit that happened from one the gate refused: both come back as an answer
     * to a call named {@code commit}, and only one of them left a transition behind.</p>
     *
     * <h2>Why every transition this reply wrote and not the last one</h2>
     *
     * <p>A reply may ask for more than one thing, and reaching the goal is a fact about the run
     * that nothing after it undoes. Read off the last transition alone, a reply that reached the
     * goal and then started a fresh episode would look like a run that had merely started one, and
     * would carry on past the thing it was asked for. An episode being over is the opposite kind of
     * fact -- it is true of now, and a reset in the same reply has genuinely ended it -- so that
     * one is still read off the last.</p>
     *
     * @param before how long the ledger was before this reply was answered
     */
    private RunStatus reached(int before) {
        int written = session.ledger().size() - before;
        if (written <= 0) {
            return RunStatus.RUNNING;
        }
        List<Transition> since = session.ledger().tail(written);
        for (Transition transition : since) {
            if (transition.reachedGoal()) {
                return RunStatus.GOAL;
            }
        }
        return since.get(since.size() - 1).endedEpisode() ? RunStatus.TERMINAL : RunStatus.RUNNING;
    }

    /** What is done between one round of thinking and the next. */
    private void afterDeliberation() {
        // Between rounds and nowhere else: a round ends at the first thing that happened to the
        // world, so this is the one moment at which the agent is about to look at where it stands
        // with nothing half-done behind it. Said as the driver, like everything else the driver says.
        String said = signals.beforeRound();
        if (!said.isBlank()) {
            transcript = transcript.plus(Turn.harness(SPEAKING + said));
        }

        Budget budget = session.budget();
        budget.record(progress());
        String plateau = budget.plateau();
        if (plateau != null) {
            if (reasonerAt + 1 < reasoners.size()) {
                escalate(plateau);
            } else if (!saidStalled) {
                // Nobody stronger left. Not an error and not a reason to stop -- exploration can
                // legitimately certify nothing for a while -- but the one thing a stalled run
                // cannot do for itself is acquire a stronger reasoner, so whoever is watching is
                // told while there is still budget left to spend on the answer.
                saidStalled = true;
                watch.stalled(plateau);
            }
        }
        if (outgrown(budget.spend())) {
            compact();
        }
    }

    /**
     * Where the run stands, taken from the certificate that was filed rather than the one being
     * held.
     *
     * <p>A standing certificate stops covering the ledger the moment anything is committed, and is
     * answered for as absent from then on. Read from there, every deliberation that acted would
     * report nothing certified, and a run that was learning steadily would look like a plateau.</p>
     */
    private Progress progress() {
        Spend  spend  = session.budget().spend();
        String latest = session.registry().latest();
        Object filed  = latest == null ? null : session.registry().certificate(latest);
        return new Progress(spend.deliberations(), count(filed, "ok"), count(filed, "mismatched"),
                            session.ledger().size(), latest, spend.actions());
    }

    private static int count(Object certificate, String field) {
        Object value = certificate == null ? null : Json.at(certificate, field);
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** Hands the session to the next reasoner up, and tells it what it has walked into. */
    private void escalate(String reason) {
        reasonerAt++;
        escalations++;
        watch.escalated(reasoner().name(), reason);
        transcript = transcript.plus(Turn.harness(
                SPEAKING + "the run has stopped getting anywhere: " + reason
                + ". A stronger reasoner has taken this over. Read the beliefs and the latest "
                + "certificate before going on." + BREAK + toolbox.status() + BREAK
                + session.beliefs().render()));
    }

    /** Whether what is being sent per round of thinking has outgrown what a request should carry. */
    private boolean outgrown(Spend spend) {
        return spend.tokensIn() > 0
               && spend.tokensIn() / Math.max(1, spend.deliberations()) > limits.compactionTokens();
    }

    /**
     * Gives up the older tool answers and says where the world is instead.
     *
     * <h2>Why nothing is said when nothing was given up</h2>
     *
     * <p>What is sent per round of thinking stays over the limit for as long as it takes the shorter
     * requests to bring the average down, so this is reached on several deliberations in a row. Only
     * the first of them has anything to squash; the rest would append a fresh status block for
     * nothing, which is spending context to save context.</p>
     */
    private void compact() {
        Transcript cut = transcript.compacted(limits.keepRecentAnswers());
        if (cut.squashed() == transcript.squashed()) {
            return;
        }
        transcript = cut.plus(Turn.harness(
                SPEAKING + "the transcript was compacted; the answers it gave up are still in the "
                + "ledger, the beliefs and the certificates, and can be asked for again. Where the "
                + "run stands now:" + BREAK + toolbox.status() + BREAK + session.beliefs().render()
                + BREAK + "notes:" + System.lineSeparator() + session.notes().tail(NOTES_CARRIED)));
        watch.compacted(transcript.squashed());
    }

    private RunResult ended(RunStatus status, String text) {
        Spend     spend  = session.budget().spend();
        RunResult result = new RunResult(status, spend.deliberations(), spend.actions(),
                                         session.ledger().size(), escalations, text);
        watch.ended(result);
        return result;
    }

    private Reasoner reasoner() {
        return reasoners.get(reasonerAt);
    }

    private static String refusedToAct(int replies) {
        return SystemPrompt.STUCK + ": " + replies + " turns in a row ended without acting and "
               + "without saying what was blocking; the harness stopped the run.";
    }

    private static String readingRatherThanActing(int calls) {
        return SPEAKING + "you have made " + calls + " calls without acting. Commit an experiment "
               + "now -- a short blind probe, or a plan against a certified model -- or reply "
               + SystemPrompt.STUCK + " with the question that is blocking you.";
    }
}
