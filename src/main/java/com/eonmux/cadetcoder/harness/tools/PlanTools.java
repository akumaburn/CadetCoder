package com.eonmux.cadetcoder.harness.tools;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.certify.Certificate;
import com.eonmux.cadetcoder.harness.certify.Certification;
import com.eonmux.cadetcoder.harness.env.Reversibility;
import com.eonmux.cadetcoder.harness.env.StepOutcome;
import com.eonmux.cadetcoder.harness.fit.Example;
import com.eonmux.cadetcoder.harness.fit.Fit;
import com.eonmux.cadetcoder.harness.fit.FitLimits;
import com.eonmux.cadetcoder.harness.ledger.Transition;
import com.eonmux.cadetcoder.harness.model.ModelException;
import com.eonmux.cadetcoder.harness.model.ModelRegistry;
import com.eonmux.cadetcoder.harness.model.WorldModel;
import com.eonmux.cadetcoder.harness.plan.Discrimination;
import com.eonmux.cadetcoder.harness.plan.DiscriminationResult;
import com.eonmux.cadetcoder.harness.plan.PlanAudit;
import com.eonmux.cadetcoder.harness.plan.Planner;
import com.eonmux.cadetcoder.harness.plan.SearchLimits;
import com.eonmux.cadetcoder.harness.plan.SearchResult;
import com.eonmux.cadetcoder.harness.plan.Simulation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Everything the agent can work out inside a theory without touching the world.
 *
 * <h2>Why planning is refused outside a green, current model</h2>
 *
 * <p>A plan is a claim that a sequence of actions leads somewhere, and it is only as good as the
 * theory it was searched in. A model the ledger has already contradicted will happily produce a
 * plan, and that plan is a fiction with a step count attached -- the most expensive kind of answer,
 * because it looks exactly like a good one. Refusing costs a turn; acting on it costs the run.</p>
 *
 * <h2>Why the search leaves out what cannot be undone</h2>
 *
 * <p>A plan is meant to be committed whole. If the shortest route to the goal runs through a
 * deletion, the search that found it has quietly decided something the agent never asked for. What
 * cannot be undone stays out of the search unless the call says otherwise, so an irreversible step
 * is always something the agent chose rather than something the planner found.</p>
 */
final class PlanTools {

    /** Letting the model's own heuristic guide the search, when it offers one. */
    private static final String AUTO = "auto";

    /** Ignoring any heuristic, for the shortest plan there is. */
    private static final String BREADTH = "breadth";

    /** How the wall clock is written in a tool call, against how a search limit is written. */
    private static final int MILLIS_PER_SECOND = 1_000;

    /** How much of a plan an answer shows before it stops being worth reading. */
    private static final int PLAN_SHOWN = 2_000;

    /** How much of a simulated run an answer shows. */
    private static final int SIMULATION_SHOWN = 4_000;

    private PlanTools() {
    }

    /** Searches a green model for a way to arrive somewhere, and keeps what it finds. */
    static String plan(ToolSession session, ToolArgs args) {
        WorldModel   model       = session.model(args.text("model", ModelRegistry.LATEST));
        Certificate  certificate = green(session, model);
        String       goal        = args.has("goal") ? model.question(args.text("goal")) : null;
        String       method      = args.text("method", AUTO).trim();
        Object       observation = session.gate().observation();
        Object       start       = model.regroundedOn(observation, certificate.finalState());
        List<Object> offered     = offered(session, observation,
                                           args.flag("allow_irreversible", false));

        SearchResult found = searched(model, start, offered, arrival(model, goal), method,
                                      limits(args, "max_depth", SearchLimits.ANY_DEPTH));
        if (!found.found()) {
            return found.summary();
        }
        Simulation simulation = Planner.simulate(model, start, found.plan());
        PlanAudit  audit      = Planner.audit(simulation, certificate.coverage().hit());
        PlanRecord kept       = session.plans().save(model.digest(), goal, found.plan(), audit);

        return String.join(System.lineSeparator(),
                           found.audited(audit).summary(),
                           kept.render(),
                           Compact.value(found.plan(), PLAN_SHOWN),
                           "commit it with commit(plan=\"" + kept.name() + "\")");
    }

    /** Runs actions through a model from where the world is, without taking any of them. */
    static String simulate(ToolSession session, ToolArgs args) {
        WorldModel  model       = session.model(args.text("model", ModelRegistry.LATEST));
        Certificate certificate = session.certified(model, false);
        Object      start       = model.regroundedOn(session.gate().observation(),
                                                     certificate.finalState());

        Simulation simulation = Planner.simulate(model, start, args.values("actions"));
        PlanAudit  audit      = Planner.audit(simulation, certificate.coverage().hit());
        String     answer     = "simulate " + model.digest() + " from where the world is now:"
                                + System.lineSeparator()
                                + Compact.indented(Compact.clipped(simulation.render(),
                                                                   SIMULATION_SHOWN), "  ");
        return audit.clean() ? answer
                             : answer + System.lineSeparator() + "  " + audit.render();
    }

    /** Finds the cheapest experiment that tells candidate theories apart. */
    static String discriminate(ToolSession session, ToolArgs args) {
        List<WorldModel> candidates = new ArrayList<>();
        for (String reference : args.texts("models")) {
            candidates.add(session.model(reference));
        }
        if (candidates.size() < 2) {
            throw new IllegalArgumentException("an experiment needs at least two candidate models "
                                               + "to tell apart, and this call named "
                                               + candidates.size());
        }
        Object observation = session.gate().observation();
        DiscriminationResult result =
                Discrimination.between(candidates, observation,
                                       session.env().actionSpace(observation),
                                       limits(args, "horizon", Discrimination.STANDARD_HORIZON));
        if (!result.found()) {
            return result.summary();
        }
        int steps = result.actions().size();
        return String.join(System.lineSeparator(),
                           result.summary(),
                           Compact.value(result.actions(), PLAN_SHOWN),
                           "commit " + (steps == 1 ? "this action" : "these " + steps + " actions")
                           + " and what the world does refutes at least one candidate");
    }

    /**
     * Fits the simplest rules that separate the transitions the environment flagged.
     *
     * <h2>Why a reset is not an example</h2>
     *
     * <p>A reset carries no flags by construction, so counting it would add a negative example
     * about a state the environment never judged -- and its state is a fresh grounding rather than
     * anything the model derived. Every other transition is one the world answered, and those are
     * the ones there is something to learn from.</p>
     */
    static String fit(ToolSession session, ToolArgs args) {
        WorldModel model    = session.model(args.text("model", ModelRegistry.LATEST));
        String     features = model.question(args.text("features"));
        String     label    = args.text("label", StepOutcome.GOAL_FLAG);

        List<Object>     states  = Certification.states(model, session.ledger());
        List<Transition> record  = session.ledger().all();
        List<Object>     kept    = new ArrayList<>();
        List<Boolean>    labels  = new ArrayList<>();
        for (int at = 0; at < states.size() && at < record.size(); at++) {
            if (states.get(at) == null || record.get(at).isReset()) {
                continue;
            }
            kept.add(states.get(at));
            labels.add(Json.truthy(record.get(at).flags().get(label)));
        }
        if (kept.isEmpty()) {
            return "there is nothing to fit yet: the ledger holds no transition this model can "
                   + "read into a state";
        }
        List<Example> examples = Fit.examples(kept, labels,
                                              state -> described(model, features, state));
        return Fit.predicate(examples, FitLimits.standard()
                                               .toAtoms(args.integer("max_atoms",
                                                                     FitLimits.STANDARD_MAX_ATOMS)))
                  .summary();
    }

    private static SearchResult searched(WorldModel model, Object start, List<Object> offered,
                                         Predicate<Object> arrival, String method,
                                         SearchLimits limits) {
        return switch (method) {
            case AUTO -> Planner.search(model, start, offered, arrival, limits);
            case BREADTH -> Planner.bfs(model, start, offered, arrival, limits);
            default -> throw new IllegalArgumentException("there is no " + method + " search; "
                                                          + "method is " + AUTO + " or " + BREADTH);
        };
    }

    /**
     * What counts as arriving.
     *
     * <p>The question is asked of a copy of the model rather than the one the search runs, so that
     * a goal called on every state the search reaches cannot leave its coverage on the model the
     * certificate is about.</p>
     */
    private static Predicate<Object> arrival(WorldModel model, String goal) {
        if (goal == null) {
            return null;
        }
        WorldModel asking = model.fresh();
        return state -> Json.truthy(asking.ask(goal, state));
    }

    private static Certificate green(ToolSession session, WorldModel model) {
        Certificate certificate = session.certified(model, false);
        if (certificate.green()) {
            return certificate;
        }
        throw new IllegalArgumentException("this model does not survive the ledger, so a plan "
                                           + "found inside it would rest on something reality has "
                                           + "already contradicted; correct it and certify again"
                                           + System.lineSeparator() + certificate.summary());
    }

    private static List<Object> offered(ToolSession session, Object observation,
                                        boolean allowIrreversible) {
        List<Object> space = session.env().actionSpace(observation);
        if (space == null || allowIrreversible) {
            return space;
        }
        List<Object> allowed = new ArrayList<>();
        for (Object action : space) {
            if (session.env().reversibility(action) != Reversibility.IRREVERSIBLE) {
                allowed.add(action);
            }
        }
        return List.copyOf(allowed);
    }

    private static SearchLimits limits(ToolArgs args, String depth, int deepest) {
        SearchLimits standard = SearchLimits.standard();
        double       seconds  = args.decimal("max_seconds", standard.maxMillis()
                                                            / (double) MILLIS_PER_SECOND);
        return new SearchLimits(args.integer("max_nodes", standard.maxNodes()),
                                Math.round(seconds * MILLIS_PER_SECOND),
                                args.integer(depth, deepest));
    }

    private static Map<String, Object> described(WorldModel model, String features, Object state) {
        Object answered = model.ask(features, state);
        if (!(answered instanceof Map)) {
            throw new ModelException(features + " answered with " + Json.readable(answered)
                                     + " rather than the named values to fit over");
        }
        Map<String, Object> named = new LinkedHashMap<>();
        ((Map<?, ?>) answered).forEach((name, value) -> named.put(String.valueOf(name), value));
        return named;
    }
}
