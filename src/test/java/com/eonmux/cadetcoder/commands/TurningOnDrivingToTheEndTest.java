package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.UberMode;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command that switches uber mode on, and what it leaves behind.
 *
 * <p><b>The defect</b>: a mode that is on for this session and off in the file it was saved to is
 * the hardest state to reason about afterwards -- the user was told it was on, the next session
 * disagrees, and nothing anywhere explains which is right. The failure is reachable whenever the
 * configuration cannot be written, which is exactly when nobody is watching.</p>
 *
 * <p><b>What is locked here</b>: that the setting reaches the file, so it survives the session it
 * was turned on in; that a bare invocation flips whatever is in force, since that is what a toggle
 * means; that asking for the status changes nothing; and that a word that is not one of the
 * command's is refused rather than read as "off".</p>
 */
public class TurningOnDrivingToTheEndTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private String originalBaseDir;
    private Path   configFile;

    /** The manager is a singleton, so a test that names its own file has to unmake the last one. */
    private static void resetSingleton() throws Exception {
        java.lang.reflect.Field instance = ConfigManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        java.lang.reflect.Field chosen = ConfigManager.class.getDeclaredField("chosen");
        chosen.setAccessible(true);
        chosen.set(null, null);
    }

    @Before
    public void useAConfigFileOfOurOwn() throws Exception {
        originalBaseDir = Configuration.defaultBaseDir;
        Configuration.defaultBaseDir = folder.getRoot().toPath().resolve("home").toString();
        resetSingleton();
        configFile = folder.getRoot().toPath().resolve("profile").resolve("config.json");
        ConfigManager.useConfigFile(configFile.toString());
    }

    @After
    public void putItBack() throws Exception {
        Configuration.defaultBaseDir = originalBaseDir;
        resetSingleton();
    }

    private static boolean inForce() {
        return ConfigManager.getInstance().getConfig().getAi().isUberMode();
    }

    @Test
    public void itIsOffUntilItIsTurnedOn() {
        assertThat(inForce()).isFalse();
        assertThat(UberMode.isOn()).isFalse();
    }

    @Test
    public void turningItOnPutsItInForceAndInTheFile() throws Exception {
        assertThat(new UberModeCommand().execute(new String[] {"on"})).isZero();

        assertThat(inForce()).isTrue();
        assertThat(UberMode.isOn()).isTrue();
        assertThat(Files.readString(configFile)).contains("uberMode");
    }

    @Test
    public void abareInvocationFlipsWhateverIsInForce() {
        UberModeCommand command = new UberModeCommand();

        assertThat(command.execute(new String[0])).isZero();
        assertThat(inForce()).isTrue();

        assertThat(command.execute(new String[0])).isZero();
        assertThat(inForce()).isFalse();
    }

    @Test
    public void turningItOffAgainIsAlsoRemembered() throws Exception {
        UberModeCommand command = new UberModeCommand();
        command.execute(new String[] {"on"});

        assertThat(command.execute(new String[] {"off"})).isZero();
        assertThat(inForce()).isFalse();
        assertThat(Files.readString(configFile)).contains("\"uberMode\" : false");
    }

    @Test
    public void askingWhatItIsDoingChangesNothing() {
        new UberModeCommand().execute(new String[] {"on"});

        assertThat(new UberModeCommand().execute(new String[] {"status"})).isZero();
        assertThat(inForce()).isTrue();
    }

    /** Read as "off", a typo would quietly undo the thing the user was asking for. */
    @Test
    public void awordThatIsNotOneOfTheCommandsIsRefused() {
        new UberModeCommand().execute(new String[] {"on"});

        assertThat(new UberModeCommand().execute(new String[] {"maybe"})).isEqualTo(1);
        assertThat(inForce()).isTrue();
    }

    /** The model must not be able to turn off the thing that makes it finish. */
    @Test
    public void itIsNotOfferedToTheModelAsSomethingItCanRun() {
        assertThat(CommandCatalog.coreCommands()).doesNotContain("ubermode");
        assertThat(CommandCatalog.workerCommands()).doesNotContain("ubermode");
    }
}
