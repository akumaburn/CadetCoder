package com.eonmux.cadetcoder.session;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A session outlives the invocation that made it, so its history has to stop growing somewhere. */
public class ConversationHistoryBoundTest {

    private static int charsIn(List<String> history) {
        int total = 0;
        for (String entry : history) {
            total += entry == null ? 0 : entry.length();
        }
        return total;
    }

    @Test
    public void bothHalvesOfAnExchangeAreRecorded() {
        SessionManager sessions = SessionManager.getInstance();
        sessions.clearConversationHistory();

        // Only responses used to be recorded, so a restored session was a list of answers with none
        // of the questions -- the model saw what it had said and no sign of what it was asked.
        sessions.addUserRequest("remember the secret code");
        sessions.addToConversationHistory("AI: the code is 7731");

        assertThat(sessions.getConversationHistory())
                .containsExactly("User: remember the secret code", "AI: the code is 7731");
    }

    @Test
    public void aRepeatedTurnIsRecordedOnce() {
        SessionManager sessions = SessionManager.getInstance();
        sessions.clearConversationHistory();

        // A retried or reformatted turn produces the same text again; one real exchange became nine
        // identical lines, which restores as the same sentence nine times.
        sessions.addToConversationHistory("AI: same answer");
        sessions.addToConversationHistory("AI: same answer");
        sessions.addToConversationHistory("AI: same answer");

        assertThat(sessions.getConversationHistory()).containsExactly("AI: same answer");
    }

    @Test
    public void aRepeatThatIsNotConsecutiveIsStillRecorded() {
        SessionManager sessions = SessionManager.getInstance();
        sessions.clearConversationHistory();

        // Only the immediately preceding turn is compared: the same answer given again later is a
        // real part of the conversation, not a retry.
        sessions.addToConversationHistory("AI: yes");
        sessions.addToConversationHistory("User: are you sure");
        sessions.addToConversationHistory("AI: yes");

        assertThat(sessions.getConversationHistory()).hasSize(3);
    }

    @Test
    public void nothingIsRecordedForAnEmptyTurn() {
        SessionManager sessions = SessionManager.getInstance();
        sessions.clearConversationHistory();

        sessions.addUserRequest("   ");
        sessions.addUserRequest(null);
        sessions.addToConversationHistory("");
        sessions.addToConversationHistory(null);

        assertThat(sessions.getConversationHistory()).isEmpty();
    }

    @Test
    public void anOrdinaryConversationIsKeptWhole() {
        SessionManager sessions = SessionManager.getInstance();
        int before = sessions.getConversationHistory().size();

        sessions.addToConversationHistory("User: hello");
        sessions.addToConversationHistory("AI: hi");

        assertThat(sessions.getConversationHistory()).hasSize(before + 2);
        assertThat(sessions.getConversationHistory()).endsWith("AI: hi");
    }

    @Test
    public void aSessionThatRunsForeverStopsGrowing() {
        SessionManager sessions = SessionManager.getInstance();
        // Every run continues the session in session.json, and the whole file is rewritten on every
        // save: unbounded history is unbounded disk AND work that grows with the session's age.
        String turn = "AI: " + "x".repeat(8_192);
        for (int i = 0; i < 400; i++) {
            sessions.addToConversationHistory(turn + " #" + i);
        }

        assertThat(charsIn(sessions.getConversationHistory()))
                .isLessThanOrEqualTo(SessionManager.maxHistoryChars());
    }

    @Test
    public void theBoundIsDerivedFromWhatCouldEverBeRestored() {
        // Not a chosen number: ResumedContext reopens at most a share of the prompt budget, so
        // anything past that is bytes no model can ever be handed.
        int restorable = (int) (com.eonmux.cadetcoder.ai.ContextWindow.tokens()
                                * ResumedContext.BUDGET_SHARE);
        int restorableChars = com.eonmux.cadetcoder.ai.metrics.TokenEstimator.charsFor(restorable);

        assertThat(SessionManager.maxHistoryChars())
                .as("must hold at least one full restore")
                .isGreaterThanOrEqualTo(restorableChars);
    }

    @Test
    public void aSmallContextWindowDoesNotTrimAwayWhatABiggerModelCouldHold() {
        // An uncatalogued endpoint reports the 8k default. Sizing storage to that would discard
        // context the user's real model can hold the moment they switch to it.
        assertThat(SessionManager.maxHistoryChars()).isGreaterThanOrEqualTo(65_536);
    }

    @Test
    public void theTurnsItKeepsAreTheMostRecentOnes() {
        SessionManager sessions = SessionManager.getInstance();
        String bulk = "x".repeat(8_192);
        for (int i = 0; i < 200; i++) {
            sessions.addToConversationHistory("turn " + i + " " + bulk);
        }

        // A session is resumed from where it stopped, so the newest turns are the ones worth keeping.
        List<String> history = sessions.getConversationHistory();
        assertThat(history.get(history.size() - 1)).startsWith("turn 199");
    }

    @Test
    public void oneTurnLargerThanTheWholeBudgetIsStillKept() {
        SessionManager sessions = SessionManager.getInstance();
        sessions.clearConversationHistory();

        // Trimming to nothing would lose the only record of what just happened.
        sessions.addToConversationHistory("x".repeat(SessionManager.maxHistoryChars() * 2));

        assertThat(sessions.getConversationHistory()).hasSize(1);
    }
}
