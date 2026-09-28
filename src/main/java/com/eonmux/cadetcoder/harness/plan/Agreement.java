package com.eonmux.cadetcoder.harness.plan;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.spec.Expect;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * When candidate models are saying the same thing, and when they are only appearing to.
 *
 * <h2>Why an expectation and an exact prediction can agree</h2>
 *
 * <p>A model may name the next observation or state beliefs about it, and the two are not rival
 * answers to the same question. A model that says "the return code will be zero" has not
 * contradicted a model that names the whole observation with a zero in it -- it has said less about
 * the same world. Treating the shapes as different answers would make every pairing of a careful
 * model with a bold one look like an experiment, and the agent would spend real actions settling
 * arguments nobody was having.</p>
 *
 * <h2>Why two expectations agree only when they name the same beliefs</h2>
 *
 * <p>There is nothing to compare two expectations against: neither has named an observation, so
 * neither can be put to the other. The conservative reading is the only sound one -- two authors
 * willing to be wrong about different things have not agreed about anything, and an action that
 * shows which of them was willing to be wrong about the right thing is worth taking.</p>
 */
final class Agreement {

    private Agreement() {
    }

    /**
     * Whether every candidate predicted the same thing about the state they all reached.
     *
     * @param predictions what each candidate said, in candidate order
     * @return whether the action settles nothing
     */
    static boolean holds(List<Object> predictions) {
        List<Object> named  = new ArrayList<>();
        List<Expect> stated = new ArrayList<>();
        for (Object prediction : predictions) {
            if (prediction instanceof Expect expectation) {
                stated.add(expectation);
            } else {
                named.add(prediction);
            }
        }
        if (named.isEmpty()) {
            return sameBeliefs(stated);
        }
        return sameObservation(named) && accepted(stated, named.get(0));
    }

    /**
     * What puts two candidates in the same camp.
     *
     * <p>Camps are what an agent reads to see whether one action settles one argument or several at
     * once, so the key has to be the prediction as it was stated: an expectation groups by the
     * beliefs it names, an exact prediction by the observation it names.</p>
     *
     * @param prediction what one candidate said
     * @return the camp it belongs to
     */
    static String camp(Object prediction) {
        if (prediction instanceof Expect expectation) {
            List<String> names = new ArrayList<>(expectation.names());
            names.sort(String::compareTo);
            return "expect:" + Json.canonical(names);
        }
        return "obs:" + Json.canonical(prediction);
    }

    private static boolean sameObservation(List<Object> named) {
        String first = Json.canonical(named.get(0));
        for (Object prediction : named) {
            if (!Json.canonical(prediction).equals(first)) {
                return false;
            }
        }
        return true;
    }

    private static boolean accepted(List<Expect> stated, Object observation) {
        for (Expect expectation : stated) {
            if (!expectation.check(observation).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameBeliefs(List<Expect> stated) {
        Set<String> camps = new LinkedHashSet<>();
        for (Expect expectation : stated) {
            camps.add(camp(expectation));
        }
        return camps.size() <= 1;
    }
}
