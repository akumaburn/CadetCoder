package com.eonmux.cadetcoder.agents;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * How many workers run at once is what the user configured.
 *
 * <p><b>The defect</b>: {@code performance.threads} and {@code performance.parallelProcessing} were
 * settable through {@code cadet config}, listed back by it, and documented -- and nothing read
 * either. The one knob that did work was an undocumented system property, {@code
 * cadet.workers.concurrency}, which is reachable only by editing a launcher script. So the user was
 * offered a setting that did nothing and not offered the setting that did something.</p>
 */
public class TheConfiguredThreadCountIsTheOneThatIsUsedTest {

    private Configuration.PerformanceConfig performance;

    @Before
    public void setUp() {
        System.clearProperty(WorkerPool.CONCURRENCY_PROPERTY);
    }

    @After
    public void tearDown() {
        System.clearProperty(WorkerPool.CONCURRENCY_PROPERTY);
    }

    private ConfigManager managerWith(int threads, boolean parallel) {
        ConfigManager manager = mock(ConfigManager.class);
        Configuration config  = new Configuration();
        performance = new Configuration.PerformanceConfig();
        performance.setThreads(threads);
        performance.setParallelProcessing(parallel);
        config.setPerformance(performance);
        when(manager.getConfig()).thenReturn(config);
        return manager;
    }

    private int concurrencyWith(int threads, boolean parallel) {
        ConfigManager manager = managerWith(threads, parallel);
        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            configured.when(ConfigManager::getInstance).thenReturn(manager);
            return WorkerPool.concurrency();
        }
    }

    @Test
    public void theConfiguredNumberIsHowManyRunAtOnce() {
        assertThat(concurrencyWith(5, true)).isEqualTo(5);
        assertThat(concurrencyWith(1, true)).isEqualTo(1);
    }

    @Test
    public void turningParallelProcessingOffRunsThemOneAtATime() {
        assertThat(concurrencyWith(8, false))
                .as("the setting is the whole of what it says: nothing runs alongside anything")
                .isEqualTo(1);
    }

    @Test
    public void anImpossibleNumberIsBroughtBackInsideTheRangeRatherThanUsed() {
        assertThat(concurrencyWith(0, true))
                .as("zero workers would run nothing at all")
                .isEqualTo(1);
        assertThat(concurrencyWith(-3, true)).isEqualTo(1);
        assertThat(concurrencyWith(500, true))
                .as("above the ceiling workers only starve each other")
                .isEqualTo(WorkerPool.MAX_CONCURRENCY);
    }

    @Test
    public void thePropertyStillOverridesTheFileForOneRun() {
        System.setProperty(WorkerPool.CONCURRENCY_PROPERTY, "2");

        assertThat(concurrencyWith(7, true)).isEqualTo(2);
    }

    @Test
    public void theShippedDefaultIsWhatTheToolUsedToRunAtAnyway() {
        assertThat(new Configuration.PerformanceConfig().getThreads())
                .isEqualTo(WorkerPool.DEFAULT_CONCURRENCY);
    }
}
