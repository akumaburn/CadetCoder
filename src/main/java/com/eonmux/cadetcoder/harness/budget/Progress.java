package com.eonmux.cadetcoder.harness.budget;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where a run stood at the end of one deliberation.
 *
 * <h2>Why these five numbers</h2>
 *
 * <p>They are the ones that have to move for the run to be learning anything. Certified transitions
 * and mispredictions are the model getting better; ledger length is the world being observed at
 * all; the digest says whether the model that produced those numbers is even the same model. Cost
 * is carried alongside them so that a plateau can be reported as what it is -- a price paid for
 * nothing -- rather than as an abstract lack of movement.</p>
 *
 * @param deliberation how many deliberations had happened when this was taken
 * @param certifiedOk  how many transitions the model replayed correctly
 * @param mismatches   how many it got wrong
 * @param ledgerLength how much had been observed
 * @param modelDigest  which model was being certified, or {@code null} when there was none
 * @param actions      how many actions had been spent
 */
public record Progress(int deliberation, int certifiedOk, int mismatches, int ledgerLength,
                       String modelDigest, int actions) {

    /** How much of a digest a progress line shows. */
    private static final int DIGEST_SHOWN = 8;

    public Map<String, Object> toValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("deliberation", deliberation);
        value.put("certified_ok", certifiedOk);
        value.put("mismatches", mismatches);
        value.put("ledger_length", ledgerLength);
        value.put("model_digest", modelDigest);
        value.put("actions", actions);
        return Map.copyOf(value);
    }

    public String render() {
        return "#" + deliberation + " certified=" + certifiedOk + " wrong=" + mismatches
               + " ledger=" + ledgerLength + " actions=" + actions + " model=" + shortDigest();
    }

    private String shortDigest() {
        if (modelDigest == null) {
            return "none";
        }
        return modelDigest.length() <= DIGEST_SHOWN ? modelDigest
                                                    : modelDigest.substring(0, DIGEST_SHOWN);
    }

    @Override
    public String toString() {
        return render();
    }
}
