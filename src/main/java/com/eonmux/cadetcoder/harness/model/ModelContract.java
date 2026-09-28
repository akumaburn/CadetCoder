package com.eonmux.cadetcoder.harness.model;

import com.eonmux.cadetcoder.harness.model.lang.Program;

import java.util.ArrayList;
import java.util.List;

/**
 * What a world model has to be, in the one place that both checks it and explains it.
 *
 * <h2>Why the contract is checked before a model is ever run</h2>
 *
 * <p>A model is written by an LLM and loaded from text. If a missing {@code predict} were found on
 * the first prediction rather than at load, the harness would already have committed to a model it
 * cannot use, in the middle of a replay, with a ledger half written. Everything that can be known
 * from the source alone is therefore known before anything runs.</p>
 *
 * <h2>Why the same list is what a model author is told</h2>
 *
 * <p>{@link #render()} is what goes into the prompt. Deriving the explanation from the same list
 * that performs the check is what stops the two drifting apart -- a contract that is described one
 * way and enforced another is how a model ends up rejected for a rule nobody was told about.</p>
 */
public final class ModelContract {

    /**
     * One function a model may or must declare.
     *
     * @param name       what it is called
     * @param parameters what it is given, in order
     * @param required   whether a model without it is refused
     * @param purpose    what it is for, as a model author is told
     */
    public record Signature(String name, List<String> parameters, boolean required,
                            String purpose) {

        /** How many arguments a model must declare it with. */
        public int arity() {
            return parameters.size();
        }

        /** How it reads in a prompt. */
        public String render() {
            return "fn " + name + "(" + String.join(", ", parameters) + ")  -- " + purpose;
        }
    }

    /** The whole contract, in the order a model author meets it. */
    public static final List<Signature> FUNCTIONS = List.of(
            new Signature("parse", List.of("obs"), true,
                          "what state this observation puts the world in; never answer with nothing"),
            new Signature("step", List.of("state", "action"), true,
                          "the state this action leads to; do not change the state you were given"),
            new Signature("predict", List.of("state"), true,
                          "the observation this state should produce, or expect() with the beliefs "
                          + "you are willing to be wrong about"),
            new Signature("is_goal", List.of("state"), true, "whether this state is the one that was asked for"),
            new Signature("actions", List.of("state"), false,
                          "the actions worth trying from this state; omit to use the environment's"),
            new Signature("key", List.of("state"), false,
                          "what makes two states the same place for search; omit to use the state "
                          + "without its hidden fields"),
            new Signature("heuristic", List.of("state"), false,
                          "an estimate of the steps left, never an overestimate"),
            new Signature("flags", List.of("state"), false,
                          "the flags this state should raise, such as {\"goal\": true}; only the "
                          + "ones you name are checked against what really happened"),
            new Signature("settings", List.of(), false,
                          "settings this model wants, such as {\"handles_reset\": true}"));

    private ModelContract() {
    }

    /**
     * Refuses a model that cannot honour the contract, before anything is run against it.
     *
     * @param program the model as parsed
     * @throws ModelException if a required function is missing or one has the wrong shape
     */
    public static void check(Program program) {
        List<String> missing = new ArrayList<>();
        List<String> wrong   = new ArrayList<>();
        for (Signature signature : FUNCTIONS) {
            if (!program.defines(signature.name())) {
                if (signature.required()) {
                    missing.add(signature.name());
                }
            } else if (program.arity(signature.name()) != signature.arity()) {
                wrong.add(signature.name() + " takes " + signature.arity() + " arguments, not "
                          + program.arity(signature.name()));
            }
        }
        if (!missing.isEmpty()) {
            throw new ModelException("this model is missing " + String.join(", ", missing));
        }
        if (!wrong.isEmpty()) {
            throw new ModelException("this model does not fit the contract: "
                                     + String.join("; ", wrong));
        }
    }

    /** The contract as a model author is told it. */
    public static String render() {
        List<String> lines = new ArrayList<>();
        for (Signature signature : FUNCTIONS) {
            lines.add((signature.required() ? "required  " : "optional  ") + signature.render());
        }
        return String.join("\n", lines);
    }
}
