package com.eonmux.cadetcoder.harness.fit;

/**
 * How much of the predicate space one fit is allowed to look at.
 *
 * <h2>Why a fit needs a budget at all</h2>
 *
 * <p>The number of conjunctions grows as the number of atoms to the power of the conjunction size,
 * and the number of atoms grows with the number of distinct values in the data. A single integer
 * feature over three hundred states contributes six hundred atoms by itself, and three-atom
 * conjunctions over six hundred atoms is thirty-six million tests. Every one of these limits exists
 * so that the answer arrives, and every one of them is reported when it binds, because a fit that
 * quietly looked at a tenth of the space and found nothing has said nothing.</p>
 *
 * @param maxAtoms        how many atoms one conjunction may hold
 * @param maxCandidates   how many candidates the result reports
 * @param maxValues       how many distinct values a numeric feature may have before equality atoms
 *                        stop being worth generating for it
 * @param atomsPerFeature how many atoms any one feature may contribute, so that a feature with
 *                        hundreds of values cannot crowd the others out of the search entirely
 * @param maxAtomsTotal   how many atoms the search runs over at all
 * @param maxCombinations how many conjunctions are tested before the search gives up
 */
public record FitLimits(int maxAtoms, int maxCandidates, int maxValues, int atomsPerFeature,
                        int maxAtomsTotal, int maxCombinations) {

    /** Conjunctions of two atoms: large enough for a real rule, small enough to trust. */
    public static final int STANDARD_MAX_ATOMS = 2;

    private static final int STANDARD_MAX_CANDIDATES   = 20;
    private static final int STANDARD_MAX_VALUES       = 12;
    private static final int STANDARD_ATOMS_PER_FEATURE = 24;
    private static final int STANDARD_MAX_ATOMS_TOTAL  = 400;
    private static final int STANDARD_MAX_COMBINATIONS = 200_000;

    public FitLimits {
        positive("maxAtoms", maxAtoms);
        positive("maxCandidates", maxCandidates);
        positive("maxValues", maxValues);
        positive("atomsPerFeature", atomsPerFeature);
        positive("maxAtomsTotal", maxAtomsTotal);
        positive("maxCombinations", maxCombinations);
    }

    /** What a fit costs when nobody has said otherwise. */
    public static FitLimits standard() {
        return new FitLimits(STANDARD_MAX_ATOMS, STANDARD_MAX_CANDIDATES, STANDARD_MAX_VALUES,
                             STANDARD_ATOMS_PER_FEATURE, STANDARD_MAX_ATOMS_TOTAL,
                             STANDARD_MAX_COMBINATIONS);
    }

    /** The same limits, allowing conjunctions of a different size. */
    public FitLimits toAtoms(int atoms) {
        return new FitLimits(atoms, maxCandidates, maxValues, atomsPerFeature, maxAtomsTotal,
                             maxCombinations);
    }

    /** The same limits, reporting a different number of candidates. */
    public FitLimits toCandidates(int candidates) {
        return new FitLimits(maxAtoms, candidates, maxValues, atomsPerFeature, maxAtomsTotal,
                             maxCombinations);
    }

    /** The same limits, giving up after a different number of conjunctions. */
    public FitLimits toCombinations(int combinations) {
        return new FitLimits(maxAtoms, maxCandidates, maxValues, atomsPerFeature, maxAtomsTotal,
                             combinations);
    }

    private static void positive(String name, int given) {
        if (given <= 0) {
            throw new IllegalArgumentException(name + " has to be at least 1, not " + given);
        }
    }
}
