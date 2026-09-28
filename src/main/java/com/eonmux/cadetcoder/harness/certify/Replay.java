package com.eonmux.cadetcoder.harness.certify;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.ledger.Transition;
import com.eonmux.cadetcoder.harness.model.GroundingException;
import com.eonmux.cadetcoder.harness.model.ModelException;
import com.eonmux.cadetcoder.harness.model.WorldModel;
import com.eonmux.cadetcoder.harness.spec.Prediction;
import com.eonmux.cadetcoder.harness.spec.PredictionKind;
import com.eonmux.cadetcoder.harness.spec.PredictionOutcome;
import com.eonmux.cadetcoder.harness.spec.Violation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ledger run through a model, one transition at a time.
 *
 * <h2>Why the replay rolls</h2>
 *
 * <p>The state is carried forward within an episode instead of being re-derived from each
 * observation, because a model's memory is exactly what a per-transition replay would never test: a
 * counter, a door that was unlocked, a build that has already run. It is re-grounded on what really
 * happened afterwards -- never on the model's own prediction, which would let a model that has come
 * loose from reality carry on agreeing with itself.</p>
 *
 * <h2>Why nothing is skipped</h2>
 *
 * <p>Every transition is accounted for, including the first, because a replay that quietly starts at
 * the second is how an off-by-one survives certification.</p>
 */
final class Replay {

    private final WorldModel             model;
    private final boolean                strictGrounding;
    private final List<TransitionReport> reports = new ArrayList<>();
    private final List<Object>           states  = new ArrayList<>();

    private Object  state;
    private boolean failedHere;
    private int     checked;
    private int     ok;
    private int     mismatched;
    private int     drifted;
    private int     resets;

    Replay(WorldModel model, boolean strictGrounding) {
        this.model           = model;
        this.strictGrounding = strictGrounding;
    }

    /**
     * Puts one transition to the model.
     *
     * <h2>Why one transition is one verdict</h2>
     *
     * <p>A transition can go wrong in more than one way at once -- a prediction that held and a
     * re-grounding that did not, a step that failed and a {@code parse} that could not recover.
     * Each of those is worth reporting, and counting each of them would make a certificate say that
     * more predictions held, or failed, than were ever checked. Everything that goes wrong here is
     * reported; the transition is counted once, and it counts as a failure if anything did.</p>
     *
     * <h2>Why a reset that cannot be read is not a reset</h2>
     *
     * <p>An episode start is a re-grounding point rather than a prediction, so it is not among the
     * transitions a model is checked on. Reading where the world restarted is the one thing the
     * model is asked to do there, though, so a {@code parse} that cannot is a model the ledger has
     * contradicted -- counted as the failed transition it is, rather than as a re-grounding that
     * happened.</p>
     */
    void take(Transition transition) {
        failedHere = false;
        boolean grounding = transition.isReset() && !(model.handlesReset() && state != null);

        state = grounding ? reground(transition, null) : stepped(transition);
        states.add(state);

        if (grounding && !failedHere) {
            resets++;
            return;
        }
        checked++;
        if (failedHere) {
            mismatched++;
        } else {
            ok++;
        }
    }

    /**
     * The state after one transition the model is answerable for.
     *
     * @return where the model says the world now is, or {@code null} when it could not say
     */
    private Object stepped(Transition transition) {
        Object before = state == null ? ground(transition) : state;
        return before == null ? null : reground(transition, check(transition, before));
    }

    /**
     * The state the model was in after each transition, in the ledger's own order.
     *
     *  one entry per transition; { null} where the model could not say where it was
     */
    List<Object> states() {
        return Collections.unmodifiableList(new ArrayList<>(states));
    }

    /** What was established, against the ledger as it stood when the replay began. */
    Certificate certificate(String head, int length, long startedNanos) {
        return new Certificate(model.digest(), head, length, checked, ok, mismatched, drifted,
                               resets, reports, model.coverage(), strictGrounding,
                               (System.nanoTime() - startedNanos) / 1_000_000L, state);
    }

    /**
     * Checks one transition the model is answerable for.
     *
     * @param transition what really happened
     * @param before     where the model was when it happened
     * @return the state the model says it led to, or {@code null} when the model could not say
     */
    private Object check(Transition transition, Object before) {
        String was = Json.canonical(before);
        Object next;
        Object prediction;
        try {
            next = model.step(before, transition.action());
            if (!Json.canonical(before).equals(was)) {
                failed(transition, ReportKind.IMPURE_STEP,
                       "step changed the state it was given rather than answering with a new one, "
                       + "so every search that reuses that state is searching something else");
                return null;
            }
            prediction = model.predict(next);
        } catch (GroundingException failure) {
            failed(transition, ReportKind.GROUNDING_FAILURE, failure.getMessage());
            return null;
        } catch (ModelException failure) {
            failed(transition, ReportKind.MODEL_FAILURE, failure.getMessage());
            return null;
        }
        record(transition, next, prediction);
        return next;
    }

    private void record(Transition transition, Object next, Object prediction) {
        PredictionOutcome outcome    = Prediction.check(prediction, transition.obsAfter());
        List<String>      violations = new ArrayList<>();
        for (Violation violation : outcome.violations()) {
            violations.add(violation.render());
        }
        for (Violation violation : Flags.check(model, next, transition.flags())) {
            violations.add(violation.render());
        }
        if (!violations.isEmpty() && outcome.kind() == PredictionKind.CONSTRAINT) {
            violations.add("the prediction was " + Prediction.describe(prediction));
        }
        String drift = outcome.kind() == PredictionKind.EXACT ? drift(next, transition.obsAfter())
                                                              : null;
        report(new TransitionReport(transition.index(), violations.isEmpty(), kindOf(outcome),
                                 violations, drift, transition.action()));
    }

    private static ReportKind kindOf(PredictionOutcome outcome) {
        return outcome.kind() == PredictionKind.CONSTRAINT ? ReportKind.CONSTRAINT
                                                           : ReportKind.EXACT;
    }

    /**
     * Whether the rule and the representation still agree with each other.
     *
     * <p>A {@code step} that reaches a state {@code parse} would never produce from the same
     * observation is a model whose halves have come apart. Every prediction can still hold, so
     * nothing else notices, and a plan more than one step long is built on the difference.</p>
     */
    private String drift(Object next, Object observation) {
        Object real;
        try {
            real = model.parse(observation);
        } catch (GroundingException failure) {
            return "the model could not ground what really happened here: " + failure.getMessage();
        }
        String reached = model.observableKey(next);
        String read    = model.observableKey(real);
        if (reached.equals(read)) {
            return null;
        }
        return "step reached " + brief(reached) + " but parse reads the same observation as "
               + brief(read) + ": the rule and the representation disagree";
    }

    private Object ground(Transition transition) {
        try {
            return model.parse(transition.obsBefore());
        } catch (GroundingException failure) {
            failed(transition, ReportKind.GROUNDING_FAILURE, failure.getMessage());
            return null;
        }
    }

    /**
     * Re-grounds on what really happened, keeping the fields one observation cannot show.
     *
     * <p>A grounding failure here is reported rather than swallowed. Falling back to the rolled
     * state would let a {@code parse} that has stopped working hide behind a {@code step} that
     * still does.</p>
     */
    private Object reground(Transition transition, Object rolled) {
        try {
            return model.regroundedOn(transition.obsAfter(), rolled);
        } catch (GroundingException failure) {
            failed(transition, ReportKind.GROUNDING_FAILURE,
                   "re-grounding after this transition failed: " + failure.getMessage());
            return rolled;
        }
    }

    private void failed(Transition transition, ReportKind kind, String detail) {
        report(new TransitionReport(transition.index(), false, kind, List.of(detail), null,
                                    transition.action()));
    }

    /**
     * Writes down what happened at one transition, without settling its verdict.
     *
     * <p>More than one of these can be about the same transition, so whether that transition counts
     * as a failure is decided once, in {@link #take}.</p>
     */
    private void report(TransitionReport report) {
        if (!report.ok()) {
            failedHere = true;
        }
        if (report.groundingDrift() != null) {
            drifted++;
        }
        if ((!report.ok() || report.groundingDrift() != null)
            && reports.size() < Certification.MAX_REPORTS) {
            reports.add(report);
        }
    }

    private static String brief(String key) {
        return key.length() <= Certification.KEY_SHOWN ? key
                                                       : key.substring(0, Certification.KEY_SHOWN) + "...";
    }
}
