package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.ai.UberMode;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.timers.AgentTimer;
import com.eonmux.cadetcoder.timers.TimerRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the tool has to say to a harness run: check-ins that have come due, and a question about a
 * completion the agent has just claimed.
 *
 * <p><b>The defect</b>: the harness is deliberately independent of the tool it runs inside -- it
 * knows about ledgers and evidence and nothing about commands or settings -- so neither of these
 * could reach it by being fetched. Without somewhere to answer from, a timer the agent set through a
 * command would fire into every loop except the one the agent is actually in, and a run under uber
 * mode would be the one run whose completion nobody checked.</p>
 *
 * <p><b>What is locked here</b>: that a firing that has come due is what is said between rounds;
 * that nothing is said when nothing is owed; that a completion is questioned only while the mode is
 * on and only while this run's questions last; and that the count belongs to one run, since a shared
 * one would spend itself on the first long run and believe every claim after it.</p>
 */
public class WhatThisToolSaysIntoARunTest {

    private boolean wasOn;

    @Before
    public void start() {
        TimerRegistry.clearAll();
        wasOn = ConfigManager.getInstance().getConfig().getAi().isUberMode();
    }

    @After
    public void finish() {
        TimerRegistry.clearAll();
        ConfigManager.getInstance().getConfig().getAi().setUberMode(wasOn);
    }

    private static void uberMode(boolean on) {
        ConfigManager.getInstance().getConfig().getAi().setUberMode(on);
    }

    @Test
    public void afiringThatHasComeDueIsWhatIsSaidBetweenRounds() {
        TimerRegistry.create("check whether the build finished", Duration.ofMinutes(1),
                             AgentTimer.UNLIMITED, Instant.now().minusSeconds(120));

        assertThat(new CadetSignals("add a retry").beforeRound())
                .contains("[timer]")
                .contains("check whether the build finished");
    }

    @Test
    public void nothingIsSaidWhenNothingIsOwed() {
        assertThat(new CadetSignals("add a retry").beforeRound()).isEmpty();
    }

    @Test
    public void acompletionIsQuestionedOnlyWhileTheModeIsOn() {
        uberMode(false);
        assertThat(new CadetSignals("add a retry").questionDone("DONE: finished")).isEmpty();

        uberMode(true);
        assertThat(new CadetSignals("add a retry").questionDone("DONE: finished"))
                .contains("add a retry")
                .contains("DONE: finished")
                .contains("DONE");
    }

    @Test
    public void thequestionsBelongToOneRunAndRunOut() {
        uberMode(true);
        CadetSignals signals = new CadetSignals("add a retry");

        assertThat(signals.questionDone("DONE: finished")).isNotEmpty();
        assertThat(signals.questionDone("DONE: finished")).isNotEmpty();
        assertThat(signals.questionDone("DONE: finished")).isEmpty();
        assertThat(signals.questionsAsked()).isEqualTo(2);

        assertThat(new CadetSignals("something else").questionDone("DONE: finished"))
                .as("a second run starts with its own questions, not with what the first left")
                .isNotEmpty();
    }

    /**
     * What the mode is for is a standing instruction, not a remark.
     *
     * <p>Sent between rounds it would be read once and compacted away, and the run would spend its
     * last half being driven by what it happened to remember of it. The harness run is the default
     * for {@code agent}, so this is the loop where being told what finishing means matters most.</p>
     */
    @Test
    public void thestandingInstructionIsWhatTheModeSaysAndOnlyWhileItIsOn() {
        uberMode(false);
        assertThat(new CadetSignals("add a retry").standingInstruction()).isEmpty();

        uberMode(true);
        assertThat(new CadetSignals("add a retry").standingInstruction())
                .isNotEmpty()
                .isEqualTo(UberMode.directive());
    }
}
