package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The {@code patch} command.
 *
 * <h2>What was missing</h2>
 *
 * <p>A model that knows what change it wants writes a unified diff without being asked, because
 * that is the format it has seen most of. This tool could not read one. The change had to be
 * restated as a SEARCH/REPLACE block or as a whole rewritten file, and a whole file rewritten from
 * memory loses whatever the model did not think to repeat.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That a patch applies to the files it names, that a patch which does not fit changes nothing at
 * all, that {@code --dry-run} says what would happen without doing it, and that read-only mode and
 * the security rules are obeyed.</p>
 *
 * <h2>What the file is besides its lines</h2>
 *
 * <p>A file is also the line endings it is written with, whether its last line has a newline after
 * it, and what it is allowed to do. A patch that changes the lines and quietly changes one of those
 * is a patch that was not applied, however cleanly the hunks went in.</p>
 */
public class TheToolCanApplyAPatchItWasGivenTest {

    @Rule
    public TemporaryFolder project = new TemporaryFolder();

    /** A folder outside the project, for the tests about the project boundary. */
    @Rule
    public TemporaryFolder outside = new TemporaryFolder();

    private TestOutputCapture output;
    private String            wasWorkingDir;

    @Before
    public void setUp() {
        wasWorkingDir = System.getProperty("user.dir");
        System.setProperty("user.dir", project.getRoot().getAbsolutePath());
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
        System.setProperty("user.dir", wasWorkingDir);
    }

    private void write(String name, String contents) throws IOException {
        Path file = project.getRoot().toPath().resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }

    private String read(String name) throws IOException {
        return Files.readString(project.getRoot().toPath().resolve(name));
    }

    /** Runs patch with the given arguments and a configuration the test controls. */
    private int patch(Configuration config, String... args) {
        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            when(manager.getConfig()).thenReturn(config);
            configured.when(ConfigManager::getInstance).thenReturn(manager);
            return new PatchCommand().execute(args);
        }
    }

    private int patch(String... args) {
        Configuration config = new Configuration();
        config.getUi().setColorEnabled(false);
        config.getSecurity().setAllowOutsideProject(true);
        return patch(config, args);
    }

    private String shown() {
        return output.getAllOutput();
    }

    private static final String CHANGE_LINE_TWO = """
            --- a/notes.txt
            +++ b/notes.txt
            @@ -1,3 +1,3 @@
             one
            -two
            +TWO
             three
            """;

    @Test
    public void apatchGivenAsAnArgumentIsApplied() throws Exception {
        write("notes.txt", "one\ntwo\nthree\n");

        int exit = patch(CHANGE_LINE_TWO);

        assertThat(exit).isZero();
        assertThat(read("notes.txt")).isEqualTo("one\nTWO\nthree\n");
    }

    @Test
    public void apatchReadFromAFileIsAppliedToo() throws Exception {
        write("notes.txt", "one\ntwo\nthree\n");
        write("change.diff", CHANGE_LINE_TWO);

        assertThat(patch("--file=change.diff")).isZero();
        assertThat(read("notes.txt")).isEqualTo("one\nTWO\nthree\n");
    }

    @Test
    public void thefilesChangedAreNamed() throws Exception {
        write("notes.txt", "one\ntwo\nthree\n");

        patch(CHANGE_LINE_TWO);

        assertThat(shown()).contains("notes.txt");
    }

    @Test
    public void apatchThatDoesNotFitChangesNothing() throws Exception {
        write("notes.txt", "something else entirely\n");

        int exit = patch(CHANGE_LINE_TWO);

        assertThat(exit).isNotZero();
        assertThat(read("notes.txt")).isEqualTo("something else entirely\n");
        assertThat(shown()).contains("Hunk 1");
    }

    @Test
    public void adryRunSaysWhatWouldHappenAndDoesNothing() throws Exception {
        write("notes.txt", "one\ntwo\nthree\n");

        int exit = patch(CHANGE_LINE_TWO, "--dry-run");

        assertThat(exit).isZero();
        assertThat(read("notes.txt")).isEqualTo("one\ntwo\nthree\n");
        assertThat(shown()).containsIgnoringCase("would");
    }

    @Test
    public void adryRunOfAPatchThatDoesNotFitSaysSoWithoutTouchingAnything() throws Exception {
        write("notes.txt", "something else entirely\n");

        assertThat(patch(CHANGE_LINE_TWO, "--dry-run")).isNotZero();
        assertThat(read("notes.txt")).isEqualTo("something else entirely\n");
    }

    @Test
    public void onefileThatWillNotTakeThePatchStopsTheOthers() throws Exception {
        write("one.txt", "a\n");
        write("two.txt", "nothing like it\n");
        String both = """
                --- a/one.txt
                +++ b/one.txt
                @@ -1 +1 @@
                -a
                +A
                --- a/two.txt
                +++ b/two.txt
                @@ -1 +1 @@
                -b
                +B
                """;

        assertThat(patch(both)).isNotZero();
        assertThat(read("one.txt"))
                .as("a half-applied patch is a state nobody asked for")
                .isEqualTo("a\n");
    }

    @Test
    public void apatchNamingAFileThatIsNotThereIsRefused() {
        assertThat(patch(CHANGE_LINE_TWO)).isNotZero();
        assertThat(shown()).contains("notes.txt");
    }

    @Test
    public void textThatIsNotAPatchIsSaidNotToBeOne() {
        int exit = patch("I changed the second line to TWO.");

        assertThat(exit).isNotZero();
        assertThat(shown()).containsIgnoringCase("no patch");
    }

    @Test
    public void readOnlyModeRefusesBeforeAnythingIsWritten() throws Exception {
        write("notes.txt", "one\ntwo\nthree\n");
        Configuration config = new Configuration();
        config.getSecurity().setReadOnlyMode(true);
        config.getSecurity().setAllowOutsideProject(true);

        assertThat(patch(config, CHANGE_LINE_TWO)).isNotZero();
        assertThat(read("notes.txt")).isEqualTo("one\ntwo\nthree\n");
    }

    @Test
    public void apatchAlreadyInTheFileIsSaidToBeAlreadyThere() throws Exception {
        write("notes.txt", "one\nTWO\nthree\n");

        patch(CHANGE_LINE_TWO);

        assertThat(shown()).containsIgnoringCase("already");
    }

    @Test
    public void whatDiffPrintsIsWhatPatchApplies() throws Exception {
        write("before.txt", "one\ntwo\nthree\n");
        String diff = com.eonmux.cadetcoder.util.UnifiedDiff.between(
                "before.txt", "one\ntwo\nthree\n", "before.txt", "one\nTWO\nthree\n", 3);

        assertThat(patch(diff)).isZero();
        assertThat(read("before.txt")).isEqualTo("one\nTWO\nthree\n");
    }

    @Test
    public void apatchThatAddsAFileCreatesIt() throws Exception {
        String addition = """
                --- /dev/null
                +++ b/added.txt
                @@ -0,0 +1,2 @@
                +one
                +two
                """;

        assertThat(patch(addition)).isZero();
        assertThat(read("added.txt")).isEqualTo("one\ntwo\n");
    }

    @Test
    public void apatchThatAddsAFileInANewDirectoryMakesTheDirectory() throws Exception {
        String addition = """
                --- /dev/null
                +++ b/src/deep/added.txt
                @@ -0,0 +1 @@
                +hello
                """;

        assertThat(patch(addition)).isZero();
        assertThat(read("src/deep/added.txt")).isEqualTo("hello\n");
    }

    @Test
    public void apatchThatAddsAFileAlreadyThereIsRefusedRatherThanOverwriting() throws Exception {
        write("added.txt", "somebody else's work\n");
        String addition = """
                --- /dev/null
                +++ b/added.txt
                @@ -0,0 +1 @@
                +one
                """;

        assertThat(patch(addition)).isNotZero();
        assertThat(read("added.txt"))
                .as("a patch that adds a file must not silently replace one")
                .isEqualTo("somebody else's work\n");
    }

    @Test
    public void apatchThatRemovesAFileDeletesIt() throws Exception {
        write("gone.txt", "one\ntwo\n");
        String removal = """
                --- a/gone.txt
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -one
                -two
                """;

        assertThat(patch(removal)).isZero();
        assertThat(Files.exists(project.getRoot().toPath().resolve("gone.txt"))).isFalse();
    }

    @Test
    public void apatchThatRemovesAFileHoldingSomethingElseIsRefused() throws Exception {
        write("gone.txt", "not what the patch expects\n");
        String removal = """
                --- a/gone.txt
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -one
                -two
                """;

        assertThat(patch(removal)).isNotZero();
        assertThat(Files.exists(project.getRoot().toPath().resolve("gone.txt"))).isTrue();
    }

    @Test
    public void apatchThatRemovesAFileAlreadyGoneSaysItIsAlreadyDone() throws Exception {
        String removal = """
                --- a/gone.txt
                +++ /dev/null
                @@ -1 +0,0 @@
                -one
                """;

        patch(removal);

        assertThat(shown()).containsIgnoringCase("already");
    }

    @Test
    public void acreationAndAnEditInOnePatchBothHappen() throws Exception {
        write("notes.txt", "one\ntwo\nthree\n");
        String both = """
                --- /dev/null
                +++ b/added.txt
                @@ -0,0 +1 @@
                +new
                --- a/notes.txt
                +++ b/notes.txt
                @@ -1,3 +1,3 @@
                 one
                -two
                +TWO
                 three
                """;

        assertThat(patch(both)).isZero();
        assertThat(read("added.txt")).isEqualTo("new\n");
        assertThat(read("notes.txt")).isEqualTo("one\nTWO\nthree\n");
    }

    @Test
    public void acreationThatCannotHappenLeavesTheEditUndoneToo() throws Exception {
        write("notes.txt", "one\ntwo\nthree\n");
        write("added.txt", "in the way\n");
        String both = """
                --- /dev/null
                +++ b/added.txt
                @@ -0,0 +1 @@
                +new
                --- a/notes.txt
                +++ b/notes.txt
                @@ -1,3 +1,3 @@
                 one
                -two
                +TWO
                 three
                """;

        assertThat(patch(both)).isNotZero();
        assertThat(read("notes.txt"))
                .as("a patch goes in whole or not at all, creations included")
                .isEqualTo("one\ntwo\nthree\n");
    }

    @Test
    public void adryRunOfACreationMakesNoFile() {
        String addition = """
                --- /dev/null
                +++ b/added.txt
                @@ -0,0 +1 @@
                +one
                """;

        assertThat(patch(addition, "--dry-run")).isZero();
        assertThat(Files.exists(project.getRoot().toPath().resolve("added.txt"))).isFalse();
    }

    @Test
    public void adryRunOfADeletionKeepsTheFile() throws Exception {
        write("gone.txt", "one\n");
        String removal = """
                --- a/gone.txt
                +++ /dev/null
                @@ -1 +0,0 @@
                -one
                """;

        assertThat(patch(removal, "--dry-run")).isZero();
        assertThat(Files.exists(project.getRoot().toPath().resolve("gone.txt"))).isTrue();
    }

    @Test
    public void readOnlyModeRefusesACreationToo() {
        Configuration config = new Configuration();
        config.getSecurity().setReadOnlyMode(true);
        config.getSecurity().setAllowOutsideProject(true);
        String addition = """
                --- /dev/null
                +++ b/added.txt
                @@ -0,0 +1 @@
                +one
                """;

        assertThat(patch(config, addition)).isNotZero();
        assertThat(Files.exists(project.getRoot().toPath().resolve("added.txt"))).isFalse();
    }

    @Test
    public void nothingAtAllIsReportedWithTheUsage() {
        assertThat(patch()).isNotZero();
        assertThat(shown()).containsIgnoringCase("patch");
    }

    @Test
    public void twoSectionsNamingTheSameFileBothGoIn() throws Exception {
        write("notes.txt", "one\ntwo\nthree\n");
        String twice = """
                --- a/notes.txt
                +++ b/notes.txt
                @@ -1 +1 @@
                -one
                +ONE
                --- a/notes.txt
                +++ b/notes.txt
                @@ -3 +3 @@
                -three
                +THREE
                """;

        assertThat(patch(twice)).isZero();
        assertThat(read("notes.txt"))
                .as("a second section worked out against the file on disk loses the first one")
                .isEqualTo("ONE\ntwo\nTHREE\n");
    }

    @Test
    public void apatchAppliesToAfileWithWindowsLineEndings() throws Exception {
        write("notes.txt", "one\r\ntwo\r\nthree\r\n");

        assertThat(patch(CHANGE_LINE_TWO)).isZero();
        assertThat(read("notes.txt"))
                .as("rewriting a CRLF file with bare newlines shows up as every line changed")
                .isEqualTo("one\r\nTWO\r\nthree\r\n");
    }

    @Test
    public void apatchThatTakesTheFinalNewlineOffTakesItOff() throws Exception {
        write("notes.txt", "one\ntwo\n");
        String removal = """
                --- a/notes.txt
                +++ b/notes.txt
                @@ -2 +2 @@
                -two
                +two
                \\ No newline at end of file
                """;

        assertThat(patch(removal)).isZero();
        assertThat(read("notes.txt")).isEqualTo("one\ntwo");
    }

    @Test
    public void afileAddedWithoutAfinalNewlineIsWrittenWithoutOne() throws Exception {
        String addition = """
                --- /dev/null
                +++ b/added.txt
                @@ -0,0 +1 @@
                +only
                \\ No newline at end of file
                """;

        assertThat(patch(addition)).isZero();
        assertThat(read("added.txt")).isEqualTo("only");
    }

    @Test
    public void apatchedScriptIsStillAscriptThatRuns() throws Exception {
        write("scripts/build.sh", "#!/bin/sh\necho one\n");
        Path script = project.getRoot().toPath().resolve("scripts/build.sh");
        assumeTrue(Files.getFileStore(script).supportsFileAttributeView("posix"));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        String change = """
                --- a/scripts/build.sh
                +++ b/scripts/build.sh
                @@ -2 +2 @@
                -echo one
                +echo two
                """;

        assertThat(patch(change)).isZero();
        assertThat(Files.getPosixFilePermissions(script))
                .as("a patch that leaves a build script unrunnable has not applied the patch")
                .contains(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ);
    }

    @Test
    public void ahunkThatWentInSomewhereElseSaysWhere() throws Exception {
        write("notes.txt", "added\nalso added\none\ntwo\nthree\n");

        assertThat(patch(CHANGE_LINE_TWO)).isZero();
        assertThat(shown())
                .as("a hunk applied away from its stated line is a guess worth saying out loud")
                .containsIgnoringCase("offset");
    }

    @Test
    public void apatchWhoseHunkDoesNotMatchItsOwnHeaderIsRefused() throws Exception {
        write("notes.txt", "one\ntwo\nthree\n");
        String malformed = """
                --- a/notes.txt
                +++ b/notes.txt
                @@ -1,3 +1,3 @@
                 one
                -two
                -three
                """;

        assertThat(patch(malformed)).isNotZero();
        assertThat(read("notes.txt"))
                .as("a hunk that writes back fewer lines than its header describes deletes the rest")
                .isEqualTo("one\ntwo\nthree\n");
    }

    /**
     * Runs one patch with reading outside the project allowed, and with the system temporary
     * directory moved inside the project, so {@link #outside} is outside both places a write may go.
     */
    private int patchWithReachOutsideTheProject(String diff) throws IOException {
        String wasTemp = System.getProperty("java.io.tmpdir");
        Path   temp    = Files.createDirectories(project.getRoot().toPath().resolve("tmp"));
        System.setProperty("java.io.tmpdir", temp.toString());
        try {
            return patch(diff);
        } finally {
            System.setProperty("java.io.tmpdir", wasTemp);
        }
    }

    @Test
    public void apatchCannotAddAFileOutsideTheProjectEvenWhenReadingThereIsAllowed() throws Exception {
        Path target = outside.getRoot().toPath().resolve("escaped.txt");
        String addition = "--- /dev/null\n+++ " + target + "\n@@ -0,0 +1 @@\n+escaped\n";

        assertThat(patchWithReachOutsideTheProject(addition)).isNotZero();
        assertThat(Files.exists(target)).isFalse();
        assertThat(shown()).contains("outside working directory");
    }

    @Test
    public void apatchCannotRemoveAFileOutsideTheProjectEvenWhenReadingThereIsAllowed() throws Exception {
        Path target = outside.getRoot().toPath().resolve("kept.txt");
        Files.writeString(target, "one\n");
        String removal = "--- " + target + "\n+++ /dev/null\n@@ -1 +0,0 @@\n-one\n";

        assertThat(patchWithReachOutsideTheProject(removal)).isNotZero();
        assertThat(Files.exists(target)).isTrue();
    }
}
