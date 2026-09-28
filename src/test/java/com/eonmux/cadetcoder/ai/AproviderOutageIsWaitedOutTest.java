package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.InterruptSignal;
import com.eonmux.cadetcoder.net.LLMException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * How a run nobody is watching waits for a provider that stopped answering.
 *
 * <h2>The defect</h2>
 *
 * <p>A loop left to run overnight met a 524 from its provider. The backend's automatic retries
 * were spent in about a minute and a half, and the failure was then offered to the person at the
 * terminal as "Enter to retry, or 'stop'". Nobody was there. The loop sat on that question for a
 * day, while the provider had long since recovered.</p>
 *
 * <h2>What is waited out and what is not</h2>
 *
 * <p>A 5xx or a failure to connect says the provider is down, and it comes back. A rejected key, a
 * malformed request and a rate limit say something about the account or the request, and waiting
 * does not change them: a provider that cut an account off answers 401 or 429 for as long as
 * anyone asks.</p>
 */
class AproviderOutageIsWaitedOutTest {

    private static final long MINUTE = 60_000L;

    private final List<Long> paused = new ArrayList<>();

    private final OutageWait policy =
            new OutageWait(MINUTE, 15 * MINUTE, 120 * MINUTE, paused::add);

    @AfterEach
    void tearDown() {
        InterruptSignal.clear();
        Thread.interrupted();
    }

    private static LLMException failure(LLMException.Kind kind, int status) {
        return new LLMException(kind, "the provider answered " + status, "a-provider", "a-model",
                                "https://example.invalid/v1", status, null, null, null);
    }

    private static long total(List<Long> waits) {
        return waits.stream().mapToLong(Long::longValue).sum();
    }

    @Test
    void anoutageIsWaitedOutWithWaitsThatGrowToTheLongest() {
        LLMException outage = failure(LLMException.Kind.SERVER_ERROR, 524);
        List<Long> waits  = new ArrayList<>();
        long       waited = 0;
        while (waited >= 0) {
            int before = paused.size();
            long now = policy.waitOut(outage, waited);
            if (now >= 0) {
                waits.add(now - waited);
                assertThat(total(paused.subList(before, paused.size())))
                        .as("what it says it waited is what it paused for")
                        .isEqualTo(now - waited);
            }
            waited = now;
        }

        assertThat(waits.subList(0, 6))
                .containsExactly(MINUTE, MINUTE, 2 * MINUTE, 4 * MINUTE, 8 * MINUTE, 15 * MINUTE);
        assertThat(waits).allMatch(wait -> wait <= 15 * MINUTE);
        assertThat(total(waits)).as("it gives up once the budget is spent").isEqualTo(120 * MINUTE);
    }

    @Test
    void afailureToConnectIsAnOutageToo() {
        assertThat(policy.waitOut(failure(LLMException.Kind.TRANSPORT, 0), 0)).isEqualTo(MINUTE);
    }

    @Test
    void afailureWaitingCannotChangeIsNotWaitedFor() {
        assertThat(policy.waitOut(failure(LLMException.Kind.AUTH, 401), 0)).isNegative();
        assertThat(policy.waitOut(failure(LLMException.Kind.RATE_LIMITED, 429), 0)).isNegative();
        assertThat(policy.waitOut(failure(LLMException.Kind.BAD_REQUEST, 400), 0)).isNegative();
        assertThat(policy.waitOut(null, 0)).isNegative();
        assertThat(paused).isEmpty();
    }

    @Test
    void astopDuringTheWaitEndsItAsAstopAndNotAsAfailure() {
        InterruptSignal.request();

        LLMException ended = catchThrowableOfType(
                () -> policy.waitOut(failure(LLMException.Kind.SERVER_ERROR, 503), 0),
                LLMException.class);

        assertThat(ended).isNotNull();
        assertThat(ended.getKind()).isEqualTo(LLMException.Kind.STOPPED);
    }

    @Test
    void thelimitsAreReadFromTheirPropertiesAndAbadValueFallsBack() {
        System.setProperty(OutageWait.FIRST_WAIT_MS_PROPERTY, "5000");
        System.setProperty(OutageWait.BUDGET_MS_PROPERTY, "not a number");
        try {
            OutageWait read = OutageWait.fromSystemProperties();

            assertThat(read.firstWaitMillis()).isEqualTo(5000L);
            assertThat(read.budgetMillis()).isEqualTo(OutageWait.DEFAULT_BUDGET_MS);
            assertThat(read.longestWaitMillis()).isEqualTo(OutageWait.DEFAULT_LONGEST_WAIT_MS);
        } finally {
            System.clearProperty(OutageWait.FIRST_WAIT_MS_PROPERTY);
            System.clearProperty(OutageWait.BUDGET_MS_PROPERTY);
        }
    }
}
