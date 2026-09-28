package com.eonmux.cadetcoder.ui;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TextSelectionModelTest {

    private static final List<String> THREE_ROWS = Arrays.asList("abcde", "fghij", "klmno");

    // ----- EMPTY / inactive -----

    @Test
    public void emptyIsInactiveWithSentinelBoundsAndNoSpanOrText() {
        TextSelectionModel m = TextSelectionModel.EMPTY;

        assertThat(m.isActive()).isFalse();
        assertThat(m.isEmptySelection()).isFalse();

        assertThat(m.startRow()).isEqualTo(-1);
        assertThat(m.startCol()).isEqualTo(-1);
        assertThat(m.endRow()).isEqualTo(-1);
        assertThat(m.endCol()).isEqualTo(-1);

        assertThat(m.spanForRow(0, 10)).isNull();
        assertThat(m.extract(Collections.singletonList("hello world"))).isEqualTo("");
    }

    @Test
    public void clearReturnsEmpty() {
        TextSelectionModel m = TextSelectionModel.EMPTY.start(1, 1).extendTo(3, 4);
        assertThat(m.isActive()).isTrue();

        TextSelectionModel cleared = m.clear();
        assertThat(cleared).isSameAs(TextSelectionModel.EMPTY);
        assertThat(cleared.isActive()).isFalse();
    }

    // ----- start / empty selection -----

    @Test
    public void startIsActiveButEmptySelection() {
        TextSelectionModel m = TextSelectionModel.EMPTY.start(2, 3);

        assertThat(m.isActive()).isTrue();
        assertThat(m.isEmptySelection()).isTrue();

        assertThat(m.startRow()).isEqualTo(2);
        assertThat(m.startCol()).isEqualTo(3);
        assertThat(m.endRow()).isEqualTo(2);
        assertThat(m.endCol()).isEqualTo(3);

        // Zero-width selection yields a null span and empty extract.
        assertThat(m.spanForRow(2, 10)).isNull();
        assertThat(m.extract(Collections.singletonList("xxxxxxxxxx"))).isEqualTo("");
    }

    @Test
    public void startReturnsNewInstanceAndDoesNotMutateOriginal() {
        TextSelectionModel original = TextSelectionModel.EMPTY;
        TextSelectionModel started = original.start(0, 0);

        assertThat(started).isNotSameAs(original);
        // EMPTY remains inactive.
        assertThat(original.isActive()).isFalse();
    }

    // ----- single-row selection -----

    @Test
    public void singleRowSelectionSpanAndExtract() {
        TextSelectionModel m = TextSelectionModel.EMPTY.start(0, 2).extendTo(0, 5);
        List<String> doc = Collections.singletonList("hello world");

        assertThat(m.isActive()).isTrue();
        assertThat(m.isEmptySelection()).isFalse();
        assertThat(m.spanForRow(0, 11)).containsExactly(2, 5);
        assertThat(m.extract(doc)).isEqualTo("llo");
    }

    @Test
    public void reversedDragNormalizesToSameSpanAndText() {
        TextSelectionModel forward = TextSelectionModel.EMPTY.start(0, 2).extendTo(0, 5);
        TextSelectionModel reversed = TextSelectionModel.EMPTY.start(0, 5).extendTo(0, 2);
        List<String> doc = Collections.singletonList("hello world");

        assertThat(reversed.startRow()).isEqualTo(0);
        assertThat(reversed.startCol()).isEqualTo(2);
        assertThat(reversed.endRow()).isEqualTo(0);
        assertThat(reversed.endCol()).isEqualTo(5);

        assertThat(reversed.spanForRow(0, 11)).containsExactly(2, 5);
        assertThat(reversed.extract(doc)).isEqualTo("llo");
        assertThat(reversed.extract(doc)).isEqualTo(forward.extract(doc));
    }

    // ----- multi-row selection -----

    @Test
    public void multiRowSelectionSpansAndExtract() {
        TextSelectionModel m = TextSelectionModel.EMPTY.start(0, 2).extendTo(2, 3);

        assertThat(m.startRow()).isEqualTo(0);
        assertThat(m.startCol()).isEqualTo(2);
        assertThat(m.endRow()).isEqualTo(2);
        assertThat(m.endCol()).isEqualTo(3);

        // First row: [startCol, rowLength); middle row: whole line; last row: [0, endCol).
        assertThat(m.spanForRow(0, 5)).containsExactly(2, 5);
        assertThat(m.spanForRow(1, 5)).containsExactly(0, 5);
        assertThat(m.spanForRow(2, 5)).containsExactly(0, 3);

        assertThat(m.extract(THREE_ROWS)).isEqualTo("cde\nfghij\nklm");
    }

    @Test
    public void reversedMultiRowDragNormalizes() {
        TextSelectionModel m = TextSelectionModel.EMPTY.start(2, 3).extendTo(0, 2);

        assertThat(m.startRow()).isEqualTo(0);
        assertThat(m.startCol()).isEqualTo(2);
        assertThat(m.endRow()).isEqualTo(2);
        assertThat(m.endCol()).isEqualTo(3);
        assertThat(m.extract(THREE_ROWS)).isEqualTo("cde\nfghij\nklm");
    }

    // ----- clamping -----

    @Test
    public void spanForRowClampsWhenRowLengthSmallerThanEndCol() {
        // End column (10) is past the actual last-row length (3): must clamp, never overflow.
        TextSelectionModel m = TextSelectionModel.EMPTY.start(0, 0).extendTo(1, 10);

        int[] lastRowSpan = m.spanForRow(1, 3);
        assertThat(lastRowSpan).containsExactly(0, 3);
        assertThat(lastRowSpan[1]).isLessThanOrEqualTo(3);
    }

    @Test
    public void singleRowSpanClampsEndColIntoRange() {
        // Selection end col 9 against a 4-char row clamps the upper bound to 4.
        TextSelectionModel m = TextSelectionModel.EMPTY.start(0, 1).extendTo(0, 9);
        assertThat(m.spanForRow(0, 4)).containsExactly(1, 4);
    }

    @Test
    public void spanForRowReturnsNullWhenStartColPastShortRow() {
        // startCol clamps to rowLength, producing from >= to -> null.
        TextSelectionModel m = TextSelectionModel.EMPTY.start(0, 8).extendTo(0, 9);
        assertThat(m.spanForRow(0, 3)).isNull();
    }

    @Test
    public void spanForRowReturnsNullForZeroLengthRow() {
        TextSelectionModel m = TextSelectionModel.EMPTY.start(0, 0).extendTo(2, 3);
        // Middle row of length 0 has an empty span.
        assertThat(m.spanForRow(1, 0)).isNull();
    }

    // ----- rows outside the selection range -----

    @Test
    public void spanForRowReturnsNullOutsideRange() {
        TextSelectionModel m = TextSelectionModel.EMPTY.start(1, 0).extendTo(3, 2);

        assertThat(m.spanForRow(0, 10)).isNull(); // before startRow
        assertThat(m.spanForRow(4, 10)).isNull(); // after endRow
        assertThat(m.spanForRow(2, 10)).isNotNull(); // inside range
    }

    // ----- extendTo on EMPTY behaves like start -----

    @Test
    public void extendToOnEmptyBehavesLikeStart() {
        TextSelectionModel viaExtend = TextSelectionModel.EMPTY.extendTo(2, 4);
        TextSelectionModel viaStart = TextSelectionModel.EMPTY.start(2, 4);

        assertThat(viaExtend.isActive()).isTrue();
        assertThat(viaExtend.isEmptySelection()).isTrue();
        assertThat(viaExtend.startRow()).isEqualTo(viaStart.startRow());
        assertThat(viaExtend.startCol()).isEqualTo(viaStart.startCol());
        assertThat(viaExtend.endRow()).isEqualTo(viaStart.endRow());
        assertThat(viaExtend.endCol()).isEqualTo(viaStart.endCol());
    }

    // ----- negative input clamping -----

    @Test
    public void negativeStartInputsClampToZero() {
        TextSelectionModel m = TextSelectionModel.EMPTY.start(-5, -3);
        assertThat(m.startRow()).isEqualTo(0);
        assertThat(m.startCol()).isEqualTo(0);
        assertThat(m.endRow()).isEqualTo(0);
        assertThat(m.endCol()).isEqualTo(0);
    }

    @Test
    public void negativeExtendInputsClampToZero() {
        TextSelectionModel m = TextSelectionModel.EMPTY.start(1, 1).extendTo(-9, -9);
        // Focus clamps to (0,0); normalized so (0,0) is start, (1,1) is end.
        assertThat(m.startRow()).isEqualTo(0);
        assertThat(m.startCol()).isEqualTo(0);
        assertThat(m.endRow()).isEqualTo(1);
        assertThat(m.endCol()).isEqualTo(1);
    }

    // ----- robustness against short / null docLines -----

    @Test
    public void extractRobustWhenDocLinesShorterThanEndRow() {
        TextSelectionModel m = TextSelectionModel.EMPTY.start(0, 1).extendTo(2, 2);
        // Only one row exists, but the selection spans three rows.
        List<String> doc = Collections.singletonList("abcde");

        String result = m.extract(doc);
        // Row 0 contributes "bcde" (from col 1 to end), rows 1 and 2 are out of range -> empty.
        assertThat(result).isEqualTo("bcde\n\n");
    }

    @Test
    public void extractHandlesNullDocLines() {
        TextSelectionModel m = TextSelectionModel.EMPTY.start(0, 0).extendTo(0, 3);
        assertThat(m.extract(null)).isEqualTo("");
    }

    @Test
    public void extractHandlesNullRowText() {
        TextSelectionModel m = TextSelectionModel.EMPTY.start(0, 0).extendTo(1, 2);
        // Middle/first row is null -> treated as empty text.
        List<String> doc = Arrays.asList(null, "klmno");
        // Row 0 null -> "", row 1 -> "kl".
        assertThat(m.extract(doc)).isEqualTo("\nkl");
    }

    @Test
    public void inactiveExtractReturnsEmpty() {
        assertThat(TextSelectionModel.EMPTY.extract(THREE_ROWS)).isEqualTo("");
    }
}
