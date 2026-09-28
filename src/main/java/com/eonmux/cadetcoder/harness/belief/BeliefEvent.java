package com.eonmux.cadetcoder.harness.belief;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One line of the belief log: something that happened to one belief.
 *
 * <h2>Why the log is events and the beliefs are replayed</h2>
 *
 * <p>The state of a belief is a summary; how it got there is the part that stops the agent going
 * round the same loop twice. Writing the events and deriving the state means a claim that was made,
 * supported twice, then refuted still reads that way months later, and it means a crashed run
 * resumes from the last complete line rather than from a half-written snapshot of everything.</p>
 *
 * @param kind        what happened
 * @param id          the belief it happened to
 * @param text        the claim, on an {@link BeliefEventKind#ADD} only
 * @param status      whether the addition was a claim or a question, on an add only
 * @param ledgerLength how long the ledger was, on an add only
 * @param evidence    ledger indices, on an add, an evidence, a refute or a resolve
 * @param tags        what it is about, on an add only
 * @param reason      why it was refuted, on a refute only
 * @param answer      what answered it, on a resolve only
 * @param supersedes  what the new claim replaces, on an add that replaces something
 * @param replacement what replaced the belief, on a supersede only
 * @param at          when it happened, in epoch milliseconds
 */
public record BeliefEvent(BeliefEventKind kind, String id, String text, BeliefStatus status,
                          int ledgerLength, List<Integer> evidence, List<String> tags,
                          String reason, String answer, String supersedes, String replacement,
                          long at) {

    public BeliefEvent {
        evidence = List.copyOf(evidence);
        tags = List.copyOf(tags);
    }

    /** A claim or a question being written down. */
    static BeliefEvent added(String id, String text, BeliefStatus status, int ledgerLength,
                             List<Integer> evidence, List<String> tags, String supersedes) {
        return new BeliefEvent(BeliefEventKind.ADD, id, text, status, ledgerLength, evidence, tags,
                               null, null, supersedes, null, System.currentTimeMillis());
    }

    /** More of the ledger named behind a claim. */
    static BeliefEvent evidence(String id, List<Integer> found) {
        return new BeliefEvent(BeliefEventKind.EVIDENCE, id, null, null, 0, found, List.of(),
                               null, null, null, null, System.currentTimeMillis());
    }

    /** A claim contradicted by what really happened. */
    static BeliefEvent refuted(String id, List<Integer> killedBy, String why) {
        return new BeliefEvent(BeliefEventKind.REFUTE, id, null, null, 0, killedBy, List.of(),
                               why, null, null, null, System.currentTimeMillis());
    }

    /** A question answered. */
    static BeliefEvent resolved(String id, String answer, List<Integer> from) {
        return new BeliefEvent(BeliefEventKind.RESOLVE, id, null, null, 0, from, List.of(),
                               null, answer, null, null, System.currentTimeMillis());
    }

    /** A claim retired in favour of a better one. */
    static BeliefEvent superseded(String id, String replacement) {
        return new BeliefEvent(BeliefEventKind.SUPERSEDE, id, null, null, 0, List.of(), List.of(),
                               null, null, null, replacement, System.currentTimeMillis());
    }

    /**
     * The event as one line of the log.
     *
     * <p>Only the fields the kind actually uses are written, so a refute event does not carry an
     * empty text and a reader is never left wondering whether a blank field meant something.</p>
     */
    public Map<String, Object> toValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", kind.label());
        value.put("id", id);
        switch (kind) {
            case ADD -> {
                value.put("text", text);
                value.put("status", status.label());
                value.put("ledger_length", ledgerLength);
                value.put("evidence", evidence);
                value.put("tags", tags);
                if (supersedes != null) {
                    value.put("supersedes", supersedes);
                }
            }
            case EVIDENCE -> value.put("evidence", evidence);
            case REFUTE -> {
                value.put("evidence", evidence);
                value.put("reason", reason);
            }
            case RESOLVE -> {
                value.put("answer", answer);
                value.put("evidence", evidence);
            }
            case SUPERSEDE -> value.put("replacement", replacement);
        }
        value.put("at", at);
        return value;
    }

    /**
     * Reads one line of the log back.
     *
     * @param value the line as parsed
     * @return the event it records
     * @throws IllegalArgumentException if it is not an event this harness knows how to apply
     */
    public static BeliefEvent fromValue(Map<String, Object> value) {
        BeliefEventKind kind = BeliefEventKind.of(text(value, "kind"));
        String          id   = text(value, "id");
        return new BeliefEvent(kind, id,
                               (String) value.get("text"),
                               value.get("status") == null ? null
                                                           : BeliefStatus.of((String) value.get("status")),
                               number(value.get("ledger_length")),
                               indices(value.get("evidence")),
                               words(value.get("tags")),
                               (String) value.get("reason"),
                               (String) value.get("answer"),
                               (String) value.get("supersedes"),
                               (String) value.get("replacement"),
                               moment(value.get("at")));
    }

    private static String text(Map<String, Object> value, String key) {
        Object found = value.get(key);
        if (!(found instanceof String written) || written.isEmpty()) {
            throw new IllegalArgumentException("a belief event has to name its " + key);
        }
        return written;
    }

    private static int number(Object value) {
        return value instanceof Number found ? found.intValue() : 0;
    }

    private static long moment(Object value) {
        return value instanceof Number found ? found.longValue() : 0L;
    }

    private static List<Integer> indices(Object value) {
        if (!(value instanceof List)) {
            return List.of();
        }
        List<Integer> found = new ArrayList<>();
        for (Object element : (List<?>) value) {
            if (!(element instanceof Number index)) {
                throw new IllegalArgumentException("a ledger index has to be a number, not "
                                                   + element);
            }
            found.add(index.intValue());
        }
        return found;
    }

    private static List<String> words(Object value) {
        if (!(value instanceof List)) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (Object element : (List<?>) value) {
            found.add(String.valueOf(element));
        }
        return found;
    }
}
