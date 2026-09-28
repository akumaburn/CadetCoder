package com.eonmux.cadetcoder.harness.plan;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.model.GroundingException;
import com.eonmux.cadetcoder.harness.model.ModelException;
import com.eonmux.cadetcoder.harness.model.WorldModel;
import com.eonmux.cadetcoder.harness.spec.Prediction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The action that settles an argument between models, searched for rather than reasoned about.
 *
 * <h2>Why an agent needs this at all</h2>
 *
 * <p>Two models that both survive a replay of the whole ledger are both consistent with everything
 * that has ever happened, so certifying either again learns nothing. What separates them is an
 * action on which they predict different things -- and until that action is taken, the agent is
 * carrying two theories and acting on whichever it happened to write last. Asking a language model
 * to imagine such an action is asking it to do in prose what a search does exactly: the search
 * cannot overlook a case, and it comes back with the shortest one.</p>
 *
 * <h2>Why the shortest one matters</h2>
 *
 * <p>Every step of an experiment is a real action on the real environment, charged to the budget and
 * possibly not undoable. Breadth first over joint states is therefore not a default but the point:
 * a five-step experiment when a two-step one exists costs three actions to learn the same fact.</p>
 *
 * <h2>Why the search runs over joint states</h2>
 *
 * <p>The candidates disagree about where an action leads, so after the first step they are not in
 * the same place any more and cannot be searched separately. A node is therefore where every
 * candidate thinks the world is, and two nodes are the same only when every candidate says so --
 * which is also what makes the search terminate on candidates that only differ far out.</p>
 */
public final class Discrimination {

    /** How far out an experiment is worth looking before the agent should be told there isn't one. */
    public static final int STANDARD_HORIZON = 6;

    /** Where every candidate thinks the world is, and how the agent got them there. */
    private record Reached(List<Object> states, List<Object> path) {
    }

    private Discrimination() {
    }

    /**
     * The shortest experiment that tells these candidates apart.
     *
     * @param candidates  the models to distinguish, each of which the ledger has failed to refute
     * @param observation where the world is now
     * @param offered     what the environment allows, or {@code null} when it allows anything
     * @return the experiment, or why there isn't one
     */
    public static DiscriminationResult between(List<WorldModel> candidates, Object observation,
                                               List<Object> offered) {
        return between(candidates, observation, offered,
                       SearchLimits.standard().toDepth(STANDARD_HORIZON));
    }

    /**
     * {@link #between(List, Object, List)} under budgets of the caller's choosing.
     *
     * @param limits what stops the search, including how far out an experiment may be
     */
    public static DiscriminationResult between(List<WorldModel> candidates, Object observation,
                                               List<Object> offered, SearchLimits limits) {
        String refusal = refused(candidates);
        if (refusal != null) {
            return DiscriminationResult.broken(refusal, SearchEffort.nothing());
        }
        return search(fresh(candidates), observation, offered, limits);
    }

    private static DiscriminationResult search(List<WorldModel> models, Object observation,
                                               List<Object> offered, SearchLimits limits) {
        Clock          clock    = new Clock(limits);
        Set<String>    seen     = new LinkedHashSet<>();
        Deque<Reached> queue    = new ArrayDeque<>();
        int            expanded = 0;
        int            deepest  = 0;
        try {
            List<Object> start = grounded(models, observation);
            seen.add(jointKey(models, start));
            queue.addLast(new Reached(start, List.of()));
            while (!queue.isEmpty()) {
                SearchStatus stopped = clock.stopped(expanded);
                if (stopped != null) {
                    return DiscriminationResult.stopped(stopped,
                                                        clock.effort(expanded, seen.size(), deepest));
                }
                Reached reached = queue.removeFirst();
                int     depth   = reached.path().size();
                if (depth >= limits.maxDepth()) {
                    continue;
                }
                expanded++;
                for (Object action : models.get(0).actions(reached.states().get(0), offered)) {
                    List<Object> next        = stepped(models, reached.states(), action);
                    List<Object> predictions = predicted(models, next);
                    if (!Agreement.holds(predictions)) {
                        return DiscriminationResult.found(
                                extended(reached.path(), action),
                                parting(models, depth, action, predictions),
                                clock.effort(expanded, seen.size(), depth + 1));
                    }
                    if (seen.add(jointKey(models, next))) {
                        deepest = Math.max(deepest, depth + 1);
                        queue.addLast(new Reached(next, extended(reached.path(), action)));
                    }
                }
            }
            return DiscriminationResult.stopped(SearchStatus.EXHAUSTED,
                                                clock.effort(expanded, seen.size(), deepest));
        } catch (ModelException failure) {
            return DiscriminationResult.broken(failure.getMessage(),
                                               clock.effort(expanded, seen.size(), deepest));
        }
    }

    /**
     * Why these candidates cannot be told apart by any search.
     *
     * <p>The same model twice is the case worth naming. A search would report it as observationally
     * equivalent, which is true, useless, and indistinguishable from the finding that two genuinely
     * different theories happen to coincide.</p>
     */
    private static String refused(List<WorldModel> candidates) {
        if (candidates == null || candidates.size() < 2) {
            return "an experiment needs at least two candidate models to tell apart";
        }
        Set<String> digests = new LinkedHashSet<>();
        for (WorldModel candidate : candidates) {
            if (candidate == null) {
                return "one of the candidates is missing, so there is nothing to predict with";
            }
            digests.add(candidate.digest());
        }
        if (digests.size() < 2) {
            return "these candidates are all the same model, so no action can tell them apart";
        }
        return null;
    }

    /**
     * The candidates as models nothing has been asked of yet.
     *
     * <p>Coverage is what a certificate measures against the ledger, and a search touches every rule
     * a model has within a few hundred states. Searching with the agent's own models would spend
     * that measurement on an experiment that has not happened.</p>
     */
    private static List<WorldModel> fresh(List<WorldModel> candidates) {
        List<WorldModel> models = new ArrayList<>();
        for (WorldModel candidate : candidates) {
            models.add(candidate.fresh());
        }
        return models;
    }

    private static List<Object> grounded(List<WorldModel> models, Object observation) {
        List<Object> states = new ArrayList<>();
        for (WorldModel model : models) {
            try {
                states.add(model.parse(observation));
            } catch (GroundingException failure) {
                throw new ModelException("candidate " + model.digest()
                                         + " cannot read the observation the experiment would start "
                                         + "from: " + failure.getMessage(), failure);
            }
        }
        return states;
    }

    private static List<Object> stepped(List<WorldModel> models, List<Object> states,
                                        Object action) {
        List<Object> next = new ArrayList<>();
        for (int at = 0; at < models.size(); at++) {
            next.add(models.get(at).step(states.get(at), action));
        }
        return next;
    }

    private static List<Object> predicted(List<WorldModel> models, List<Object> states) {
        List<Object> predictions = new ArrayList<>();
        for (int at = 0; at < models.size(); at++) {
            predictions.add(models.get(at).predict(states.get(at)));
        }
        return predictions;
    }

    private static Disagreement parting(List<WorldModel> models, int step, Object action,
                                        List<Object> predictions) {
        Map<String, String>       said  = new LinkedHashMap<>();
        Map<String, List<String>> camps = new LinkedHashMap<>();
        for (int at = 0; at < models.size(); at++) {
            String digest     = models.get(at).digest();
            Object prediction = predictions.get(at);
            said.put(digest, Prediction.describe(prediction));
            camps.computeIfAbsent(Agreement.camp(prediction), camp -> new ArrayList<>()).add(digest);
        }
        return new Disagreement(step, action, said, new ArrayList<>(camps.values()));
    }

    private static String jointKey(List<WorldModel> models, List<Object> states) {
        List<Object> keys = new ArrayList<>();
        for (int at = 0; at < models.size(); at++) {
            keys.add(models.get(at).key(states.get(at)));
        }
        return Json.canonical(keys);
    }

    private static List<Object> extended(List<Object> path, Object action) {
        List<Object> longer = new ArrayList<>(path);
        longer.add(action);
        return List.copyOf(longer);
    }
}
