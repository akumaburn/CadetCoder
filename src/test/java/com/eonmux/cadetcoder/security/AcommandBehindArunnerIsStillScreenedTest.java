package com.eonmux.cadetcoder.security;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The program a command line really runs is the program that is screened.
 *
 * <h2>Why the first word is not enough</h2>
 *
 * <p>The screens look up the program a segment runs in a list of what may not be run. The program
 * was taken to be the segment's first word, and a command line has several ordinary ways of putting
 * something else there: an environment assignment in front of it, and a runner that takes the real
 * command as its arguments. {@code FOO=1 rm -rf /}, {@code timeout 60 rm -rf /} and
 * {@code xargs -n 1 rm} were each read as running {@code FOO=1}, {@code timeout} and {@code xargs},
 * none of which is on any list, so all three were allowed through as ordinary work.</p>
 *
 * <h2>Why a shell given a script is not read as safe</h2>
 *
 * <p>A nested shell hides whatever it is told to run. {@code bash -c "rm -rf /"} carries the
 * command in an argument, {@code bash -lc "..."} carries it behind two letters in one flag, and
 * {@code echo ... | bash} carries it in the pipe where this screen cannot see it at all. What can
 * be read is screened; what cannot is refused as unclear, which sends it to whoever can decide,
 * rather than being passed as safe.</p>
 */
public class AcommandBehindArunnerIsStillScreenedTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private ConfigManager                mockConfigManager;
    private Configuration                mockConfig;
    private Configuration.SecurityConfig mockSecurityConfig;
    private String                       originalWorkingDir;

    @Before
    public void setUp() {
        mockConfigManager  = mock(ConfigManager.class);
        mockConfig         = mock(Configuration.class);
        mockSecurityConfig = mock(Configuration.SecurityConfig.class);

        when(mockConfigManager.getConfig()).thenReturn(mockConfig);
        when(mockConfig.getSecurity()).thenReturn(mockSecurityConfig);
        when(mockSecurityConfig.isAllowOutsideProject()).thenReturn(false);
        when(mockSecurityConfig.isSandboxMode()).thenReturn(false);

        originalWorkingDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());
    }

    @After
    public void restoreWorkingDirectory() {
        // user.dir is global JVM state and this fork is reused by every other test.
        if (originalWorkingDir != null) {
            System.setProperty("user.dir", originalWorkingDir);
        }
    }

    /** Screens one line with the validator the rest of the tool uses. */
    private SecurityValidator.CommandScreening screen(String command) {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            return new SecurityValidator().screenCommand(command);
        }
    }

    @Test
    public void anEnvironmentAssignmentInFrontOfAcommandDoesNotHideIt() {
        assertThat(screen("FOO=1 rm -rf /tmp/anything").allowed())
                .as("the program run is rm, whatever is set in front of it")
                .isFalse();
    }

    @Test
    public void arunnerDoesNotHideWhatItRuns() {
        assertThat(screen("timeout 60 rm -rf /tmp/anything").allowed()).isFalse();
        assertThat(screen("nice -n 10 rm -rf /tmp/anything").allowed()).isFalse();
        assertThat(screen("env FOO=1 rm -rf /tmp/anything").allowed()).isFalse();
        assertThat(screen("xargs -n 1 rm").allowed()).isFalse();
    }

    @Test
    public void arunnerRunningSomethingOrdinaryIsStillOrdinary() {
        assertThat(screen("timeout 60 mvn -o test").allowed()).isTrue();
        assertThat(screen("nice -n 10 git status").allowed()).isTrue();
    }

    @Test
    public void ashellToldWhatToRunIsScreenedOnWhatItWasTold() {
        assertThat(screen("bash -c \"rm -rf /tmp/anything\"").allowed()).isFalse();
        assertThat(screen("bash -lc \"rm -rf /tmp/anything\"").allowed())
                .as("two short flags written as one still carry the command")
                .isFalse();
        assertThat(screen("sh -c \"git status\"").allowed()).isTrue();
    }

    @Test
    public void ashellWhoseCommandCannotBeReadIsSentToSomebodyWhoCanDecide() {
        SecurityValidator.CommandScreening piped = screen("echo rm -rf / | bash");
        assertThat(piped.allowed()).isFalse();
        assertThat(piped.reconsiderable())
                .as("what goes into the pipe is not readable here, so this is not a refusal")
                .isTrue();

        assertThat(screen("bash build/release").allowed())
                .as("a shell running a file is a program running a file, like make or python")
                .isTrue();

        SecurityValidator.CommandScreening script = screen("bash deploy.sh");
        assertThat(script.allowed())
                .as("a script gets the same answer however it is started")
                .isFalse();
        assertThat(script.reconsiderable()).isTrue();
        assertThat(screen("./deploy.sh").allowed()).isFalse();
    }

    @Test
    public void awriteOutsideWhatMayBeWrittenIsRefused() {
        assertThat(screen("echo broken > /etc/hosts").allowed())
                .as("a redirect writes a file as surely as a command that names it")
                .isFalse();
        assertThat(screen("echo done > " + tempFolder.getRoot().getAbsolutePath() + "/build.log")
                           .allowed())
                .isTrue();
    }
}
