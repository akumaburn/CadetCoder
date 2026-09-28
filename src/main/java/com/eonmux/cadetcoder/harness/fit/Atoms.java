package com.eonmux.cadetcoder.harness.fit;

import com.eonmux.cadetcoder.harness.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The atoms the data itself suggests, and no others.
 *
 * <h2>Why the atoms come from the data</h2>
 *
 * <p>Nobody knows in advance which value of which feature matters, so every value that appears is a
 * candidate to compare against and every gap between adjacent numeric values is a candidate to
 * split at. Those gaps are exactly the distinct splits the data can express: a threshold anywhere
 * between two neighbouring values covers the same examples as a threshold at either end of the gap,
 * so generating more would enlarge the search without enlarging the answer.</p>
 *
 * <h2>Why no feature may spend the whole budget</h2>
 *
 * <p>A step counter with three hundred distinct values contributes six hundred thresholds; two
 * booleans contribute four. Capping the atoms globally and taking them in the order they were
 * generated therefore spends the entire search on the counter and never looks at the booleans that
 * decide the label -- and the fit reports, with a straight face, that no predicate separates the
 * data. Each feature gets its own allowance instead, and a feature that hits it is named, because
 * the agent is the one who can decide to describe its states differently.</p>
 *
 * <h2>Which atoms a thinned feature keeps</h2>
 *
 * <p>An atom that holds of no positive example cannot appear in any conjunction that covers one, so
 * it is dropped before the allowance is spent rather than after. What is left is ordered by where
 * the labels change: a split between two values whose examples disagree is where a rule could
 * plausibly be, and a split between two blocks that are all one label and the same label is where
 * one could not.</p>
 */
final class Atoms {

    /** The atoms one fit will search over, and what had to be left out to get there. */
    record Harvest(List<Atom> atoms, List<String> notes) {
    }

    /** What one feature took across the examples, and on which side of the question. */
    private record Seen(List<Object> distinct, Set<String> inPositive, Set<String> inNegative) {

        boolean pure(Object value) {
            String key = Json.canonical(value);
            return !(inPositive.contains(key) && inNegative.contains(key));
        }

        boolean positive(Object value) {
            return inPositive.contains(Json.canonical(value));
        }
    }

    private Atoms() {
    }

    /**
     * Reads the atoms out of the examples.
     *
     * @param examples what was seen
     * @param limits   how much of the space the fit may look at
     * @return the atoms, and every allowance that bound
     */
    static Harvest from(List<Example> examples, FitLimits limits) {
        List<Atom>   kept  = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (Map.Entry<String, Seen> feature : read(examples).entrySet()) {
            List<Atom> offered = useful(suggested(feature.getKey(), feature.getValue(), limits),
                                        examples);
            if (offered.size() > limits.atomsPerFeature()) {
                notes.add(feature.getKey() + " offered " + offered.size()
                          + " atoms and was thinned to " + limits.atomsPerFeature()
                          + "; a predicate about it may have been missed");
                offered = offered.subList(0, limits.atomsPerFeature());
            }
            kept.addAll(offered);
        }
        if (kept.size() > limits.maxAtomsTotal()) {
            notes.add("the features offered " + kept.size() + " atoms and the search was cut to "
                      + limits.maxAtomsTotal() + "; describe the states with fewer features");
            kept = kept.subList(0, limits.maxAtomsTotal());
        }
        return new Harvest(List.copyOf(kept), List.copyOf(notes));
    }

    private static Map<String, Seen> read(List<Example> examples) {
        Map<String, LinkedHashMap<String, Object>> values     = new LinkedHashMap<>();
        Map<String, Set<String>>                   inPositive = new LinkedHashMap<>();
        Map<String, Set<String>>                   inNegative = new LinkedHashMap<>();
        for (Example example : examples) {
            for (Map.Entry<String, Object> feature : example.features().entrySet()) {
                String key = Json.canonical(feature.getValue());
                values.computeIfAbsent(feature.getKey(), name -> new LinkedHashMap<>())
                      .putIfAbsent(key, feature.getValue());
                (example.label() ? inPositive : inNegative)
                        .computeIfAbsent(feature.getKey(), name -> new LinkedHashSet<>()).add(key);
            }
        }
        Map<String, Seen> seen = new LinkedHashMap<>();
        values.forEach((feature, distinct) -> seen.put(feature,
                new Seen(List.copyOf(distinct.values()),
                         inPositive.getOrDefault(feature, Set.of()),
                         inNegative.getOrDefault(feature, Set.of()))));
        return seen;
    }

    private static List<Atom> suggested(String feature, Seen seen, FitLimits limits) {
        List<Object> distinct = seen.distinct();
        if (all(distinct, Boolean.class)) {
            return List.of(new Atom(feature, AtomOp.EQ, Boolean.TRUE),
                           new Atom(feature, AtomOp.EQ, Boolean.FALSE));
        }
        if (all(distinct, Number.class)) {
            return numeric(feature, seen, limits);
        }
        return categorical(feature, seen);
    }

    private static List<Atom> numeric(String feature, Seen seen, FitLimits limits) {
        List<Object> sorted = new ArrayList<>(seen.distinct());
        sorted.sort(Comparator.comparingDouble(value -> ((Number) value).doubleValue()));
        List<Atom> atoms = new ArrayList<>();
        if (sorted.size() <= limits.maxValues()) {
            for (Object value : sorted) {
                atoms.add(new Atom(feature, AtomOp.EQ, value));
            }
        }
        List<Object> where    = new ArrayList<>();
        List<Object> elsewhen = new ArrayList<>();
        for (int i = 0; i + 1 < sorted.size(); i++) {
            Object below = sorted.get(i);
            Object above = sorted.get(i + 1);
            (splits(seen, below, above) ? where : elsewhen).add(threshold(below, above));
        }
        where.addAll(elsewhen);
        for (Object bound : where) {
            atoms.add(new Atom(feature, AtomOp.GE, bound));
            atoms.add(new Atom(feature, AtomOp.LT, bound));
        }
        return atoms;
    }

    /** Whether the labels could change across this gap, which is the only place a threshold helps. */
    private static boolean splits(Seen seen, Object below, Object above) {
        return !(seen.pure(below) && seen.pure(above)
                 && seen.positive(below) == seen.positive(above));
    }

    /**
     * Where to cut between two neighbouring values: at the upper one when both are whole, so the
     * threshold reads as a value the data actually contains, and halfway when they are not.
     */
    private static Object threshold(Object below, Object above) {
        double low  = ((Number) below).doubleValue();
        double high = ((Number) above).doubleValue();
        return low == Math.rint(low) && high == Math.rint(high) ? above : (low + high) / 2.0;
    }

    private static List<Atom> categorical(String feature, Seen seen) {
        List<Object> deciding = new ArrayList<>();
        List<Object> rest     = new ArrayList<>();
        for (Object value : seen.distinct()) {
            (seen.positive(value) ? deciding : rest).add(value);
        }
        deciding.addAll(rest);
        List<Atom> atoms = new ArrayList<>();
        for (Object value : deciding) {
            atoms.add(new Atom(feature, AtomOp.EQ, value));
            atoms.add(new Atom(feature, AtomOp.NE, value));
        }
        return atoms;
    }

    /** Drops the atoms that hold of no positive example, which no useful conjunction can contain. */
    private static List<Atom> useful(List<Atom> atoms, List<Example> examples) {
        List<Atom> kept = new ArrayList<>();
        for (Atom atom : atoms) {
            for (Example example : examples) {
                if (example.label() && atom.holds(example.features())) {
                    kept.add(atom);
                    break;
                }
            }
        }
        return kept;
    }

    private static boolean all(List<Object> values, Class<?> kind) {
        for (Object value : values) {
            if (!kind.isInstance(value)) {
                return false;
            }
        }
        return true;
    }
}
