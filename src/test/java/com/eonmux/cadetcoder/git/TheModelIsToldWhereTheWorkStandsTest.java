package com.eonmux.cadetcoder.git;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.eclipse.jgit.api.Git;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What the repository is in the middle of reaches the prompt when it was asked for.
 *
 * <p><b>The defect</b>: {@code git.includeBranches}, {@code git.includeCommitHistory} and
 * {@code git.maxCommitHistory} were settable, listed by {@code cadet config} and documented in the
 * README -- and no prompt anywhere was assembled from any of them. Turning them on changed nothing,
 * so a model was told the project's files and never that it was on a branch called
 * {@code fix/the-parser} with four commits of attempts behind it.</p>
 */
public class TheModelIsToldWhereTheWorkStandsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private TestOutputCapture output;
    private File              project;

    @Before
    public void setUp() throws Exception {
        output  = new TestOutputCapture();
        project = folder.newFolder("checkout");
    }

    @After
    public void tearDown() {
        output.restore();
    }

    private Configuration.GitConfig asked(boolean branches, boolean history, int maxHistory) {
        Configuration.GitConfig config = new Configuration.GitConfig();
        config.setIncludeBranches(branches);
        config.setIncludeCommitHistory(history);
        config.setMaxCommitHistory(maxHistory);
        return config;
    }

    private Git repositoryWith(String... messages) throws Exception {
        Path where = project.toPath();
        Git  git   = Git.init().setDirectory(project).call();
        int  n     = 0;
        for (String message : messages) {
            Files.writeString(where.resolve("file" + n++ + ".txt"), message);
            git.add().addFilepattern(".").call();
            git.commit().setMessage(message).call();
        }
        return git;
    }

    @Test
    public void nothingIsSaidUntilSomethingIsAskedFor() throws Exception {
        try (Git ignored = repositoryWith("the work so far")) {
            assertThat(RepositoryContext.render(asked(false, false, 10), project))
                    .as("both parts are off by default and cost prompt when they are not")
                    .isEmpty();
        }
    }

    @Test
    public void theBranchTheWorkIsOnIsNamed() throws Exception {
        try (Git git = repositoryWith("the work so far")) {
            git.checkout().setCreateBranch(true).setName("fix/the-parser").call();

            String block = RepositoryContext.render(asked(true, false, 10), project);

            assertThat(block).startsWith(RepositoryContext.HEADING);
            assertThat(block).contains("Current branch: fix/the-parser");
            assertThat(block).contains("Other branches: ");
            assertThat(block).doesNotContain("Recent commits");
        }
    }

    @Test
    public void theCommitsBehindItAreListedNewestFirstAndBounded() throws Exception {
        try (Git ignored = repositoryWith("oldest", "middle", "newest")) {
            String block = RepositoryContext.render(asked(false, true, 2), project);

            assertThat(block).contains("Recent commits (newest first):");
            assertThat(block).contains("newest").contains("middle");
            assertThat(block)
                    .as("git.maxCommitHistory is the bound the user set, not a suggestion")
                    .doesNotContain("oldest");
            assertThat(block).doesNotContain("Current branch");
        }
    }

    @Test
    public void gitTurnedOffMeansNothingIsReadAtAll() throws Exception {
        try (Git ignored = repositoryWith("the work so far")) {
            Configuration.GitConfig config = asked(true, true, 10);
            config.setEnabled(false);

            assertThat(RepositoryContext.render(config, project)).isEmpty();
        }
    }

    @Test
    public void adirectoryThatIsNotAcheckoutSaysNothingRatherThanFailing() {
        assertThat(RepositoryContext.render(asked(true, true, 10), project))
                .as("not every project is a repository, and that is not an error")
                .isEmpty();
    }

    @Test
    public void arepositoryWithNoCommitsYetHasNoHistoryRatherThanAfailure() throws Exception {
        try (Git ignored = Git.init().setDirectory(project).call()) {
            assertThat(RepositoryContext.render(asked(false, true, 10), project))
                    .as("a fresh 'git init' has no HEAD, which is not a failure to read one")
                    .isEmpty();
        }
    }

    @Test
    public void ahistoryBoundOfZeroCarriesNoCommits() throws Exception {
        try (Git ignored = repositoryWith("the work so far")) {
            assertThat(RepositoryContext.render(asked(false, true, 0), project)).isEmpty();
        }
    }

    /**
     * The method the prompts actually call, rather than the one the rest of this exercises.
     *
     * <p>{@code forPrompt} is {@code render} plus the two things that can only go wrong outside it:
     * reading the configuration, and finding the repository the process is running in. Both were
     * untested while every test here called straight past them.</p>
     */
    @Test
    public void theMethodThePromptsCallReadsTheConfigurationAndTheWorkingDirectory() throws Exception {
        String wasWorkingDir = System.getProperty("user.dir");
        try (Git ignored = repositoryWith("the work so far")) {
            System.setProperty("user.dir", project.getAbsolutePath());

            ConfigManager manager = mock(ConfigManager.class);
            Configuration config  = new Configuration();
            config.setGit(asked(true, true, 5));
            when(manager.getConfig()).thenReturn(config);

            try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
                configured.when(ConfigManager::getInstance).thenReturn(manager);

                assertThat(RepositoryContext.forPrompt())
                        .as("what render() produces, for the repository this process is in")
                        .contains(RepositoryContext.HEADING)
                        .contains("Current branch: ")
                        .contains("the work so far");

                config.setGit(asked(false, false, 5));
                assertThat(RepositoryContext.forPrompt())
                        .as("and nothing at all when neither part was asked for")
                        .isEmpty();
            }
        } finally {
            if (wasWorkingDir != null) {
                System.setProperty("user.dir", wasWorkingDir);
            }
        }
    }

    /** A configuration that cannot be read is a prompt without this, not a failed run. */
    @Test
    public void aconfigurationThatCannotBeReadCostsTheBlockRatherThanTheRun() {
        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            configured.when(ConfigManager::getInstance)
                      .thenThrow(new IllegalStateException("no configuration"));

            assertThat(RepositoryContext.forPrompt()).isEmpty();
        }
    }
}
