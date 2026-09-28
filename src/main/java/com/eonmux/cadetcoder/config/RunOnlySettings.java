package com.eonmux.cadetcoder.config;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The settings a run was given on its command line, which belong to that run and not to the file.
 *
 * <h2>Why it records the difference and not the flags</h2>
 *
 * <p>The flags write through ordinary setters, a dozen of them, and more are added over time. A list
 * of which flag touches which setting would be one more list to keep in step. Comparing the
 * configuration just before the flags with the configuration just after names exactly what they
 * changed, whatever they are.</p>
 *
 * <h2>Why a setting changed again during the run is saved</h2>
 *
 * <p>A setting that no longer holds the flag's value was changed by something in the run --
 * {@code models use} after {@code --model} -- and that is a choice the user expects to keep. Only a
 * setting still holding what the flag put there is put back.</p>
 *
 * <p>Immutable: {@link #plus} returns a new record.</p>
 */
final class RunOnlySettings {

    /** Nothing was given on the command line. */
    static final RunOnlySettings NONE = new RunOnlySettings(List.of());

    /**
     * One setting a flag changed.
     *
     * @param path     where it is in the file's JSON
     * @param fromFile what the file said, or {@code null} when the file did not have it
     * @param byFlag   what the flag set, or {@code null} when the flag removed it
     */
    private record Change(JsonPointer path, JsonNode fromFile, JsonNode byFlag) {
    }

    private final List<Change> changes;

    private RunOnlySettings(List<Change> changes) {
        this.changes = List.copyOf(changes);
    }

    /**
     * What changed between two renderings of the configuration.
     *
     * @param before the configuration as the file gave it
     * @param after  the configuration once the flags were applied
     * @return the changes, as settings that belong to this run
     */
    static RunOnlySettings between(JsonNode before, JsonNode after) {
        List<Change> found = new ArrayList<>();
        collect(JsonPointer.empty(), before, after, found);
        return new RunOnlySettings(found);
    }

    /**
     * These settings and some more.
     *
     * <p>A path already recorded keeps the file value it was first recorded with: that, not what an
     * earlier flag set, is what the file said.</p>
     *
     * @param more settings from a later application of flags
     * @return a new record holding both
     */
    RunOnlySettings plus(RunOnlySettings more) {
        List<Change> joined = new ArrayList<>(changes);
        for (Change added : more.changes) {
            Change earlier = find(added.path());
            if (earlier == null) {
                joined.add(added);
            } else {
                joined.set(joined.indexOf(earlier), new Change(added.path(), earlier.fromFile(),
                                                               added.byFlag()));
            }
        }
        return new RunOnlySettings(joined);
    }

    /**
     * These settings less the ones the user has since chosen in the run.
     *
     * <p>A choice can be the flag's own value -- {@code models use X} after {@code --model X} -- and
     * then nothing in the configuration shows it was made. The command that made it says so, and
     * the setting is from then on saved like any other.</p>
     *
     * @param chosen where the chosen settings are; a setting under one of them is chosen too
     * @return a new record without them
     */
    RunOnlySettings except(Collection<JsonPointer> chosen) {
        List<Change> kept = new ArrayList<>();
        for (Change change : changes) {
            if (chosen.stream().noneMatch(path -> contains(path, change.path()))) {
                kept.add(change);
            }
        }
        return new RunOnlySettings(kept);
    }

    /**
     * Where a setting is, from its name as {@code config set} spells it.
     *
     * @param rendered the configuration as JSON
     * @param setting  a dotted name such as {@code ai.model}, in any case
     * @return its place, or {@code null} when the configuration has no such setting
     */
    static JsonPointer pointerTo(JsonNode rendered, String setting) {
        JsonPointer path  = JsonPointer.empty();
        JsonNode    node  = rendered;
        String[]    parts = setting.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            String name = fieldNamed(node, parts[i]);
            if (name == null) {
                // A key of a map may hold dots of its own, as a model id such as qwen2.5-coder
                // does, so the rest of the name is tried as one key before giving up.
                String rest = String.join(".", java.util.Arrays.copyOfRange(parts, i, parts.length));
                name = i < parts.length - 1 ? fieldNamed(node, rest) : null;
                if (name == null) {
                    return null;
                }
                return path.appendProperty(name);
            }
            path = path.appendProperty(name);
            node = node.get(name);
        }
        return path;
    }

    private static String fieldNamed(JsonNode node, String wanted) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (name.equalsIgnoreCase(wanted)) {
                return name;
            }
        }
        return null;
    }

    private static boolean contains(JsonPointer outer, JsonPointer inner) {
        String prefix = outer.toString();
        String path   = inner.toString();
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /**
     * The configuration as the file should record it: every setting still holding a flag's value is
     * put back to what the file said.
     *
     * @param live the configuration in use, rendered as JSON; not modified
     * @return a copy fit to write
     */
    JsonNode withoutThem(JsonNode live) {
        JsonNode toWrite = live.deepCopy();
        for (Change change : changes) {
            JsonNode now = toWrite.at(change.path());
            boolean stillTheFlags = change.byFlag() == null ? now.isMissingNode()
                                                            : change.byFlag().equals(now);
            if (stillTheFlags) {
                putBack(toWrite, change);
            }
        }
        return toWrite;
    }

    private Change find(JsonPointer path) {
        for (Change change : changes) {
            if (change.path().equals(path)) {
                return change;
            }
        }
        return null;
    }

    /** Walks two objects together, recording each leaf that differs. */
    private static void collect(JsonPointer at, JsonNode before, JsonNode after, List<Change> found) {
        if (before != null && after != null && before.isObject() && after.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = after.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                collect(at.appendProperty(field.getKey()), before.get(field.getKey()),
                        field.getValue(), found);
            }
            Iterator<String> names = before.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!after.has(name)) {
                    found.add(new Change(at.appendProperty(name), before.get(name), null));
                }
            }
            return;
        }
        if (before == null ? after != null : !before.equals(after)) {
            found.add(new Change(at, before, after));
        }
    }

    /** Sets, or removes, one setting in a rendering that is about to be written. */
    private static void putBack(JsonNode root, Change change) {
        JsonNode parent = root.at(change.path().head());
        if (!(parent instanceof ObjectNode holder)) {
            return;
        }
        String name = change.path().last().getMatchingProperty();
        if (change.fromFile() == null) {
            holder.remove(name);
        } else {
            holder.set(name, change.fromFile());
        }
    }
}
