package com.eonmux.cadetcoder.harness.tools;

/**
 * The name of every tool, written once.
 *
 * <h2>Why the names are not simply spelled out where they are used</h2>
 *
 * <p>A tool's name appears in three places that have to agree: the catalog the agent reads, the
 * dispatch that runs it, and whatever else in the harness reaches for it by name. Spelled out at
 * each, a name can be changed in one and left in the others, and the failure is a tool the agent is
 * told about and the harness refuses -- or worse, one the harness runs and never described. Named
 * once, the compiler is what keeps the three in step.</p>
 */
public final class ToolName {

    /** Where the world is now. */
    public static final String OBSERVE = "observe";

    /** The last few things that really happened. */
    public static final String LEDGER_TAIL = "ledger_tail";

    /** One transition in full. */
    public static final String LEDGER_GET = "ledger_get";

    /** Save a theory and replay the ledger through it. */
    public static final String WRITE_MODEL = "write_model";

    /** Replay the ledger through a theory again. */
    public static final String CERTIFY = "certify";

    /** Which of a theory's rules reality has never exercised. */
    public static final String COVERAGE = "coverage";

    /** Search a theory for a way to arrive somewhere. */
    public static final String PLAN = "plan";

    /** Run actions through a theory without taking them. */
    public static final String SIMULATE = "simulate";

    /** Design the cheapest experiment that tells theories apart. */
    public static final String DISCRIMINATE = "discriminate";

    /** Fit the simplest rule the ledger supports. */
    public static final String FIT_PREDICATE = "fit_predicate";

    /** The only way to act. */
    public static final String COMMIT = "commit";

    /** Start a new episode. */
    public static final String RESET = "reset";

    /** Record something now held to be true. */
    public static final String BELIEF_ADD = "belief_add";

    /** Mark a belief dead. */
    public static final String BELIEF_REFUTE = "belief_refute";

    /** Record something not known yet. */
    public static final String BELIEF_QUESTION = "belief_question";

    /** Answer a question that was recorded. */
    public static final String BELIEF_RESOLVE = "belief_resolve";

    /** Everything held and everything still open. */
    public static final String BELIEFS = "beliefs";

    /** Add a line to the working notes. */
    public static final String NOTES_APPEND = "notes_append";

    /** Read the working notes back. */
    public static final String NOTES_READ = "notes_read";

    /** Every version ever written. */
    public static final String MODELS = "models";

    /** A version as it was written. */
    public static final String MODEL_SOURCE = "model_source";

    /** What the run has spent. */
    public static final String BUDGET = "budget";

    private ToolName() {
    }
}
