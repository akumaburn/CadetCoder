package com.eonmux.cadetcoder.commands;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model starting jobs is told how many cores and how much memory the machine has.
 *
 * <h2>The defect</h2>
 *
 * <p>A run started seven fits at once, each with a 32 GB heap, on a machine with 125 GB of
 * memory. Nothing it had been shown said how large the machine was, so it could not have known
 * that the jobs it was told to run side by side needed more memory than there was.</p>
 */
class JobsAreToldWhatTheMachineHasTest {

    @Test
    void theCatalogStatesTheCoresAndTheMemory() {
        String catalog = CommandCatalog.coreCommands();

        assertThat(catalog).contains(Runtime.getRuntime().availableProcessors() + " cores");
        assertThat(catalog).containsPattern("\\d+ GB of memory");
    }
}
