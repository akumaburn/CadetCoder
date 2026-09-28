package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ui.Glyphs;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shell's bottom line reports where the console is and which keys move it from there.
 *
 * <h2>The defect</h2>
 *
 * <p>Both halves of the line read the shell's fields directly, inside a class whose constructor
 * takes over the process's output routing -- so neither could be asked anything without standing up
 * a terminal UI, and neither had ever been asked anything. The question that had gone unasked was
 * the one that was wrong: with a result focused, the hints named "Tab/S-Tab results", and Tab cycles
 * <em>workers</em> whichever kind of region is focused, so the legend named a key that does
 * something else.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>The order the states outrank one another -- a flash, then select mode, then focus, then scroll
 * position, then live; the hints matching the state they are shown beside, including naming workers
 * for a focused <em>result</em>; and a focus on something no longer in its list still counting from
 * one rather than from zero.</p>
 */
public class TheStatusLineSaysWhereYouAreTest {

    private final ShellStatusBar bar = new ShellStatusBar(Glyphs.ASCII);

    private static ShellStatusBar.View live() {
        return new ShellStatusBar.View(null, false, false, null, true, 0, 0);
    }

    private static ShellStatusBar.View of(String flash, boolean prompting, boolean selectMode,
                                          ShellStatusBar.Focus focus, boolean followBottom,
                                          int scroll, int maxScroll) {
        return new ShellStatusBar.View(flash, prompting, selectMode, focus, followBottom, scroll,
                                       maxScroll);
    }

    @Test
    public void followingTheTailSaysLive() {
        assertThat(bar.state(live())).contains("live");
    }

    @Test
    public void aFlashOutranksEverythingElse() {
        ShellStatusBar.View view = of("copied 412 chars", false, true,
                                      new ShellStatusBar.Focus(ShellStatusBar.Of.WORKER, 1, 3, ""), false, 5, 10);

        assertThat(bar.state(view)).isEqualTo("copied 412 chars");
    }

    @Test
    public void selectModeOutranksFocusAndScroll() {
        ShellStatusBar.View view = of(null, false, true,
                                      new ShellStatusBar.Focus(ShellStatusBar.Of.RESULT, 2, 5, "read pom.xml"),
                                      false, 5, 10);

        assertThat(bar.state(view)).contains("select mode");
    }

    @Test
    public void aFocusedResultIsNamedWithItsPositionAndTitle() {
        ShellStatusBar.View view = of(null, false, false,
                                      new ShellStatusBar.Focus(ShellStatusBar.Of.RESULT, 2, 5, "read pom.xml"),
                                      true, 0, 0);

        assertThat(bar.state(view)).contains("2/5").contains("read pom.xml");
    }

    @Test
    public void aFocusedWorkerIsNamedAsAWorker() {
        ShellStatusBar.View view = of(null, false, false,
                                      new ShellStatusBar.Focus(ShellStatusBar.Of.WORKER, 3, 4, ""), true, 0, 0);

        assertThat(bar.state(view)).contains("worker").contains("3/4");
    }

    @Test
    public void aFocusOnSomethingNoLongerListedStillCountsFromOne() {
        // indexOf returned -1, so the index arrives as 0. "0/4" would say the console is showing a
        // result before the first one.
        ShellStatusBar.View view = of(null, false, false,
                                      new ShellStatusBar.Focus(ShellStatusBar.Of.WORKER, 0, 4, ""), true, 0, 0);

        assertThat(bar.state(view)).contains("1/4");
    }

    @Test
    public void anEmptyListIsNotCounted() {
        ShellStatusBar.View view = of(null, false, false,
                                      new ShellStatusBar.Focus(ShellStatusBar.Of.WORKER, 0, 0, ""), true, 0, 0);

        assertThat(bar.state(view)).doesNotContain("/");
    }

    @Test
    public void beingScrolledBackSaysHowFarAndHowToGetBack() {
        assertThat(bar.state(of(null, false, false, null, false, 5, 10)))
                .contains("50%")
                .contains("Ctrl+End");
    }

    @Test
    public void aScrollWithNowhereToGoReadsAsTheEnd() {
        // maxScroll of zero would otherwise divide by it.
        assertThat(bar.state(of(null, false, false, null, false, 0, 0))).contains("100%");
    }

    @Test
    public void theHintsForAFocusedResultNamePanesBecauseThatIsWhatTabCycles() {
        ShellStatusBar.View view = of(null, false, false,
                                      new ShellStatusBar.Focus(ShellStatusBar.Of.RESULT, 1, 2, "read pom.xml"),
                                      true, 0, 0);

        assertThat(bar.hints(view)).contains("Tab/S-Tab panes").doesNotContain("results");
    }

    @Test
    public void afocusedJobIsNamedAsAjobAndCountedAmongThePanes() {
        ShellStatusBar.View view = of(null, false, false,
                                      new ShellStatusBar.Focus(ShellStatusBar.Of.JOB, 3, 4, ""),
                                      true, 0, 0);

        assertThat(bar.state(view)).contains("job").contains("3/4");
    }

    @Test
    public void thelistOfRunningWorkSaysThatIsWhatItIs() {
        ShellStatusBar.View view = of(null, false, false,
                                      new ShellStatusBar.Focus(ShellStatusBar.Of.OVERVIEW, 1, 2, ""),
                                      true, 0, 0);

        assertThat(bar.state(view)).contains("background work");
    }

    /** The list is a list of things to open, so its keys are not the keys for reading one. */
    @Test
    public void thelistNamesTheKeysThatPickAndOpenRatherThanScroll() {
        ShellStatusBar.View view = of(null, false, false,
                                      new ShellStatusBar.Focus(ShellStatusBar.Of.OVERVIEW, 1, 2, ""),
                                      true, 0, 0);

        assertThat(bar.hints(view))
                .contains("Up/Down pick")
                .contains("Enter open")
                .doesNotContain("PgUp/PgDn scroll");
    }

    @Test
    public void promptingOutranksEveryOtherLegend() {
        ShellStatusBar.View view = of(null, true, true,
                                      new ShellStatusBar.Focus(ShellStatusBar.Of.WORKER, 1, 1, ""), true, 0, 0);

        assertThat(bar.hints(view)).isEqualTo("Enter submit  " + Glyphs.ASCII.bullet() + "  Esc cancel");
    }

    @Test
    public void selectModeExplainsHowToLeaveIt() {
        assertThat(bar.hints(of(null, false, true, null, true, 0, 0)))
                .contains("F4/Esc leave");
    }

    @Test
    public void selectModeSaysHowToReachPastTheVisibleRows() {
        // The one thing about the mode nobody guesses: a drag held against an edge keeps going.
        assertThat(bar.hints(of(null, false, true, null, true, 0, 0)))
                .contains("hold at an edge");
    }

    @Test
    public void theLiveViewNamesTheKeysThatOpenEverythingElse() {
        assertThat(bar.hints(live()))
                .contains("F1 Help")
                .contains("F5 Running")
                .contains("F2 Interrupt")
                .contains("Ctrl+Q Quit");
    }
}
