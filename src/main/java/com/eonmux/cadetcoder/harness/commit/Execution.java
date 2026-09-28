package com.eonmux.cadetcoder.harness.commit;

import com.eonmux.cadetcoder.harness.budget.Budget;
import com.eonmux.cadetcoder.harness.certify.Certification;
import com.eonmux.cadetcoder.harness.certify.LiveCheck;
import com.eonmux.cadetcoder.harness.env.Environment;
import com.eonmux.cadetcoder.harness.env.StepOutcome;
import com.eonmux.cadetcoder.harness.ledger.Ledger;
import com.eonmux.cadetcoder.harness.ledger.Transition;
import com.eonmux.cadetcoder.harness.model.GroundingException;
import com.eonmux.cadetcoder.harness.model.ModelException;
import com.eonmux.cadetcoder.harness.model.WorldModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The acting loop: the only place in the system where something happens to the world.
 *
 * <h2>Why every step is recorded before the next one is decided</h2>
 *
 * <p>An action is a fact the moment the environment takes it, whatever the model believed. Writing
 * the transition before anything else -- before the surprise is raised, before the goal is noticed,
 * before the plan is abandoned -- is what makes the ledger a record of what happened rather than a
 * record of what the loop decided to keep. A budget that runs out, a model that breaks and a world
 * that contradicts all leave the same complete trail behind them.</p>
 *
 * <h2>Why the state rolls and the observation rolls with it</h2>
 *
 * <p>The model's state carries what one observation cannot show, so it is stepped forward and then
 * re-grounded on what really happened; the raw observation is carried separately because it is what
 * the next transition records as the world before it. Losing either one is how a plan's second step
 * gets predicted from a world nobody was in.</p>
 */
final class Execution {

    private final Environment env;
    private final Ledger      ledger;
    private final Budget      budget;
    private final WorldModel  model;
    private final int         episode;
    private final String      note;

    private final List<Integer> indices = new ArrayList<>();

    private Object              state;
    private Object              observation;
    private CommitStatus        status = CommitStatus.COMPLETED;
    private String              reason;
    private Surprise            surprise;
    private Map<String, Object> flags  = Map.of();

    Execution(Environment env, Ledger ledger, Budget budget, WorldModel model, Object state,
              Object observation, int episode, String note) {
        this.env         = env;
        this.ledger      = ledger;
        this.budget      = budget;
        this.model       = model;
        this.state       = state;
        this.observation = observation;
        this.episode     = episode;
        this.note        = note;
    }

    /** Takes the first {@code count} actions of the plan, stopping at the first reason to. */
    void run(List<Object> actions, int count) {
        for (int at = 0; at < count; at++) {
            if (!take(at, actions.get(at))) {
                return;
            }
        }
    }

    List<Integer> indices() {
        return List.copyOf(indices);
    }

    Object state() {
        return state;
    }

    Object observation() {
        return observation;
    }

    CommitStatus status() {
        return status;
    }

    String reason() {
        return reason;
    }

    Surprise surprise() {
        return surprise;
    }

    Map<String, Object> flags() {
        return flags;
    }

    /**
     * One action: allowed, taken, checked, recorded, paid for.
     *
     * <p>The allowance is asked about and only then charged, because the two questions are about
     * different things. Whether the run may act is about an action that has not happened yet;
     * a charge is a record of one that has. Charged first and stopped on the refusal, the action
     * that broke the allowance is counted and never taken -- the tally says six where the ledger
     * says five -- and every certification, plateau and report that reads the two together is
     * reading about a run nobody made.</p>
     *
     * @return whether the plan may continue
     */
    private boolean take(int at, Object action) {
        String spent = budget.actionsSpent();
        if (spent != null) {
            status = CommitStatus.BUDGET;
            reason = spent;
            return false;
        }
        Object      before  = observation;
        StepOutcome outcome = env.act(action);
        budget.chargeActions(1);
        LiveCheck   check   = check(action, outcome);
        Transition  written = record(before, action, outcome, check);

        observation = outcome.observation();
        flags       = outcome.flags();
        indices.add(written.index());

        if (check != null && !check.held()) {
            return stopped(at, written, action, check.describe(), check.violations());
        }
        if (check != null && !reground(at, written, action, check)) {
            return false;
        }
        if (outcome.isGoal()) {
            status = CommitStatus.GOAL;
            return false;
        }
        if (outcome.isTerminal()) {
            status = CommitStatus.TERMINAL;
            return false;
        }
        return true;
    }

    /**
     * What the model says about a step that has already happened.
     *
     * @return the verdict, or {@code null} when there is no model to be answerable
     */
    private LiveCheck check(Object action, StepOutcome outcome) {
        if (model == null) {
            return null;
        }
        try {
            return Certification.checkLive(model, state, action, outcome.observation(),
                                           outcome.flags());
        } catch (ModelException failure) {
            return new LiveCheck(null, null, false,
                                 List.of("the model failed while being checked against what really "
                                         + "happened: " + failure.getMessage()));
        }
    }

    private Transition record(Object before, Object action, StepOutcome outcome, LiveCheck check) {
        Transition proposal = Transition.proposal(episode, before, action, outcome.observation(),
                                                  outcome.flags(), outcome.info());
        if (check != null) {
            proposal = proposal.predicting(model.digest(), check.prediction(), check.held());
        }
        if (note != null && !note.isEmpty()) {
            proposal = proposal.annotated(note);
        }
        return ledger.append(proposal);
    }

    /**
     * Re-grounds the model on what really happened.
     *
     * <p>A model that can no longer read the world is stopped rather than carried on from memory. Its
     * prediction may well have held -- an expectation about one field says nothing about the rest of
     * the observation -- but every step after this one would be reasoning from a state nothing
     * confirmed, and certification is going to count this as a mismatch anyway.</p>
     *
     * @return whether the plan may continue
     */
    private boolean reground(int at, Transition written, Object action, LiveCheck check) {
        try {
            state = model.regroundedOn(observation, check.state());
            return true;
        } catch (GroundingException failure) {
            return stopped(at, written, action, check.describe(),
                           List.of("re-grounding after this step failed: " + failure.getMessage()));
        }
    }

    private boolean stopped(int at, Transition written, Object action, String expected,
                            List<String> violations) {
        budget.countSurprise();
        status   = CommitStatus.SURPRISE;
        surprise = new Surprise(at, written.index(), action, expected, violations);
        return false;
    }
}
