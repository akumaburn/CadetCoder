package com.eonmux.cadetcoder;

import com.eonmux.cadetcoder.commands.InputRouter;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.AiTestSupport;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import picocli.CommandLine;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for the command-line front door ({@link Main}).
 *
 * <p>Covers the defects the CLI shipped with:</p>
 * <ul>
 *   <li>subcommands registered with picocli that it cannot invoke, surfacing as a raw
 *       {@code CommandLine$ExecutionException} stack trace;</li>
 *   <li>a hardcoded command-name list that could drift from {@link CommandRegistry};</li>
 *   <li>script mode re-executing itself until a {@code StackOverflowError};</li>
 *   <li>{@code -h}/{@code -V} being rejected because redundant options shadowed
 *       {@code mixinStandardHelpOptions};</li>
 *   <li>a bare usage dump (preceded by Git errors) as the first-run experience;</li>
 *   <li>absent boolean flags silently resetting persisted configuration.</li>
 * </ul>
 */
public class MainCliTest {

    /** Marker picocli emits when a registered subcommand is not invokable. */
    private static final String PICOCLI_NOT_INVOKABLE = "is not a Method, Runnable or Callable";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private TestOutputCapture outputCapture;

    // Saved configuration state: these tests exercise the CLI's configuration overrides against the
    // shared ConfigManager singleton, so every touched value is restored afterwards.
    private boolean savedReadOnly;
    private boolean savedColorEnabled;
    private boolean savedGitEnabled;
    private boolean savedDebugEnabled;
    private int     savedVerbosity;
    private String  savedBaseDir;
    private String  savedIndexLocation;
    private String  savedLogFile;

    @Before
    public void setUp() {
        // Any command that falls through to chat must stay offline.
        AiTestSupport.installOfflineStub();
        outputCapture = new TestOutputCapture();

        Configuration config = ConfigManager.getInstance().getConfig();
        savedReadOnly      = config.getSecurity().isReadOnlyMode();
        savedColorEnabled  = config.getUi().isColorEnabled();
        savedGitEnabled    = config.getGit().isEnabled();
        savedDebugEnabled  = config.getLogging().isDebugEnabled();
        savedVerbosity     = config.getUi().getVerbosityLevel();
        savedBaseDir       = config.getBaseDir();
        savedIndexLocation = config.getIndexing().getIndexLocation();
        savedLogFile       = config.getLogging().getLogFile();
    }

    @After
    public void tearDown() {
        Configuration config = ConfigManager.getInstance().getConfig();
        config.getSecurity().setReadOnlyMode(savedReadOnly);
        config.getUi().setColorEnabled(savedColorEnabled);
        config.getGit().setEnabled(savedGitEnabled);
        config.getLogging().setDebugEnabled(savedDebugEnabled);
        config.getUi().setVerbosityLevel(savedVerbosity);
        config.setBaseDir(savedBaseDir);
        config.getIndexing().setIndexLocation(savedIndexLocation);
        config.getLogging().setLogFile(savedLogFile);

        outputCapture.restore();
        AiTestSupport.reset();
    }

    // ------------------------------------------------------------------
    // Dispatch: CommandRegistry, and only CommandRegistry
    // ------------------------------------------------------------------

    /**
     * No command is registered as a picocli subcommand.
     *
     * <p>This replaces a check that every REGISTERED subcommand was invokable by picocli. That
     * guard existed because a class registered here but implementing neither {@code Runnable} nor
     * {@code Callable} made picocli abort at startup — a real failure, and one that cannot happen
     * when nothing is registered. Registering half the commands was itself the problem: it gave the
     * tool two dispatch paths with different rules, so {@code --help} described half of it,
     * {@code help <name>} ran {@code <name>} for that half, and a command name in the middle of a
     * sentence executed.</p>
     */
    @Test
    public void noCommandIsRegisteredAsAPicocliSubcommand() {
        Class<?>[] subcommands = Main.class.getAnnotation(CommandLine.Command.class).subcommands();

        assertThat(subcommands)
                .as("commands are dispatched by CommandRegistry; a second path cannot stay in step")
                .isEmpty();
    }

    @Test
    public void commandsThatArentSubcommandsAreStillDispatchableThroughTheRegistry() {
        Class<?>[] subcommands = Main.class.getAnnotation(CommandLine.Command.class).subcommands();
        Map<String, CommandRegistry.Command> registered = new CommandRegistry().getCommands();

        // The classes deliberately dropped from the subcommand list must all still be reachable.
        for (String name : Arrays.asList("search", "quit", "shell", "config", "index", "help",
                "analyze", "explain", "suggest", "refactor", "prompt", "commit")) {
            assertThat(registered)
                    .as("'%s' is not a picocli subcommand, so CommandRegistry must be able to run it", name)
                    .containsKey(name);
        }

        // ...and none of them may be re-added to the subcommand list by accident.
        assertThat(Arrays.stream(subcommands).map(Class::getSimpleName))
                .doesNotContain("SearchCommand", "QuitCommand", "ShellCommand", "ConfigCommand",
                        "IndexCommand", "HelpCommand", "AnalyzeCommand", "ExplainCommand",
                        "SuggestCommand", "RefactorCommand", "PromptCommand",
                        "CommitCommand");
    }

    @Test
    public void previouslyCrashingCommandsRunWithoutAFrameworkStackTrace() {
        // 'config' with no arguments prints the configuration. It used to abort inside picocli
        // before its first line of code ran.
        for (String name : Arrays.asList("config", "help")) {
            outputCapture.reset();
            int exitCode = Main.execute(new String[] {name});
            String output = outputCapture.getAllOutput();

            assertThat(output)
                    .as("'%s' must not surface a picocli failure", name)
                    .doesNotContain(PICOCLI_NOT_INVOKABLE)
                    .doesNotContain("picocli.CommandLine$ExecutionException")
                    .doesNotContain("at picocli.CommandLine.executeUserObject");
            assertThat(exitCode).as("'%s' should succeed", name).isEqualTo(0);
        }
    }

    @Test
    public void helpCommandResolvesToThisProjectsHelpCommandNotPicoclis() {
        // Main used to single-type-import picocli.CommandLine.HelpCommand, which outranks the
        // com.eonmux.cadetcoder.commands.* wildcard and shadowed the project's own HelpCommand.
        int exitCode = Main.execute(new String[] {"help"});

        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getAllOutput();
        assertThat(output).contains("Commands");
        // The registry-backed help lists commands picocli's HelpCommand never knew about.
        assertThat(output).contains("login");
    }

    // ------------------------------------------------------------------
    // A5a (b): routing is driven by the registry, never by a hardcoded list
    // ------------------------------------------------------------------

    @Test
    public void everyRegisteredCommandRoutesToItselfRatherThanChat() {
        CommandRegistry registry = new CommandRegistry();

        for (String name : registry.getCommands().keySet()) {
            InputRouter.Routed routed = route(registry, name, "arg");
            assertThat(routed.isCommand())
                    .as("'%s' is a registered command and must not be rerouted to chat", name)
                    .isTrue();
            assertThat(routed.getName()).isEqualTo(name);
            assertThat(routed.getArgs()).containsExactly("arg");
        }
        // Guards the drift the hardcoded list allowed: the registry is non-trivial.
        assertThat(registry.getCommands()).hasSizeGreaterThan(30);
    }

    @Test
    public void unknownFirstWordIsRoutedToChatWithAllArgumentsPreserved() {
        CommandRegistry registry = new CommandRegistry();

        InputRouter.Routed routed = route(registry, "explain-this-repository", "please");

        assertThat(routed.isChat()).isTrue();
        assertThat(routed.getText()).isEqualTo("explain-this-repository please");
    }

    @Test
    public void commandRoutingIsCaseInsensitive() {
        CommandRegistry registry = new CommandRegistry();

        assertThat(route(registry, "HELP").getName()).isEqualTo("help");
        assertThat(route(registry, "HELP").isKnown()).isTrue();
    }

    @Test
    public void routingMissingInputAsksForNothing() {
        CommandRegistry registry = new CommandRegistry();

        assertThat(route(registry).isEmpty()).isTrue();
        assertThat(InputRouter.route((String[]) null, null, InputRouter.Mode.ARGV).isEmpty())
                .isTrue();
    }

    /** How the command line routes one argument vector. */
    private static InputRouter.Routed route(CommandRegistry registry, String... argv) {
        return InputRouter.route(argv, registry.getCommands().keySet(), InputRouter.Mode.ARGV);
    }

    // ------------------------------------------------------------------
    // A5a (c): script mode must not re-enter itself
    // ------------------------------------------------------------------

    @Test
    public void scriptModeRunsEachCommandExactlyOnceAndSkipsCommentsAndBlankLines() throws Exception {
        File script = tempFolder.newFile("commands.cadet");
        Files.writeString(script.toPath(), "# a comment\n\n   \ntheme list\n");

        int exitCode = Main.execute(new String[] {"-s", script.getAbsolutePath()});

        String output = outputCapture.getAllOutput();
        assertThat(exitCode).isEqualTo(0);
        // Before the fix the same line was re-executed hundreds of times until a StackOverflowError.
        assertThat(countOccurrences(output, "Executing command: theme list")).isEqualTo(1);
        assertThat(output).doesNotContain("StackOverflowError");
        assertThat(output).doesNotContain("Executing command: # a comment");
        assertThat(output).contains("Script execution completed.");
        // The command really ran.
        assertThat(output).contains("Available TUI Themes");
    }

    @Test
    public void scriptModeReportsTheWorstExitCodeOfItsCommands() throws Exception {
        File script = tempFolder.newFile("failing.cadet");
        // 'help <unknown>' is a deterministic, offline failure (exit 1).
        Files.writeString(script.toPath(), "theme list\nhelp no-such-command\n");

        int exitCode = Main.execute(new String[] {"-s", script.getAbsolutePath()});

        assertThat(exitCode).isEqualTo(1);
        String output = outputCapture.getAllOutput();
        assertThat(output).contains("failed with exit code 1");
    }

    @Test
    public void scriptModeRejectsAMissingScriptFile() {
        Path missing = tempFolder.getRoot().toPath().resolve("does-not-exist.cadet");

        int exitCode = Main.execute(new String[] {"-s", missing.toString()});

        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("Script file not found");
    }

    @Test
    public void scriptModeRefusesToRunReentrantly() throws Exception {
        File script = tempFolder.newFile("guarded.cadet");
        Files.writeString(script.toPath(), "theme list\n");

        AtomicBoolean guard = scriptModeGuard();
        guard.set(true); // simulate an outer script already running
        try {
            int exitCode = Main.execute(new String[] {"-s", script.getAbsolutePath()});

            assertThat(exitCode).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("A script is already running");
            assertThat(output).doesNotContain("Executing command: theme list");
        } finally {
            guard.set(false);
        }
    }

    // ------------------------------------------------------------------
    // A5a (d): short standard help/version options
    // ------------------------------------------------------------------

    @Test
    public void shortHelpOptionIsAccepted() {
        int exitCode = Main.execute(new String[] {"-h"});

        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getAllOutput();
        assertThat(output).doesNotContain("Unknown option: '-h'");
        assertThat(output).contains("Usage: cadet");
        assertThat(output).contains("AI-assisted coding tool");
    }

    @Test
    public void shortVersionOptionIsAccepted() {
        int exitCode = Main.execute(new String[] {"-V"});

        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getAllOutput();
        assertThat(output).doesNotContain("Unknown option: '-V'");
        assertThat(output).contains("CadetCoder 1.0");
    }

    // ------------------------------------------------------------------
    // A5a (e): no framework exception ever reaches the user as a stack trace
    // ------------------------------------------------------------------

    @Test
    public void executionExceptionsAreReportedAsOneLineWithANonZeroExitCode() {
        CommandLine commandLine = new CommandLine(new ThrowingCommand());
        commandLine.setExecutionExceptionHandler(new FriendlyExecutionExceptionHandler());

        int exitCode = commandLine.execute();

        assertThat(exitCode).isNotEqualTo(0);
        String output = outputCapture.getAllOutput();
        assertThat(output).contains("boom from a broken command");
        assertThat(output).doesNotContain("at picocli.CommandLine");
        assertThat(output).doesNotContain("java.lang.IllegalStateException:");
    }

    @Test
    public void executionExceptionsWithoutAMessageStillProduceAReadableLine() {
        CommandLine commandLine = new CommandLine(new ThrowingCommandWithoutMessage());
        commandLine.setExecutionExceptionHandler(new FriendlyExecutionExceptionHandler());

        int exitCode = commandLine.execute();

        assertThat(exitCode).isNotEqualTo(0);
        assertThat(outputCapture.getAllOutput()).contains("IllegalStateException");
    }

    // ------------------------------------------------------------------
    // A5f: first-run experience
    // ------------------------------------------------------------------

    @Test
    public void noArgumentsPrintsAShortOnboardingBannerInsteadOfAUsageDump() {
        int exitCode = Main.execute(new String[0]);

        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getAllOutput();

        assertThat(output).contains("AI-powered coding assistant");
        assertThat(output).contains("cadet login");
        assertThat(output).contains("cadet -i");
        assertThat(output).contains("cadet help");
        assertThat(output).contains("cadet --help");
        assertThat(output).contains("Usage: cadet");

        // A banner, not the 60-line option reference.
        assertThat(output).doesNotContain("--openrouter-endpoint");
        assertThat(output).doesNotContain("--context-tokens");
        assertThat(output.split("\\R", -1).length)
                .as("the onboarding banner must stay short")
                .isLessThan(25);
    }

    @Test
    public void aMissingGitRepositoryIsNotReportedAsAnErrorOnStartup() {
        // Git is optional (--no-git exists), and auto-commit is off by default, so a run in a
        // directory without a repository must not probe Git at all.
        assertThat(ConfigManager.getInstance().getConfig().getGit().isAutoCommitEnabled())
                .as("this test assumes the default: auto-commit disabled")
                .isFalse();

        Main.execute(new String[0]);

        String output = outputCapture.getAllOutput();
        assertThat(output).doesNotContain("Git repository not found");
        assertThat(output).doesNotContain("Git integration failed to initialize");
        assertThat(output).doesNotContain("GitIntegration is not available");
        assertThat(output).doesNotContain("AutoCommitScheduler");
    }

    // ------------------------------------------------------------------
    // A6: an absent flag must never overwrite persisted configuration
    // ------------------------------------------------------------------

    @Test
    public void persistedReadOnlyModeSurvivesARunWithoutTheFlag() {
        Configuration config = ConfigManager.getInstance().getConfig();
        config.getSecurity().setReadOnlyMode(true);

        int exitCode = Main.execute(new String[0]);

        assertThat(exitCode).isEqualTo(0);
        assertThat(config.getSecurity().isReadOnlyMode())
                .as("--read-only was absent, so the persisted value must be left alone; it used to "
                    + "be reset to false and then written back to disk by the next saveConfig()")
                .isTrue();
    }

    @Test
    public void readOnlyFlagStillEnablesReadOnlyMode() {
        Configuration config = ConfigManager.getInstance().getConfig();
        config.getSecurity().setReadOnlyMode(false);

        int exitCode = Main.execute(new String[] {"--read-only"});

        assertThat(exitCode).isEqualTo(0);
        assertThat(config.getSecurity().isReadOnlyMode()).isTrue();
    }

    @Test
    public void explicitlyNegatedReadOnlyFlagStillDisablesReadOnlyMode() {
        Configuration config = ConfigManager.getInstance().getConfig();
        config.getSecurity().setReadOnlyMode(true);

        int exitCode = Main.execute(new String[] {"--read-only=false"});

        assertThat(exitCode).isEqualTo(0);
        assertThat(config.getSecurity().isReadOnlyMode())
                .as("an explicit --read-only=false is a real user choice and must be applied")
                .isFalse();
    }

    @Test
    public void persistedUiGitAndLoggingFlagsSurviveARunWithoutTheirFlags() {
        Configuration config = ConfigManager.getInstance().getConfig();
        config.getUi().setColorEnabled(false);
        config.getGit().setEnabled(false);
        config.getLogging().setDebugEnabled(true);
        config.getUi().setVerbosityLevel(Configuration.UiConfig.VERBOSITY.VERBOSE.ordinal());

        Main.execute(new String[0]);

        assertThat(config.getUi().isColorEnabled()).as("--no-color was absent").isFalse();
        assertThat(config.getGit().isEnabled()).as("--no-git was absent").isFalse();
        assertThat(config.getLogging().isDebugEnabled()).as("--debug was absent").isTrue();
        assertThat(config.getUi().getVerbosityLevel())
                .as("--verbose was absent")
                .isEqualTo(Configuration.UiConfig.VERBOSITY.VERBOSE.ordinal());
    }

    @Test
    public void noGitFlagStillDisablesGitIntegration() {
        Configuration config = ConfigManager.getInstance().getConfig();
        config.getGit().setEnabled(true);

        Main.execute(new String[] {"--no-git"});

        assertThat(config.getGit().isEnabled()).isFalse();
    }

    @Test
    public void persistedBaseDirIndexLocationAndLogFileSurviveARunWithoutBaseDirFlag() {
        Configuration config = ConfigManager.getInstance().getConfig();
        config.setBaseDir("/custom/cadet-home");
        config.getIndexing().setIndexLocation("/custom/index");
        config.getLogging().setLogFile("/custom/logs/cadet.log");

        Main.execute(new String[0]);

        assertThat(config.getBaseDir()).isEqualTo("/custom/cadet-home");
        assertThat(config.getIndexing().getIndexLocation()).isEqualTo("/custom/index");
        assertThat(config.getLogging().getLogFile()).isEqualTo("/custom/logs/cadet.log");
    }

    /**
     * Where the tool keeps its files is settled before anything acts on a flag.
     *
     * <p><b>The defect</b>: the flags were applied first and {@code --base-dir} afterwards. Applying
     * a flag is not always just recording a setting -- {@code --debug} builds the debug logger,
     * which opens its file there and then, from wherever the base directory happened to be. Given
     * both flags, the log was opened under the default directory, the base directory moved out from
     * under it, and the line telling the user where to find the log named a directory nothing had
     * been written to.</p>
     */
    @Test
    public void thebaseDirectoryIsSettledBeforeAflagCanActOnIt() {
        String target = tempFolder.getRoot().getAbsolutePath();
        outputCapture.startCapture();
        try {
            Main.execute(new String[] {"--debug", "--base-dir", target});
        } finally {
            outputCapture.stopCapture();
            forgetDebugLogger();
        }

        assertThat(outputCapture.getAllOutput())
                .as("the debug log is announced at the base directory the run was given")
                .contains(target + "/logs/debug/");
    }

    /**
     * Lets go of the debug logger the test above turned on.
     *
     * <p>It holds a writer open on a file inside the rule's temporary folder, which is about to be
     * deleted. Every test after this one would go on writing to a file that is gone.</p>
     */
    private static void forgetDebugLogger() {
        try {
            Field instance = com.eonmux.cadetcoder.logging.DebugLogger.class
                    .getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (ReflectiveOperationException unavailable) {
            throw new AssertionError("the debug logger could not be let go of", unavailable);
        }
    }

    @Test
    public void baseDirFlagStillOverridesTheDerivedPaths() {
        Configuration config = ConfigManager.getInstance().getConfig();
        String        target = tempFolder.getRoot().getAbsolutePath();

        Main.execute(new String[] {"--base-dir", target});

        assertThat(config.getBaseDir()).isEqualTo(target);
        assertThat(config.getIndexing().getIndexLocation()).isEqualTo(target + "/index");
        assertThat(config.getLogging().getLogFile()).isEqualTo(target + "/logs/cadet.log");
    }


    /**
     * {@code --config} was declared, documented and read by nothing: the field picocli filled in
     * was never used, so a run pointed at a second configuration silently used the first.
     */
    @Test
    public void theConfigFlagPointsTheToolAtTheFileItNames() throws Exception {
        Path named = tempFolder.newFolder("named-config").toPath().resolve("config.json");
        Files.writeString(named, "{\"ai\":{\"model\":\"a-model-only-this-file-names\"}}");

        try {
            Main.execute(new String[] {"--config", named.toString()});

            assertThat(ConfigManager.getInstance().getConfig().getAi().getModel())
                    .isEqualTo("a-model-only-this-file-names");
        } finally {
            // The manager is process-wide, and this one has been pointed at a folder the rule is
            // about to delete. Left standing, every test after this one reads a file that is gone.
            forget("chosen");
            forget("instance");
        }
    }

    /** Clears one of {@link ConfigManager}'s static fields, so the next caller rebuilds it. */
    private static void forget(String field) throws Exception {
        Field held = ConfigManager.class.getDeclaredField(field);
        held.setAccessible(true);
        held.set(null, null);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    @SuppressWarnings ("unchecked")
    private static AtomicBoolean scriptModeGuard() throws ReflectiveOperationException {
        Field field = Main.class.getDeclaredField("SCRIPT_MODE_ACTIVE");
        field.setAccessible(true);
        return (AtomicBoolean) field.get(null);
    }

    @CommandLine.Command (name = "throwing")
    static class ThrowingCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            throw new IllegalStateException("boom from a broken command");
        }
    }

    @CommandLine.Command (name = "throwing-silently")
    static class ThrowingCommandWithoutMessage implements Callable<Integer> {
        @Override
        public Integer call() {
            throw new IllegalStateException();
        }
    }
}
