package com.eonmux.cadetcoder.harness.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * A prediction made of named beliefs rather than of an exact observation.
 *
 * <h2>Why a prediction is not always a value</h2>
 *
 * <p>A world model earns its keep by being contradictable, and the sharpest way to contradict one
 * is to have it name the next observation exactly. That works in a puzzle and fails everywhere
 * else: a build log carries durations, a test run carries a seed, a listing carries timestamps. A
 * model asked to predict those exactly is wrong at every step for reasons that say nothing about
 * whether it understands the environment, and a harness that halts every step is a harness nobody
 * leaves switched on.</p>
 *
 * <p>An expectation is the same bar aimed at what the model actually claims to know. It is still
 * falsifiable -- a violated constraint halts a plan exactly as a mismatched grid would -- but the
 * halt points at the belief that was wrong instead of at a wall of diff, which is the difference
 * between evidence the next model can use and a note that something changed.</p>
 *
 * <h2>Why it is immutable</h2>
 *
 * <p>An expectation is written into the ledger beside the transition it predicted. If adding a
 * constraint changed the expectation a caller already held, a record could come to describe a check
 * that was never run against it.</p>
 */
public final class Expect {

    private static final Expect NOTHING = new Expect(List.of());

    private final List<Constraint> constraints;

    private Expect(List<Constraint> constraints) {
        this.constraints = List.copyOf(constraints);
    }

    /** An expectation that no observation can contradict, and the start of every other one. */
    public static Expect anything() {
        return NOTHING;
    }

    /** That the value at {@code path} is {@code value}. */
    public Expect eq(String path, Object value) {
        return eq(path, value, null);
    }

    /** That the value at {@code path} is {@code value}, known by {@code name}. */
    public Expect eq(String path, Object value, String name) {
        return with(Constraints.eq(path, value, name));
    }

    /** That the value at {@code path} is anything but {@code value}. */
    public Expect ne(String path, Object value) {
        return ne(path, value, null);
    }

    /** That the value at {@code path} is anything but {@code value}, known by {@code name}. */
    public Expect ne(String path, Object value, String name) {
        return with(Constraints.ne(path, value, name));
    }

    /** That the number at {@code path} is above {@code bound}. */
    public Expect gt(String path, Object bound) {
        return gt(path, bound, null);
    }

    /** That the number at {@code path} is above {@code bound}, known by {@code name}. */
    public Expect gt(String path, Object bound, String name) {
        return with(Constraints.gt(path, bound, name));
    }

    /** That the number at {@code path} is at least {@code bound}. */
    public Expect ge(String path, Object bound) {
        return ge(path, bound, null);
    }

    /** That the number at {@code path} is at least {@code bound}, known by {@code name}. */
    public Expect ge(String path, Object bound, String name) {
        return with(Constraints.ge(path, bound, name));
    }

    /** That the number at {@code path} is below {@code bound}. */
    public Expect lt(String path, Object bound) {
        return lt(path, bound, null);
    }

    /** That the number at {@code path} is below {@code bound}, known by {@code name}. */
    public Expect lt(String path, Object bound, String name) {
        return with(Constraints.lt(path, bound, name));
    }

    /** That the number at {@code path} is at most {@code bound}. */
    public Expect le(String path, Object bound) {
        return le(path, bound, null);
    }

    /** That the number at {@code path} is at most {@code bound}, known by {@code name}. */
    public Expect le(String path, Object bound, String name) {
        return with(Constraints.le(path, bound, name));
    }

    /** That the text, list or object at {@code path} contains {@code needle}. */
    public Expect contains(String path, Object needle) {
        return contains(path, needle, null);
    }

    /** That the text, list or object at {@code path} contains {@code needle}, known by {@code name}. */
    public Expect contains(String path, Object needle, String name) {
        return with(Constraints.contains(path, needle, name));
    }

    /** That {@code path} resolves at all. */
    public Expect present(String path) {
        return present(path, null);
    }

    /** That {@code path} resolves at all, known by {@code name}. */
    public Expect present(String path, String name) {
        return with(Constraints.present(path, name));
    }

    /** That {@code path} does not resolve. */
    public Expect absent(String path) {
        return absent(path, null);
    }

    /** That {@code path} does not resolve, known by {@code name}. */
    public Expect absent(String path, String name) {
        return with(Constraints.absent(path, name));
    }

    /** That the text at {@code path} has a match for {@code regex} somewhere in it. */
    public Expect matches(String path, String regex) {
        return matches(path, regex, null);
    }

    /** That the text at {@code path} has a match for {@code regex}, known by {@code name}. */
    public Expect matches(String path, String regex, String name) {
        return with(Constraints.matches(path, regex, name));
    }

    /**
     * Any belief the builders do not cover, which still has to be named.
     *
     * <p>An unnamed escape hatch would put the harness back where exact match left it: able to say
     * that something was wrong and unable to say what was believed.</p>
     *
     * @param name  what the belief is called
     * @param holds whether an observation satisfies it
     * @return a new expectation carrying this belief as well
     */
    public Expect where(String name, Predicate<Object> holds) {
        return with(Constraints.where(name, holds));
    }

    /**
     * Every belief this expectation states that the observation did not satisfy, in the order they
     * were stated.
     *
     * @param observation what actually happened
     * @return the violations, empty when the expectation held
     */
    public List<Violation> check(Object observation) {
        List<Violation> violations = new ArrayList<>();
        for (Constraint constraint : constraints) {
            Violation violation = constraint.inspect(observation);
            if (violation != null) {
                violations.add(violation);
            }
        }
        return List.copyOf(violations);
    }

    /** The beliefs, for a caller that needs them one at a time. */
    public List<Constraint> constraints() {
        return constraints;
    }

    /** What each belief is called. */
    public List<String> names() {
        return constraints.stream().map(Constraint::name).collect(Collectors.toList());
    }

    /** How many beliefs this expectation states. */
    public int size() {
        return constraints.size();
    }

    /** Whether it states none, and so can never be contradicted. */
    public boolean isEmpty() {
        return constraints.isEmpty();
    }

    /** How the expectation reads, as the checks it performs rather than the names it goes by. */
    public String describe() {
        return constraints.stream()
                .map(Constraint::description)
                .collect(Collectors.joining(", ", "expect[", "]"));
    }

    @Override
    public String toString() {
        return describe();
    }

    private Expect with(Constraint constraint) {
        List<Constraint> extended = new ArrayList<>(constraints);
        extended.add(constraint);
        return new Expect(extended);
    }
}
