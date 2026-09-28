package com.eonmux.cadetcoder.commands;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How much of a command's output the model is given, as distinct from how much the console shows. */
public class CommandOutputBudgetTest {

    private String original;

    @Before
    public void setUp() {
        original = System.getProperty(CommandOutputBudget.PROPERTY);
        System.clearProperty(CommandOutputBudget.PROPERTY);
    }

    @After
    public void tearDown() {
        if (original == null) {
            System.clearProperty(CommandOutputBudget.PROPERTY);
        } else {
            System.setProperty(CommandOutputBudget.PROPERTY, original);
        }
    }

    @Test
    public void ordinaryCommandOutputReachesTheModelWhole() {
        // A listing of this repository's Java sources runs to about 16,000 characters. The limits
        // this replaced -- 5,000 in the chat loop and 2,000 in the agent loop -- cut that to under a
        // third, and the model spent its next turn trying to recover the rest.
        String listing = ("src/main/java/com/eonmux/cadetcoder/ui/ThemedOutputFormatter.java"
                          + "   Jun 24 12:57 (17.1 KB)\n").repeat(200);

        assertThat(listing.length()).isGreaterThan(16_000);
        assertThat(CommandOutputBudget.forPrompt(listing)).isEqualTo(listing);
    }

    @Test
    public void aFullDefaultFileReadStillFits() {
        // ReadCommand's own default is 2000 lines; the backstop has to sit above the limits the
        // commands themselves enforce, or it overrides them.
        String read = "     1  a line of source code that is reasonably long\n".repeat(1_800);

        assertThat(CommandOutputBudget.forPrompt(read)).isEqualTo(read);
    }

    @Test
    public void pathologicalOutputIsStillBounded() {
        String runaway = "x".repeat(CommandOutputBudget.DEFAULT_LIMIT + 5_000);

        String bounded = CommandOutputBudget.forPrompt(runaway);

        assertThat(bounded.length()).isLessThan(runaway.length());
        assertThat(bounded).startsWith("x".repeat(1_000));
    }

    @Test
    public void theNoteSaysHowMuchWasDroppedAndWhatToDoAboutIt() {
        String runaway = "x".repeat(CommandOutputBudget.DEFAULT_LIMIT + 5_000);

        String note = CommandOutputBudget.forPrompt(runaway)
                                         .substring(CommandOutputBudget.DEFAULT_LIMIT);

        // "(output truncated)" told a model that something was missing but not how to get it.
        assertThat(note).contains("5000 of " + runaway.length() + " characters");
        assertThat(note).contains("Re-running the same command will not return more");
        assertThat(note).contains("narrow it");
    }

    @Test
    public void theLimitCanBeRaisedForOneRun() {
        System.setProperty(CommandOutputBudget.PROPERTY, "10");

        assertThat(CommandOutputBudget.limit()).isEqualTo(10);
        assertThat(CommandOutputBudget.forPrompt("0123456789abcdef")).startsWith("0123456789");
    }

    @Test
    public void zeroRemovesTheLimitEntirely() {
        System.setProperty(CommandOutputBudget.PROPERTY, "0");
        String huge = "y".repeat(CommandOutputBudget.DEFAULT_LIMIT * 2);

        assertThat(CommandOutputBudget.forPrompt(huge)).isEqualTo(huge);
    }

    @Test
    public void anUnusableOverrideFallsBackToTheDefaultRatherThanFailingTheRun() {
        System.setProperty(CommandOutputBudget.PROPERTY, "not a number");
        assertThat(CommandOutputBudget.limit()).isEqualTo(CommandOutputBudget.DEFAULT_LIMIT);

        System.setProperty(CommandOutputBudget.PROPERTY, "-5");
        assertThat(CommandOutputBudget.limit()).isEqualTo(CommandOutputBudget.DEFAULT_LIMIT);
    }

    @Test
    public void nothingIsRenderedAsAnEmptyStringRatherThanNull() {
        assertThat(CommandOutputBudget.forPrompt(null)).isEmpty();
    }
}
