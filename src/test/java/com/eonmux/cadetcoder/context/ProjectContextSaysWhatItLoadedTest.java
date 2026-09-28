package com.eonmux.cadetcoder.context;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the project context tells the user about where it came from and what it could not read.
 *
 * <p>A {@code CADET.md} found in a parent directory was handled as a lesser case of the same file
 * found in the working directory, and lost something at every step: a read failure was passed over
 * in silence where the working-directory copy warned, the confirmation named no directory, the path
 * recorded for {@code context show} pointed at a file that does not exist, and the file's own
 * {@code @include} directives were resolved against the working directory instead of the directory
 * that declares them, so they resolved to nothing and were dropped without a word.</p>
 */
public class ProjectContextSaysWhatItLoadedTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private TestOutputCapture outputCapture;
    private String            originalWorkingDir;

    @Before
    public void setUp() {
        outputCapture      = new TestOutputCapture();
        originalWorkingDir = System.getProperty("user.dir");
        // Other suites reach this singleton and leave it loaded from the real project directory;
        // without this, what is asserted below is whatever they happened to load first.
        ProjectContextTest.ProjectContextTestHelper.resetInstance();
    }

    @After
    public void tearDown() {
        outputCapture.restore();
        System.setProperty("user.dir", originalWorkingDir);
        ProjectContextTest.ProjectContextTestHelper.resetInstance();
    }

    /**
     * A working directory with a parent, both empty.
     *
     * @return the parent, whose {@code sub} child is now {@code user.dir}
     */
    private Path parentOfWorkingDirectory() throws IOException {
        File parent     = tempFolder.newFolder("project");
        File workingDir = new File(parent, "sub");
        assertThat(workingDir.mkdirs()).isTrue();
        System.setProperty("user.dir", workingDir.getAbsolutePath());
        return parent.toPath();
    }

    /**
     * Makes a path unreadable in a way that does not depend on who runs the test.
     *
     * <p>A permission bit is ignored by a user who can read anything; a directory where a file is
     * expected cannot be read as text by anyone.</p>
     */
    private void makeUnreadable(Path file) throws IOException {
        Files.createDirectories(file);
        Files.writeString(file.resolve("occupied.txt"), "in the way");
    }

    @Test
    public void aParentContextFileThatCouldNotBeReadIsReported() throws IOException {
        Path parent = parentOfWorkingDirectory();
        makeUnreadable(parent.resolve("CADET.md"));

        ProjectContext context = ProjectContext.getInstance();

        assertThat(context.hasProjectContext()).isFalse();
        assertThat(outputCapture.getAllOutput())
                .as("a CADET.md that is there but unreadable is not the same as no CADET.md")
                .contains("CADET.md");
    }

    @Test
    public void theDirectoryTheContextWasLoadedFromIsNamed() throws IOException {
        Path parent = parentOfWorkingDirectory();
        Files.writeString(parent.resolve("CADET.md"), "# Parent context");

        ProjectContext.getInstance();

        assertThat(outputCapture.getAllOutput())
                .as("'from parent directory' names one of three directories it could have been")
                .contains(parent.resolve("CADET.md").toString());
    }

    @Test
    public void theRecordedContextFileNamesAFileThatExists() throws IOException {
        Path parent = parentOfWorkingDirectory();
        Files.writeString(parent.resolve("CADET.md"), "# Parent context");

        ProjectContext context = ProjectContext.getInstance();

        assertThat(context.getContextFiles()).hasSize(1);
        Path listed = Path.of(System.getProperty("user.dir")).resolve(context.getContextFiles().get(0));
        assertThat(Files.exists(listed))
                .as("`context show` listed %s, which is not a file", listed)
                .isTrue();
    }

    @Test
    public void anIncludeInAParentContextFileIsResolvedAgainstThatParent() throws IOException {
        Path parent = parentOfWorkingDirectory();
        Files.createDirectories(parent.resolve("src"));
        Files.writeString(parent.resolve("src/Main.java"), "public class Main {}");
        Files.writeString(parent.resolve("CADET.md"), "# Parent context\n@include src/Main.java\n");

        ProjectContext context = ProjectContext.getInstance();

        assertThat(context.getContextFiles())
                .as("the directive belongs to the file that declares it, not to the shell's cwd")
                .hasSize(2);
        assertThat(context.getContextFiles().get(1)).contains("Main.java");
    }

    @Test
    public void anIncludeThatNamesNoFileIsReported() throws IOException {
        File workingDir = tempFolder.newFolder("solo");
        System.setProperty("user.dir", workingDir.getAbsolutePath());
        Files.writeString(workingDir.toPath().resolve("CADET.md"),
                "# Context\n@include docs/design.md\n");

        ProjectContext.getInstance();

        assertThat(outputCapture.getAllOutput())
                .as("a declared include that resolves to nothing is a mistake worth saying out loud")
                .contains("docs/design.md");
    }
}
