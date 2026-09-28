package com.eonmux.cadetcoder.config;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.security.OwnerOnlyFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

public class ConfigManager {
    private static final String        CONFIG_FILE  = "config.json";
    private static       ConfigManager instance;
    /** Where the configuration is when the process was told, rather than the default location. */
    private static       Path          chosen;
    private static       boolean       initializing = false;
    /** The manager being built right now, so anything it prints is answered with it. */
    private static       ConfigManager settling;
    /**
     * Problems found while the singleton was being constructed, reported once it is built.
     *
     * <p>{@code initializing} exists to keep startup quiet -- "Configuration loaded from ..." on
     * every invocation is noise. It was also suppressing the failures, and the two most important
     * ones are silent by exactly the same flag: a {@code config.json} that will not parse reverts
     * the user's entire configuration to defaults, and a file holding {@code ai.apiKey} that could
     * not be made owner-only stays readable by every local account. Neither is noise, and both
     * happen only during construction, which is the one window where nothing was printed.</p>
     *
     * <p>How loudly each is told is carried with it. Held back and then all printed as errors, a
     * file that loaded with one key too many read as a file that had not loaded at all.</p>
     */
    private static final java.util.List<Deferred> deferredProblems =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Something to tell the user, and whether it stopped anything working. */
    private record Deferred(String message, boolean fatal) {
        void print() {
            if (fatal) {
                OutputFormatter.printError(message);
            } else {
                OutputFormatter.printWarning(message);
            }
        }
    }

    /** Reports a failure now, or once construction has finished. */
    private static void reportProblem(String message) {
        report(new Deferred(message, true));
    }

    /** Reports something worth knowing but not fatal, now or once construction has finished. */
    private static void reportWarning(String message) {
        report(new Deferred(message, false));
    }

    private static void report(Deferred problem) {
        if (initializing) {
            deferredProblems.add(problem);
        } else {
            problem.print();
        }
    }

    private       Path          configPath;
    private       Configuration config;

    /** What the command line changed, which {@link #saveConfig()} leaves out of the file. */
    private volatile RunOnlySettings runOnly = RunOnlySettings.NONE;

    /**
     * Builds a manager that knows where its file is and holds the defaults until it is read.
     *
     * <p>Nothing is read, created or printed here. Reading the file can fail, and reporting that
     * failure eventually reaches the logger, which asks for the configuration -- so any I/O done
     * inside the constructor is done while {@code getInstance()} has nothing to hand that caller
     * back. Holding the defaults from the first instruction means a re-entrant reader gets the
     * settings that really are in force at that moment rather than a null.</p>
     */
    private ConfigManager() {
        configPath = chosen != null ? chosen
                                   : Paths.get(Configuration.defaultBaseDir).resolve(CONFIG_FILE);
        config     = new Configuration();
    }

    /**
     * Reads settings from a named file from now on, and writes every change back to that file.
     *
     * <h2>Why both halves have to move together</h2>
     *
     * <p>Reading from the named file while saving to the default one is worse than not honouring
     * the option at all: {@code config} would report the change as saved, and it would land in a
     * file the run is not reading. So this re-points the manager rather than loading a document
     * into it.</p>
     *
     * <h2>Why it is applied before anything else</h2>
     *
     * <p>Whatever was read from the default file is dropped, because it is not the configuration the
     * user asked for. The command line applies this before any other flag for the same reason: a
     * setting overridden onto the old document and then replaced would be accepted and lost.</p>
     *
     * @param file where the configuration is; created with defaults when it is not there yet
     */
    public static synchronized void useConfigFile(String file) {
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("a configuration file has to be named");
        }
        chosen = Paths.get(file);
        // Nothing has read a configuration yet, so the manager is built on the named file rather
        // than built on the default one and moved. Moving it would create the default file on a
        // machine that has never had one, which is the opposite of what naming a second
        // configuration is asking for.
        if (instance == null) {
            getInstance();
        } else {
            instance.readFrom(chosen);
        }
    }

    /**
     * The file {@code --config} named, or {@code null} when the run uses the default one.
     *
     * <p>Static, so the security checks can ask without building the manager.</p>
     *
     * @return the named configuration file
     */
    public static synchronized Path namedConfigFile() {
        return chosen;
    }

    /** Points this manager at one file and reads it. */
    private synchronized void readFrom(Path file) {
        this.configPath = file;
        Path holding = file.getParent();
        if (holding != null && !Files.exists(holding)) {
            try {
                Files.createDirectories(holding);
            } catch (IOException e) {
                OutputFormatter.printError("Failed to create config directory: " + e.getMessage());
            }
        }
        loadDefaultConfig();
        ensureDirectoriesExist();
    }

    public void loadDefaultConfig() {
        // A fresh document carries none of the flags, so nothing recorded against the old one
        // applies to it.
        runOnly = RunOnlySettings.NONE;
        if (Files.exists(configPath)) {
            loadConfig();
            if (!initializing) {
                OutputFormatter.printSuccess("Loaded default configuration from " + configPath);
            }
        } else {
            config = new Configuration();
            // Announced only when the file is actually there. saveConfig() reports its own I/O
            // failure; printing "Created new default configuration at ..." on top of that named a
            // file that does not exist, and the next run repeats the whole thing.
            boolean created = saveConfig();
            if (!initializing && created) {
                OutputFormatter.printSuccess("Created new default configuration at " + configPath);
            }
        }
    }

    private void ensureDirectoriesExist() {
        try {
            // Create base directory
            Path    baseDir = Paths.get(config.getBaseDir());
            boolean created = false;
            if (!Files.exists(baseDir)) {
                Files.createDirectories(baseDir);
                created = true;
                if (!initializing) {
                    OutputFormatter.printSuccess("Created base directory: " + baseDir);
                }
            }
            restrictIfOwned(baseDir, created);

            // Create logs directory
            String logFile = config.getLogging().getLogFile();
            Path   logPath;
            if (logFile.startsWith(".cadet/")) {
                // Relative to home directory
                logPath = Paths.get(System.getProperty("user.home"), logFile);
            } else if (!Paths.get(logFile).isAbsolute()) {
                // Relative to base directory
                logPath = baseDir.resolve(logFile);
            } else {
                // Absolute path
                logPath = Paths.get(logFile);
            }

            Path logDir = logPath.getParent();
            if (logDir != null && !Files.exists(logDir)) {
                Files.createDirectories(logDir);
                if (!initializing) {
                    OutputFormatter.printSuccess("Created log directory: " + logDir);
                }
            }

            // Create sessions directory
            Path sessionsDir = baseDir.resolve("sessions");
            if (!Files.exists(sessionsDir)) {
                Files.createDirectories(sessionsDir);
                if (!initializing) {
                    OutputFormatter.printSuccess("Created sessions directory: " + sessionsDir);
                }
            }

            // Create indexes directory
            Path indexesDir = baseDir.resolve("indexes");
            if (!Files.exists(indexesDir)) {
                Files.createDirectories(indexesDir);
                if (!initializing) {
                    OutputFormatter.printSuccess("Created indexes directory: " + indexesDir);
                }
            }

            // Create models directory for local AI models
            Path modelsDir = baseDir.resolve("models");
            if (!Files.exists(modelsDir)) {
                Files.createDirectories(modelsDir);
                if (!initializing) {
                    OutputFormatter.printSuccess("Created models directory: " + modelsDir);
                }
            }

        } catch (IOException e) {
            if (!initializing) {
                OutputFormatter.printError("Failed to create directories: " + e.getMessage());
            }
        }
    }

    /**
     * Makes the base directory owner-only when it is one this tool owns.
     *
     * <p>It holds sessions, logs and an index of the user's code, which carry file contents and
     * command output. The default directory is repaired on every start, the same way the
     * configuration file is, so an install made before this rule is covered too. A directory the
     * user named is changed only if this run created it, because it may be shared with other
     * things.</p>
     *
     * @param baseDir the base directory in use
     * @param created whether this run created it
     */
    private void restrictIfOwned(Path baseDir, boolean created) {
        Path defaultDir = Paths.get(Configuration.defaultBaseDir).toAbsolutePath().normalize();
        if (!created && !baseDir.toAbsolutePath().normalize().equals(defaultDir)) {
            return;
        }
        String problem = com.eonmux.cadetcoder.security.OwnerOnlyFile.restrictDirectory(baseDir);
        if (problem != null && !initializing) {
            OutputFormatter.printWarning("Could not make " + baseDir + " private to you: " + problem);
        }
    }

    public void loadConfig() {
        // Repair an existing config written before permissions were enforced (or loosened since):
        // the secrets in it are only protected if the file on disk is owner-only.
        restrictToOwnerOnly(configPath);
        try {
            ConfigFile.Loaded loaded = ConfigFile.read(configPath);
            config = loaded.config();
            reportUnrecognized(configPath, loaded.unrecognized());
            if (!initializing) {
                OutputFormatter.printSuccess("Configuration loaded from " + configPath);
            }
        } catch (IOException e) {
            // Reported whatever the phase: every setting the user has ever saved has just been
            // replaced by a default, and staying quiet about it means they find out by watching
            // the tool behave as though they had configured nothing.
            reportProblem("Failed to load configuration from " + configPath
                          + " - continuing with defaults, which means none of your saved settings "
                          + "are in effect. The file is not overwritten, so this repeats until it "
                          + "is fixed. Cause: " + e.getMessage());
            config = new Configuration(); // Use defaults if loading fails
        }
    }

    /**
     * Names the keys that were read past, so that a setting nothing acts on is not left believed
     * in.
     *
     * <p>A warning rather than an error: the file was read, and the settings around the key are in
     * effect. It is worth a line because the usual cause is a spelling mistake in a file the user
     * edits by hand.</p>
     */
    private static void reportUnrecognized(Path path, java.util.List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        java.util.List<String> unknown = new java.util.ArrayList<>();
        for (String key : keys) {
            String became = RetiredSettings.whatBecameOf(key);
            if (became == null) {
                unknown.add(key);
            } else {
                reportWarning(path + " still has " + key + ", which is no longer a setting: "
                              + became + ". The line is ignored and can be deleted.");
            }
        }
        if (unknown.isEmpty()) {
            return;
        }
        reportWarning(path + " contains " + unknown.size()
                      + (unknown.size() == 1 ? " setting this build does not know: "
                                             : " settings this build does not know: ")
                      + String.join(", ", unknown)
                      + ". They are ignored; everything else in the file is in effect.");
    }

    /**
     * Persists the configuration to disk.
     *
     * @return {@code true} if the configuration was saved successfully, {@code false} if an
     *         I/O error prevented the save (the error is also reported via the output formatter
     *         unless the manager is still initializing). Callers that surface a success message
     *         to the user should gate it on this result.
     */
    public boolean saveConfig() {
        try {
            if (!Files.exists(configPath.getParent())) {
                Files.createDirectories(configPath.getParent()); // Ensure parent directories exist
            }
            // Brought into existence owner-only rather than created and then narrowed, so there is
            // no moment at which the file holding the API keys exists at whatever the umask
            // allowed. Narrowed again after the write in case it replaced the file rather than
            // rewriting it in place.
            String unprotected = OwnerOnlyFile.createOwnerOnly(configPath);
            if (unprotected != null) {
                reportProblem("Failed to create " + configPath + " restricted to this account - it "
                              + "may contain API keys and be readable by other users: "
                              + unprotected);
            }
            ConfigFile.write(configPath, runOnly.withoutThem(ConfigFile.rendered(config)));
            restrictToOwnerOnly(configPath);
            if (!initializing) {
                OutputFormatter.printSuccess("Configuration saved to " + configPath);
            }
            return true;
        } catch (IOException e) {
            if (!initializing) {
                OutputFormatter.printError("Failed to save configuration: " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * Restricts a file that may contain secrets to owner-only access ({@code rw-------}) on POSIX
     * filesystems.
     *
     * <p>Filesystems without POSIX permissions (Windows, and non-POSIX mounts elsewhere) are left
     * alone: {@link Files#setPosixFilePermissions} is only attempted when the file's store supports
     * the POSIX attribute view, and an {@link UnsupportedOperationException} from a store that
     * reports support but does not provide it is treated the same way. A missing file is a no-op --
     * there is nothing to protect yet. Any other I/O failure is reported rather than swallowed,
     * because it means the secrets are still readable by other local accounts.</p>
     *
     * @param path the file to restrict; ignored when it does not exist
     */
    private void restrictToOwnerOnly(Path path) {
        String failure = OwnerOnlyFile.restrict(path);
        if (failure != null) {
            reportProblem("Failed to restrict permissions on " + path
                          + " - it may contain API keys and remain readable by other users: "
                          + failure);
        }
    }

    /**
     * The one manager, built on first use.
     *
     * <h2>Why the half-built manager is handed back rather than a second one being made</h2>
     *
     * <p>Building this reads a file, and every step of that can report a failure. Reporting reaches
     * the logger, the logger asks where its file is, and that question comes straight back here.
     * With the whole of the work inside the constructor, {@code instance} was still unassigned when
     * that second call arrived, so it built a second manager -- which finished, declared
     * initialisation over and printed the first one's held-back problems while the first one was
     * still halfway through collecting them. The manager that then went on to be used was the one
     * whose completion nobody had waited for.</p>
     *
     * <p>So the manager is allocated first and settled afterwards: from the moment there is
     * something to return, anything that re-enters gets it, holding the defaults that really are in
     * force until the file has been read.</p>
     */
    public static synchronized ConfigManager getInstance() {
        if (instance != null) {
            return instance;
        }
        if (settling != null) {
            return settling;
        }
        settling     = new ConfigManager();
        initializing = true;
        try {
            settling.settleIn();
        } finally {
            initializing = false;
            instance     = settling;
            settling     = null;
            flushDeferredProblems();
        }
        return instance;
    }

    /** Whether the manager has finished reading its file, rather than still being built. */
    public static synchronized boolean isSettled() {
        return instance != null;
    }

    /** Reads the file this manager was pointed at, and makes the places it names. */
    private void settleIn() {
        readFrom(configPath);
    }

    /** Prints anything that went wrong during construction, now that printing is allowed. */
    private static void flushDeferredProblems() {
        synchronized (deferredProblems) {
            deferredProblems.forEach(Deferred::print);
            deferredProblems.clear();
        }
    }

    public Configuration getConfig() {
        return config;
    }

    /**
     * Saves the configuration after the user chose the named settings in this run.
     *
     * <p>{@link #saveConfig()} keeps a setting out of the file while it holds the value a flag
     * gave it, and a choice of that same value looks no different. A command that sets a value the
     * user asked for names it here, so the choice is saved and stays saved.</p>
     *
     * @param settings the settings chosen, named as {@code config set} names them
     * @return whether the file was written
     */
    public synchronized boolean saveChoice(String... settings) {
        com.fasterxml.jackson.databind.JsonNode rendered = ConfigFile.rendered(config);
        java.util.List<com.fasterxml.jackson.core.JsonPointer> chosen = new java.util.ArrayList<>();
        for (String setting : settings) {
            com.fasterxml.jackson.core.JsonPointer path = RunOnlySettings.pointerTo(rendered, setting);
            if (path != null) {
                chosen.add(path);
            }
        }
        runOnly = runOnly.except(chosen);
        return saveConfig();
    }

    /**
     * Applies settings that last for this run and are never written to the file.
     *
     * <h2>Why the command line needs this</h2>
     *
     * <p>The flags used to be written into the configuration like any other change, and
     * {@link #saveConfig()} writes the configuration. So the first command of a run that saved
     * anything saved the run's flags as well: one run with {@code --base-dir} pointing at a scratch
     * folder moved the user's base directory there for every session after it. What the flags change
     * is taken as the difference between the configuration before and after {@code apply}, and a
     * save puts each such setting back to what the file said -- unless the run has changed it again
     * since, which is a choice the user made and is saved.</p>
     *
     * @param apply writes the flags into {@link #getConfig()}
     */
    public synchronized void applyForThisRunOnly(Runnable apply) {
        com.fasterxml.jackson.databind.JsonNode before = ConfigFile.rendered(config);
        apply.run();
        runOnly = runOnly.plus(RunOnlySettings.between(before, ConfigFile.rendered(config)));
    }

    public void overrideFromCommandLine(Map<String, String> overrides) {
        // Apply command-line overrides to configuration
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            applyOverride(entry.getKey(), entry.getValue());
        }
        // baseDir is settable here too, and the tree under it is built once in the constructor.
        // Without this the user would be told the setting was saved while nothing under the new
        // base directory existed. It only creates what is missing, so re-running it is free.
        //
        // The log file, its size cap and its rotation count are all settable here, and the writer
        // resolved its destination once. Without this the user would be told the setting was saved
        // while every subsequent line kept going to the old file. Both are skipped while the
        // singleton is still being built, since resolving a destination needs the singleton.
        if (!initializing) {
            ensureDirectoriesExist();
            com.eonmux.cadetcoder.logging.CadetLogger.reopenLogFile();
        }
    }

    private void applyOverride(String key, String value) {
        ConfigOverrides.apply(config, key, value);
    }

    public void loadConfigFromFile(String filePath) {
        runOnly = RunOnlySettings.NONE;
        Path path = Paths.get(filePath);
        try {
            ConfigFile.Loaded loaded = ConfigFile.read(path);
            config = loaded.config();
            reportUnrecognized(path, loaded.unrecognized());
            if (!initializing) {
                OutputFormatter.printSuccess("Configuration loaded from " + path);
            }
        } catch (IOException e) {
            if (!initializing) {
                OutputFormatter.printError("Failed to load configuration from " + path + ": " + e.getMessage());
            }
            config = new Configuration(); // Use defaults if loading fails
        }
    }
}
