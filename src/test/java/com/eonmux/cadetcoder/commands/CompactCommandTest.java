package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * The {@code compact} command: reading and changing when a run folds its own history.
 *
 * <p>{@link ConfigManager} is mocked so {@code saveConfig} does not write to the developer's real
 * configuration, but the {@link Configuration.CompactionConfig} behind it is a genuine one -- the
 * validation under test is about the relationship between two stored values, and a mock that
 * returned canned numbers would not exercise it.</p>
 */
public class CompactCommandTest {

    private final ByteArrayOutputStream captured    = new ByteArrayOutputStream();
    private final PrintStream           originalOut = System.out;
    private final PrintStream           originalErr = System.err;

    private MockedStatic<ConfigManager>    configMock;
    private ConfigManager                  manager;
    private Configuration.CompactionConfig settings;
    private CompactCommand                 command;

    @Before
    public void setUp() {
        command  = new CompactCommand();
        settings = new Configuration.CompactionConfig();

        Configuration config = new Configuration();
        config.setCompaction(settings);

        manager    = mock(ConfigManager.class);
        configMock = mockStatic(ConfigManager.class);
        configMock.when(ConfigManager::getInstance).thenReturn(manager);
        when(manager.getConfig()).thenReturn(config);
        when(manager.saveConfig()).thenReturn(true);

        PrintStream sink = new PrintStream(captured);
        System.setOut(sink);
        System.setErr(sink);
    }

    @After
    public void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        if (configMock != null) {
            configMock.close();
        }
    }

    private String output() {
        System.out.flush();
        return captured.toString();
    }

    @Test
    public void noArgumentsShowsTheCurrentThresholds() {
        assertThat(command.execute(new String[0])).isZero();

        assertThat(output())
                .contains("Context compaction")
                .contains("Folds at:")
                .contains("Kept verbatim:");
    }

    @Test
    public void statusReportsTheBudgetTheSharesAreMeasuredAgainst() {
        command.execute(new String[]{"status"});

        // A percentage on its own is unactionable; the tokens it works out to are what a user can
        // compare against a run that fell over.
        assertThat(output()).contains("Input window:").contains("tokens");
    }

    @Test
    public void compactionCanBeTurnedOffAndOnAgain() {
        assertThat(command.execute(new String[]{"off"})).isZero();
        assertThat(settings.isEnabled()).isFalse();

        assertThat(command.execute(new String[]{"on"})).isZero();
        assertThat(settings.isEnabled()).isTrue();
    }

    @Test
    public void aChangedThresholdIsPersisted() {
        assertThat(command.execute(new String[]{"trigger", "0.9"})).isZero();

        assertThat(settings.getTrigger()).isEqualTo(0.9);
        verify(manager, atLeastOnce()).saveConfig();
    }

    @Test
    public void aTargetAtOrAboveTheTriggerIsRefused() {
        double before = settings.getTarget();

        // Target >= trigger means every fold lands back over the line, so the next turn folds again:
        // the prefix is rewritten every turn and the cache never survives.
        assertThat(command.execute(new String[]{"target", "0.95"})).isEqualTo(1);

        assertThat(settings.getTarget()).isEqualTo(before);
        assertThat(output()).contains("fold on every turn");
    }

    @Test
    public void aTriggerAtOrBelowTheTargetIsRefused() {
        double before = settings.getTrigger();

        assertThat(command.execute(new String[]{"trigger", "0.2"})).isEqualTo(1);

        assertThat(settings.getTrigger()).isEqualTo(before);
        assertThat(output()).contains("fold on every turn");
    }

    @Test
    public void aShareOutsideZeroToOneIsRefused() {
        assertThat(command.execute(new String[]{"trigger", "80"})).isEqualTo(1);
        assertThat(command.execute(new String[]{"trigger", "0"})).isEqualTo(1);
        assertThat(command.execute(new String[]{"target", "1"})).isEqualTo(1);

        assertThat(settings.getTrigger()).isEqualTo(0.80);
        assertThat(settings.getTarget()).isEqualTo(0.45);
    }

    @Test
    public void somethingThatIsNotANumberIsRefusedRatherThanTreatedAsZero() {
        assertThat(command.execute(new String[]{"trigger", "high"})).isEqualTo(1);
        assertThat(command.execute(new String[]{"keep-head", "lots"})).isEqualTo(1);

        assertThat(output()).contains("Not a number");
        assertThat(settings.getTrigger()).isEqualTo(0.80);
    }

    @Test
    public void theRetainedEntryCountsCanBeChanged() {
        assertThat(command.execute(new String[]{"keep-head", "12"})).isZero();
        assertThat(command.execute(new String[]{"keep-tail", "20"})).isZero();

        assertThat(settings.getKeepHeadEntries()).isEqualTo(12);
        assertThat(settings.getKeepTailEntries()).isEqualTo(20);
    }

    @Test
    public void theHeadMayBeGivenUpEntirelyButTheTailMayNot() {
        // Head 0 is a legitimate choice: it trades cache for room. A tail of 0 is not -- it would
        // fold away the step the model is answering, leaving it nothing to continue from.
        assertThat(command.execute(new String[]{"keep-head", "0"})).isZero();
        assertThat(settings.getKeepHeadEntries()).isZero();

        assertThat(command.execute(new String[]{"keep-tail", "0"})).isZero();
        assertThat(settings.getKeepTailEntries()).isEqualTo(1);
        // And the clamp is stated: reporting back the 0 that was asked for would be a lie about
        // what the run will actually do.
        assertThat(output()).contains("raised from 0");
    }

    @Test
    public void aNegativeCountIsRefused() {
        assertThat(command.execute(new String[]{"keep-head", "-3"})).isEqualTo(1);
        assertThat(settings.getKeepHeadEntries()).isEqualTo(6);
    }

    @Test
    public void aMissingValueSaysWhatWasExpected() {
        assertThat(command.execute(new String[]{"trigger"})).isEqualTo(1);
        assertThat(command.execute(new String[]{"keep-head"})).isEqualTo(1);

        assertThat(output()).contains("between 0 and 1").contains("number of entries");
    }

    @Test
    public void anUnknownActionShowsTheUsage() {
        assertThat(command.execute(new String[]{"now"})).isEqualTo(1);

        assertThat(output()).contains("Unknown compact action").contains(command.getUsage());
    }

    @Test
    public void aFailedSaveIsReportedRatherThanClaimedAsSuccess() {
        when(manager.saveConfig()).thenReturn(false);

        assertThat(command.execute(new String[]{"off"})).isEqualTo(1);
        assertThat(output()).contains("this run only");
    }
}
