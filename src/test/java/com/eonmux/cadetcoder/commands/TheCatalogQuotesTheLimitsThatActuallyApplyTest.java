package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.agents.WorkerPool;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The numbers the model is given are the numbers the tool will enforce.
 *
 * <p><b>The defect</b>: the catalog told the model "at most 8 workers, 3 run at a time" in prose.
 * Eight is a constant, but three stopped being one the moment {@code performance.threads} was
 * wired up -- so a user who raised it to six had a model planning around three, and one who
 * lowered it to one had a model planning around three runs that would in fact be serialised. A
 * limit quoted from a different place than it is enforced drifts on the first change to either.</p>
 */
public class TheCatalogQuotesTheLimitsThatActuallyApplyTest {

    private static String catalogWith(int threads, boolean parallel) {
        ConfigManager manager = mock(ConfigManager.class);
        Configuration config  = new Configuration();
        Configuration.PerformanceConfig performance = new Configuration.PerformanceConfig();
        performance.setThreads(threads);
        performance.setParallelProcessing(parallel);
        config.setPerformance(performance);
        when(manager.getConfig()).thenReturn(config);

        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            configured.when(ConfigManager::getInstance).thenReturn(manager);
            return CommandCatalog.coreCommands();
        }
    }

    @Test
    public void theConcurrencyQuotedIsTheConcurrencyConfigured() {
        assertThat(catalogWith(6, true))
                .as("what the model is told about how many run at once")
                .contains("6 run at a time");
        assertThat(catalogWith(2, true)).contains("2 run at a time");
    }

    @Test
    public void runningThemOneAtATimeIsSaidInWordsRatherThanAsAnumber() {
        assertThat(catalogWith(6, false))
                .as("'1 run at a time' is a plural that reads as a mistake")
                .contains("1 at a time");
    }

    @Test
    public void theCeilingQuotedIsTheCeilingEnforced() {
        assertThat(catalogWith(3, true))
                .contains("at most " + WorkerPool.MAX_WORKERS + " workers");
    }

    @Test
    public void aworkersCatalogSaysTheSameLimits() {
        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(new Configuration());
        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            configured.when(ConfigManager::getInstance).thenReturn(manager);

            assertThat(CommandCatalog.workerCommands())
                    .as("a worker is not offered `workers`, so it is not told about its limits")
                    .doesNotContain("run at a time");
        }
    }
}
