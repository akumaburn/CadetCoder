package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line plan mode tells you to type is a line plan mode accepts.
 *
 * <p><b>The defect</b>: entering plan mode prints "use '<i>prefix</i>plan --exit' to exit plan
 * mode", where the prefix is {@code /} in the shell and {@code cadet } on the command line -- and
 * the loop matched the bare spelling {@code plan --exit} with no prefix at all. So on the only
 * surface where the loop actually runs, the exact line the tool had just told the user to type was
 * appended to their plan as though it were part of it, and the plan could be left only by typing
 * {@code done}.</p>
 */
public class TheWayOutOfPlanModeIsTheWayItWasOfferedTest {

    @Test
    public void thewordsThePromptSuggestsAllEndIt() {
        assertThat(PlanModeCommand.saysTheyAreFinished("done")).isTrue();
        assertThat(PlanModeCommand.saysTheyAreFinished("exit")).isTrue();
        assertThat(PlanModeCommand.saysTheyAreFinished("DONE")).isTrue();
        assertThat(PlanModeCommand.saysTheyAreFinished("  exit  ")).isTrue();
    }

    @Test
    public void theCommandSpellingEndsItOnEitherSurface() {
        assertThat(PlanModeCommand.saysTheyAreFinished("plan --exit")).isTrue();
        assertThat(PlanModeCommand.saysTheyAreFinished("/plan --exit"))
                .as("the shell prefixes commands with a slash and says so when offering this one")
                .isTrue();
        assertThat(PlanModeCommand.saysTheyAreFinished("cadet plan --exit"))
                .as("the command line prefixes them with the program name, and says that instead")
                .isTrue();
        assertThat(PlanModeCommand.saysTheyAreFinished("/plan -e")).isTrue();
        assertThat(PlanModeCommand.saysTheyAreFinished("cadet plan -e")).isTrue();
    }

    @Test
    public void alineOfTheActualPlanIsNotMistakenForAwayOut() {
        assertThat(PlanModeCommand.saysTheyAreFinished("plan the migration in two phases")).isFalse();
        assertThat(PlanModeCommand.saysTheyAreFinished("we are done when tests pass")).isFalse();
        assertThat(PlanModeCommand.saysTheyAreFinished("exit the loop early")).isFalse();
        assertThat(PlanModeCommand.saysTheyAreFinished("")).isFalse();
        assertThat(PlanModeCommand.saysTheyAreFinished(null)).isFalse();
    }
}
