package com.eonmux.cadetcoder.harness.fit;

/**
 * The four comparisons an atomic predicate can make.
 *
 * <h2>Why only four</h2>
 *
 * <p>Equality and its negation cover categories; a lower bound and its negation cover order, and
 * between them they cover every split of a numeric feature that the data can distinguish. Adding
 * {@code >} and {@code <=} would double the search without adding a single new extension, because
 * {@code x > t} over values the data actually contains is {@code x >= t'} for the next value up.</p>
 */
public enum AtomOp {

    /** The feature holds this value. */
    EQ("=="),

    /** The feature holds some other value. */
    NE("!="),

    /** The feature is a number at or above this bound. */
    GE(">="),

    /** The feature is a number below this bound. */
    LT("<");

    private final String symbol;

    AtomOp(String symbol) {
        this.symbol = symbol;
    }

    /** How the comparison is written in a predicate. */
    public String symbol() {
        return symbol;
    }

    /**
     * The comparison written this way.
     *
     * @param symbol one of {@code ==}, {@code !=}, {@code >=}, {@code <}
     * @return the comparison
     * @throws IllegalArgumentException if nothing is written that way
     */
    public static AtomOp of(String symbol) {
        for (AtomOp op : values()) {
            if (op.symbol.equals(symbol)) {
                return op;
            }
        }
        throw new IllegalArgumentException("there is no comparison written " + symbol);
    }

    @Override
    public String toString() {
        return symbol;
    }
}
