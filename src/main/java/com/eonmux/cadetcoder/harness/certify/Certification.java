package com.eonmux.cadetcoder.harness.certify;

import com.eonmux.cadetcoder.harness.ledger.Ledger;
import com.eonmux.cadetcoder.harness.ledger.Transition;
import com.eonmux.cadetcoder.harness.model.WorldModel;
import com.eonmux.cadetcoder.harness.spec.Prediction;
import com.eonmux.cadetcoder.harness.spec.PredictionOutcome;
import com.eonmux.cadetcoder.harness.spec.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one way a model becomes something the agent is entitled to act on.
 *
 * <h2>Why a replay and not a test suite</h2>
 *
 * <p>A model is written by an LLM about an environment nobody wrote down. There is no oracle for it
 * except the record of what really happened, so certification runs the whole ledger through the
 * model and reports every place the two disagree. The same check, one step at a time, is what the
 * commit gate uses live -- {@link #checkLive} -- so a plan cannot pass one and fail the other.</p>
 *
 * <h2>Why the model is asked afresh</h2>
 *
 * <p>Coverage accumulates over a model's life. Certifying with the model the agent has been using
 * would report the arms that agent happened to touch, not the arms the ledger tests, so a replay
 * starts from {@link WorldModel#fresh()}.</p>
 */
public final class Certification {

    /** How many transitions a certificate describes in full before it stops being readable. */
    public static final int MAX_REPORTS = 25;

    /** How much of a state key a drift report shows. */
    static final int KEY_SHOWN = 160;

    private Certification() {
    }

    /**
     * Replays the whole ledger through a model.
     *
     * @param model  the theory to test
     * @param ledger everything that really happened
     * @return what the replay established
     */
    public static Certificate certify(WorldModel model, Ledger ledger) {
        return certify(model, ledger, false);
    }

    /**
     * Replays the whole ledger through a model, choosing how much drift a run can afford.
     *
     * @param model           the theory to test
     * @param ledger          everything that really happened
     * @param strictGrounding whether a rule and a representation that disagree is fatal rather than
     *                        merely reported -- what a run about to commit something irreversible asks for
     * @return what the replay established
     */
    public static Certificate certify(WorldModel model, Ledger ledger, boolean strictGrounding) {
        long   started = System.nanoTime();
        String head    = ledger.head();
        int    length  = ledger.size();
        Replay replay  = new Replay(model.fresh(), strictGrounding);
        for (Transition transition : ledger.all()) {
            replay.take(transition);
        }
        return replay.certificate(head, length, started);
    }

    /**
     * The state a model was in after each transition of the ledger.
     *
     * <h2>Why this comes from the replay</h2>
     *
     * <p>Anything that reasons over the ledger in a model's own terms -- fitting a rule to what the
     * environment flagged, say -- needs the state the model was in at each point. Deriving those
     * somewhere else means a second walk with its own idea of what a reset does and what a model
     * remembers, and two answers about the same ledger that quietly disagree. This is the walk that
     * certifies, asked a different question.</p>
     *
     *  model  the theory to roll forward
     *  ledger everything that really happened
     *  one state per transition, in the ledger's own order; { null} where the model
     *         could not say where it was
     */
    public static List<Object> states(WorldModel model, Ledger ledger) {
        Replay replay = new Replay(model.fresh(), false);
        for (Transition transition : ledger.all()) {
            replay.take(transition);
        }
        return replay.states();
    }

    /**
     * Checks one step that was actually taken, while there is still time to stop.
     *
     * @param model     the model that made the prediction
     * @param state     where the model thought the world was
     * @param action    what was done
     * @param obsAfter  what really happened
     * @param flags     the verdicts the environment reported
     * @return the state the model reaches, and whether reality agreed with it
     */
    public static LiveCheck checkLive(WorldModel model, Object state, Object action,
                                      Object obsAfter, Map<String, Object> flags) {
        Object            next       = model.step(state, action);
        Object            prediction = model.predict(next);
        PredictionOutcome outcome    = Prediction.check(prediction, obsAfter);
        List<String>      violations = new ArrayList<>();
        for (Violation violation : outcome.violations()) {
            violations.add(violation.render());
        }
        for (Violation violation : Flags.check(model, next, flags == null ? Map.of() : flags)) {
            violations.add(violation.render());
        }
        return new LiveCheck(next, prediction, violations.isEmpty(), violations);
    }
}
