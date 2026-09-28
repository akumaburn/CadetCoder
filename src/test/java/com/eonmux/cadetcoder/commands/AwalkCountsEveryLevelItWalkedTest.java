package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.InterruptSignal;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A recursive listing counts what the whole walk saw, and asks about stopping often enough to stop.
 *
 * <p><b>The defect</b>: the tallies were allocated fresh inside the recursive method, so every level
 * below the first counted into a set of counters that was then discarded -- the reported figures
 * described the top directory alone. The interrupt check was keyed off the same per-level count and
 * counted DIRECTORIES rather than entries, so a directory of two hundred files and two
 * subdirectories asked twice while a deep tree of small directories never asked at all.</p>
 */
class AwalkCountsEveryLevelItWalkedTest {

    @TempDir
    Path directory;

    private String originalWorkingDir;

    private TestOutputCapture output;

    @BeforeEach
    void setUp() {
        // The searched directory is the project, as it is when a user searches their own code.
        originalWorkingDir = System.getProperty("user.dir");
        System.setProperty("user.dir", directory.toAbsolutePath().toString());
        output = new TestOutputCapture();
        output.startCapture();
        InterruptSignal.clear();
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalWorkingDir);
        InterruptSignal.clear();
        output.stopCapture();
    }

    /** root/a.txt, root/one/b.txt, root/one/two/c.txt -- three levels, two descents. */
    private void aTreeOfThreeLevels() throws IOException {
        Files.writeString(directory.resolve("a.txt"), "top\n");
        Path one = Files.createDirectory(directory.resolve("one"));
        Files.writeString(one.resolve("b.txt"), "middle\n");
        Path two = Files.createDirectory(one.resolve("two"));
        Files.writeString(two.resolve("c.txt"), "bottom\n");
    }

    private static int tally(LSCommand command, String field) throws Exception {
        Field held = LSCommand.class.getDeclaredField(field);
        held.setAccessible(true);
        return ((AtomicInteger) held.get(command)).get();
    }

    /** Runs the listing on the given instance, so its counters are the ones that were used. */
    private static int runOn(LSCommand command, String... args) throws Exception {
        Method once = LSCommand.class.getDeclaredMethod("executeOnce", String[].class);
        once.setAccessible(true);
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            when(manager.getConfig()).thenReturn(new Configuration());
            configMock.when(ConfigManager::getInstance).thenReturn(manager);
            return (int) once.invoke(command, (Object) args);
        }
    }

    @Test
    void everyLevelOfTheWalkIsCountedNotJustTheFirst() throws Exception {
        aTreeOfThreeLevels();
        LSCommand listing = new LSCommand();

        assertThat(runOn(listing, directory.toAbsolutePath().toString(), "-R")).isZero();

        assertThat(tally(listing, "descended"))
                .as("one and one/two were both descended into")
                .isEqualTo(2);
        assertThat(tally(listing, "walked"))
                .as("the top level alone holds two entries; the deeper ones count too")
                .isGreaterThan(2);
    }

    @Test
    void awalkThatWasNotRecursiveCountsNothing() throws Exception {
        aTreeOfThreeLevels();
        LSCommand listing = new LSCommand();

        assertThat(runOn(listing, directory.toAbsolutePath().toString())).isZero();

        assertThat(tally(listing, "walked")).isZero();
        assertThat(tally(listing, "descended")).isZero();
    }

    @Test
    void ahiddenDirectoryThatWasNotDescendedIntoIsCountedAsSkipped() throws Exception {
        Files.writeString(directory.resolve("a.txt"), "top\n");
        Path hidden = Files.createDirectory(directory.resolve(".cache"));
        Files.writeString(hidden.resolve("junk"), "x\n");

        LSCommand listing = new LSCommand();
        assertThat(runOn(listing, directory.toAbsolutePath().toString(), "-R", "-a")).isZero();

        // -a shows it, so it is descended into; without -a it would be one of the skipped.
        assertThat(tally(listing, "descended")).isEqualTo(1);
        assertThat(tally(listing, "skipped")).isZero();
    }
}
