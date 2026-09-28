package com.eonmux.cadetcoder.ui;

import java.util.List;

/**
 * Pure-logic model of a click-drag text selection over a list of already-rendered
 * text rows (a "document").
 *
 * <p>This type carries no terminal or UI-library dependency. It only deals with
 * coordinates and strings so that a TUI can (a) highlight the selected cells and
 * (b) extract the selected text to copy.
 *
 * <p>Coordinates are {@code (row, col)} where {@code row} is a 0-based index into
 * the document's list of rendered lines and {@code col} is a 0-based character
 * column within that line. A selection has an <em>anchor</em> (where the drag
 * started) and a <em>focus</em> (where it currently is / ended). The normalized
 * {@code start} is the lexicographically smaller of anchor/focus (compared by row,
 * then col) and {@code end} the larger.
 *
 * <p>The type is an immutable value type: every "mutator" returns a NEW instance and
 * no existing instance is ever mutated in place.
 */
public final class TextSelectionModel {

    /**
     * Sentinel returned by the normalized-bound accessors when the selection is not
     * active. Callers must guard with {@link #isActive()} before relying on bounds.
     */
    private static final int INACTIVE_BOUND = -1;

    /**
     * The inactive/empty selection singleton. Has no anchor: {@link #isActive()} is
     * {@code false}, bounds return {@code -1}, {@link #spanForRow(int, int)} returns
     * {@code null}, and {@link #extract(List)} returns {@code ""}.
     */
    public static final TextSelectionModel EMPTY = new TextSelectionModel();

    /** Whether an anchor has been set (i.e. a selection has been started). */
    private final boolean active;

    private final int anchorRow;
    private final int anchorCol;
    private final int focusRow;
    private final int focusCol;

    /** Constructs the inactive EMPTY instance. */
    private TextSelectionModel() {
        this.active = false;
        this.anchorRow = 0;
        this.anchorCol = 0;
        this.focusRow = 0;
        this.focusCol = 0;
    }

    /** Constructs an active selection from already-clamped coordinates. */
    private TextSelectionModel(int anchorRow, int anchorCol, int focusRow, int focusCol) {
        this.active = true;
        this.anchorRow = anchorRow;
        this.anchorCol = anchorCol;
        this.focusRow = focusRow;
        this.focusCol = focusCol;
    }

    private static int clampNonNegative(int value) {
        return value < 0 ? 0 : value;
    }

    /**
     * Begins a selection: anchor = focus = {@code (row, col)}. Negative inputs clamp
     * to {@code 0}. Returns a NEW active model.
     */
    public TextSelectionModel start(int row, int col) {
        int r = clampNonNegative(row);
        int c = clampNonNegative(col);
        return new TextSelectionModel(r, c, r, c);
    }

    /**
     * Moves the focus to {@code (row, col)}, keeping the anchor. Negative inputs clamp
     * to {@code 0}. Returns a NEW model. If called on an inactive model (no anchor yet),
     * behaves like {@link #start(int, int)}.
     */
    public TextSelectionModel extendTo(int row, int col) {
        if (!active) {
            return start(row, col);
        }
        int r = clampNonNegative(row);
        int c = clampNonNegative(col);
        return new TextSelectionModel(anchorRow, anchorCol, r, c);
    }

    /** Returns {@link #EMPTY} (clears the selection). */
    public TextSelectionModel clear() {
        return EMPTY;
    }

    /** Whether a selection has been started (anchor set). */
    public boolean isActive() {
        return active;
    }

    /**
     * True when active but anchor == focus (zero characters selected). Always
     * {@code false} when the model is not active.
     */
    public boolean isEmptySelection() {
        return active && anchorRow == focusRow && anchorCol == focusCol;
    }

    /**
     * Whether the anchor is lexicographically less-than-or-equal-to the focus,
     * comparing by row, then col. Used to pick which endpoint is start vs. end.
     */
    private boolean anchorIsStart() {
        if (anchorRow != focusRow) {
            return anchorRow < focusRow;
        }
        return anchorCol <= focusCol;
    }

    /** Normalized start row (inclusive). Returns {@code -1} when {@code !isActive()}. */
    public int startRow() {
        if (!active) {
            return INACTIVE_BOUND;
        }
        return anchorIsStart() ? anchorRow : focusRow;
    }

    /** Normalized start column (inclusive). Returns {@code -1} when {@code !isActive()}. */
    public int startCol() {
        if (!active) {
            return INACTIVE_BOUND;
        }
        return anchorIsStart() ? anchorCol : focusCol;
    }

    /** Normalized end row (inclusive). Returns {@code -1} when {@code !isActive()}. */
    public int endRow() {
        if (!active) {
            return INACTIVE_BOUND;
        }
        return anchorIsStart() ? focusRow : anchorRow;
    }

    /**
     * Normalized end column (exclusive boundary of the selection on the end row).
     * Returns {@code -1} when {@code !isActive()}.
     */
    public int endCol() {
        if (!active) {
            return INACTIVE_BOUND;
        }
        return anchorIsStart() ? focusCol : anchorCol;
    }

    private static int clampToRange(int value, int max) {
        if (value < 0) {
            return 0;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    /**
     * For document row {@code row} whose text length is {@code rowLength}, returns the
     * half-open column span {@code [from, toExclusive)} that is selected on that row, or
     * {@code null} if the row is not part of the selection (or the span is empty).
     *
     * <p>Standard multi-row selection semantics:
     * <ul>
     *   <li>first row: {@code from = startCol}, {@code to = (single-row ? endCol : rowLength)}</li>
     *   <li>middle rows: {@code from = 0}, {@code to = rowLength} (whole line)</li>
     *   <li>last row: {@code from = 0}, {@code to = endCol}</li>
     * </ul>
     *
     * <p>{@code from} and {@code to} are clamped into {@code [0, rowLength]}. Returns
     * {@code null} if {@code !isActive()}, {@code row < startRow}, {@code row > endRow},
     * or the clamped {@code from >= to}. {@code rowLength} is treated as non-negative
     * (negative values clamp to {@code 0}).
     */
    public int[] spanForRow(int row, int rowLength) {
        if (!active) {
            return null;
        }
        int sRow = startRow();
        int eRow = endRow();
        if (row < sRow || row > eRow) {
            return null;
        }

        int max = rowLength < 0 ? 0 : rowLength;

        int rawFrom;
        int rawTo;
        boolean singleRow = sRow == eRow;
        if (singleRow) {
            rawFrom = startCol();
            rawTo = endCol();
        } else if (row == sRow) {
            rawFrom = startCol();
            rawTo = max;
        } else if (row == eRow) {
            rawFrom = 0;
            rawTo = endCol();
        } else {
            rawFrom = 0;
            rawTo = max;
        }

        int from = clampToRange(rawFrom, max);
        int to = clampToRange(rawTo, max);
        if (from >= to) {
            return null;
        }
        return new int[] {from, to};
    }

    /**
     * Extracts the selected text from {@code docLines} (the document's rendered lines),
     * joining rows with {@code "\n"}. Uses {@link #spanForRow(int, int)} semantics; rows
     * beyond {@code docLines} size or with {@code null} spans contribute an empty string
     * (so an empty middle row contributes an empty segment between newlines).
     *
     * <p>Returns {@code ""} if {@code !isActive()}, the selection is empty, or
     * {@code docLines} is {@code null}. Never throws on out-of-range rows: a row index
     * outside {@code docLines} is treated as empty text.
     */
    public String extract(List<String> docLines) {
        if (!active || isEmptySelection() || docLines == null) {
            return "";
        }

        int sRow = startRow();
        int eRow = endRow();
        int size = docLines.size();

        StringBuilder out = new StringBuilder();
        for (int row = sRow; row <= eRow; row++) {
            if (row > sRow) {
                out.append('\n');
            }
            if (row < 0 || row >= size) {
                // Out-of-range row: contributes empty text but preserves newline structure.
                continue;
            }
            String text = docLines.get(row);
            if (text == null) {
                text = "";
            }
            int[] span = spanForRow(row, text.length());
            if (span != null) {
                out.append(text, span[0], span[1]);
            }
        }
        return out.toString();
    }
}
