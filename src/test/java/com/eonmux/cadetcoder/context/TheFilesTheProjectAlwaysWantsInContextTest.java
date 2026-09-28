package com.eonmux.cadetcoder.context;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@code context.priorityFiles} names the files that go in front of the model every time.
 *
 * <h2>The defect</h2>
 *
 * <p>The setting could be written, was saved, was documented in the README and was read by nothing.
 * A project whose conventions live in {@code CONTRIBUTING.md} could say so, and the model went on
 * being shown whatever an index search over file contents happened to return for the sentence the
 * user typed. A knob that does nothing is worse than a missing one: the user believes the tool has
 * been told.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>The configured files are put in front of the model whether or not the request mentions them,
 * ahead of the files the request names, without repeats, and under the same ceiling on how many
 * whole files one prompt may carry. They are held to the boundary every other path into a prompt is
 * held to: a configured name cannot reach outside the project, cannot be a credential file, and
 * cannot be something that is not text.</p>
 */
public class TheFilesTheProjectAlwaysWantsInContextTest {

    @Rule
    public TemporaryFolder project = new TemporaryFolder();

    private MockedStatic<ConfigManager> configManager;
    private Configuration               config;

    @Before
    public void setUp() {
        config = new Configuration();

        ConfigManager configured = mock(ConfigManager.class);
        when(configured.getConfig()).thenReturn(config);

        configManager = mockStatic(ConfigManager.class);
        configManager.when(ConfigManager::getInstance).thenReturn(configured);
    }

    @After
    public void tearDown() {
        configManager.close();
    }

    @Test
    public void aConfiguredFileIsIncludedWhenTheRequestNeverMentionsIt() throws IOException {
        Path conventions = file("CONTRIBUTING.md", "# How we write code here");
        config.getContext().setPriorityFiles(new String[] {"CONTRIBUTING.md"});

        assertThat(forRequest("make the parser reject an empty ARGS block"))
                .containsExactly(conventions);
    }

    @Test
    public void aConfiguredFileComesBeforeTheFilesTheRequestNames() throws IOException {
        Path conventions = file("CONTRIBUTING.md", "# How we write code here");
        Path parser      = file("Parser.java", "class Parser {}");
        config.getContext().setPriorityFiles(new String[] {"CONTRIBUTING.md"});

        assertThat(forRequest("make Parser.java reject an empty ARGS block"))
                .containsExactly(conventions, parser);
    }

    @Test
    public void aConfiguredFileTheRequestAlsoNamesIsIncludedOnce() throws IOException {
        Path readme = file("README.md", "# Read me");
        config.getContext().setPriorityFiles(new String[] {"README.md"});

        assertThat(forRequest("fix the broken link in README.md")).containsExactly(readme);
    }

    @Test
    public void theConfiguredOrderIsTheOrderTheyAreGivenIn() throws IOException {
        Path first  = file("docs/one.md", "one");
        Path second = file("docs/two.md", "two");
        config.getContext().setPriorityFiles(new String[] {"docs/two.md", "docs/one.md"});

        assertThat(forRequest("tidy the wording")).containsExactly(second, first);
    }

    @Test
    public void configuringNothingChangesNothing() throws IOException {
        Path parser = file("Parser.java", "class Parser {}");

        assertThat(forRequest("make Parser.java reject an empty ARGS block"))
                .containsExactly(parser);
    }

    @Test
    public void aConfiguredNameThatIsNotThereIsPassedOver() throws IOException {
        Path parser = file("Parser.java", "class Parser {}");
        config.getContext().setPriorityFiles(new String[] {"NoSuchFile.md"});

        assertThat(forRequest("make Parser.java reject an empty ARGS block"))
                .containsExactly(parser);
    }

    @Test
    public void aConfiguredNameCannotReachOutsideTheProject() throws IOException {
        Path outside = project.getRoot().toPath().getParent().resolve("outside.txt");
        Files.writeString(outside, "not this project's");
        config.getContext().setPriorityFiles(new String[] {"../outside.txt"});

        assertThat(forRequest("tidy the wording")).isEmpty();
    }

    @Test
    public void aConfiguredNameCannotBeACredentialFile() throws IOException {
        file(".env", "OPENAI_API_KEY=sk-live-000");
        config.getContext().setPriorityFiles(new String[] {".env"});

        assertThat(forRequest("tidy the wording")).isEmpty();
    }

    @Test
    public void aConfiguredNameThatIsNotTextIsNotPastedIntoAPrompt() throws IOException {
        Files.write(project.getRoot().toPath().resolve("logo.png"),
                    new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0});
        config.getContext().setPriorityFiles(new String[] {"logo.png"});

        assertThat(forRequest("tidy the wording")).isEmpty();
    }

    @Test
    public void configuredFilesCannotCarryMoreThanTheContextCeiling() throws IOException {
        int      ceiling   = 3;
        String[] configured = new String[ceiling + 4];
        for (int i = 0; i < configured.length; i++) {
            file("Priority" + i + ".java", "class Priority" + i + " {}");
            configured[i] = "Priority" + i + ".java";
        }
        config.getContext().setMaxFiles(ceiling);
        config.getContext().setPriorityFiles(configured);

        assertThat(forRequest("tidy the wording")).hasSize(ceiling);
    }

    @Test
    public void theCeilingCountsTheConfiguredFilesAndTheNamedOnesTogether() throws IOException {
        file("Always.md", "always");
        file("Named.java", "class Named {}");
        config.getContext().setMaxFiles(1);
        config.getContext().setPriorityFiles(new String[] {"Always.md"});

        assertThat(forRequest("change Named.java"))
                .as("the ceiling bounds what one prompt carries, not one source of it")
                .hasSize(1);
    }

    @Test
    public void nothingIsWantedByNothing() {
        assertThat(ContextFiles.forRequest(null, null)).isEmpty();
    }

    private List<Path> forRequest(String request) {
        return ContextFiles.forRequest(request, project.getRoot().toPath());
    }

    private Path file(String name, String content) throws IOException {
        Path path = project.getRoot().toPath().resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
