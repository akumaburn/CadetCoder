package com.eonmux.cadetcoder.harness;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The one shape a value takes anywhere in the harness, and the one way it is written down.
 *
 * <h2>Why everything is a plain Java value</h2>
 *
 * <p>Observations, actions, model states and predictions all cross the same three boundaries: they
 * are stored in the ledger, hashed into a chain, and handed to a world model written in the model
 * language. A single representation -- {@link Map}, {@link List}, {@link String}, {@link Number},
 * {@link Boolean} and {@code null}, which is exactly what JSON can carry -- means none of those
 * boundaries needs a conversion, and a value that survives one of them survives all three.</p>
 *
 * <h2>Why the serialisation is canonical</h2>
 *
 * <p>The ledger is a hash chain and a model is addressed by the hash of its source, so "the same
 * value" has to produce the same bytes every time it is written. Object keys are therefore sorted
 * and there is no insignificant whitespace. Numbers are normalised for the same reason: the model
 * language computes in {@code double}, so {@code 0.5 + 0.5} and the literal {@code 1} are the same
 * value and must not hash differently.</p>
 */
public final class Json {

    /** Jackson is used only at the edges: text in, plain values out, and back. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Object> ANY = new TypeReference<>() {
    };

    private Json() {
    }

    /**
     * Reads JSON text as plain values.
     *
     * @param text the JSON document
     * @return the value it denotes
     * @throws IllegalArgumentException if the text is not JSON
     */
    public static Object parse(String text) {
        try {
            return MAPPER.readValue(text, ANY);
        } catch (Exception e) {
            throw new IllegalArgumentException("not JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Writes a value in the one form the harness hashes and compares.
     *
     * @param value any plain value
     * @return its canonical JSON text
     */
    public static String canonical(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    /**
     * Writes a value the way a person reads it: text as itself, anything else as canonical JSON.
     *
     * <p>Canonical form is what the harness compares; this is what it shows. Quoting and escaping a
     * command's output before searching it for a phrase would make every constraint about a build
     * log wrong for a reason that has nothing to do with the build.</p>
     *
     * @param value any plain value
     * @return its readable form
     */
    public static String readable(Object value) {
        return value instanceof String text ? text : canonical(value);
    }

    /**
     * The leading hex characters of the SHA-256 of a value's canonical form.
     *
     * @param value  the value to identify
     * @param length how many hex characters to keep
     * @return the truncated digest
     */
    public static String digest(Object value, int length) {
        return digestOfText(canonical(value), length);
    }

    /**
     * The leading hex characters of the SHA-256 of text.
     *
     * @param text   the text to identify
     * @param length how many hex characters to keep
     * @return the truncated digest
     */
    public static String digestOfText(String text, int length) {
        try {
            byte[]        bytes = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex   = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, Math.min(length, hex.length()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /** Whether two values are the same value, by the definition the ledger and the model share. */
    public static boolean equal(Object a, Object b) {
        return canonical(a).equals(canonical(b));
    }

    /**
     * A value that shares nothing with the original.
     *
     * <p>The model language hands a state to {@code step} by reference so that a mutation can be
     * detected rather than hidden; every caller that must not be affected by one copies first.</p>
     *
     * @param value the value to copy
     * @return an independent copy
     */
    @SuppressWarnings ("unchecked")
    public static Object deepCopy(Object value) {
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            ((Map<String, Object>) value).forEach((k, v) -> copy.put(k, deepCopy(v)));
            return copy;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object element : (List<Object>) value) {
                copy.add(deepCopy(element));
            }
            return copy;
        }
        return value;
    }

    /**
     * A value nothing can change afterwards.
     *
     * <p>Observations and actions are hashed into the ledger chain the moment they are recorded, so
     * a later mutation would leave a record whose contents no longer match its own hash. Copying on
     * every read would hide that; refusing the write reports it. It is also what stops a world model
     * from editing the observation it was asked to explain.</p>
     *
     * @param value the value to freeze
     * @return a deep, unmodifiable copy
     */
    @SuppressWarnings ("unchecked")
    public static Object frozen(Object value) {
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            ((Map<String, Object>) value).forEach((k, v) -> copy.put(k, frozen(v)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object element : (List<Object>) value) {
                copy.add(frozen(element));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    /**
     * {@link #frozen} for a value already known to be an object, treating absence as emptiness.
     *
     * @param value the map to freeze; {@code null} becomes an empty map
     * @return a deep, unmodifiable copy
     */
    @SuppressWarnings ("unchecked")
    public static Map<String, Object> frozenMap(Map<String, Object> value) {
        return value == null ? Map.of() : (Map<String, Object>) frozen(value);
    }

    /**
     * Resolves a dotted path such as {@code hud.score} or {@code grid.3.4}.
     *
     * @param value the value to look inside
     * @param path  the path; empty selects the value itself
     * @return what the path names
     * @throws IllegalArgumentException if the path does not resolve
     */
    @SuppressWarnings ("unchecked")
    public static Object at(Object value, String path) {
        Object current = value;
        if (path == null || path.isEmpty()) {
            return current;
        }
        for (String part : path.split("\\.")) {
            if (current instanceof Map && ((Map<String, Object>) current).containsKey(part)) {
                current = ((Map<String, Object>) current).get(part);
            } else if (current instanceof List) {
                List<Object> list = (List<Object>) current;
                int          i    = parseIndex(part, path);
                if (i < 0 || i >= list.size()) {
                    throw new IllegalArgumentException("no such element: " + path);
                }
                current = list.get(i);
            } else {
                throw new IllegalArgumentException("no such path: " + path);
            }
        }
        return current;
    }

    private static int parseIndex(String part, String path) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("no such path: " + path, e);
        }
    }

    /** Whether a path resolves, for callers that want the question rather than the exception. */
    public static boolean has(Object value, String path) {
        try {
            at(value, path);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The truth value of a value, as the model language reads it.
     *
     * <p>Empty is false -- no string, no list, no object, zero -- which is what a model author
     * writing {@code if (state.errors)} means, and what every language they are likely to have
     * written a model in already does.</p>
     *
     * @param value any value
     * @return whether it counts as true
     */
    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0.0;
        }
        if (value instanceof String) {
            return !((String) value).isEmpty();
        }
        if (value instanceof List) {
            return !((List<?>) value).isEmpty();
        }
        if (value instanceof Map) {
            return !((Map<?, ?>) value).isEmpty();
        }
        return true;
    }

    @SuppressWarnings ("unchecked")
    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else if (value instanceof Number) {
            writeNumber(((Number) value).doubleValue(), out);
        } else if (value instanceof String) {
            writeString((String) value, out);
        } else if (value instanceof Map) {
            writeObject((Map<String, Object>) value, out);
        } else if (value instanceof List) {
            writeArray((List<Object>) value, out);
        } else {
            // Nothing else is a value. Writing its toString() would let a foreign object into the
            // ledger under a shape nothing can read back, so it is named instead of serialised.
            writeString(value.getClass().getSimpleName() + ":" + value, out);
        }
    }

    private static void writeNumber(double d, StringBuilder out) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            // JSON has no way to say either; a model that produced one has a bug, and the bug must
            // be visible in the record rather than crash the writer.
            writeString(Double.toString(d), out);
            return;
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            out.append((long) d);
            return;
        }
        out.append(d);
    }

    private static void writeObject(Map<String, Object> map, StringBuilder out) {
        out.append('{');
        boolean first = true;
        for (String key : new TreeSet<>(map.keySet())) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(key, out);
            out.append(':');
            write(map.get(key), out);
        }
        out.append('}');
    }

    private static void writeArray(List<Object> list, StringBuilder out) {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            write(list.get(i), out);
        }
        out.append(']');
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default   -> appendPlain(c, out);
            }
        }
        out.append('"');
    }

    private static void appendPlain(char c, StringBuilder out) {
        if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
        } else {
            out.append(c);
        }
    }
}
