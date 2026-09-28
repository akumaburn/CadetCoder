package com.eonmux.cadetcoder.harness.loop;

/**
 * What a run came to.
 *
 * <h2>Why the counts are here and not left to be looked up</h2>
 *
 * <p>The budget, the ledger and the registry all outlive the run and all keep moving if anything
 * else touches them. A result read back out of them afterwards is a result about whenever it was
 * read. These are the figures as at the moment the run stopped, which is the only moment they
 * describe.</p>
 *
 * @param status        how it ended
 * @param deliberations how many rounds of thinking it took
 * @param actions       how many things really happened to the world
 * @param ledgerLength  how much the record held when it stopped
 * @param escalations   how many times a stronger reasoner took over
 * @param finalText     the last thing said, which is the declaration or the allowance that ran out
 */
public record RunResult(RunStatus status, int deliberations, int actions, int ledgerLength,
                        int escalations, String finalText) {

    public RunResult {
        if (status == null || status == RunStatus.RUNNING) {
            throw new IllegalArgumentException("a result is about a run that is over");
        }
        if (deliberations < 0 || actions < 0 || ledgerLength < 0 || escalations < 0) {
            throw new IllegalArgumentException("a run cannot have done something a negative number "
                                               + "of times");
        }
        finalText = finalText == null ? "" : finalText;
    }

    /** The one line a driver prints when a run stops. */
    public String render() {
        return status + " after " + deliberations + " deliberations, " + actions + " actions, "
               + ledgerLength + " in the ledger"
               + (escalations == 0 ? "" : ", " + escalations + " escalations")
               + (finalText.isEmpty() ? "" : System.lineSeparator() + finalText);
    }

    @Override
    public String toString() {
        return render();
    }
}
