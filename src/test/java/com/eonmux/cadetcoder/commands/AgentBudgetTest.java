package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent's step and time budgets are opt-in.
 *
 * <p>Fixed ceilings were the wrong stop condition: they cut off long tasks that were making steady
 * progress, and did nothing about a run stuck on step three. What ends a stuck run now is
 * {@link ActionLoopGuard} -- see {@link ActionLoopGuardTest}.</p>
 */
public class AgentBudgetTest {

    @Test
    public void unlimitedIsTheDefaultAndIsDescribedAsSuch() {
        assertThat(AgentOptions.UNLIMITED).isZero();
        assertThat(AgentCommand.describeStepBudget(AgentOptions.UNLIMITED))
                .isEqualTo("as many steps as needed");
    }

    @Test
    public void anExplicitBudgetIsDescribedExactly() {
        assertThat(AgentCommand.describeStepBudget(12)).isEqualTo("up to 12 steps");
    }

    @Test
    public void theUsageStatesThatThereIsNoDefaultLimit() {
        String usage = new AgentCommand().getUsage();

        assertThat(usage)
                .contains("--max-steps")
                .contains("--timeout")
                .contains("as many steps and for as long as the task needs");
    }
}
