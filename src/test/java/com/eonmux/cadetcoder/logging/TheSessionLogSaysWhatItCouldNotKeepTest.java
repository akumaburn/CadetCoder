package com.eonmux.cadetcoder.logging;

import org.junit.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A session log that could not keep up says so in itself.
 *
 * <h2>The defect</h2>
 *
 * <p>Entries were handed to a bounded queue with {@code offer}, whose {@code false} return was
 * discarded. When a burst of output filled the queue -- and everything a command prints goes
 * through this logger -- the entries that did not fit were dropped without a word. The file that
 * remained looked complete: nothing in it said that lines were missing, so a session log read to
 * work out what happened would be believed, and the reader would conclude that whatever was lost
 * had never occurred.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>Entries that do not fit are still dropped -- blocking the thread that logged, so that the tool
 * stops to keep its diary, is worse -- but they are counted, and the count is handed to the writer
 * to record. The hole in the log is described in the log, once per gap, and the count starts again
 * afterwards so that a later gap is reported as its own.</p>
 */
public class TheSessionLogSaysWhatItCouldNotKeepTest {

    private static SessionLogEntry entry(long sequence) {
        return SessionLogEntry.of(sequence, LogEntryType.SESSION_EVENT, "Test",
                                  "entry " + sequence, null, null);
    }

    private static PendingEntries holding(int capacity) {
        return new PendingEntries(capacity);
    }

    @Test
    public void anEntryIsHandedBackToTheWriter() throws InterruptedException {
        PendingEntries pending = holding(4);

        pending.add(entry(1));

        assertThat(pending.next(1, TimeUnit.MILLISECONDS).message).isEqualTo("entry 1");
    }

    @Test
    public void entriesComeBackInTheOrderTheyArrived() {
        PendingEntries pending = holding(4);

        pending.add(entry(1));
        pending.add(entry(2));

        assertThat(pending.nextIfWaiting().message).isEqualTo("entry 1");
        assertThat(pending.nextIfWaiting().message).isEqualTo("entry 2");
        assertThat(pending.nextIfWaiting()).isNull();
    }

    @Test
    public void aQueueWithRoomHasNothingToReport() {
        PendingEntries pending = holding(4);

        pending.add(entry(1));
        pending.add(entry(2));

        assertThat(pending.whatWasLost()).isEmpty();
    }

    @Test
    public void anEntryThatDoesNotFitIsCountedRatherThanForgotten() {
        PendingEntries pending = holding(2);

        pending.add(entry(1));
        pending.add(entry(2));
        pending.add(entry(3));

        assertThat(pending.whatWasLost())
                .as("the offer that did not fit used to return false to nobody")
                .isPresent();
    }

    @Test
    public void theReportSaysHowManyWereLost() {
        PendingEntries pending = holding(1);

        pending.add(entry(1));
        pending.add(entry(2));
        pending.add(entry(3));
        pending.add(entry(4));

        assertThat(pending.whatWasLost()).hasValueSatisfying(
                said -> assertThat(said).contains("3").contains("were not recorded"));
    }

    @Test
    public void oneLostEntryIsDescribedAsOne() {
        PendingEntries pending = holding(1);

        pending.add(entry(1));
        pending.add(entry(2));

        assertThat(pending.whatWasLost()).hasValueSatisfying(
                said -> assertThat(said).contains("1 log entry was not recorded"));
    }

    @Test
    public void aGapIsReportedOnceAndNotAgain() {
        PendingEntries pending = holding(1);
        pending.add(entry(1));
        pending.add(entry(2));

        Optional<String> first  = pending.whatWasLost();
        Optional<String> second = pending.whatWasLost();

        assertThat(first).isPresent();
        assertThat(second)
                .as("the same gap repeated every time round the writer's loop would fill the log")
                .isEmpty();
    }

    @Test
    public void aLaterGapIsReportedAsItsOwn() {
        PendingEntries pending = holding(1);
        pending.add(entry(1));
        pending.add(entry(2));
        pending.whatWasLost();

        pending.nextIfWaiting();
        pending.add(entry(3));
        pending.add(entry(4));

        assertThat(pending.whatWasLost()).hasValueSatisfying(
                said -> assertThat(said).contains("1 log entry was not recorded"));
    }

    @Test
    public void roomFreedByTheWriterIsUsedAgain() {
        PendingEntries pending = holding(1);
        pending.add(entry(1));
        pending.add(entry(2));

        pending.nextIfWaiting();
        pending.add(entry(3));

        assertThat(pending.nextIfWaiting().message).isEqualTo("entry 3");
    }

    @Test
    public void howMuchIsWaitingIsWhatHasNotBeenWritten() {
        PendingEntries pending = holding(4);

        pending.add(entry(1));
        pending.add(entry(2));

        assertThat(pending.isEmpty()).isFalse();
        assertThat(pending.size()).isEqualTo(2);
    }

    @Test
    public void anEmptyQueueSaysSo() {
        assertThat(holding(4).isEmpty()).isTrue();
    }
}
