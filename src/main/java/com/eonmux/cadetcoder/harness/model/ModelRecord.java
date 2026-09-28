package com.eonmux.cadetcoder.harness.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the store remembers about one version of a model, beside the source itself.
 *
 * <p>None of this can be recovered from the source. How much of the ledger the agent had seen when
 * it wrote this version says whether a later contradiction is new evidence or evidence the model
 * was already supposed to account for; which version it came from turns a directory of digests into
 * the history of a theory; and the complexity is the measurement that only means something next to
 * the same measurement of the version before.</p>
 *
 * @param digest       the name of the version: the digest of its source
 * @param parent       the version it was written from, or {@code null} for the first
 * @param ledgerLength how many transitions had happened when it was written
 * @param complexity   how much theory it carries
 * @param note         why it was written, in the author's words
 */
public record ModelRecord(String digest, String parent, int ledgerLength,
                          ModelComplexity complexity, String note) {

    /** The record as it appears on one line of the index. */
    Map<String, Object> toValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("digest", digest);
        value.put("parent", parent);
        value.put("ledger", ledgerLength);
        value.put("complexity", complexityValue());
        value.put("note", note);
        return value;
    }

    private Map<String, Object> complexityValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("nodes", complexity.nodes());
        value.put("lines", complexity.lines());
        value.put("arms", complexity.arms());
        value.put("functions", complexity.functions());
        return value;
    }

    /**
     * Reads back one line of the index.
     *
     * @param value the parsed line
     * @return the record it denotes
     * @throws ModelStoreException if the line is not a record
     */
    static ModelRecord fromValue(Map<String, Object> value) {
        Object complexity = value.get("complexity");
        if (!(value.get("digest") instanceof String digest) || !(complexity instanceof Map)) {
            throw new ModelStoreException("a model index entry needs a digest and a complexity");
        }
        @SuppressWarnings ("unchecked")
        Map<String, Object> measured = (Map<String, Object>) complexity;
        return new ModelRecord(digest, (String) value.get("parent"),
                               count(value.get("ledger")),
                               new ModelComplexity(count(measured.get("nodes")),
                                                   count(measured.get("lines")),
                                                   count(measured.get("arms")),
                                                   count(measured.get("functions"))),
                               value.get("note") instanceof String note ? note : "");
    }

    private static int count(Object value) {
        if (!(value instanceof Number number)) {
            throw new ModelStoreException("a model index entry needs numbers, not " + value);
        }
        return number.intValue();
    }

    /** How the version reads in a report. */
    public String render() {
        return digest + "  " + complexity.render()
               + (parent == null ? "" : "  from " + parent)
               + "  at ledger " + ledgerLength
               + (note.isEmpty() ? "" : "  -- " + note);
    }

    @Override
    public String toString() {
        return render();
    }
}
