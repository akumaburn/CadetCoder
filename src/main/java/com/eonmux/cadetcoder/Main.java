package com.eonmux.cadetcoder;

/**
 * Main class serving as the application entry point for CadetCoder.
 * This class handles command line argument parsing, configuration loading,
 * and coordinates between different components of the application.
 *
 * @author [Your Name]
 * @since 1.0
 */

import com.eonmux.cadetcoder.net.HttpClientTuning;
import com.eonmux.cadetcoder.net.PinnedConnection;
import com.eonmux.cadetcoder.commands.*;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.util.UserPath;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.error.ErrorHandler;
import com.eonmux.cadetcoder.git.AutoCommitScheduler;
import com.eonmux.cadetcoder.git.GitIntegrationManager;
import com.eonmux.cadetcoder.logging.DebugLogger;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.session.SessionResumption;
import com.eonmux.cadetcoder.ui.ThemedOutputFormatter;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * picocli parses the global options and nothing else. No command is registered as a picocli
 * subcommand: every command reaches CommandRegistry through the @Parameters commandArgs catch-all,
 * and CommandRegistry discovers and runs all of them.
 *
 * picocli.CommandLine.HelpCommand must not be imported here. A single-type import outranks the
 * com.eonmux.cadetcoder.commands.* wildcard, so it would shadow this project's own HelpCommand.
 * "help" is served by CommandRegistry, and -h/--help/-V/--version come from
 * mixinStandardHelpOptions.
 */
// The name is what picocli prints as the program in "Usage: ... " and it has to be the word the
// user actually types. It said "CadetCoder", which is the artifact, so --help opened with a usage
// line for a command that does not exist and the footer told the reader to run "CadetCoder help",
// while every other message in the tool -- the no-argument banner, every command's usage -- said
// "cadet". One name, and it is the one the install instructions alias.
@Command (name = "cadet",
          mixinStandardHelpOptions = true,
          version = "CadetCoder 1.0",
          description = "AI-assisted coding tool",
          // No picocli subcommands. A second dispatch path would have its own rules, and a
          // command name in the middle of a sentence could match a subcommand and run. Every
          // command parses its own arguments.
          //
          // Dispatch is CommandRegistry, once, for all of them. '/name' is the invocation form;
          // a bare name still resolves for convenience on the command line, and anything that is
          // not a command name goes to the model.
          footer = {
                  "",
                  "Run 'cadet help' to list every command, or 'cadet help <name>' for one.",
                  "The leading '/' is optional on the command line and required in the",
                  "interactive shell ('cadet -i').",
                  "",
                  "Anything that is not a command name is sent to the AI as a request."
          })
public class Main implements Callable<Integer> {

    /** Version string shown by the onboarding banner; kept in sync with the @Command version. */
    private static final String APP_VERSION = "1.0";

    /**
     * Guards against re-entering script mode. Script execution used to re-run the whole
     * CommandLine on this very instance (with {@code scriptFile} still set), which recursed until
     * a StackOverflowError. Dispatch now goes through CommandRegistry, and this flag makes a
     * nested invocation fail loudly instead of recursing.
     */
    private static final AtomicBoolean SCRIPT_MODE_ACTIVE = new AtomicBoolean(false);

    /**
     * Scheduler for automatic commit operations when Git integration is enabled.
     */
    private AutoCommitScheduler autoCommitScheduler;

    /**
     * Injected by picocli so {@link #overrideConfiguration()} can tell "flag absent" from
     * "flag explicitly false". Without it every run would overwrite persisted boolean settings
     * with the default {@code false} of the corresponding field.
     */
    @Spec
    private CommandLine.Model.CommandSpec spec;

    // Configuration Overrides
    @Option (names = {"--config"},
             description = "Configuration file to read and write (default ~/.cadet/config.json)")
    private String configPath;

    @Option (names = {"--ai-endpoint"}, description = "AI API endpoint URL")
    private String aiEndpoint;

    @Option (names = {"--ai-model", "--model"}, description = "Model name to use")
    private String aiModel;

    @Option (names = {"--ai-temp"}, description = "Temperature for sampling")
    private Float aiTemperature;

    @Option (names = {"--context-tokens"},
             description = "Input context window in tokens (default: the model's published limit)")
    private Integer contextTokens;

    @Option (names = {"--no-color"}, description = "Disable colored output")
    private boolean noColor;

    @Option (names = {"--verbose"}, description = "Enable verbose output")
    private boolean verbose;

    @Option (names = {"--debug"}, description = "Enable debug logging to file")
    private boolean debug;

    @Option (names = {"--read-only"}, description = "Enable read-only mode")
    private boolean readOnly;

    @Option (names = {"--no-git"}, description = "Disable Git integration")
    private boolean noGit;

    @Option (names = {"--base-dir"},
             description = "Base directory for storing global files")
    private String baseDir;

    // NOTE: --help/-h and --version/-V are provided by mixinStandardHelpOptions. Declaring them
    // again here as @Option fields shadowed the mixin and broke the short forms ("Unknown option:
    // '-h'"), so they are intentionally absent.

    @Option (names = {"-i", "--interactive"}, description = "Start in interactive shell mode")
    private boolean interactive;

    @Option (names = {"-s", "--script"}, description = "Run commands from script file")
    private String scriptFile;

    @Option (names = {"-c", "--continue"}, description = "Continue the most recent session")
    private boolean continueSession;

    @Option (names = {"-r", "--resume"},
             description = "Resume a specific session by ID (see '/session list')")
    private String resumeSessionId;


    // Chat template options
    @Option (names = {"--chat-template"},
             description = "Chat template to use (e.g., plain, chatml, alpaca, llama2, llama3, openchat, deepseek)")
    private String chatTemplate;

    // Connector/provider selection (opencode-style connectors + models.dev catalog)
    @Option (names = {"--provider"},
             description = "AI provider/connector id (e.g. anthropic, openai, openrouter, google, xai, "
                           + "amazon-bedrock, azure, github-copilot, groq, deepseek)")
    private String provider;

    @Option (names = {"--provider-key"},
             description = "API key for the selected --provider. Visible to anyone who can list "
                           + "processes and kept in shell history; prefer the provider's environment "
                           + "variable, or 'login', which reads the key without echoing it")
    private String providerKey;

    @Parameters (description = "Command and arguments to execute")
    private String[] commandArgs;

    /**
     * Main entry point for the CadetCoder application.
     * This method parses command line arguments and executes the application.
     *
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        int exitCode = execute(args);
        System.exit(exitCode);
    }

    /**
     * Builds the top-level parser.
     *
     * <p>Shared with the tests so the parsing rules they assert are the ones that actually run;
     * a copy of this configuration in a test would keep passing after someone changed the real
     * one, which is precisely how the behaviour below went unnoticed.</p>
     *
     * @return the configured parser
     */
    static CommandLine newCommandLine() {
        CommandLine cmd = new CommandLine(new Main());

        // Never let a framework exception reach the user as a raw stack trace.
        cmd.setExecutionExceptionHandler(new FriendlyExecutionExceptionHandler());

        // An option this top-level parser does not recognise belongs to the command being invoked,
        // not to CadetCoder, so collect it as a positional instead of aborting.
        //
        // picocli parses the top level BEFORE the command name is routed, so without this
        // `cadet /read lines.txt --limit=3` fails with "Unknown option: '--limit=3'" and prints the
        // whole global usage block. The same applies to a natural-language request that contains a
        // dashed token. Options CadetCoder does define are still matched, and still win.
        cmd.setUnmatchedOptionsArePositionalParams(true);

        // Once free text begins, it is ALL free text: a later token that looks like an option is
        // part of the request, and is never parsed as a global option. A command name is only
        // recognised first, before any other positional, so `cadet please ls` reaches the model
        // while `cadet ls src` and `cadet --no-color ls src` run `ls`.
        cmd.setStopAtPositional(true);

        return cmd;
    }

    /**
     * Execute the application without calling System.exit.
     * This method is useful for testing.
     *
     * @param args Command line arguments
     * @return Exit code (0 for success, non-zero for errors)
     */
    public static int execute(String[] args) {
        // Before anything builds an HttpClient: the JDK freezes these into static fields when its
        // connection pool first initialises, so a later call would be silently ignored.
        HttpClientTuning.apply();
        // Likewise before jgit or anything else loads the JDK's HTTP protocol handler, which reads
        // this once and never again.
        PinnedConnection.allowHostHeader();

        CommandLine cmd = newCommandLine();


        // Apply the global flags BEFORE the resolved command runs. The execution strategy runs
        // after parsing, so the fields are populated, and before dispatch, so the settings are in
        // force for whichever command runs.
        cmd.setExecutionStrategy(parseResult -> {
            Main parsed = cmd.getCommand();
            if (parsed != null) {
                parsed.overrideConfiguration();
            }
            return new CommandLine.RunLast().execute(parseResult);
        });

        // Add Shutdown Hook BEFORE execution to ensure it's always registered
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Throughout: Throwable, not Exception. A shutdown hook runs when class loading may no
            // longer succeed, so a LinkageError here is ordinary rather than exceptional -- and an
            // Error is not an Exception, so narrower guards let it escape and the process ends with
            // a stack trace over whatever the user was looking at.
            shutdownStep("stop the auto-commit scheduler", () -> {
                Main mainApp = cmd.getCommand();
                if (mainApp != null && mainApp.autoCommitScheduler != null) {
                    mainApp.autoCommitScheduler.stop();
                    com.eonmux.cadetcoder.logging.DebugLogger.getInstance()
                            .debug("Main", "AutoCommitScheduler stopped");
                }
            });
            shutdownStep("report session metrics", () -> {
                // One line of "what did this session cost", printed only when at least one request
                // actually completed so a run that never called a model stays silent.
                String metricsSummary = com.eonmux.cadetcoder.ai.metrics.RequestMetricsRecorder
                        .getInstance().sessionSummary();
                if (metricsSummary != null) {
                    OutputFormatter.printInfo(metricsSummary);
                }
            });
            shutdownStep("save the session", () -> {
                SessionManager.getInstance().saveSession();
                com.eonmux.cadetcoder.logging.DebugLogger.getInstance()
                        .debug("Main", "Session saved on shutdown");
            });
        }));

        // An explicit "/name ..." invocation bypasses picocli's argument parsing entirely and is
        // handed straight to the registry via call()'s quick-command path.
        //
        // Safe because the form is unambiguous: the leading slash says "this is a command", and no
        // global CadetCoder flag can precede it. It also means a command's own options are parsed by
        // that command alone, so `cadet /grep --limit 3 X` reaches grep with its flag intact rather
        // than failing on an option the top-level parser has never heard of.
        if (InputRouter.route(args, null, InputRouter.Mode.ARGV).isExplicit()) {
            Main app = cmd.getCommand();
            app.commandArgs = args;
            try {
                return app.call();
            } catch (Exception e) {
                ErrorHandler.getInstance().handleException(e);
                return 1;
            }
        }

        return cmd.execute(args);
    }

    /**
     * Main execution method for the application when running as a Callable.
     * This method handles configuration loading, command execution, and error handling.
     *
     * @return Exit code (0 for success, non-zero for errors)
     * @throws Exception If an error occurs during execution
     */
    @Override
    public Integer call() throws Exception {
        try {
            // Load Configuration
            Configuration config = ConfigManager.getInstance().getConfig();

            // Override Configuration with CLI Flags
            overrideConfiguration();

            // Initialize AutoCommitScheduler with GitIntegration.
            // Git is OPTIONAL (there is a --no-git flag), so running outside a repository is a
            // normal state, not an error. Touch Git only when a Git feature is actually requested:
            // AutoCommitScheduler.start() is a no-op unless auto-commit is enabled, so probing for
            // a repository on every run only produced noise.
            if (isGitEnabled(config) && config.getGit().isAutoCommitEnabled()) {
                GitIntegrationManager gitManager = GitIntegrationManager.getInstance();
                if (gitManager.isAvailable()) {
                    autoCommitScheduler = new AutoCommitScheduler(gitManager.getGitIntegration());
                    autoCommitScheduler.start();
                } else {
                    OutputFormatter.printInfo(
                            "Auto-commit is enabled but this directory is not a Git repository; skipping it.");
                }
            }

            // Initialize theme
            ThemedOutputFormatter.initializeTheme();

            // The UI flags (--no-color, --verbose, --debug, --read-only) are applied by
            // applyUiFlags, from overrideConfiguration, before any command runs.

            int resumption = SessionResumption.open(continueSession, resumeSessionId,
                                                    SessionManager.getInstance());
            if (resumption != SessionResumption.CARRY_ON) {
                return resumption;
            }

            if (interactive) {
                startInteractiveMode();
            } else if (scriptFile != null) {
                return handleScriptMode();
            } else if (commandArgs != null && commandArgs.length > 0) {
                // One command line, routed by the same rules the shell prompt uses.
                CommandRegistry    registry = new CommandRegistry();
                InputRouter.Routed routed   = InputRouter.route(commandArgs,
                        registry.getCommands().keySet(), InputRouter.Mode.ARGV);
                if (routed.isEmpty()) {
                    printOnboardingBanner(config);
                    return 0;
                }
                // An explicitly slash-prefixed command must fail loudly if it is not registered
                // rather than being forwarded to the model as a chat message.
                int exitCode = routed.isCommand()
                        ? registry.executeCommand(routed.getName(), routed.getArgs(), false)
                        : registry.executeCommand("chat", new String[] {routed.getText()}, true);
                awaitWorkersBeforeExit();
                return exitCode;
            } else {
                // No arguments: greet the user instead of dumping the full option reference.
                printOnboardingBanner(config);
            }

            return 0;
        } catch (Exception e) {
            ErrorHandler.getInstance().handleException(e);
            return 1;
        }
    }

    /**
     * Lets a background worker run finish before the process ends.
     *
     * <p>Workers run on daemon threads inside this process, which is right for a session that stays
     * open — a shell, or an agentic run spanning many turns — and wrong for a one-shot invocation,
     * where {@code workers start} would otherwise launch several agents and kill them microseconds
     * later without reporting anything. Waiting here means the background form does something
     * sensible however it was reached, rather than being a trap in one of the ways it can be used.</p>
     */
    private static void awaitWorkersBeforeExit() {
        com.eonmux.cadetcoder.agents.WorkerRegistry.active().ifPresent(run -> {
            OutputFormatter.printInfo("Waiting for " + run.stillRunning().size()
                                      + " worker(s) to finish before exiting...");
            run.await(0);
        });
    }


    /**
     * Whether Git integration should be considered active for this run.
     *
     * @param config the effective configuration
     * @return {@code true} when neither {@code --no-git} nor the persisted configuration disabled Git
     */
    private boolean isGitEnabled(Configuration config) {
        return !noGit && config.getGit().isEnabled();
    }

    /**
     * Prints the short onboarding banner shown when CadetCoder is started with no arguments.
     * Mirrors the interactive shell's welcome screen so both entry points describe the tool the
     * same way, and points at {@code --help} rather than reproducing the whole option reference.
     *
     * @param config the effective configuration, used to show where settings live
     */
    private void printOnboardingBanner(Configuration config) {
        com.eonmux.cadetcoder.ui.Glyphs glyphs = com.eonmux.cadetcoder.ui.Glyphs.system();
        OutputFormatter.println(glyphs.headerMarker() + " CadetCoder " + APP_VERSION + " "
                                + glyphs.dash() + " AI-powered coding assistant" + glyphs.headerCloser());
        OutputFormatter.println("");
        OutputFormatter.println("Usage: cadet [OPTIONS] <command> [ARGS]");
        OutputFormatter.println("       cadet \"<request in plain English>\"   (anything that is not a command"
                                + " is sent to 'chat')");
        OutputFormatter.println("");
        OutputFormatter.println("Getting started:");
        OutputFormatter.println("  cadet login                connect an AI provider, then 'cadet models select'");
        OutputFormatter.println("  cadet -i                   open the interactive shell");
        OutputFormatter.println("  cadet chat \"...\"           ask for an explanation or a change");
        OutputFormatter.println("  cadet help                 list every command");
        OutputFormatter.println("");
        OutputFormatter.println("Configure: 'cadet config' lists every setting, "
                                + "'cadet config <name> <value>' changes one");
        OutputFormatter.println("           (stored in " + config.getBaseDir() + "/config.json)");
        OutputFormatter.println("Full option reference: cadet --help");
    }

    /**
     * Whether the given option was explicitly present on the command line.
     * <p>
     * Boolean {@code @Option} fields default to {@code false}, so the field alone cannot
     * distinguish "flag absent" from "flag explicitly false". Consulting the parse result keeps an
     * absent flag from overwriting (and, once any command calls {@code saveConfig()}, permanently
     * erasing) a persisted setting such as {@code security.readOnlyMode}.
     *
     * @param names the option names to look for, e.g. {@code "--read-only"}
     * @return {@code true} when at least one of the names was matched during parsing
     */
    private boolean isOptionMatched(String... names) {
        if (spec == null) {
            return false;
        }
        CommandLine commandLine = spec.commandLine();
        if (commandLine == null) {
            return false;
        }
        ParseResult parseResult = commandLine.getParseResult();
        if (parseResult == null) {
            return false;
        }
        for (String name : names) {
            if (parseResult.hasMatchedOption(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a boolean flag should be applied to the persisted configuration: either it was
     * matched on the command line (including an explicit {@code --flag=false}), or the field was
     * set programmatically to {@code true} by a caller that did not go through picocli parsing.
     *
     * @param currentValue the current value of the flag field
     * @param names        the option names of the flag
     * @return {@code true} when the flag's value should be written to the configuration
     */
    private boolean shouldApplyFlag(boolean currentValue, String... names) {
        return currentValue || isOptionMatched(names);
    }

    /**
     * Whether {@link #overrideConfiguration()} has already run for this invocation.
     *
     * <p>It is now applied by the execution strategy, before any command runs, and {@link #call()}
     * still calls it for the paths that do not go through that strategy (the explicit {@code /name}
     * form). Applying it twice would repeat the notices it prints ("Colored output disabled.",
     * "Using chat template: ..."), so it is idempotent.</p>
     */
    private boolean configurationOverridden = false;

    /** Applies the command-line flags to the configuration, once per invocation. */
    void overrideConfiguration() {
        if (configurationOverridden) {
            return;
        }
        configurationOverridden = true;
        applyConfigurationOverrides();
    }

    /**
     * Applies the flags that change how the tool talks to the user, and says so.
     *
     * <p>These are side effects beyond writing the configuration: they clear the colour cache and
     * switch the debug logger on. They run from {@link #overrideConfiguration}, before dispatch,
     * so {@code cadet --no-color ls} and {@code cadet --no-color /ls} behave the same.</p>
     *
     * @param config the effective configuration
     */
    private void applyUiFlags(Configuration config) {
        if (noColor) {
            OutputFormatter.disableColor();
            OutputFormatter.printSuccess("Colored output disabled.");
        }
        if (verbose) {
            OutputFormatter.enableVerbose();
            OutputFormatter.printSuccess("Verbose mode enabled.");
        }
        if (debug) {
            DebugLogger.getInstance().setDebugEnabled(true);
            OutputFormatter.printSuccess("Debug logging enabled. Log files in: "
                                         + config.getBaseDir() + "/logs/debug/");
        }
        if (readOnly) {
            config.getSecurity().setReadOnlyMode(true);
            OutputFormatter.printSuccess("Read-only mode enabled.");
        }
    }

    /**
     * Writes the command-line flags into the effective configuration.
     *
     * <p>Every setter is applied ONLY when the corresponding option was actually supplied: an absent
     * flag must not overwrite -- and, once any command calls {@code saveConfig()}, permanently erase
     * -- a persisted setting such as {@code security.readOnlyMode}.</p>
     */
    private void applyConfigurationOverrides() {
        // First, because everything below is applied to whatever document this reads. A setting
        // overridden onto the default configuration and then replaced by the named one would have
        // been accepted and silently lost.
        if (configPath != null && !configPath.isEmpty()) {
            ConfigManager.useConfigFile(expandTildePath(configPath));
        }
        // The flags belong to this run. Written into the configuration like any other change, they
        // were saved by the first command that saved anything -- see ConfigManager.applyForThisRunOnly.
        ConfigManager manager = ConfigManager.getInstance();
        manager.applyForThisRunOnly(() -> applyFlags(manager.getConfig()));
    }

    /**
     * Writes each flag that was given into the configuration.
     *
     * @param config the effective configuration
     */
    private void applyFlags(Configuration config) {
        // Second, and before anything acts on a flag. Every path this tool writes -- the index, the
        // session log, the debug log -- hangs off the base directory, and the things below do not
        // merely record settings: --debug builds the debug logger, which opens its file straight
        // away, from wherever the base directory was at that moment. Set afterwards, --base-dir
        // moved the base directory out from under a log that had already been opened somewhere
        // else, and the line announcing where to find it named a directory nothing was written to.
        //
        // Applied only when --base-dir was actually given: deriving these three paths from
        // Configuration.defaultBaseDir on every run overwrote whatever the user had configured.
        if (baseDir != null && !baseDir.isEmpty()) {
            String resolvedBaseDir = expandTildePath(baseDir);
            config.getIndexing().setIndexLocation(resolvedBaseDir + "/index");
            config.getLogging().setLogFile(resolvedBaseDir + "/logs/cadet.log");
            config.setBaseDir(resolvedBaseDir);
        }

        if (aiEndpoint != null) {
            if (!aiEndpoint.startsWith("http://") && !aiEndpoint.startsWith("https://")) {
                aiEndpoint = "http://" + aiEndpoint;
            }
            config.getAi().setApiEndpoint(aiEndpoint);
        }
        if (aiModel != null) {
            config.getAi().setModel(aiModel);
        }
        if (aiTemperature != null) {
            config.getAi().setTemperature(aiTemperature);
        }
        if (contextTokens != null) {
            config.getAi().setContextTokens(contextTokens);
        }

        if (shouldApplyFlag(noColor, "--no-color")) {
            config.getUi().setColorEnabled(!noColor);
        }
        if (shouldApplyFlag(verbose, "--verbose")) {
            config.getUi().setVerbosityLevel(verbose ? Configuration.UiConfig.VERBOSITY.VERBOSE.ordinal() :
                                             Configuration.UiConfig.VERBOSITY.NORMAL.ordinal());
        }
        if (shouldApplyFlag(debug, "--debug")) {
            config.getLogging().setDebugEnabled(debug);
        }
        if (shouldApplyFlag(readOnly, "--read-only")) {
            config.getSecurity().setReadOnlyMode(readOnly);
        }
        applyUiFlags(config);
        if (shouldApplyFlag(noGit, "--no-git")) {
            config.getGit().setEnabled(!noGit);
        }

        // Handle chat template configuration
        if (chatTemplate != null && !chatTemplate.isEmpty()) {
            config.getAi().setChatTemplate(chatTemplate);
            OutputFormatter.printInfo("Using chat template: " + chatTemplate);
        }

        // Handle connector/provider selection
        if (provider != null && !provider.isEmpty()) {
            config.getAi().setProvider(provider);
            OutputFormatter.printInfo("Using AI provider: " + provider);
        }
        if (providerKey != null && !providerKey.isEmpty()) {
            // Said once, at the point it happens. A key on the command line is in the process list
            // for as long as the run lasts and in the shell's history afterwards, and the tool
            // documents everywhere else that it never reads a credential this way. Kept because CI
            // has no terminal to type one at, but the person who typed it deserves to be told.
            OutputFormatter.printWarning(
                    "--provider-key puts the key in this machine's process list and your shell "
                    + "history. Prefer the provider's environment variable, or 'login'.");
            String targetProvider = (provider != null && !provider.isEmpty())
                                    ? provider : config.getAi().getProvider();
            if (targetProvider != null && !targetProvider.isEmpty()) {
                config.getAi().getProviderApiKeys().put(targetProvider, providerKey);
            }
        }
    }

    /**
     * Runs one shutdown step, absorbing anything it throws.
     *
     * <p>A failure to save a session or print a summary is not a reason to end the process with a
     * stack trace. The cause is written to stderr as a single sentence, and to the debug log in
     * full when the logger itself is still usable.</p>
     *
     * @param what a short description of the step
     * @param step the step to run
     */
    private static void shutdownStep(String what, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            System.err.println("Could not " + what + " on exit: " + t);
        }
    }

    /**
     * Starts the interactive shell mode where users can enter commands directly.
     * This method prints the command prompt and processes user input until exit is requested.
     */
    private void startInteractiveMode() {
        // Use the enhanced TamboUI-based interactive shell
        InteractiveShell shell = null;
        try {
            shell = new InteractiveShell();
            shell.runShell();
        } catch (Throwable e) {
            // Throwable: the shell runs a terminal backend and a render loop, and a failure there --
            // including a LinkageError -- should end as a reported failure with the console restored,
            // not as a stack trace over a terminal still in raw mode.
            OutputFormatter.printError("Failed to start interactive shell: " + e);

            // Ensure any partial TUI initialization is cleaned up
            try {
                if (shell != null) {
                    shell.requestQuit();
                }
            } catch (Exception cleanupEx) {
                // Ignore cleanup exceptions, but ensure console is restored
                System.err.println("Warning: Console state may be corrupted. Try running 'reset' command.");
            }

            // Fallback to basic shell
            new ShellCommand().execute(new String[] {});
        }

        SessionManager.getInstance().saveSession(); // Save session before exiting
    }

    /**
     * Executes commands from a script file.
     * <p>
     * Each non-empty, non-comment line is dispatched through {@link CommandRegistry}, exactly the
     * way {@code InteractiveShell} runs a typed line. It must NOT be re-run through picocli on this
     * same instance: {@code scriptFile} would still be set, {@link #call()} would re-enter script
     * mode and recurse until a StackOverflowError.
     *
     * @return Exit code: 0 when every command succeeded, otherwise the worst (numerically largest)
     *         non-zero exit code observed, or 1 when the script itself could not be run
     */
    private int handleScriptMode() {
        // Clear the option before running anything so that even a command that somehow re-enters
        // Main cannot restart the script.
        String path = scriptFile;
        scriptFile = null;

        if (path == null || path.isEmpty()) {
            OutputFormatter.printError("Please provide a script file to execute.");
            return 1;
        }

        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            OutputFormatter.printError("Script file not found: " + path);
            return 1;
        }

        if (!SCRIPT_MODE_ACTIVE.compareAndSet(false, true)) {
            OutputFormatter.printError("A script is already running; refusing to execute '" + path
                                       + "' recursively.");
            return 1;
        }

        try {
            List<String>    lines     = Files.readAllLines(file.toPath());
            CommandRegistry registry  = new CommandRegistry();
            int             worstCode = 0;
            for (String line : lines) {
                String trimmedLine = line.trim();
                if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) { // Ignore empty lines and comments
                    continue;
                }
                // Quote-aware, and a leading '/' is accepted so script lines can be written exactly as
                // they are typed at the interactive prompt. A bare split("\\s+") shredded quoted
                // arguments, e.g. `grep "class Foo"` became two arguments.
                String[] args = com.eonmux.cadetcoder.commands.CommandLineTokenizer.tokenize(trimmedLine);
                if (args.length == 0) {
                    continue;
                }
                String commandName = args[0].startsWith("/") && args[0].length() > 1
                                     ? args[0].substring(1)
                                     : args[0];
                OutputFormatter.printInfo("Executing command: " + trimmedLine);
                int exitCode = registry.executeCommand(commandName, Arrays.copyOfRange(args, 1, args.length));
                if (exitCode != 0) {
                    OutputFormatter.printWarning("Command '" + commandName + "' failed with exit code " + exitCode);
                    worstCode = Math.max(worstCode, exitCode);
                }
            }
            OutputFormatter.printSuccess("Script execution completed.");
            return worstCode;
        } catch (IOException e) {
            OutputFormatter.printError("Failed to execute script: " + e.getMessage());
            return 1;
        } finally {
            SCRIPT_MODE_ACTIVE.set(false);
            SessionManager.getInstance().saveSession(); // Save session after script execution
        }
    }

    /**
     * Expands a leading tilde to the user's home directory.
     *
     * <p>Kept as a name of its own because the flags read it before a {@link Configuration} exists
     * to normalise it; {@link UserPath} is the single implementation, so a path typed at the
     * command line and one typed at {@code config} cannot disagree about what {@code ~} means.</p>
     *
     * @param path the path that may begin with a tilde
     * @return the path the filesystem should be asked about
     */
    private static String expandTildePath(String path) {
        return UserPath.expanded(path);
    }

}
