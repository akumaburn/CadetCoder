package com.eonmux.cadetcoder.config;

import com.eonmux.cadetcoder.OutputFormatter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

/**
 * Building the configuration manager reports its own failures, and reporting asks for the manager.
 *
 * <p><b>The defect</b>: the whole of the work sat in the constructor, so {@code instance} was still
 * unassigned when that question came back round. A second manager was built to answer it, and the
 * second one's completion declared initialisation over -- clearing the flag, and printing the
 * first one's held-back problems -- while the first was still halfway through collecting them.</p>
 */
class OneManagerIsBuiltEvenWhenBuildingItTalksTest {

    @TempDir
    Path directory;

    private Object savedInstance;
    private Object savedChosen;

    private static void set(String field, Object value) throws Exception {
        Field held = ConfigManager.class.getDeclaredField(field);
        held.setAccessible(true);
        held.set(null, value);
    }

    private static Object get(String field) throws Exception {
        Field held = ConfigManager.class.getDeclaredField(field);
        held.setAccessible(true);
        return held.get(null);
    }

    @BeforeEach
    void rememberTheRealOne() throws Exception {
        savedInstance = get("instance");
        savedChosen   = get("chosen");
    }

    @AfterEach
    void putItBack() throws Exception {
        set("settling", null);
        set("instance", savedInstance);
        set("chosen", savedChosen);
    }

    /**
     * A path the manager cannot make a home for, so that building it has something to report.
     *
     * @return a configuration path whose parent directory cannot be created, because a regular file
     *         stands where one of its ancestors would have to be
     */
    private Path somewhereItCannotWrite() throws Exception {
        Path blocking = Files.writeString(directory.resolve("in-the-way"), "not a directory");
        return blocking.resolve("below").resolve("config.json");
    }

    @Test
    void whatIsHandedToAcallerThatArrivesMidBuildIsTheManagerBeingBuilt() throws Exception {
        set("instance", null);
        set("settling", null);
        set("chosen", somewhereItCannotWrite());

        List<ConfigManager> seenWhileBuilding = new ArrayList<>();
        try (MockedStatic<OutputFormatter> printing = mockStatic(OutputFormatter.class)) {
            // Reporting a failure is what reaches the logger, and the logger asks where its file
            // is -- which is this question, arriving before the answer to it exists.
            printing.when(() -> OutputFormatter.printError(org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(call -> {
                        seenWhileBuilding.add(ConfigManager.getInstance());
                        return null;
                    });

            ConfigManager built = ConfigManager.getInstance();

            assertThat(seenWhileBuilding)
                    .as("building it did report something; otherwise this test proves nothing")
                    .isNotEmpty();
            assertThat(seenWhileBuilding)
                    .as("no second manager was made to answer the question")
                    .allMatch(seen -> seen == built);
        }
    }

    @Test
    void aManagerHandedOutMidBuildAlreadyKnowsTheSettingsInForce() throws Exception {
        set("instance", null);
        set("settling", null);
        set("chosen", somewhereItCannotWrite());

        List<Configuration> seenWhileBuilding = new ArrayList<>();
        try (MockedStatic<OutputFormatter> printing = mockStatic(OutputFormatter.class)) {
            printing.when(() -> OutputFormatter.printError(org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(call -> {
                        seenWhileBuilding.add(ConfigManager.getInstance().getConfig());
                        return null;
                    });

            ConfigManager.getInstance();
        }

        assertThat(seenWhileBuilding).isNotEmpty();
        assertThat(seenWhileBuilding)
                .as("the defaults are what is in force until the file has been read")
                .doesNotContainNull();
    }

    @Test
    void theManagerSaysWhetherItHasFinishedBeingBuilt() throws Exception {
        set("instance", null);
        set("settling", null);
        set("chosen", somewhereItCannotWrite());

        List<Boolean> duringBuild = new ArrayList<>();
        try (MockedStatic<OutputFormatter> printing = mockStatic(OutputFormatter.class)) {
            printing.when(() -> OutputFormatter.printError(org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(call -> {
                        duringBuild.add(ConfigManager.isSettled());
                        return null;
                    });

            ConfigManager.getInstance();
        }

        assertThat(duringBuild).isNotEmpty().containsOnly(false);
        assertThat(ConfigManager.isSettled())
                .as("and true once there is nothing left to read")
                .isTrue();
    }
}
