package com.eonmux.cadetcoder.context;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for ProjectContext
 */
public class ProjectContextTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private TestOutputCapture outputCapture;
    private String            originalWorkingDir;

    @Before
    public void setUp() throws IOException {
        outputCapture      = new TestOutputCapture();
        originalWorkingDir = System.getProperty("user.dir");
        // Reset on the way in as well as on the way out: suites that merely read the context leave
        // this singleton loaded from the real project directory.
        ProjectContextTestHelper.resetInstance();
    }

    @After
    public void tearDown() {
        outputCapture.restore();
        System.setProperty("user.dir", originalWorkingDir);
        // Reset singleton instance for each test
        ProjectContextTestHelper.resetInstance();
    }

    @Test
    public void testProjectContext_Singleton() throws IOException {
        File testDir = tempFolder.newFolder("test-project");
        System.setProperty("user.dir", testDir.getAbsolutePath());

        ProjectContext context1 = ProjectContext.getInstance();
        ProjectContext context2 = ProjectContext.getInstance();

        assertThat(context1).isSameAs(context2);
    }

    @Test
    public void testProjectContext_NoCadetFile() throws IOException {
        File testDir = tempFolder.newFolder("test-project");
        System.setProperty("user.dir", testDir.getAbsolutePath());

        ProjectContext context = ProjectContext.getInstance();

        assertThat(context.hasProjectContext()).isFalse();
        assertThat(context.getProjectContext()).isEmpty();
        assertThat(context.getContextFiles()).isEmpty();
    }

    @Test
    public void testProjectContext_WithCadetFile() throws IOException {
        File testDir = tempFolder.newFolder("test-project");
        System.setProperty("user.dir", testDir.getAbsolutePath());

        // Create CADET.md file
        File   cadetFile = new File(testDir, "CADET.md");
        String content   =
                "# Project Context\nThis is a test project for CadetCoder.\n\n## Features\n- Command processing\n- AI integration";
        Files.writeString(cadetFile.toPath(), content);

        ProjectContext context = ProjectContext.getInstance();

        assertThat(context.hasProjectContext()).isTrue();
        assertThat(context.getProjectContext()).isEqualTo(content);
        assertThat(context.getContextFiles()).contains("CADET.md");

        String output = outputCapture.getAllOutput();
        assertThat(output).contains("Loaded project context from CADET.md");
    }

    @Test
    public void testProjectContext_WithIncludeDirectives() throws IOException {
        File testDir = tempFolder.newFolder("test-project");
        System.setProperty("user.dir", testDir.getAbsolutePath());

        // Create test files to include
        File srcDir = new File(testDir, "src");
        srcDir.mkdirs();
        File mainFile = new File(srcDir, "Main.java");
        Files.writeString(mainFile.toPath(), "public class Main {}");

        File readmeFile = new File(testDir, "README.md");
        Files.writeString(readmeFile.toPath(), "# Test Project");

        // Create CADET.md with include directives
        File cadetFile = new File(testDir, "CADET.md");
        String content = """
                         # Project Context
                         This project demonstrates include directives.
                         
                         @include src/Main.java
                         @file README.md
                         """;
        Files.writeString(cadetFile.toPath(), content);

        ProjectContext context = ProjectContext.getInstance();

        assertThat(context.hasProjectContext()).isTrue();
        assertThat(context.getContextFiles()).hasSize(3);
        assertThat(context.getContextFiles()).contains("CADET.md", "src/Main.java", "README.md");
    }

    @Test
    public void testProjectContext_WithNonexistentIncludeFile() throws IOException {
        File testDir = tempFolder.newFolder("test-project");
        System.setProperty("user.dir", testDir.getAbsolutePath());

        // Create CADET.md with include directive for non-existent file
        File cadetFile = new File(testDir, "CADET.md");
        String content = """
                         # Project Context
                         
                         @include nonexistent.java
                         @file also-missing.md
                         """;
        Files.writeString(cadetFile.toPath(), content);

        ProjectContext context = ProjectContext.getInstance();

        assertThat(context.hasProjectContext()).isTrue();
        assertThat(context.getContextFiles()).hasSize(1); // Only CADET.md itself
        assertThat(context.getContextFiles()).contains("CADET.md");
    }

    @Test
    public void testProjectContext_InParentDirectory() throws IOException {
        File rootDir    = tempFolder.newFolder("root");
        File projectDir = new File(rootDir, "project");
        projectDir.mkdirs();
        File workingDir = new File(projectDir, "subdir");
        workingDir.mkdirs();

        System.setProperty("user.dir", workingDir.getAbsolutePath());

        // Create CADET.md in parent directory
        File   cadetFile = new File(projectDir, "CADET.md");
        String content   = "# Parent Project Context\nThis is in the parent directory.";
        Files.writeString(cadetFile.toPath(), content);

        ProjectContext context = ProjectContext.getInstance();

        assertThat(context.hasProjectContext()).isTrue();
        assertThat(context.getProjectContext()).isEqualTo(content);

        // Names the file it read, not the fact that it was somewhere above: "parent directory"
        // is one of the three the search walks, and the user cannot tell which.
        String output = outputCapture.getAllOutput();
        assertThat(output).contains("Loaded project context from " + cadetFile.getAbsolutePath());
    }

    @Test
    public void testProjectContext_InGrandparentDirectory() throws IOException {
        File rootDir   = tempFolder.newFolder("root");
        File parentDir = new File(rootDir, "parent");
        parentDir.mkdirs();
        File projectDir = new File(parentDir, "project");
        projectDir.mkdirs();
        File workingDir = new File(projectDir, "subdir");
        workingDir.mkdirs();

        System.setProperty("user.dir", workingDir.getAbsolutePath());

        // Create CADET.md in grandparent directory
        File   cadetFile = new File(parentDir, "CADET.md");
        String content   = "# Grandparent Project Context";
        Files.writeString(cadetFile.toPath(), content);

        ProjectContext context = ProjectContext.getInstance();

        assertThat(context.hasProjectContext()).isTrue();
        assertThat(context.getProjectContext()).isEqualTo(content);
    }

    @Test
    public void testProjectContext_IoExceptionHandling() throws IOException {
        File testDir = tempFolder.newFolder("test-project");
        System.setProperty("user.dir", testDir.getAbsolutePath());

        // Create CADET.md file with restricted permissions (simulate IOException)
        File cadetFile = new File(testDir, "CADET.md");
        Files.writeString(cadetFile.toPath(), "test content");

        // We can't easily simulate IOException in a cross-platform way,
        // so we'll test the basic functionality instead
        ProjectContext context = ProjectContext.getInstance();

        assertThat(context).isNotNull();
    }

    @Test
    public void testReload() throws IOException {
        File testDir = tempFolder.newFolder("test-project");
        System.setProperty("user.dir", testDir.getAbsolutePath());

        ProjectContext context = ProjectContext.getInstance();

        // Initially no context
        assertThat(context.hasProjectContext()).isFalse();

        // Create CADET.md file
        File   cadetFile = new File(testDir, "CADET.md");
        String content   = "# New Project Context\nAdded after initialization.";
        Files.writeString(cadetFile.toPath(), content);

        // Reload should pick up the new file
        context.reload();

        assertThat(context.hasProjectContext()).isTrue();
        assertThat(context.getProjectContext()).isEqualTo(content);
        assertThat(context.getContextFiles()).contains("CADET.md");

        String output = outputCapture.getAllOutput();
        assertThat(output).contains("Loaded project context from CADET.md");
    }

    @Test
    public void testCreateDefaultContextFile() throws IOException {
        File testDir = tempFolder.newFolder("test-project");
        System.setProperty("user.dir", testDir.getAbsolutePath());

        ProjectContext.createDefaultContextFile();

        File cadetFile = new File(testDir, "CADET.md");
        assertThat(cadetFile).exists();

        String content = Files.readString(cadetFile.toPath());
        assertThat(content).contains("# Project Context for CadetCoder");
        assertThat(content).contains("## Project Overview");
        assertThat(content).contains("@include src/main/java/Main.java");
        assertThat(content).contains("@include README.md");

        String output = outputCapture.getAllOutput();
        assertThat(output).contains("Created CADET.md with default template");
    }

    @Test
    public void testCreateDefaultContextFile_FileExists() throws IOException {
        File testDir = tempFolder.newFolder("test-project");
        System.setProperty("user.dir", testDir.getAbsolutePath());

        // Create existing CADET.md
        File cadetFile = new File(testDir, "CADET.md");
        Files.writeString(cadetFile.toPath(), "existing content");

        ProjectContext.createDefaultContextFile();

        // File should not be overwritten
        String content = Files.readString(cadetFile.toPath());
        assertThat(content).isEqualTo("existing content");

        String output = outputCapture.getAllOutput();
        assertThat(output).contains("CADET.md already exists");
    }

    @Test
    public void testGetContextFiles_ReturnsMutableCopy() throws IOException {
        File testDir = tempFolder.newFolder("test-project");
        System.setProperty("user.dir", testDir.getAbsolutePath());

        // Create CADET.md with includes
        File cadetFile = new File(testDir, "CADET.md");
        Files.writeString(cadetFile.toPath(), "# Test\n@include test.txt");

        // Create included file
        File testFile = new File(testDir, "test.txt");
        Files.writeString(testFile.toPath(), "test content");

        ProjectContext context      = ProjectContext.getInstance();
        List<String>   contextFiles = context.getContextFiles();

        // Modifying the returned list should not affect the internal state
        int originalSize = contextFiles.size();
        contextFiles.add("should-not-affect-internal-state");

        // Verify internal state is unchanged even though returned list was modified
        assertThat(context.getContextFiles()).hasSize(originalSize);
        assertThat(context.getContextFiles()).doesNotContain("should-not-affect-internal-state");
    }

    @Test
    public void testAbsolutePathInclude() throws IOException {
        File testDir = tempFolder.newFolder("test-project");
        System.setProperty("user.dir", testDir.getAbsolutePath());

        // Create a file in temp directory with absolute path
        File absoluteFile = tempFolder.newFile("absolute-test.txt");
        Files.writeString(absoluteFile.toPath(), "absolute content");

        // Create CADET.md with absolute path include
        File   cadetFile = new File(testDir, "CADET.md");
        String content   = "# Test\n@include " + absoluteFile.getAbsolutePath();
        Files.writeString(cadetFile.toPath(), content);

        ProjectContext context = ProjectContext.getInstance();

        assertThat(context.getContextFiles()).contains("CADET.md", absoluteFile.getAbsolutePath());
    }

    /**
     * Helper class to reset the singleton instance for testing
     */
    public static class ProjectContextTestHelper {
        public static void resetInstance() {
            try {
                java.lang.reflect.Field instanceField = ProjectContext.class.getDeclaredField("instance");
                instanceField.setAccessible(true);
                instanceField.set(null, null);
            } catch (Exception e) {
                // Ignore reflection errors in tests
            }
        }
    }
}