package com.eonmux.cadetcoder.harness.spec;

import com.eonmux.cadetcoder.harness.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Where two values stopped agreeing, said in a way a reader can act on.
 *
 * <h2>Why a diff and not a boolean</h2>
 *
 * <p>A halt is only worth having if the next thing the agent does is informed by it. "The model was
 * contradicted" sends it back to guessing; "hud.score: expected 7, actual 9" is the observation the
 * next model is built from.</p>
 *
 * <h2>Why a grid is counted instead</h2>
 *
 * <p>The one shape where naming every difference makes the report worse is a grid. A world shifted
 * by one row is hundreds of differing cells and not one of them is the point, so a grid is counted
 * and sampled rather than enumerated. Long reports are capped for the same reason: past a handful
 * of examples the count carries the information and the list only costs attention.</p>
 */
public final class Difference {

    /** How many individual differences a report shows before it settles for counting them. */
    public static final int SAMPLE_LIMIT = 8;

    /** How much of a single value a sample quotes. */
    private static final int VALUE_LENGTH = 80;

    /** Stands for a key or element that one side simply does not have. */
    private static final Object MISSING = new Object();

    private final int          count;
    private final int          cells;
    private final List<String> samples;

    private Difference(int count, int cells, List<String> samples) {
        this.count   = count;
        this.cells   = cells;
        this.samples = List.copyOf(samples);
    }

    /**
     * Compares what was predicted against what happened.
     *
     * @param expected the predicted value
     * @param actual   the observed value
     * @return the differences between them
     */
    public static Difference between(Object expected, Object actual) {
        Walk walk = new Walk();
        walk.compare("", expected, actual);
        return new Difference(walk.count, walk.cells, walk.samples);
    }

    /** How many places the two values disagree. */
    public int count() {
        return count;
    }

    /** Whether they agree everywhere. */
    public boolean isEmpty() {
        return count == 0;
    }

    /** Up to {@link #SAMPLE_LIMIT} of the differences, written out. */
    public List<String> samples() {
        return samples;
    }

    /** How the whole comparison reads in a report. */
    public String render() {
        if (count == 0) {
            return "no difference";
        }
        StringBuilder out = new StringBuilder(headline());
        if (!samples.isEmpty()) {
            out.append(": ").append(String.join("; ", samples));
            if (count > samples.size()) {
                out.append("; ...");
            }
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return render();
    }

    private String headline() {
        if (cells == 0) {
            return count + (count == 1 ? " difference" : " differences");
        }
        int elsewhere = count - cells;
        return cells + " cells differ"
               + (elsewhere == 0 ? ""
                                 : " and " + elsewhere
                                   + (elsewhere == 1 ? " other difference" : " other differences"));
    }

    /** The comparison in progress: the one place in this class where anything changes. */
    private static final class Walk {

        private final List<String> samples = new ArrayList<>();

        private int count;
        private int cells;

        private void compare(String path, Object expected, Object actual) {
            if (Json.equal(expected, actual)) {
                return;
            }
            if (expected instanceof Map<?, ?> left && actual instanceof Map<?, ?> right) {
                objects(path, left, right);
            } else if (expected instanceof List<?> left && actual instanceof List<?> right) {
                lists(path, left, right);
            } else {
                record(path, expected, actual);
            }
        }

        private void objects(String path, Map<?, ?> expected, Map<?, ?> actual) {
            Set<String> keys = new TreeSet<>();
            expected.keySet().forEach(key -> keys.add(String.valueOf(key)));
            actual.keySet().forEach(key -> keys.add(String.valueOf(key)));
            for (String key : keys) {
                Object left  = expected.containsKey(key) ? expected.get(key) : MISSING;
                Object right = actual.containsKey(key) ? actual.get(key) : MISSING;
                if (left == MISSING || right == MISSING) {
                    record(join(path, key), left, right);
                } else {
                    compare(join(path, key), left, right);
                }
            }
        }

        private void lists(String path, List<?> expected, List<?> actual) {
            if (isGrid(expected) && isGrid(actual) && sameShape(expected, actual)) {
                grid(path, expected, actual);
                return;
            }
            if (expected.size() != actual.size()) {
                record(join(path, "length"), expected.size(), actual.size());
            }
            for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
                compare(path + "[" + i + "]", expected.get(i), actual.get(i));
            }
        }

        private void grid(String path, List<?> expected, List<?> actual) {
            for (int y = 0; y < expected.size(); y++) {
                List<?> left  = (List<?>) expected.get(y);
                List<?> right = (List<?>) actual.get(y);
                for (int x = 0; x < left.size(); x++) {
                    if (!Json.equal(left.get(x), right.get(x))) {
                        cells++;
                        record(path + "[" + y + "][" + x + "]", left.get(x), right.get(x));
                    }
                }
            }
        }

        private void record(String path, Object expected, Object actual) {
            count++;
            if (samples.size() < SAMPLE_LIMIT) {
                samples.add((path.isEmpty() ? "value" : path)
                            + ": expected " + show(expected) + ", actual " + show(actual));
            }
        }

        private static String join(String path, String key) {
            return path.isEmpty() ? key : path + "." + key;
        }
    }

    private static String show(Object value) {
        if (value == MISSING) {
            return "nothing";
        }
        String written = Json.canonical(value);
        return written.length() <= VALUE_LENGTH ? written
                                                : written.substring(0, VALUE_LENGTH) + "...";
    }

    /** A list of rows of scalars, which is the shape a report should count rather than list. */
    private static boolean isGrid(List<?> value) {
        if (value.size() < 2) {
            return false;
        }
        for (Object row : value) {
            if (!(row instanceof List)) {
                return false;
            }
        }
        return scalarRow((List<?>) value.get(0)) && scalarRow((List<?>) value.get(1));
    }

    private static boolean scalarRow(List<?> row) {
        for (Object cell : row) {
            if (cell instanceof List || cell instanceof Map) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameShape(List<?> expected, List<?> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int y = 0; y < expected.size(); y++) {
            if (((List<?>) expected.get(y)).size() != ((List<?>) actual.get(y)).size()) {
                return false;
            }
        }
        return true;
    }
}
