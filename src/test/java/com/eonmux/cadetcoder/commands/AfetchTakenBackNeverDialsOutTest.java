package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.InterruptSignal;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fetch that has already been taken back does not open a connection.
 *
 * <p><b>The defect</b>: webfetch declared itself interruptible and then never asked. Pressing stop
 * left it dialling the URL, following up to five redirects and reading the body to completion --
 * every one of them after the user had been told it had stopped.</p>
 */
class AfetchTakenBackNeverDialsOutTest {

    private TestOutputCapture output;

    @BeforeEach
    void setUp() {
        output = new TestOutputCapture();
        output.startCapture();
        InterruptSignal.clear();
    }

    @AfterEach
    void tearDown() {
        InterruptSignal.clear();
        output.stopCapture();
    }

    private static Map<String, Object> aFetchAboutToStart() {
        Map<String, Object> context = new HashMap<>();
        context.put("step", "fetch_content");
        context.put("url", "http://nothing.invalid/page");
        context.put("timeoutSec", 5);
        context.put("prompt", "summarise it");
        return context;
    }

    @Test
    void aFetchAlreadyTakenBackIsNotStarted() {
        InterruptSignal.request();

        IterativeCommand.StepResult result =
                new WebFetchCommand().executeStep(new String[] {"http://nothing.invalid/page"},
                                                  aFetchAboutToStart(), null);

        assertThat(result.isInterrupted())
                .as("taken back, not failed")
                .isTrue();
        assertThat(result.isError()).isFalse();
        assertThat(output.getAllOutput())
                .as("nothing was dialled")
                .doesNotContain("Fetching content from");
    }

    @Test
    void aFetchNobodyStoppedIsStarted() {
        IterativeCommand.StepResult result =
                new WebFetchCommand().executeStep(new String[] {"http://nothing.invalid/page"},
                                                  aFetchAboutToStart(), null);

        assertThat(result.isInterrupted()).isFalse();
        assertThat(output.getAllOutput())
                .as("it got as far as trying, which is the point")
                .contains("Fetching content from");
    }

    @Test
    void afetchTheUserDeclinedIsTakenBackRatherThanFailed() {
        Map<String, Object> context = aFetchAboutToStart();
        context.put("step", "await_confirmation");

        IterativeCommand.StepResult result =
                new WebFetchCommand().executeStep(new String[] {"http://nothing.invalid/page"},
                                                  context, "no");

        assertThat(result.isInterrupted()).isTrue();
        assertThat(result.isError())
                .as("the user said no; nothing went wrong")
                .isFalse();
    }
}
