package com.eonmux.cadetcoder.config;

import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for Configuration
 */
public class ConfigurationTest {

    private Configuration config;

    @Before
    public void setUp() {
        config = new Configuration();
    }

    @Test
    public void testDefaultConfiguration() {
        // Asserted against the static a fresh Configuration actually reads, not against a literal.
        //
        // This used to expect "<user.home>/.config/cadet", which is not and never was the default --
        // Configuration uses "<user.home>/.cadet". It passed only because ConfigManagerTest runs
        // earlier alphabetically and its teardown wrote that wrong path into the shared static. The
        // test was pinning another test's leak, so fixing the leak broke the test, and the leak was
        // building a configuration tree in the user's home.
        assertThat(config.getBaseDir()).isEqualTo(Configuration.defaultBaseDir);
        assertThat(config.getAi()).isNotNull();
        assertThat(config.getContext()).isNotNull();
        assertThat(config.getIndexing()).isNotNull();
        assertThat(config.getGit()).isNotNull();
        assertThat(config.getUi()).isNotNull();
        assertThat(config.getPerformance()).isNotNull();
        assertThat(config.getSecurity()).isNotNull();
        assertThat(config.getLogging()).isNotNull();
    }

    @Test
    public void testSecurityConfig_ShipsConfinedToTheProjectWithThePersonAsked() {
        Configuration.SecurityConfig security = config.getSecurity();

        assertThat(security.isAllowOutsideProject()).isFalse();
        assertThat(security.getCommandApproval()).isEqualTo("manual");
        assertThat(security.isRequireConfirmation()).isTrue();
        assertThat(security.isAllowRemoteExecution()).isFalse();
    }

    @Test
    public void testSetBaseDir() {
        config.setBaseDir("/custom/path");
        assertThat(config.getBaseDir()).isEqualTo("/custom/path");
    }

    @Test
    public void testAiConfig_Defaults() {
        Configuration.AiConfig aiConfig = config.getAi();

        assertThat(aiConfig.getApiEndpoint()).isEqualTo("https://api.openai.com/v1");
        assertThat(aiConfig.getLocalEndpoint()).isEqualTo("http://localhost:8012");
        assertThat(aiConfig.getModel()).isEqualTo("o1-mini");
        assertThat(aiConfig.getLocalModel()).isEqualTo("llama3-8b-q4");
        assertThat(aiConfig.getApiKey()).isEmpty();
        assertThat(aiConfig.getTemperature()).isEqualTo(0.7f);
        // Zero means no ceiling is asked for; see net/OutputBudget.
        assertThat(aiConfig.getMaxTokens()).isEqualTo(0);
        // Nothing streams, so this is the whole generation rather than a wait for the first byte.
        // At 60 a reasoning model on a large prompt was cut off mid-answer.
        assertThat(aiConfig.getCompletionTimeoutSeconds()).isEqualTo(300);
        assertThat(aiConfig.getChatTemplate()).isEqualTo("chatml");
    }

    @Test
    public void testAiConfig_OpenRouter() {
        Configuration.AiConfig aiConfig = config.getAi();
    }

    @Test
    public void testContextConfig_Defaults() {
        Configuration.ContextConfig contextConfig = config.getContext();

        assertThat(contextConfig.getMaxFiles()).isEqualTo(10);
        assertThat(contextConfig.getMaxLinesPerFile()).isEqualTo(500);
        assertThat(contextConfig.getPriorityFiles()).isEmpty();
    }

    @Test
    public void testIndexingConfig_Defaults() {
        Configuration.IndexingConfig indexingConfig = config.getIndexing();

        assertThat(indexingConfig.isEnabled()).isTrue();
        assertThat(indexingConfig.getIndexLocation()).isEqualTo(".cadet/index");
        // Derived from the shared project-walk rule rather than written out again: this
        // assertion was four names long and the walk knew eight, which is how bundles under
        // dist/ and out/ came to be indexed and served to the model as project sources.
        assertThat(indexingConfig.getExcludePatterns())
                .containsExactlyInAnyOrderElementsOf(
                        com.eonmux.cadetcoder.util.ProjectTreeWalk.buildOutputDirectories());
        assertThat(indexingConfig.getRefreshIntervalMinutes()).isEqualTo(60);
    }

    @Test
    public void testGitConfig_Defaults() {
        Configuration.GitConfig gitConfig = config.getGit();

        assertThat(gitConfig.isEnabled()).isTrue();
        assertThat(gitConfig.isAutoCommitEnabled()).isFalse();
        assertThat(gitConfig.getCommitMessageTemplate()).isEqualTo("Auto-commit on {date}");
        assertThat(gitConfig.getCommitTrigger()).isEqualTo("onChange");
    }

    @Test
    public void testGitConfig_Setters() {
        Configuration.GitConfig gitConfig = config.getGit();

        gitConfig.setEnabled(false);
        gitConfig.setAutoCommitEnabled(true);
        gitConfig.setCommitMessageTemplate("Custom message");
        gitConfig.setCommitTrigger("onInterval");
        gitConfig.setIncludeBranches(true);
        gitConfig.setIncludeCommitHistory(true);
        gitConfig.setMaxCommitHistory(20);

        assertThat(gitConfig.isEnabled()).isFalse();
        assertThat(gitConfig.isAutoCommitEnabled()).isTrue();
        assertThat(gitConfig.getCommitMessageTemplate()).isEqualTo("Custom message");
        assertThat(gitConfig.getCommitTrigger()).isEqualTo("onInterval");
        assertThat(gitConfig.isIncludeBranches()).isTrue();
        assertThat(gitConfig.isIncludeCommitHistory()).isTrue();
        assertThat(gitConfig.getMaxCommitHistory()).isEqualTo(20);
    }

    @Test
    public void testConfiguration_SetAllConfigs() {
        Configuration.AiConfig newAiConfig = new Configuration.AiConfig();
        newAiConfig.setModel("custom-model");
        config.setAi(newAiConfig);
        assertThat(config.getAi().getModel()).isEqualTo("custom-model");

        Configuration.ContextConfig newContextConfig = new Configuration.ContextConfig();
        newContextConfig.setMaxFiles(20);
        config.setContext(newContextConfig);
        assertThat(config.getContext().getMaxFiles()).isEqualTo(20);

        Configuration.IndexingConfig newIndexingConfig = new Configuration.IndexingConfig();
        newIndexingConfig.setEnabled(false);
        config.setIndexing(newIndexingConfig);
        assertThat(config.getIndexing().isEnabled()).isFalse();

        Configuration.GitConfig newGitConfig = new Configuration.GitConfig();
        newGitConfig.setAutoCommitEnabled(true);
        config.setGit(newGitConfig);
        assertThat(config.getGit().isAutoCommitEnabled()).isTrue();

        Configuration.UiConfig newUiConfig = new Configuration.UiConfig();
        newUiConfig.setColorEnabled(false);
        config.setUi(newUiConfig);
        assertThat(config.getUi().isColorEnabled()).isFalse();

        Configuration.PerformanceConfig newPerfConfig = new Configuration.PerformanceConfig();
        newPerfConfig.setThreads(8);
        config.setPerformance(newPerfConfig);
        assertThat(config.getPerformance().getThreads()).isEqualTo(8);

        Configuration.SecurityConfig newSecurityConfig = new Configuration.SecurityConfig();
        newSecurityConfig.setReadOnlyMode(true);
        config.setSecurity(newSecurityConfig);
        assertThat(config.getSecurity().isReadOnlyMode()).isTrue();

        Configuration.LoggingConfig newLoggingConfig = new Configuration.LoggingConfig();
        newLoggingConfig.setLevel("DEBUG");
        config.setLogging(newLoggingConfig);
        assertThat(config.getLogging().getLevel()).isEqualTo("DEBUG");
    }
}