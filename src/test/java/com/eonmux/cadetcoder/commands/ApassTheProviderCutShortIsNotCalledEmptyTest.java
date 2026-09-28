package com.eonmux.cadetcoder.commands;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a loop says when the provider stops answering part-way through a pass.
 *
 * <h2>The defect</h2>
 *
 * <p>Pass 2 of a loop ran for four and a half hours and 123 iterations before the provider began
 * answering every request with HTTP 524. The loop then said "The model could not be reached, so pass
 * 2 did no work. Stopping with 99 of the 100 passes unrun". Both halves were wrong: the pass had done
 * most of an afternoon's work, and it was not unrun, so 98 passes were left, not 99. Somebody
 * reading that line would conclude the work was lost and look no further.</p>
 */
class ApassTheProviderCutShortIsNotCalledEmptyTest {

    @Test
    void apassThatRanSaysHowFarItGot() {
        String said = LoopCommand.cutShort(2, 100, 1, 123);

        assertThat(said)
                .contains("pass 2")
                .contains("123 iterations")
                .contains("98 of the 100 passes")
                .doesNotContain("did no work");
    }

    @Test
    void apassThatNeverReachedTheModelStillSaysItDidNoWork() {
        String said = LoopCommand.cutShort(2, 100, 1, 0);

        assertThat(said).contains("pass 2 did no work").contains("99 of the 100 passes");
    }

    @Test
    void oneIterationIsCountedInTheSingular() {
        assertThat(LoopCommand.cutShort(1, 3, 0, 1)).contains("after 1 iteration,");
    }
}
