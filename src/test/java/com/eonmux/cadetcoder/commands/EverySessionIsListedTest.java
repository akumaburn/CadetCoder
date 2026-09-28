package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.session.SessionState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code session list} lists every session unless it is asked for fewer.
 *
 * <p>It stopped at ten without saying so, and the eleventh session could not be found from the
 * list it was meant to be found in.</p>
 */
class EverySessionIsListedTest {

    @Test
    void withNoCountEverySessionIsListed() {
        assertThat(SessionCommand.listLimit(new String[] {"list"})).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void acountLimitsTheList() {
        assertThat(SessionCommand.listLimit(new String[] {"list", "5"})).isEqualTo(5);
    }

    @Test
    void eachSessionIsListedWithItsWholeFirstInstruction() {
        String instruction = "Understand this project, then do a full improvement pass with headless"
                             + " experimentation on CustomStrategy, the goal is to improve profit";
        SessionState state = new SessionState();
        state.getConversationHistory().add("User: " + instruction);

        assertThat(SessionCommand.preview(state)).isEqualTo(instruction);
    }

    @Test
    void acountThatIsNotAnumberIsRefused() {
        assertThat(SessionCommand.listLimit(new String[] {"list", "some"})).isNegative();
    }
}
