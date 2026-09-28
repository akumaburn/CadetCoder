package com.eonmux.cadetcoder.harness.model;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.model.lang.Coverage;
import com.eonmux.cadetcoder.harness.model.lang.ExecutionLimits;
import com.eonmux.cadetcoder.harness.model.lang.Interpreter;
import com.eonmux.cadetcoder.harness.model.lang.ModelRuntimeException;
import com.eonmux.cadetcoder.harness.model.lang.ModelSyntaxException;
import com.eonmux.cadetcoder.harness.model.lang.Program;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The agent's theory of its environment, in the only form that can be argued with.
 *
 * <h2>Why a model is a program and not a prompt</h2>
 *
 * <p>Beliefs written in prose cannot be replayed, cannot be measured and cannot be shown to be
 * wrong. A model is a program, so the ledger can be run through it transition by transition, the
 * rules it states can be counted, and a prediction it makes can be contradicted by what actually
 * happened. Everything downstream refers to it by {@link #digest()} rather than by name, so no
 * certificate can drift onto a source that has since been edited.</p>
 *
 * <h2>Why every answer is checked</h2>
 *
 * <p>Each contract function either answers or fails here. A {@code parse} that returns nothing is a
 * {@link GroundingException} rather than an empty state, because an empty state is a lie the rest
 * of the harness has no way to detect: predictions would be made about a world the agent is not in,
 * the ledger would fill with steps that were never really checked, and certification would pass.</p>
 *
 * <h2>One model, one measurement</h2>
 *
 * <p>Coverage accumulates over the life of this object, because the question it answers -- which of
 * the model's rules reality has put to the test -- is about a whole replay rather than one call.</p>
 */
public final class WorldModel {

    /** Which setting a model uses to say it wants resets handed to {@code step}. */
    private static final String HANDLES_RESET = "handles_reset";

    private final Program             program;
    private final Interpreter         interpreter;
    private final ExecutionLimits     limits;
    private final Map<String, Object> settings;

    private WorldModel(Program program, Interpreter interpreter, ExecutionLimits limits,
                       Map<String, Object> settings) {
        this.program     = program;
        this.interpreter = interpreter;
        this.limits      = limits;
        this.settings    = settings;
    }

    /**
     * Reads a model and refuses it now if it cannot honour the contract.
     *
     * @param source the model as written
     * @return the loaded model
     * @throws ModelException if the source does not fit {@link ModelContract}
     */
    public static WorldModel load(String source) {
        return load(source, ExecutionLimits.standard());
    }

    /**
     * Reads a model under limits of the caller's choosing.
     *
     * @param source the model as written
     * @param limits what stops a model that does not stop by itself
     * @return the loaded model
     * @throws ModelException if the source does not fit {@link ModelContract}
     */
    public static WorldModel load(String source, ExecutionLimits limits) {
        Program program = read(source);
        ModelContract.check(program);
        Interpreter interpreter = new Interpreter(program, limits);
        return new WorldModel(program, interpreter, limits, readSettings(program, interpreter));
    }

    /**
     * A source that will not parse is a model that failed, not a separate kind of problem.
     *
     * <p>Everything that loads a model is dealing with text an LLM wrote, and has exactly one thing
     * to do about any of the ways that text can be wrong.</p>
     */
    private static Program read(String source) {
        try {
            return Program.parse(source);
        } catch (ModelSyntaxException failure) {
            throw new ModelException("this model does not parse: " + failure.getMessage(), failure);
        }
    }

    /**
     * The same model with nothing measured yet.
     *
     * <p>Coverage accumulates over the life of a model, because the question it answers is about a
     * whole replay. A replay therefore starts from a model that has not been asked anything yet,
     * rather than reaching into one that has and clearing it -- a certificate reporting coverage
     * that included whatever the agent happened to do before certifying would be measuring the
     * agent, not the ledger.</p>
     *
     * @return an equal model that has exercised no rules
     */
    public WorldModel fresh() {
        return load(source(), limits);
    }

    /** What stops this model when it does not stop by itself. */
    public ExecutionLimits limits() {
        return limits;
    }

    /** The model as parsed. */
    public Program program() {
        return program;
    }

    /** The model exactly as it was written. */
    public String source() {
        return program.source();
    }

    /** What the model is called: the digest of its source. */
    public String digest() {
        return program.digest();
    }

    /** The fields the model says one observation cannot recover. */
    public Set<String> hidden() {
        return program.hidden();
    }

    /** The settings the model asked for. */
    public Map<String, Object> settings() {
        return settings;
    }

    /** Whether the model wants a reset handed to {@code step} rather than handled for it. */
    public boolean handlesReset() {
        return Json.truthy(settings.get(HANDLES_RESET));
    }

    /** Which of the model's rules have been exercised so far. */
    public Coverage coverage() {
        return interpreter.coverage();
    }

    /** How much theory the model carries. */
    public ModelComplexity complexity() {
        return ModelComplexity.of(program);
    }

    /**
     * What state an observation puts the world in.
     *
     * @param observation what the environment reported
     * @return the state the model says that means
     * @throws GroundingException if the model cannot say, for any reason at all
     */
    public Object parse(Object observation) {
        Object state = ground("parse", observation);
        if (state == null) {
            throw new GroundingException(
                    "parse answered with nothing; a model that cannot ground an observation has to "
                    + "say so rather than fall back to an empty state");
        }
        return state;
    }

    /**
     * What an observation puts the world in, keeping what one observation cannot show.
     *
     * <p>Grounding on its own throws away everything the model invented to remember something --
     * whether it is carrying a key, whether a build has already run -- because no observation
     * mentions those fields. Every caller that keeps a state across a real transition therefore
     * needs the same two-part answer: what reality says, plus what only the previous state knew.
     * Having it in one place is what stops the certifier and the commit gate from disagreeing about
     * what the model remembers.</p>
     *
     * @param observation what the environment reported
     * @param remembered  the state before, whose hidden fields are carried across; may be {@code null}
     * @return the re-grounded state
     * @throws GroundingException if the model cannot ground the observation
     */
    public Object regroundedOn(Object observation, Object remembered) {
        Object fresh = parse(observation);
        if (hidden().isEmpty() || !(fresh instanceof Map) || !(remembered instanceof Map)) {
            return fresh;
        }
        Map<?, ?>           before  = (Map<?, ?>) remembered;
        Map<String, Object> carried = new LinkedHashMap<>();
        ((Map<?, ?>) fresh).forEach((name, value) -> carried.put(String.valueOf(name), value));
        for (String name : hidden()) {
            if (before.containsKey(name)) {
                carried.put(name, before.get(name));
            }
        }
        return carried;
    }

    /**
     * The state an action leads to.
     *
     * @param state  where the world is
     * @param action what is done
     * @return where the model says that leads
     * @throws ModelException if the model cannot say
     */
    public Object step(Object state, Object action) {
        Object next = invoke("step", state, action);
        if (next == null) {
            throw new ModelException("step answered with nothing");
        }
        return next;
    }

    /**
     * The observation a state should produce, or the beliefs the model will stand behind.
     *
     * @param state where the world is
     * @return an observation, or an {@link com.eonmux.cadetcoder.harness.spec.Expect}
     * @throws ModelException if the model cannot say
     */
    public Object predict(Object state) {
        return invoke("predict", state);
    }

    /**
     * Whether a state is the one that was asked for.
     *
     * @param state where the world is
     * @return the model's verdict
     * @throws ModelException if the model cannot say
     */
    public boolean isGoal(Object state) {
        return Json.truthy(invoke("is_goal", state));
    }

    /**
     * The actions worth trying from a state.
     *
     * @param state    where the world is
     * @param offered  what the environment allows, or {@code null} when it allows anything
     * @return the actions to search over
     * @throws ModelException if neither the model nor the environment can name any
     */
    public List<Object> actions(Object state, List<Object> offered) {
        if (!program.defines("actions")) {
            if (offered == null) {
                throw new ModelException(
                        "this model declares no actions and the environment's action space is open, "
                        + "so there is nothing to search over");
            }
            return offered;
        }
        Object answered = invoke("actions", state);
        if (!(answered instanceof List)) {
            throw new ModelException("actions answered with " + describe(answered)
                                     + " rather than a list of actions");
        }
        return List.copyOf((List<?>) answered);
    }

    /**
     * What makes two states the same place for search.
     *
     * @param state where the world is
     * @return the identity of that state
     * @throws ModelException if the model declares a {@code key} it cannot compute
     */
    public String key(Object state) {
        return program.defines("key") ? Json.canonical(invoke("key", state))
                                      : observableKey(state);
    }

    /**
     * The state's identity with the fields one observation cannot show left out.
     *
     * <p>Two states that look the same from outside are the same place to a planner. A field the
     * model invented to remember something -- whether it is carrying a key, whether a build has run
     * -- would otherwise make search expand the same position again and again.</p>
     *
     * @param state where the world is
     * @return its observable identity
     */
    public String observableKey(Object state) {
        if (hidden().isEmpty() || !(state instanceof Map)) {
            return Json.canonical(state);
        }
        Map<String, Object> visible = new LinkedHashMap<>();
        ((Map<?, ?>) state).forEach((name, value) -> {
            if (!hidden().contains(String.valueOf(name))) {
                visible.put(String.valueOf(name), value);
            }
        });
        return Json.canonical(visible);
    }

    /**
     * The model's estimate of how far a state is from the goal.
     *
     * @param state where the world is
     * @return the estimate, or {@code null} when the model offers none
     * @throws ModelException if the model declares a {@code heuristic} that does not answer with a number
     */
    public Double heuristic(Object state) {
        if (!program.defines("heuristic")) {
            return null;
        }
        Object estimate = invoke("heuristic", state);
        if (!(estimate instanceof Number number)) {
            throw new ModelException("heuristic answered with " + describe(estimate)
                                     + " rather than a number");
        }
        return number.doubleValue();
    }

    /**
     * The flags the model says a state should raise.
     *
     * <p>A model that names none is not asserting that none are raised: only the flags it names are
     * checked, so a model may make the goal predicate falsifiable without having to account for
     * every other signal the environment reports.</p>
     *
     * @param state where the world is
     * @return the flags the model commits to, empty when it commits to none
     * @throws ModelException if the model declares {@code flags} and it does not answer with an object
     */
    public Map<String, Object> flags(Object state) {
        if (!program.defines("flags")) {
            return Map.of();
        }
        Object answered = invoke("flags", state);
        if (!(answered instanceof Map)) {
            throw new ModelException("flags answered with " + describe(answered)
                                     + " rather than an object of flags");
        }
        return asObject(answered);
    }

    /**
     * Checks that a question is one this model can be asked, without asking it.
     *
     * <h2>Why a goal and a feature set are functions of the model</h2>
     *
     * <p>A plan needs somewhere to get to and a fit needs something to say about a state, and
     * neither is a question the environment has a general answer for. Letting the agent write a
     * fragment of code at the call site would put a second, unversioned theory beside the one that
     * has been certified; naming a function of the model instead puts the goal and the feature
     * vocabulary inside the thing the ledger tests and the digest identifies.</p>
     *
     * <h2>Why the check is separate from the asking</h2>
     *
     * <p>A search asks its goal thousands of times and a fit asks its features once per recorded
     * state, so both find out that the name was wrong somewhere deep inside the work, where it
     * reads as a failed search rather than a question nobody could answer. Both look the name up
     * first, and an empty ledger or an exhausted search then tells the agent the same thing.</p>
     *
     * @param function what would be asked; a function this model defines, taking one state
     * @return that name, with its surrounding space removed
     * @throws ModelException if the model defines no such function, or defines one of another shape
     */
    public String question(String function) {
        String name = function == null ? "" : function.trim();
        if (name.isEmpty()) {
            throw new ModelException("a question has to name a function of the model; this one "
                                     + "defines " + defines());
        }
        if (!program.defines(name)) {
            throw new ModelException("this model defines no " + name + "; it defines " + defines());
        }
        int arity = program.arity(name);
        if (arity != 1) {
            throw new ModelException(name + " takes " + arity + " argument" + (arity == 1 ? "" : "s")
                                     + ", and a question about a state has to take one");
        }
        return name;
    }

    /**
     * Asks one of the model's own functions about a state.
     *
     * @param function what to ask; a function this model defines, taking one state
     * @param state    where the world is
     * @return whatever that function answered
     * @throws ModelException if the model defines no such function, or defines one of another shape
     */
    public Object ask(String function, Object state) {
        return invoke(question(function), state);
    }

    private String defines() {
        return String.join(", ", program.functionNames());
    }

    private static Map<String, Object> readSettings(Program program, Interpreter interpreter) {
        if (!program.defines("settings")) {
            return Map.of();
        }
        Object answered = call(interpreter, "settings", arguments());
        if (!(answered instanceof Map)) {
            throw new ModelException("settings answered with " + describe(answered)
                                     + " rather than an object of settings");
        }
        return Json.frozenMap(asObject(answered));
    }

    @SuppressWarnings ("unchecked")
    private static Map<String, Object> asObject(Object value) {
        return (Map<String, Object>) value;
    }

    private Object ground(String function, Object... values) {
        try {
            return call(interpreter, function, arguments(values));
        } catch (ModelException failure) {
            throw new GroundingException(failure.getMessage(), failure.getCause());
        }
    }

    private Object invoke(String function, Object... values) {
        return call(interpreter, function, arguments(values));
    }

    private static Object call(Interpreter interpreter, String function, List<Object> arguments) {
        try {
            return interpreter.call(function, arguments);
        } catch (ModelRuntimeException failure) {
            throw new ModelException(function + " failed: " + failure.getMessage(), failure);
        }
    }

    /** {@code List.of} refuses a null, and a model may legitimately be handed one. */
    private static List<Object> arguments(Object... values) {
        List<Object> arguments = new ArrayList<>(values.length);
        Collections.addAll(arguments, values);
        return arguments;
    }

    private static String describe(Object value) {
        return value == null ? "nothing" : Json.canonical(value);
    }
}
