package com.eonmux.cadetcoder.config;

import com.eonmux.cadetcoder.ai.parsing.ParsedAction;
import com.eonmux.cadetcoder.ai.parsing.ParsingContext;
import com.eonmux.cadetcoder.ai.parsing.SecurityValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@code ~/.cadet/config.json} is a file the user edits by hand, so it is an untrusted boundary.
 *
 * <p>Jackson calls the setters with whatever the file says, and a JSON {@code null} for a section
 * used to be stored as a Java {@code null}. Nothing downstream expected that: every consumer reads
 * {@code getSecurity().isReadOnlyMode()} or {@code getGit().isEnabled()} directly, so one null
 * section turned into a bare NullPointerException on a path with no recovery. {@code baseDir} was
 * the worst of them -- {@code ConfigManager}'s constructor resolves it before anything else, so
 * {@code {"baseDir": null}} made every single invocation fail, including the {@code cadet config
 * baseDir <value>} that would have repaired it.</p>
 *
 * <p>The fix belongs in the setters rather than in the consumers: there are far more readers than
 * writers, and a null-check at each reader is a rule that has to be remembered every time somebody
 * adds one.</p>
 */
public class HostileConfigTest {

    private static Configuration parse(String json) throws Exception {
        return new ObjectMapper().readValue(json, Configuration.class);
    }

    @Test
    public void aNullSectionBecomesItsDefaultsRatherThanNull() throws Exception {
        Configuration config = parse("{\"ai\":null,\"context\":null,\"indexing\":null,\"git\":null,"
                                     + "\"ui\":null,\"performance\":null,\"security\":null,"
                                     + "\"logging\":null,\"compaction\":null}");

        assertThat(config.getAi()).isNotNull();
        assertThat(config.getContext()).isNotNull();
        assertThat(config.getIndexing()).isNotNull();
        assertThat(config.getGit()).isNotNull();
        assertThat(config.getUi()).isNotNull();
        assertThat(config.getPerformance()).isNotNull();
        assertThat(config.getSecurity()).isNotNull();
        assertThat(config.getLogging()).isNotNull();
        assertThat(config.getCompaction()).isNotNull();
    }

    @Test
    public void theDefaultsSubstitutedForANullSectionAreTheRealDefaults() throws Exception {
        Configuration config = parse("{\"security\":null,\"git\":null}");

        // Substituting a section must not quietly relax a security setting: these are the shipped
        // values, and a hostile config.json must not be able to reach a different one by writing null.
        assertThat(config.getSecurity().isReadOnlyMode())
                .isEqualTo(new Configuration.SecurityConfig().isReadOnlyMode());
        assertThat(config.getSecurity().isSandboxMode())
                .isEqualTo(new Configuration.SecurityConfig().isSandboxMode());
        assertThat(config.getGit().isEnabled())
                .isEqualTo(new Configuration.GitConfig().isEnabled());
    }

    @Test
    public void aNullBaseDirFallsBackToTheDefaultInsteadOfBrickingEveryInvocation() throws Exception {
        Configuration config = parse("{\"baseDir\":null}");

        assertThat(config.getBaseDir())
                .as("ConfigManager resolves baseDir before anything else runs, so a null here "
                    + "failed every command including the one that would fix it")
                .isEqualTo(Configuration.defaultBaseDir);
    }

    @Test
    public void aBlankBaseDirIsTreatedTheSameAsAMissingOne() throws Exception {
        assertThat(parse("{\"baseDir\":\"\"}").getBaseDir()).isEqualTo(Configuration.defaultBaseDir);
        assertThat(parse("{\"baseDir\":\"   \"}").getBaseDir()).isEqualTo(Configuration.defaultBaseDir);
    }

    @Test
    public void aWholeConfigOfNullsStillProducesAUsableConfiguration() {
        assertThatCode(() -> {
            Configuration config = parse("{\"baseDir\":null,\"security\":null,\"logging\":null}");
            config.getLogging().getLogFile();
            config.getSecurity().isReadOnlyMode();
        }).doesNotThrowAnyException();
    }

    /**
     * The other two paths {@code ConfigManager} resolves before anything can report a problem.
     *
     * <p>{@code baseDir} was guarded and these were not, although they are read exactly the same
     * way: {@code ensureDirectoriesExist} calls {@code getLogFile().startsWith(".cadet/")} while
     * creating the directory tree, and {@code ContextEngine}'s constructor hands
     * {@code getIndexLocation()} to {@code Paths.get}. Neither sits inside the {@code IOException}
     * that method catches, so {@code {"logging": {"logFile": null}}} was a NullPointerException out
     * of every single invocation -- including {@code cadet config logging.logFile <path>}, the one
     * command that could have repaired the file.</p>
     */
    @Test
    public void aNullPathSettingFallsBackToItsDefaultInsteadOfBrickingEveryInvocation() throws Exception {
        Configuration config = parse("{\"logging\":{\"logFile\":null},"
                                     + "\"indexing\":{\"indexLocation\":null}}");

        assertThat(config.getLogging().getLogFile())
                .isNotNull()
                .isEqualTo(new Configuration.LoggingConfig().getLogFile());
        assertThat(config.getIndexing().getIndexLocation())
                .isNotNull()
                .isEqualTo(new Configuration.IndexingConfig().getIndexLocation());
    }

    @Test
    public void aBlankPathSettingIsTreatedTheSameAsAMissingOne() throws Exception {
        Configuration config = parse("{\"logging\":{\"logFile\":\"   \"},"
                                     + "\"indexing\":{\"indexLocation\":\"\"}}");

        assertThat(config.getLogging().getLogFile())
                .isEqualTo(new Configuration.LoggingConfig().getLogFile());
        assertThat(config.getIndexing().getIndexLocation())
                .isEqualTo(new Configuration.IndexingConfig().getIndexLocation());
    }

    /**
     * The exact sequence a null log file used to fail at, through the code that failed.
     *
     * <p>Not an assertion about a getter: the point is that the value can be used, and the use that
     * broke was a plain {@code String} method call on the way to creating the log directory.</p>
     */
    @Test
    public void aNullLogFileDoesNotStopTheDirectoryTreeFromBeingResolved() {
        assertThatCode(() -> {
            Configuration config = parse("{\"logging\":{\"logFile\":null}}");
            String        logFile = config.getLogging().getLogFile();
            java.nio.file.Paths.get(config.getBaseDir()).resolve(logFile).getParent();
        }).doesNotThrowAnyException();
    }

    /**
     * A number the file states is checked where it is set, as a typed one is.
     *
     * <p>{@code ConfigOverrides} refuses {@code context.maxFiles 0} and explains why: it is handed
     * to Lucene as a result count, Lucene refuses zero, and every request that consults the index
     * fails from then on. That check was on the typed path alone, and this file is the one the
     * project documents as hand-edited, so writing the same zero into it reached the field with
     * nothing looking at it.</p>
     */
    @Test
    public void aNumberTheFileStatesIsHeldInsideTheRangeItCanMeanSomethingIn() throws Exception {
        Configuration config = parse("{\"context\":{\"maxFiles\":0},"
                                     + "\"ai\":{\"temperature\":9.5,\"completionTimeoutSeconds\":0,"
                                     + "\"maxTokens\":-1},"
                                     + "\"ui\":{\"verbosityLevel\":99},"
                                     + "\"performance\":{\"threads\":0},"
                                     + "\"logging\":{\"maxLogFiles\":0,\"maxLogSize\":0},"
                                     + "\"git\":{\"autoCommitIntervalMinutes\":0}}");

        assertThat(config.getContext().getMaxFiles())
                .as("Lucene refuses a result count of zero")
                .isGreaterThanOrEqualTo(1);
        assertThat(config.getAi().getTemperature()).isBetween(0.0f, 2.0f);
        assertThat(config.getAi().getCompletionTimeoutSeconds())
                .as("a deadline of zero abandons every request the moment it is made")
                .isGreaterThanOrEqualTo(1);
        assertThat(config.getAi().getMaxTokens()).isGreaterThanOrEqualTo(0);
        assertThat(config.getUi().getVerbosityLevel())
                .as("above the last level the readers that compare ordinals and the ones that "
                    + "compare for equality stop agreeing")
                .isBetween(0, Configuration.UiConfig.VERBOSITY.values().length - 1);
        assertThat(config.getPerformance().getThreads())
                .isBetween(1, com.eonmux.cadetcoder.agents.WorkerPool.MAX_CONCURRENCY);
        assertThat(config.getLogging().getMaxLogFiles()).isGreaterThanOrEqualTo(1);
        assertThat(config.getLogging().getMaxLogSize())
                .as("a rotation slot with no room in it is full when it is opened and rotates for ever")
                .isGreaterThanOrEqualTo(1);
        assertThat(config.getGit().getAutoCommitIntervalMinutes()).isGreaterThanOrEqualTo(1);
    }

    /** A value the file states that IS usable is left exactly as it was written. */
    @Test
    public void anumberInsideItsRangeIsUntouched() throws Exception {
        Configuration config = parse("{\"context\":{\"maxFiles\":25},"
                                     + "\"ai\":{\"temperature\":0.2,\"maxTokens\":0},"
                                     + "\"ui\":{\"verbosityLevel\":2},"
                                     + "\"performance\":{\"threads\":2}}");

        assertThat(config.getContext().getMaxFiles()).isEqualTo(25);
        assertThat(config.getAi().getTemperature()).isEqualTo(0.2f);
        assertThat(config.getAi().getMaxTokens())
                .as("zero is how 'ask for no ceiling at all' is written, and it is the default")
                .isZero();
        assertThat(config.getUi().getVerbosityLevel()).isEqualTo(2);
        assertThat(config.getPerformance().getThreads()).isEqualTo(2);
    }

    /**
     * The meanings a field documents for itself outlive the range check.
     *
     * <p>{@code maxLinesPerFile} says zero or less means the whole file, and
     * {@code maxFileContentSize} falls back to the shipped ten megabytes when it is not positive.
     * Those are answers rather than the absence of one, and clamping them to the smallest usable
     * number would turn "show me everything" into "show me one line" and a ten-megabyte limit into
     * a one-megabyte one.</p>
     */
    @Test
    public void avalueAFieldGivesAMeaningOfItsOwnIsKept() throws Exception {
        Configuration config = parse("{\"context\":{\"maxLinesPerFile\":0},"
                                     + "\"security\":{\"maxFileContentSize\":0}}");

        assertThat(config.getContext().getMaxLinesPerFile()).isZero();
        assertThat(config.getSecurity().getMaxFileContentBytes())
                .isEqualTo(Configuration.SecurityConfig.DEFAULT_MAX_FILE_CONTENT_MB * 1024L * 1024L);
    }

    /**
     * Writing the configuration and reading it back must produce the same configuration.
     *
     * <p>A derived getter is a serialized property unless it says otherwise, and a property with no
     * setter fails deserialization outright. {@code ConfigManager.loadConfig} catches that as an
     * {@code IOException} and quietly substitutes defaults, so the symptom is not an error -- it is
     * every setting the user ever saved reverting on the next run.</p>
     */
    @Test
    public void aSavedConfigurationSurvivesBeingReadBack() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Configuration original = new Configuration();
        original.getAi().setModel("some-model");
        original.getSecurity().setReadOnlyMode(true);
        original.getSecurity().setMaxFileContentSize(64);

        Configuration reloaded =
                mapper.readValue(mapper.writeValueAsString(original), Configuration.class);

        assertThat(reloaded.getAi().getModel()).isEqualTo("some-model");
        assertThat(reloaded.getSecurity().isReadOnlyMode()).isTrue();
        assertThat(reloaded.getSecurity().getMaxFileContentSize()).isEqualTo(64);
    }

    @Test
    public void anExplicitSectionStillWins() throws Exception {
        Configuration config = parse("{\"security\":{\"readOnlyMode\":true}}");

        assertThat(config.getSecurity().isReadOnlyMode())
                .as("null-coalescing must not swallow a section the user really did set")
                .isTrue();
    }

    /**
     * The three list-valued settings, read straight out of the file.
     *
     * <p>Nothing downstream checks these for null. {@code SecurityValidator} asks
     * {@code getAllowedCommands().length} and lowercases every entry; {@code glob}, {@code grep},
     * {@code ls} and the context indexer each wrap {@code getExcludePatterns()} in
     * {@code Arrays.asList}. One {@code null} in the file was a NullPointerException in all
     * five.</p>
     */
    @Test
    public void aNullListSettingBecomesAnEmptyListRatherThanNull() throws Exception {
        Configuration config = parse("{\"security\":{\"allowedCommands\":null},"
                                     + "\"indexing\":{\"excludePatterns\":null},"
                                     + "\"context\":{\"priorityFiles\":null}}");

        assertThat(config.getSecurity().getAllowedCommands()).isNotNull().isEmpty();
        assertThat(config.getIndexing().getExcludePatterns()).isNotNull().isEmpty();
        assertThat(config.getContext().getPriorityFiles()).isNotNull().isEmpty();
    }

    @Test
    public void aNullEntryInsideAListSettingIsDropped() throws Exception {
        Configuration config = parse("{\"security\":{\"allowedCommands\":[\"git\",null,\"ls\"]},"
                                     + "\"indexing\":{\"excludePatterns\":[null,\"target\"]},"
                                     + "\"context\":{\"priorityFiles\":[\"README.md\",null]}}");

        assertThat(config.getSecurity().getAllowedCommands()).containsExactly("git", "ls");
        assertThat(config.getIndexing().getExcludePatterns()).containsExactly("target");
        assertThat(config.getContext().getPriorityFiles()).containsExactly("README.md");
    }

    /**
     * A blank entry carries no intent, and one of them is actively destructive.
     *
     * <p>An exclude pattern is compiled as a regular expression against a directory name, and the
     * empty expression matches every name there is -- so {@code "excludePatterns": ["", "target"]}
     * excluded the whole project from indexing, globbing and grepping. {@code cadet config} already
     * dropped blanks on its own path, which meant the same setting meant one thing typed at the
     * prompt and another written to the file.</p>
     */
    @Test
    public void aBlankEntryInsideAListSettingIsDroppedAndTheRestAreTrimmed() throws Exception {
        Configuration config = parse("{\"security\":{\"allowedCommands\":[\" git \",\"\",\"  \"]},"
                                     + "\"indexing\":{\"excludePatterns\":[\"\",\"target\"]},"
                                     + "\"context\":{\"priorityFiles\":[\"  \"]}}");

        assertThat(config.getSecurity().getAllowedCommands()).containsExactly("git");
        assertThat(config.getIndexing().getExcludePatterns()).containsExactly("target");
        assertThat(config.getContext().getPriorityFiles()).isEmpty();
    }

    /**
     * The setting is the configuration's, not the caller's.
     *
     * <p>Handing out the live array put the invariant back in the hands of every reader: one of
     * them writing a null into the array it was given would break it again for everyone else
     * holding the same {@link Configuration}.</p>
     */
    @Test
    public void aListSettingCannotBeRewrittenThroughTheArrayItHandsOut() {
        Configuration.SecurityConfig security = new Configuration.SecurityConfig();
        String[] given = {"git"};
        security.setAllowedCommands(given);

        given[0] = null;
        security.getAllowedCommands()[0] = null;

        assertThat(security.getAllowedCommands()).containsExactly("git");
    }

    /**
     * The crash this actually caused, through the code that caused it.
     *
     * <p>{@code SecurityValidator} guards {@code securityConfig} for null and then dereferences
     * what it returns, so {@code {"security":{"allowedCommands":null}}} threw out of every action
     * the model proposed -- on a path with no recovery, before any of the checks that come
     * after.</p>
     */
    @Test
    public void anActionIsStillScreenedWhenTheFileSaysTheAllowlistIsNull() throws Exception {
        Configuration config = parse("{\"security\":{\"allowedCommands\":null}}");
        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(config);

        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(manager);

            SecurityValidator validator = new SecurityValidator();
            ParsedAction action = new ParsedAction.Builder("bash")
                    .addParameter("command", "ls")
                    .setReasoning("list the directory")
                    .build();

            assertThatCode(() -> validator.validateAction(
                    action,
                    new ParsingContext.Builder("do some work")
                            .workingDirectory(System.getProperty("user.dir"))
                            .build()))
                    .doesNotThrowAnyException();
        }
    }
}
