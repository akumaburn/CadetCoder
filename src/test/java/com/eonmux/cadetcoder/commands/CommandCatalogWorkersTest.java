package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the model is told about running other agents.
 *
 * <p>A capability the model is never told about does not exist. These assert the catalog covers the
 * whole lifecycle rather than only how to start, because a model that can spawn workers and cannot
 * discover how to read or stop them will either abandon them or wait on them forever.</p>
 */
public class CommandCatalogWorkersTest {

    private static String core() {
        return CommandCatalog.coreCommands();
    }

    @Test
    public void theModelIsToldItCanRunOtherAgentsAtAll() {
        assertThat(core()).contains("workers");
        assertThat(core()).containsIgnoringCase("in parallel");
    }

    @Test
    public void itIsToldHowToStartThemBothWays() {
        assertThat(core()).contains("workers \"");
        assertThat(core()).contains("workers start");
    }

    @Test
    public void itIsToldHowToSeeProgress() {
        assertThat(core()).contains("workers status");
    }

    @Test
    public void itIsToldHowToReadResults() {
        assertThat(core()).contains("workers list");
        assertThat(core()).contains("workers show");
    }

    @Test
    public void itIsToldHowToWaitAndHowToTerminate() {
        assertThat(core()).contains("workers wait");
        assertThat(core()).contains("workers stop");
    }

    @Test
    public void itIsToldWhenNotToUseThem() {
        // The failure this prevents is a model splitting a dependent chain across workers and
        // getting three answers that each assume the others did not happen.
        assertThat(core()).containsIgnoringCase("depend on each other");
    }

    @Test
    public void itIsToldTheLimitsItWillOtherwiseDiscoverByBeingRefused() {
        assertThat(core()).contains("8");
        assertThat(core()).containsIgnoringCase("one run at a time");
        assertThat(core()).containsIgnoringCase("cannot start workers of its own");
    }

    @Test
    public void aWorkerIsNotOfferedTheOneCommandItMayNotUse() {
        // Offering a capability that is refused on use costs the worker a turn and teaches nothing.
        assertThat(CommandCatalog.workerCommands()).doesNotContain("- workers");
        assertThat(CommandCatalog.workerCommands()).isNotEqualTo(core());
    }

    @Test
    public void aWorkerStillGetsEveryOtherCommand() {
        String worker = CommandCatalog.workerCommands();

        // Withholding one entry must not quietly cost it the rest of its tools.
        assertThat(worker).contains("- read").contains("- multiread").contains("- grep")
                          .contains("- bash").contains("- edit");
    }

    @Test
    public void everySubcommandTheCatalogAdvertisesIsOneTheCommandActuallyAccepts() {
        // The catalog is a promise. A verb named here that the command does not handle would be
        // read as a task and silently spawn workers instead of doing what was asked.
        WorkersCommand command = new WorkersCommand();
        for (String verb : new String[]{"start", "status", "wait", "stop", "list", "show"}) {
            assertThat(core()).as("catalog advertises %s", verb).contains("workers " + verb);
            assertThat(command.getUsage()).as("usage covers %s", verb).contains(verb);
        }
    }

    @Test
    public void theCommandTheCatalogNamesIsActuallyRegistered() {
        assertThat(new CommandRegistry().getCommands()).containsKey("workers");
    }
}
