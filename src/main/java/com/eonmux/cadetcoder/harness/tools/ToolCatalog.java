package com.eonmux.cadetcoder.harness.tools;

import com.eonmux.cadetcoder.harness.model.ModelRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.eonmux.cadetcoder.harness.tools.ToolParam.optional;
import static com.eonmux.cadetcoder.harness.tools.ToolParam.required;
import static com.eonmux.cadetcoder.harness.tools.ToolType.DECIMAL;
import static com.eonmux.cadetcoder.harness.tools.ToolType.FLAG;
import static com.eonmux.cadetcoder.harness.tools.ToolType.INTEGER;
import static com.eonmux.cadetcoder.harness.tools.ToolType.LIST;
import static com.eonmux.cadetcoder.harness.tools.ToolType.TEXT;

/**
 * Everything the agent can do, in the one list that both describes it and checks it.
 *
 * <h2>Why the catalog is prose rather than an API tool array</h2>
 *
 * <p>The completion layer this harness runs on is text in, text out: a request carries a system
 * prompt and a user prompt, and a reply comes back as a string. A tool array on the wire is
 * therefore not available, and pretending otherwise would mean an agent that only works against one
 * provider. {@link #render()} is what the agent reads, and {@link ToolArgs} enforces the same list
 * on the way back, so the surface is identical whichever model is behind it.</p>
 *
 * <h2>Why nothing here runs code the harness did not write</h2>
 *
 * <p>The reference harness has a scratch interpreter the agent can send arbitrary programs to. That
 * is a second, unrecorded way to compute -- and the things it was used for are all things the
 * harness already does properly: {@code fit_predicate} fits a rule to the ledger, {@code simulate}
 * runs a plan through a model, {@code discriminate} designs an experiment, and {@code ledger_get}
 * gives back a transition in full. Every one of those answers is derived from the ledger and can be
 * replayed; a scratch program's answer is a number the agent has to be trusted about. The model
 * language is the only code that runs here, and it runs only as a model.</p>
 */
public final class ToolCatalog {

    /** How many transitions {@code ledger_tail} shows when the call does not say. */
    public static final int TAIL_SHOWN = 10;

    private static final String WHICH_MODEL =
            "which version: a digest, a unique prefix of one, or " + ModelRegistry.LATEST;

    /** Every tool, in the order an agent meets them: look, theorise, plan, act, remember. */
    public static final List<ToolSchema> TOOLS = List.of(
            ToolSchema.of(ToolName.OBSERVE,
                          "Where the world is now, with the ledger, the budget and the standing "
                          + "model underneath it."),

            ToolSchema.of(ToolName.LEDGER_TAIL,
                          "The last few things that really happened, newest last.",
                          optional("n", INTEGER, "how many transitions back to show; "
                                                 + TAIL_SHOWN + " by default"),
                          optional("with_obs", FLAG,
                                   "whether to show what could be seen after each one")),

            ToolSchema.of(ToolName.LEDGER_GET,
                          "One transition in full: what could be seen before it, what was done, "
                          + "what could be seen after, and what the environment reported.",
                          required("index", INTEGER, "which transition of the ledger")),

            ToolSchema.of(ToolName.WRITE_MODEL,
                          "Save a theory of this world and replay the whole ledger through it. "
                          + "Answers with the version's name, what the replay found, and which of "
                          + "the model's rules reality has never exercised.",
                          required("source", TEXT, "the model, written in the model language"),
                          optional("note", TEXT, "why this version was written")),

            ToolSchema.of(ToolName.CERTIFY,
                          "Replay the ledger through a model again, which is what a certificate "
                          + "needs after anything else has happened.",
                          optional("model", TEXT, WHICH_MODEL),
                          optional("strict_grounding", FLAG,
                                   "whether a rule and a representation that disagree end the "
                                   + "replay rather than only being reported")),

            ToolSchema.of(ToolName.COVERAGE,
                          "Which rules of a certified model no real transition has ever exercised. "
                          + "A green replay says nothing at all about these.",
                          optional("model", TEXT, WHICH_MODEL)),

            ToolSchema.of(ToolName.PLAN,
                          "Search inside a green, current model for a sequence of actions that "
                          + "arrives somewhere. Irreversible actions are left out of the search "
                          + "unless the call asks for them.",
                          optional("model", TEXT, WHICH_MODEL),
                          optional("goal", TEXT,
                                   "a one-argument function this model defines; its is_goal by "
                                   + "default"),
                          optional("method", TEXT,
                                   "auto to let the model's heuristic guide the search, or breadth "
                                   + "for the shortest plan there is"),
                          optional("max_nodes", INTEGER, "how many states the search may expand"),
                          optional("max_seconds", DECIMAL, "how long the search may run"),
                          optional("max_depth", INTEGER, "how long a plan may be"),
                          optional("allow_irreversible", FLAG,
                                   "whether the search may use actions that cannot be undone")),

            ToolSchema.of(ToolName.SIMULATE,
                          "Run actions through a model from where the world is now, and see what "
                          + "it predicts at each step and which steps reach untested rules.",
                          optional("model", TEXT, WHICH_MODEL),
                          required("actions", LIST, "the actions to run through the model")),

            ToolSchema.of(ToolName.DISCRIMINATE,
                          "Find the shortest sequence of actions on which two or more candidate "
                          + "models predict different things. This is how to design an experiment "
                          + "instead of guessing which theory is right.",
                          required("models", LIST, "the versions to tell apart"),
                          optional("horizon", INTEGER, "how far out an experiment may be"),
                          optional("max_nodes", INTEGER, "how many states the search may expand"),
                          optional("max_seconds", DECIMAL, "how long the search may run")),

            ToolSchema.of(ToolName.FIT_PREDICATE,
                          "Fit the simplest rules that separate the transitions the environment "
                          + "flagged from the ones it did not, over values a function of your own "
                          + "model reads off each state.",
                          optional("model", TEXT, WHICH_MODEL),
                          required("features", TEXT,
                                   "a one-argument function this model defines, answering with the "
                                   + "named values to fit over"),
                          optional("label", TEXT,
                                   "which flag the environment raises counts as a positive "
                                   + "example; goal by default"),
                          optional("max_atoms", INTEGER,
                                   "how many comparisons one rule may be made of")),

            ToolSchema.of(ToolName.COMMIT,
                          "THE ONLY WAY TO ACT. With a model, every step is checked against what "
                          + "that model predicted and execution stops at the first surprise, and a "
                          + "step that reaches a rule reality has never exercised cuts the plan "
                          + "off there. Without a model it is a blind probe of a few actions.",
                          optional("actions", LIST, "the actions to take"),
                          optional("model", TEXT, WHICH_MODEL),
                          optional("plan", TEXT, "the name of a plan the planner saved"),
                          optional("allow_untested", FLAG,
                                   "whether this is deliberately an experiment and may run past an "
                                   + "untested rule"),
                          optional("note", TEXT, "a line for whoever reads the ledger later")),

            ToolSchema.of(ToolName.RESET,
                          "Start a new episode. It costs, it is recorded like anything else, and "
                          + "every certificate goes stale.",
                          optional("note", TEXT, "why the run started over")),

            ToolSchema.of(ToolName.BELIEF_ADD,
                          "Record something you now hold to be true about this world, with the "
                          + "transitions that convinced you.",
                          required("text", TEXT, "what you believe"),
                          optional("evidence", LIST, "the ledger indices that support it"),
                          optional("tags", LIST, "words to find it by later"),
                          optional("supersedes", TEXT,
                                   "the belief this one replaces, if it replaces one")),

            ToolSchema.of(ToolName.BELIEF_REFUTE,
                          "Mark a belief dead, naming what killed it.",
                          required("id", TEXT, "which belief"),
                          required("evidence", LIST, "the ledger indices that refuted it"),
                          required("reason", TEXT, "why those indices refute it")),

            ToolSchema.of(ToolName.BELIEF_QUESTION,
                          "Record something you do not know yet and mean to settle by experiment.",
                          required("text", TEXT, "the question"),
                          optional("tags", LIST, "words to find it by later")),

            ToolSchema.of(ToolName.BELIEF_RESOLVE,
                          "Answer a question you opened.",
                          required("id", TEXT, "which question"),
                          required("answer", TEXT, "what the answer turned out to be"),
                          optional("evidence", LIST, "the ledger indices that settled it")),

            ToolSchema.of(ToolName.BELIEFS,
                          "What you still hold, what you are still asking, and what has already "
                          + "been refuted."),

            ToolSchema.of(ToolName.NOTES_APPEND,
                          "Add a line to your working notes. Notes survive compaction only through "
                          + "their tail, so put conclusions here, not transcripts.",
                          required("text", TEXT, "the line to add")),

            ToolSchema.of(ToolName.NOTES_READ,
                          "Read the end of your working notes.",
                          optional("tail_chars", INTEGER,
                                   "how much of the end to read; " + Notes.STANDARD_TAIL
                                   + " by default")),

            ToolSchema.of(ToolName.MODELS,
                          "Every version you have written, with what its last replay established."),

            ToolSchema.of(ToolName.MODEL_SOURCE,
                          "A version as you wrote it.",
                          optional("model", TEXT, WHICH_MODEL)),

            ToolSchema.of(ToolName.BUDGET,
                          "What this run has spent, and whether it has stopped learning."));

    private static final Map<String, ToolSchema> BY_NAME = byName();

    private ToolCatalog() {
    }

    /**
     * The tool of a given name.
     *
     * @param name what the agent called
     * @return the tool, or {@code null} when there is no such tool
     */
    public static ToolSchema of(String name) {
        return name == null ? null : BY_NAME.get(name);
    }

    /** Every tool, named. */
    public static List<String> names() {
        return List.copyOf(BY_NAME.keySet());
    }

    /** The whole surface, as the agent is told it. */
    public static String render() {
        List<String> written = new ArrayList<>(TOOLS.size());
        for (ToolSchema schema : TOOLS) {
            written.add(schema.render());
        }
        return String.join(System.lineSeparator(), written);
    }

    private static Map<String, ToolSchema> byName() {
        Map<String, ToolSchema> named = new LinkedHashMap<>();
        for (ToolSchema schema : TOOLS) {
            if (named.put(schema.name(), schema) != null) {
                throw new IllegalStateException("two tools are called " + schema.name());
            }
        }
        return Map.copyOf(named);
    }
}
