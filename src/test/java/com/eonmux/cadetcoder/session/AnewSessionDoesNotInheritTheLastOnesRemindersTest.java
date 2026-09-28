package com.eonmux.cadetcoder.session;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.timers.AgentTimer;
import com.eonmux.cadetcoder.timers.TimerRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Drawing a line under a conversation draws it under the reminders too.
 *
 * <p><b>The defect</b>: a timer belongs to the run that set it -- it says "check whether the build
 * finished", and what that means is only legible beside the conversation it came from. The registry
 * is process-global and keyed by scope, and everything the person at the terminal drives shares one
 * scope, so {@code session new} and {@code session resume} left every standing timer running. The
 * new conversation was then interrupted by check-ins about work it had never heard of, counted
 * against a limit it had not set, and could not cancel without ids it had never been shown.</p>
 */
public class AnewSessionDoesNotInheritTheLastOnesRemindersTest {

    private static final Duration EVERY_MINUTE = Duration.ofMinutes(1);

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private MockedStatic<ConfigManager> configMock;

    @Before
    public void setUp() throws Exception {
        resetSessionManager();
        TimerRegistry.clearAll();
        Path baseDir = tempFolder.getRoot().toPath();
        Files.createDirectories(baseDir.resolve("sessions"));

        ConfigManager mockConfigManager = mock(ConfigManager.class);
        Configuration mockConfig        = new Configuration();
        mockConfig.setBaseDir(baseDir.toString());
        when(mockConfigManager.getConfig()).thenReturn(mockConfig);

        configMock = mockStatic(ConfigManager.class);
        configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
    }

    @After
    public void tearDown() throws Exception {
        TimerRegistry.clearAll();
        if (configMock != null) {
            configMock.close();
        }
        resetSessionManager();
    }

    private void resetSessionManager() throws Exception {
        java.lang.reflect.Field instance = SessionManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static void aReminderIsStanding() {
        TimerRegistry.create("check whether the build finished", EVERY_MINUTE,
                             AgentTimer.UNLIMITED, Instant.now());
        assertThat(TimerRegistry.active()).hasSize(1);
    }

    @Test
    public void startingAnewSessionStopsTheRemindersTheOldOneLeft() {
        SessionManager sessions = SessionManager.getInstance();
        aReminderIsStanding();

        sessions.startNewSession();

        assertThat(TimerRegistry.active())
                .as("the conversation that set it is over, so nobody can read what it means")
                .isEmpty();
    }

    @Test
    public void reopeningAnEarlierSessionStopsThemToo() {
        SessionManager sessions = SessionManager.getInstance();
        String         first    = sessions.getCurrentSessionId();
        sessions.startNewSession();
        aReminderIsStanding();

        assertThat(sessions.loadSessionById(first)).isTrue();

        assertThat(TimerRegistry.active()).isEmpty();
    }
}
