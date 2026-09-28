package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lines about a step, rather than from it, are hidden with its output.
 *
 * <h2>What counts as bookkeeping</h2>
 *
 * <p>The iteration a step belongs to, and the record of how it went: {@code Iteration 7} and
 * {@code [8] ok  (463 lines)}. Neither says what the run is doing, and a turn that issues a dozen
 * commands prints two dozen of them. {@code IterativeExecutor} collapses the iteration line and
 * {@code ChatCommand}/{@code AgentCommand} collapse the record of a step that worked.</p>
 *
 * <p>A step that FAILED keeps its record on screen. The record is marked as a status precisely so
 * there is one column to scan down for the place a run went wrong, and a failure hidden behind a
 * click is not in that column.</p>
 */
public class TheRunsBookkeepingIsHiddenUntilItIsOpenedTest {

    private TestOutputCapture output;
    private String            previousTui;
    private String            previousVisibility;

    @Before
    public void setUp() {
        previousTui        = System.getProperty(TuiMode.OVERRIDE_PROPERTY);
        previousVisibility = System.getProperty(CommandOutputVisibility.PROPERTY);
        System.setProperty(CommandOutputVisibility.PROPERTY, "false");
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
        restore(TuiMode.OVERRIDE_PROPERTY, previousTui);
        restore(CommandOutputVisibility.PROPERTY, previousVisibility);
    }

    private static void restore(String property, String previous) {
        if (previous == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, previous);
        }
    }

    private List<String> printedLines() {
        return Arrays.asList(output.getAllOutput().split("\n"));
    }

    @Test
    public void theShellIsSentItSoAnOpenedResultStillHasIt() {
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");

        CollapsedOutput.hiding(() -> UnifiedOutput.println("Iteration 7"));

        assertThat(output.getAllOutput()).contains("Iteration 7");
        assertThat(CollapsedOutput.visible(printedLines()))
                .as("the live console draws none of it")
                .noneMatch(line -> line.contains("Iteration 7"));
        assertThat(CollapsedOutput.expanded(printedLines()))
                .anyMatch(line -> line.contains("Iteration 7"));
    }

    @Test
    public void aConsoleWithNothingToClickIsToldAsMuchAsBefore() {
        System.clearProperty(TuiMode.OVERRIDE_PROPERTY);

        CollapsedOutput.hiding(() -> UnifiedOutput.println("[8] ok  (463 lines)"));

        assertThat(output.getAllOutput()).contains("[8] ok  (463 lines)");
        assertThat(output.getAllOutput()).doesNotContain(CollapsedOutput.OPEN);
    }

    @Test
    public void theSettingThatShowsEverythingShowsThisToo() {
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");
        System.setProperty(CommandOutputVisibility.PROPERTY, "true");

        CollapsedOutput.hiding(() -> UnifiedOutput.println("Iteration 7"));

        assertThat(output.getAllOutput()).contains("Iteration 7");
        assertThat(output.getAllOutput()).doesNotContain(CollapsedOutput.OPEN);
    }

    @Test
    public void arunLeftOpenByAfailureWouldSwallowTheRestOfTheRun() {
        // Everything printed after an unclosed marker is filed as hidden, so the closing marker has
        // to survive whatever the printing did.
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");

        assertThatThrownBy(() -> CollapsedOutput.hiding(() -> {
            throw new IllegalStateException("the record could not be built");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(output.getAllOutput()).contains(CollapsedOutput.CLOSE);
    }

    @Test
    public void nothingIsPrintedForNothingToPrint() {
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");

        CollapsedOutput.hiding(null);

        assertThat(output.getAllOutput()).isEmpty();
    }
}
