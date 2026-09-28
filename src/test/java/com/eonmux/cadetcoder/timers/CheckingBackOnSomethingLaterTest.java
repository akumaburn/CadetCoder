package com.eonmux.cadetcoder.timers;

import com.eonmux.cadetcoder.agents.WorkerPool;
import com.eonmux.cadetcoder.agents.WorkerTask;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Timers the model sets itself, and what arrives when one comes due.
 *
 * <p><b>The defect</b>: an agent has no clock. It sees a prompt, answers it, and is gone until the
 * next one -- so "check back on this in two minutes" is something it can intend and cannot arrange.
 * Long jobs were therefore either waited out inside one command, with the run blind for the
 * duration, or polled by spending a model call on asking whether it was time yet.</p>
 *
 * <p><b>What is locked here</b>: that a timer is not owed before its interval and is owed after it;
 * that the count the model is given is the number of times the thing has actually happened; that a
 * firing is delivered once and not to two prompts; that a long command does not bank up ten
 * identical reminders; that a limited timer reports its last firing and then stops existing; that
 * one agent's timers never reach another's prompt; and that the notice says who is speaking, since
 * it arrives where the user's own words arrive.</p>
 */
public class CheckingBackOnSomethingLaterTest {

    private static final Duration EVERY_MINUTE = Duration.ofMinutes(1);

    private Instant start;

    @Before
    public void clearTimers() {
        TimerRegistry.clearAll();
        start = Instant.parse("2026-09-13T10:00:00Z");
    }

    @After
    public void leaveNothingRunning() {
        TimerRegistry.clearAll();
    }

    @Test
    public void atimerIsNotOwedBeforeItsIntervalHasPassed() {
        TimerRegistry.create("check the build", EVERY_MINUTE, AgentTimer.UNLIMITED, start);

        assertThat(TimerRegistry.takeDue(start.plusSeconds(59))).isEmpty();
        assertThat(TimerRegistry.takeDue(start.plusSeconds(60))).hasSize(1);
    }

    /** The nonce is what tells a third check on a build from a first. */
    @Test
    public void thecountIsHowManyTimesTheThingHasActuallyHappened() {
        TimerRegistry.create("check the build", EVERY_MINUTE, AgentTimer.UNLIMITED, start);

        assertThat(TimerRegistry.takeDue(start.plusSeconds(60)).get(0).nonce()).isEqualTo(1);
        assertThat(TimerRegistry.takeDue(start.plusSeconds(120)).get(0).nonce()).isEqualTo(2);
        assertThat(TimerRegistry.takeDue(start.plusSeconds(180)).get(0).nonce()).isEqualTo(3);
    }

    /**
     * Asking twice at the same moment must not hand the same firing to two prompts: a count that
     * could be read twice would stop meaning how many times anything had happened.
     */
    @Test
    public void afiringIsTakenWhenItIsReported() {
        TimerRegistry.create("check the build", EVERY_MINUTE, AgentTimer.UNLIMITED, start);

        assertThat(TimerRegistry.takeDue(start.plusSeconds(60))).hasSize(1);
        assertThat(TimerRegistry.takeDue(start.plusSeconds(60))).isEmpty();
    }

    /**
     * A run can spend twenty minutes inside one command. Ten identical reminders would be nine
     * pieces of noise, and the count in each of them would be wrong about what had happened.
     */
    @Test
    public void alongCommandDoesNotBankUpTenIdenticalReminders() {
        TimerRegistry.create("check the build", EVERY_MINUTE, AgentTimer.UNLIMITED, start);

        List<TimerFiring> owed = TimerRegistry.takeDue(start.plusSeconds(600));

        assertThat(owed).hasSize(1);
        assertThat(owed.get(0).nonce()).isEqualTo(1);
        assertThat(TimerRegistry.takeDue(start.plusSeconds(601))).isEmpty();
    }

    @Test
    public void alimitedTimerReportsItsLastFiringAndThenStopsExisting() {
        TimerRegistry.create("check twice", EVERY_MINUTE, 2, start);

        assertThat(TimerRegistry.takeDue(start.plusSeconds(60))).hasSize(1);

        List<TimerFiring> last = TimerRegistry.takeDue(start.plusSeconds(120));
        assertThat(last).hasSize(1);
        assertThat(last.get(0).nonce()).isEqualTo(2);
        assertThat(last.get(0).remaining()).isEqualTo("0");

        assertThat(TimerRegistry.active()).isEmpty();
        assertThat(TimerRegistry.takeDue(start.plusSeconds(180))).isEmpty();
    }

    @Test
    public void cancellingOneLeavesTheOthersRunning() {
        AgentTimer first = TimerRegistry.create("one", EVERY_MINUTE, AgentTimer.UNLIMITED, start);
        TimerRegistry.create("two", EVERY_MINUTE, AgentTimer.UNLIMITED, start);

        assertThat(TimerRegistry.cancel(first.id())).isTrue();
        assertThat(TimerRegistry.cancel(first.id())).isFalse();
        assertThat(TimerRegistry.active()).hasSize(1);
        assertThat(TimerRegistry.active().get(0).instruction()).isEqualTo("two");

        assertThat(TimerRegistry.cancelAll()).isEqualTo(1);
        assertThat(TimerRegistry.active()).isEmpty();
    }

    /** Every live timer takes a share of every prompt it fires into, so there is a ceiling. */
    @Test
    public void thereIsALimitToHowManyOneRunMayHave() {
        for (int i = 0; i < TimerRegistry.MAX_PER_SCOPE; i++) {
            TimerRegistry.create("check " + i, EVERY_MINUTE, AgentTimer.UNLIMITED, start);
        }
        assertThatThrownBy(() -> TimerRegistry.create("one too many", EVERY_MINUTE,
                                                      AgentTimer.UNLIMITED, start))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancel one");
    }

    /**
     * Several agents run at once under {@code workers}. A note one of them left itself must not
     * arrive in another's prompt, telling it to check on a job it has never heard of.
     */
    @Test
    public void oneAgentsTimersNeverReachAnothersPrompt() throws Exception {
        TimerRegistry.create("the session's own", EVERY_MINUTE, AgentTimer.UNLIMITED, start);

        AtomicReference<List<AgentTimer>> seenByWorker = new AtomicReference<>();
        AtomicReference<List<TimerFiring>> owedToWorker = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread worker = new Thread(() -> TimerScope.in("Worker 1", () -> {
            TimerRegistry.create("the worker's own", EVERY_MINUTE, AgentTimer.UNLIMITED, start);
            seenByWorker.set(TimerRegistry.active());
            owedToWorker.set(TimerRegistry.takeDue(start.plusSeconds(60)));
            done.countDown();
        }));
        worker.start();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(seenByWorker.get()).hasSize(1);
        assertThat(seenByWorker.get().get(0).instruction()).isEqualTo("the worker's own");
        assertThat(owedToWorker.get()).hasSize(1);
        assertThat(owedToWorker.get().get(0).instruction()).isEqualTo("the worker's own");

        // The session's timer was never taken by the worker, so it is still owed here.
        List<TimerFiring> owedToSession = TimerRegistry.takeDue(start.plusSeconds(60));
        assertThat(owedToSession).hasSize(1);
        assertThat(owedToSession.get(0).instruction()).isEqualTo("the session's own");
    }

    /**
     * The same isolation, through the thing that actually runs the workers.
     *
     * <p>The test above establishes what a scope does; this one establishes that the pool enters
     * one. Without that call each worker would set its timers in the session's own scope, and a
     * check-in one worker asked for would be read out in the prompt of whichever agent -- or of the
     * person's own next turn -- happened to be built next.</p>
     */
    @Test
    public void thepoolGivesEachWorkerAScopeOfItsOwn() {
        List<String> scopes = Collections.synchronizedList(new ArrayList<>());

        WorkerPool.run(List.of(new WorkerTask(1, "read the log", "briefing"),
                               new WorkerTask(2, "read the other log", "briefing")),
                       0, null,
                       (task, steps) -> {
                           scopes.add(TimerScope.current());
                           TimerRegistry.create("check on " + task.task(), EVERY_MINUTE,
                                                AgentTimer.UNLIMITED, start);
                           return 0;
                       });

        assertThat(scopes).containsExactlyInAnyOrder("Worker 1", "Worker 2");
        assertThat(TimerRegistry.active())
                .as("what a worker set is the worker's, not the session's")
                .isEmpty();
        assertThat(TimerRegistry.takeDue(start.plusSeconds(60))).isEmpty();
    }

    /** A pooled thread that kept a scope would hand the next worker the last one's timers. */
    @Test
    public void ascopeIsGivenBackWhenTheWorkThatNeededItEnds() {
        TimerScope.in("Worker 1", () ->
                TimerRegistry.create("the worker's own", EVERY_MINUTE, AgentTimer.UNLIMITED, start));

        assertThat(TimerScope.current()).isEqualTo(TimerScope.SESSION);
        assertThat(TimerRegistry.active()).isEmpty();
    }

    @Test
    public void thenoticeSaysWhoIsSpeakingAndWhatWasAskedFor() {
        TimerRegistry.create("check whether the build finished", EVERY_MINUTE,
                             AgentTimer.UNLIMITED, start);

        String notice = TimerNotice.render(TimerRegistry.takeDue(start.plusSeconds(60)));

        assertThat(notice).contains("[timer]");
        assertThat(notice).contains("check whether the build finished");
        assertThat(notice).contains("firing number 1");
        assertThat(notice).contains("every 1m");
        assertThat(notice).contains("timer cancel");
        assertThat(notice).contains(TimerNotice.at(start.plusSeconds(60)));
    }

    /** Nothing due has to be nothing at all: an empty notice must not disturb a cached prefix. */
    @Test
    public void nothingDueAddsNothingToThePrompt() {
        assertThat(TimerNotice.render(List.of())).isEmpty();
        assertThat(TimerNotice.render(null)).isEmpty();
        assertThat(TimerNotice.dueNow()).isEmpty();
    }

    /**
     * A malformed timer is refused where it is built rather than where it fires. Constructed with
     * nothing to say, no interval or no id, the failure would otherwise surface several steps later
     * as a notice the model could not act on -- or as one that never arrived at all.
     */
    @Test
    public void atimerThatCouldNotBeActedOnIsRefusedWhereItIsBuilt() {
        assertThatThrownBy(() -> AgentTimer.starting("t1", "  ", EVERY_MINUTE, 0, start))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reminding");
        assertThatThrownBy(() -> AgentTimer.starting(" ", "check", EVERY_MINUTE, 0, start))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cancelled by");
        assertThatThrownBy(() -> AgentTimer.starting("t1", "check", Duration.ZERO, 0, start))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentTimer.starting("t1", "check", EVERY_MINUTE, -1, start))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentTimer("t1", "check", EVERY_MINUTE, 0, 0, null, start))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A firing reports one event; one that could not say which, or when, is not a report. */
    @Test
    public void afiringHasToSayWhichTimerAndWhen() {
        assertThatThrownBy(() -> new TimerFiring(" ", "check", 1, "unlimited", EVERY_MINUTE, start))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimerFiring("t1", "check", 0, "unlimited", EVERY_MINUTE, start))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimerFiring("t1", "check", 1, "unlimited", EVERY_MINUTE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Surrounding space in an instruction is not part of it, and would be quoted back as if it were. */
    @Test
    public void aninstructionIsKeptAsItWouldBeRead() {
        AgentTimer set = TimerRegistry.create("  check the build  ", EVERY_MINUTE,
                                              AgentTimer.UNLIMITED, start);
        assertThat(set.instruction()).isEqualTo("check the build");
    }

    /** Cancelling by a name that is not a timer's must not quietly cancel something else. */
    @Test
    public void cancellingNothingIsNotCancellingSomething() {
        TimerRegistry.create("check the build", EVERY_MINUTE, AgentTimer.UNLIMITED, start);

        assertThat(TimerRegistry.cancel(null)).isFalse();
        assertThat(TimerRegistry.cancel("  ")).isFalse();
        assertThat(TimerRegistry.cancel("T999")).isFalse();
        assertThat(TimerRegistry.active()).hasSize(1);
    }

    @Test
    public void whenTheNextOneIsOwedIsKnownWithoutTakingIt() {
        assertThat(TimerRegistry.nextDueAt()).isEmpty();

        TimerRegistry.create("later", Duration.ofMinutes(5), AgentTimer.UNLIMITED, start);
        TimerRegistry.create("sooner", EVERY_MINUTE, AgentTimer.UNLIMITED, start);

        assertThat(TimerRegistry.nextDueAt()).contains(start.plusSeconds(60));
        assertThat(TimerRegistry.takeDue(start.plusSeconds(60))).hasSize(1);
    }

    /**
     * A scope belongs to a run, and a run ends.
     *
     * <p>Workers are numbered from one in every run, so "Worker 1" is a different agent doing
     * different work each time {@code workers} is used. A timer left behind under that name is
     * therefore not merely a leak: it is handed to the next worker to carry it, which is the
     * cross-talk scoping exists to prevent, arriving by way of the mechanism meant to prevent it.
     * It also counts against that worker's allowance before the worker has set anything.</p>
     */
    @Test
    public void whatAWorkerLeftBehindIsNotHandedToTheNextWorkerOfThatName() {
        TimerScope.in("Worker 1", () ->
                TimerRegistry.create("check the first run's build", EVERY_MINUTE,
                                     AgentTimer.UNLIMITED, start));

        List<AgentTimer>  inheritedTimers  = new ArrayList<>();
        List<TimerFiring> inheritedFirings = new ArrayList<>();
        TimerScope.in("Worker 1", () -> {
            inheritedTimers.addAll(TimerRegistry.active());
            inheritedFirings.addAll(TimerRegistry.takeDue(start.plusSeconds(60)));
        });

        assertThat(inheritedTimers).isEmpty();
        assertThat(inheritedFirings).isEmpty();
    }

    /** The session is what everything the person drives shares, so it is never what is discarded. */
    @Test
    public void aworkerEndingDoesNotTakeTheSessionsOwnTimersWithIt() {
        TimerRegistry.create("the session's own", EVERY_MINUTE, AgentTimer.UNLIMITED, start);

        TimerScope.in("Worker 1", () ->
                TimerRegistry.create("the worker's own", EVERY_MINUTE, AgentTimer.UNLIMITED, start));
        TimerScope.in(TimerScope.SESSION, () -> { });
        TimerScope.in("", () -> { });

        assertThat(TimerRegistry.active()).hasSize(1);
        assertThat(TimerRegistry.active().get(0).instruction()).isEqualTo("the session's own");
    }

    /**
     * A scope entered again under the name it already has is the same run, not a new one.
     *
     * <p>The timers belong to whoever is inside that name. Discarded on the way out of an inner
     * entry, they would be taken from a run that is still going and still expecting them.</p>
     */
    @Test
    public void enteringAscopeFromInsideItselfDoesNotEndIt() {
        List<AgentTimer> stillThere = new ArrayList<>();
        TimerScope.in("Worker 1", () -> {
            TimerRegistry.create("check the build", EVERY_MINUTE, AgentTimer.UNLIMITED, start);
            TimerScope.in("Worker 1", () -> { });
            stillThere.addAll(TimerRegistry.active());
        });

        assertThat(stillThere).hasSize(1);
        assertThat(stillThere.get(0).instruction()).isEqualTo("check the build");
    }
}
