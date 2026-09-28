package com.eonmux.cadetcoder.config;


/**
 * Applies one {@code <section>.<property> = <value>} setting to a {@link Configuration}.
 *
 * <h2>Why an unknown property must be refused</h2>
 *
 * <p>{@code cadet config} prints the whole configuration, so every property named here is one the
 * user has been shown and told is real. Six of the nine sections used to have no {@code default}
 * branch: an unrecognised property fell out of the switch, and the caller still saved the file and
 * printed "Configuration updated: security.readOnlyMode = true" over a value that was still false.
 * Silently ignoring a setting is bad everywhere and worst in {@code security}, where the failure is
 * somebody believing they have restricted the tool. Every section therefore rejects what it cannot
 * apply, and names the properties it can.</p>
 *
 * <h2>What is deliberately not settable here</h2>
 *
 * <p>{@code ai.providerApiKeys} and {@code ai.providerOptions} are per-provider maps, not scalars,
 * so there is no {@code <section>.<property> <value>} spelling for them. {@code login} owns the
 * first: it reads a key with {@code Console.readPassword} and refuses one given as an argument,
 * precisely so a credential never reaches the shell's history or this process's argument list, and
 * adding a {@code config} path for it would hand that back.</p>
 *
 * <p>Kept apart from {@link ConfigManager} so this is reachable without the singleton: the manager
 * owns process-wide state (the config file, the directory tree), and none of that is needed to
 * decide what a setting means.</p>
 */
public final class ConfigOverrides {

    /** The sections, named once so the two places that list them cannot disagree. */
    private static final String SECTION_NAMES =
            "ai, context, indexing, git, ui, performance, security, logging, compaction";

    private ConfigOverrides() {
    }

    /**
     * Applies one setting.
     *
     * @param config   the configuration to modify
     * @param key      {@code <section>.<property>}, or the name of a setting that is in no section
     * @param value    the new value, parsed according to the property's type
     * @throws IllegalArgumentException if the section or property is unknown, or the value does not
     *                                  parse as the property's type
     */
    public static void apply(Configuration config, String key, String value) {
        // Split once only. A setting's name is a section and everything after it: most properties
        // hold no dot, but a provider option is 'ai.providerOptions.<provider>.<option>' and has to
        // arrive at applyAi whole. Splitting on every dot also silently truncated anything longer
        // than two segments, so 'ai.model.typo' was accepted as 'ai.model'.
        String[] parts = key == null || key.isBlank() ? new String[0] : key.split("\\.", 2);
        if (parts.length == 1) {
            applyRoot(config, parts[0], value);
            return;
        }
        if (parts.length < 2) {
            throw new IllegalArgumentException(
                    "Invalid configuration key '" + key + "'. Expected format '<section>.<property>'.");
        }

        String section  = parts[0].toLowerCase(java.util.Locale.ROOT);
        String property = parts[1];

        switch (section) {
            case "ai":          applyAi(config, property, value);          break;
            case "context":     applyContext(config, property, value);     break;
            case "indexing":    applyIndexing(config, property, value);    break;
            case "git":         applyGit(config, property, value);         break;
            case "ui":          applyUi(config, property, value);          break;
            case "performance": applyPerformance(config, property, value); break;
            case "security":    applySecurity(config, property, value);    break;
            case "logging":     applyLogging(config, property, value);     break;
            case "compaction":  applyCompaction(config, property, value);  break;
            default:
                throw new IllegalArgumentException(
                        "Unknown configuration section '" + section + "'. Valid sections: "
                        + SECTION_NAMES + ".");
        }
    }

    /**
     * Applies a setting that lives outside every section.
     *
     * <p>{@code baseDir} is the only one, and it was unreachable. Every key had to contain a dot,
     * so the one spelling the user is ever shown -- {@code cadet config} lists it under "paths" and
     * {@code cadet config baseDir} prints its value -- came back as "Invalid configuration key
     * 'baseDir'". {@link Configuration}'s own class comment names {@code cadet config baseDir
     * <value>} as the way to repair a corrupted base directory, and that command did not exist, so
     * where the logs, sessions, indexes and downloaded models live could only be changed by editing
     * the file by hand.</p>
     *
     * @param config   the configuration to modify
     * @param property the setting name as typed
     * @param value    the new value
     */
    private static void applyRoot(Configuration config, String property, String value) {
        switch (canonical(config, property)) {
            case "baseDir": config.setBaseDir(value); break;
            default:
                throw new IllegalArgumentException(
                        "Unknown configuration setting '" + property + "'. Settings in a section "
                        + "are named '<section>.<property>' (sections: " + SECTION_NAMES
                        + "); the only setting outside a section is 'baseDir'.");
        }
    }

    /** How a per-provider option is named, e.g. {@code ai.providerOptions.azure.resourceName}. */
    private static final String PROVIDER_OPTION_PREFIX = "providerOptions.";

    /**
     * Sets one option belonging to one provider.
     *
     * <h2>Why this setting needed a path of its own</h2>
     *
     * <p>Everything else here is a scalar with a setter, and this is a map of maps, so it was left
     * out -- and three connectors cannot be reached without it. Azure builds its endpoint from
     * {@code resourceName} and is unreachable while that is unset; both Cloudflare connectors build
     * theirs from {@code accountId} and {@code gatewayId} and, with those empty, produce a URL with
     * holes in it that is non-blank enough to look configured and answers 404. Nothing in the tool
     * could write any of the three, so the only way in was to hand-edit {@code config.json}, which
     * nothing tells the user. {@code /config} made it worse by listing the option once it existed
     * and then refusing to set the name it had just printed.</p>
     *
     * <h2>Why the provider is not filed under the name that was typed</h2>
     *
     * <p>The {@code providerOptions.} prefix is matched without regard to case, as every other
     * setting name is, and the provider that follows it was then stored exactly as the user wrote
     * it. {@code ConnectorAIClient} looks the options up by the connector's id and nothing else, so
     * {@code ai.providerOptions.Azure.resourceName} was accepted, saved, printed back by
     * {@code cadet config} -- and never read, which for Azure means an endpoint that cannot be
     * built and a report of missing credentials that were never missing. The option is filed under
     * the id the lookup will use, which is the same service {@link #canonical} performs for every
     * other property.</p>
     *
     * @param ai       the AI settings to modify
     * @param property the property as typed, beginning {@code providerOptions.}
     * @param value    the new value; blank removes the option
     */
    private static void applyProviderOption(Configuration.AiConfig ai, String property, String value) {
        String[] parts = property.split("\\.", 3);
        if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IllegalArgumentException(
                    "A provider option is named ai.providerOptions.<provider>.<option>, for example "
                    + "ai.providerOptions.azure.resourceName. '" + property + "' is not.");
        }
        String provider = canonicalProvider(parts[1].trim());
        String option   = parts[2];

        java.util.Map<String, String> options =
                ai.getProviderOptions().computeIfAbsent(provider, name -> new java.util.LinkedHashMap<>());
        if (value == null || value.isBlank()) {
            options.remove(option);
            if (options.isEmpty()) {
                ai.getProviderOptions().remove(provider);
            }
            return;
        }
        options.put(option, value.trim());
    }

    /**
     * A connector's id, given whatever case the provider's name was typed in.
     *
     * <p>Taken from the registry rather than lower-cased on the spot, so the answer is an id that
     * exists rather than a guess about how ids are spelled. A name matching no connector is kept
     * lower-cased: it is the shape every registered id has, and refusing the setting outright would
     * make a connector registered after this call unreachable.</p>
     *
     * @param typed the provider name as the user wrote it
     * @return the id to file the option under
     */
    private static String canonicalProvider(String typed) {
        for (com.eonmux.cadetcoder.ai.providers.ProviderConnector connector
                : com.eonmux.cadetcoder.ai.providers.ProviderRegistry.getInstance().all()) {
            if (connector.getId().equalsIgnoreCase(typed)) {
                return connector.getId();
            }
        }
        return typed.toLowerCase(java.util.Locale.ROOT);
    }

    /** How a model's window is named, e.g. {@code ai.modelContextTokens.local/qwen3-coder}. */
    private static final String MODEL_WINDOW_PREFIX = "modelContextTokens.";

    /**
     * Gives one model its input window, as {@code models context} does for the active model.
     *
     * <p>Everything after the prefix is the key, dots included: a model id such as
     * {@code qwen2.5-coder} holds dots of its own, and splitting it at them would file the window
     * under a model that does not exist. The provider is filed under its connector's id, for the
     * reason {@link #applyProviderOption} gives.</p>
     *
     * @param ai       the AI settings to modify
     * @param property the property as typed, beginning {@code modelContextTokens.}
     * @param value    the window in tokens; blank or {@code 0} removes it
     */
    private static void applyModelWindow(Configuration.AiConfig ai, String property, String value) {
        String key   = property.substring(MODEL_WINDOW_PREFIX.length()).trim();
        int    slash = key.indexOf('/');
        if (slash <= 0 || slash == key.length() - 1) {
            throw new IllegalArgumentException(
                    "A model's window is named ai.modelContextTokens.<provider>/<model>, for example "
                    + "ai.modelContextTokens.local/qwen3-coder. '" + property + "' is not.");
        }
        String filed = com.eonmux.cadetcoder.ai.ContextWindow.keyFor(
                canonicalProvider(key.substring(0, slash).trim()), key.substring(slash + 1).trim());
        if (value == null || value.isBlank() || value.trim().equals("0")) {
            ai.getModelContextTokens().remove(filed);
            return;
        }
        ai.getModelContextTokens().put(filed, atLeast(property, value, 1));
    }

    private static void applyAi(Configuration config, String property, String value) {
        Configuration.AiConfig ai = config.getAi();
        if (property.regionMatches(true, 0, PROVIDER_OPTION_PREFIX, 0, PROVIDER_OPTION_PREFIX.length())) {
            applyProviderOption(ai, property, value);
            return;
        }
        if (property.regionMatches(true, 0, MODEL_WINDOW_PREFIX, 0, MODEL_WINDOW_PREFIX.length())) {
            applyModelWindow(ai, property, value);
            return;
        }
        switch (canonical(ai, property)) {
            case "model":                    ai.setModel(value); break;
            case "escalateTo":               ai.setEscalateTo(value); break;
            case "provider":                 ai.setProvider(value); break;
            case "apiKey":                   ai.setApiKey(value); break;
            case "apiEndpoint":              ai.setApiEndpoint(value); break;
            case "localEndpoint":            ai.localEndpoint = value; break;
            case "localModel":               ai.localModel = value; break;
            case "temperature":              ai.setTemperature((float) within(property, value, 0.0, 2.0)); break;
            // Floor of zero, not one: zero is how "no ceiling at all" is written, and it is the
            // shipped default, so a floor of one made the default unreachable through this command.
            case "maxTokens":                ai.setMaxTokens(atLeast(property, value, 0)); break;
            case "contextTokens":            ai.setContextTokens(atLeast(property, value, 0)); break;
            case "completionTimeoutSeconds": ai.setCompletionTimeoutSeconds(atLeast(property, value, 1)); break;
            case "chatTemplate":             ai.setChatTemplate(value); break;
            case "zeroDataRetention":        ai.setZeroDataRetention(asBoolean(property, value)); break;
            case "uberMode":                 ai.setUberMode(asBoolean(property, value)); break;
            default: throw unknown("ai", property, "model, escalateTo, provider, apiKey, apiEndpoint, "
                    + "localEndpoint, localModel, temperature, maxTokens, contextTokens, "
                    + "completionTimeoutSeconds, chatTemplate, "
                    + "zeroDataRetention, uberMode; "
                    + "providerOptions.<provider>.<option>; and "
                    + "modelContextTokens.<provider>/<model>");
        }
    }

    private static void applyContext(Configuration config, String property, String value) {
        Configuration.ContextConfig context = config.getContext();
        switch (canonical(context, property)) {
            case "maxFiles":        context.setMaxFiles(atLeast(property, value, 1)); break;
            case "maxLinesPerFile": context.setMaxLinesPerFile(atLeast(property, value, 1)); break;
            case "priorityFiles":   context.setPriorityFiles(asList(value)); break;
            default: throw unknown("context", property,
                                   "maxFiles, maxLinesPerFile, priorityFiles");
        }
    }

    private static void applyIndexing(Configuration config, String property, String value) {
        Configuration.IndexingConfig indexing = config.getIndexing();
        switch (canonical(indexing, property)) {
            case "enabled":                indexing.setEnabled(asBoolean(property, value)); break;
            case "indexLocation":          indexing.setIndexLocation(value); break;
            case "excludePatterns":        indexing.setExcludePatterns(asList(value)); break;
            case "refreshIntervalMinutes": indexing.setRefreshIntervalMinutes(atLeast(property, value, 0)); break;
            default: throw unknown("indexing", property,
                    "enabled, indexLocation, excludePatterns, refreshIntervalMinutes");
        }
    }

    private static void applyGit(Configuration config, String property, String value) {
        Configuration.GitConfig git = config.getGit();
        switch (canonical(git, property)) {
            case "enabled":               git.setEnabled(asBoolean(property, value)); break;
            case "includeBranches":       git.setIncludeBranches(asBoolean(property, value)); break;
            case "includeCommitHistory":  git.setIncludeCommitHistory(asBoolean(property, value)); break;
            case "maxCommitHistory":      git.setMaxCommitHistory(atLeast(property, value, 1)); break;
            case "autoCommitEnabled":     git.setAutoCommitEnabled(asBoolean(property, value)); break;
            case "commitMessageTemplate": git.setCommitMessageTemplate(value); break;
            case "commitTrigger":         git.setCommitTrigger(value); break;
            case "autoCommitIntervalMinutes":
                git.setAutoCommitIntervalMinutes(atLeast(property, value, 1)); break;
            default: throw unknown("git", property, "enabled, includeBranches, includeCommitHistory, "
                    + "maxCommitHistory, autoCommitEnabled, commitMessageTemplate, commitTrigger, "
                    + "autoCommitIntervalMinutes");
        }
    }

    private static void applyUi(Configuration config, String property, String value) {
        Configuration.UiConfig ui = config.getUi();
        switch (canonical(ui, property)) {
            case "colorEnabled":      ui.setColorEnabled(asBoolean(property, value)); break;
            case "colorTheme":        ui.setColorTheme(value); break;
            case "verbosityLevel":    ui.setVerbosityLevel(withinLevels(property, value)); break;
            case "showCommandOutput": ui.setShowCommandOutput(asBoolean(property, value)); break;
            case "interactivePrompts": ui.setInteractivePrompts(asBoolean(property, value)); break;
            default: throw unknown("ui", property, "colorEnabled, colorTheme, verbosityLevel, "
                    + "showCommandOutput, interactivePrompts");
        }
    }

    private static void applyPerformance(Configuration config, String property, String value) {
        Configuration.PerformanceConfig performance = config.getPerformance();
        switch (canonical(performance, property)) {
            case "threads":             performance.setThreads(withinWorkerRange(property, value)); break;
            case "parallelProcessing":  performance.setParallelProcessing(asBoolean(property, value)); break;
            default: throw unknown("performance", property, "threads, parallelProcessing");
        }
    }

    private static void applySecurity(Configuration config, String property, String value) {
        Configuration.SecurityConfig security = config.getSecurity();
        switch (canonical(security, property)) {
            case "allowRemoteExecution": security.setAllowRemoteExecution(asBoolean(property, value)); break;
            case "allowedCommands":      security.setAllowedCommands(asList(value)); break;
            case "allowedActions":       security.setAllowedActions(asList(value)); break;
            case "sandboxMode":          security.setSandboxMode(asBoolean(property, value)); break;
            case "readOnlyMode":         security.setReadOnlyMode(asBoolean(property, value)); break;
            case "requireConfirmation":  security.setRequireConfirmation(asBoolean(property, value)); break;
            case "allowOutsideProject":  security.setAllowOutsideProject(asBoolean(property, value)); break;
            case "maxFileContentSize":   security.setMaxFileContentSize(atLeast(property, value, 1)); break;
            case "commandApproval":      security.setCommandApproval(asApprovalMode(property, value)); break;
            default: throw unknown("security", property, "allowRemoteExecution, "
                    + "allowedCommands, allowedActions, sandboxMode, readOnlyMode, requireConfirmation, "
                    + "allowOutsideProject, maxFileContentSize, commandApproval");
        }
    }

    private static void applyLogging(Configuration config, String property, String value) {
        Configuration.LoggingConfig logging = config.getLogging();
        switch (canonical(logging, property)) {
            case "level":                 logging.setLevel(value); break;
            case "logFile":               logging.setLogFile(value); break;
            case "consoleLoggingEnabled": logging.setConsoleLoggingEnabled(asBoolean(property, value)); break;
            case "maxLogFiles":           logging.setMaxLogFiles(atLeast(property, value, 1)); break;
            case "maxLogSize":            logging.setMaxLogSize(atLeast(property, value, 1)); break;
            case "debugEnabled":          logging.setDebugEnabled(asBoolean(property, value)); break;
            case "sessionLoggingEnabled": logging.setSessionLoggingEnabled(asBoolean(property, value)); break;
            case "structuredLogging":     logging.setStructuredLogging(asBoolean(property, value)); break;
            case "maxSessionLogSize":     logging.setMaxSessionLogSize(atLeast(property, value, 1)); break;
            case "maxSessionLogs":        logging.setMaxSessionLogs(atLeast(property, value, 1)); break;
            default: throw unknown("logging", property, "level, logFile, consoleLoggingEnabled, "
                    + "maxLogFiles, maxLogSize, debugEnabled, sessionLoggingEnabled, "
                    + "structuredLogging, maxSessionLogSize, maxSessionLogs");
        }
    }

    private static void applyCompaction(Configuration config, String property, String value) {
        Configuration.CompactionConfig compaction = config.getCompaction();
        switch (canonical(compaction, property)) {
            case "enabled":         compaction.setEnabled(asBoolean(property, value)); break;
            case "trigger":         compaction.setTrigger(within(property, value, 0.05, 1.0)); break;
            case "target":          compaction.setTarget(within(property, value, 0.05, 1.0)); break;
            case "keepHeadEntries": compaction.setKeepHeadEntries(atLeast(property, value, 0)); break;
            case "keepTailEntries": compaction.setKeepTailEntries(atLeast(property, value, 0)); break;
            default: throw unknown("compaction", property,
                    "enabled, trigger, target, keepHeadEntries, keepTailEntries");
        }
    }

    /**
     * The setting's name as this build spells it, given whatever case the user typed.
     *
     * <p>Reading a setting has always been case-insensitive -- {@code config ui.colortheme} prints
     * the value -- while writing one was not, so the tool answered {@code config ui.colortheme
     * matrix} with "Unknown property ui.colortheme", naming a setting it had just printed. Taken
     * from the section's own fields rather than from a list kept beside them, so it is the same set
     * of names {@code /config} lists and cannot drift from them.</p>
     *
     * @param sectionConfig the section the setting lives in
     * @param property      the name as typed
     * @return the canonical spelling, or {@code property} unchanged when nothing matches, so the
     *         switch that called this still produces its own "unknown property" message
     */
    private static String canonical(Object sectionConfig, String property) {
        if (sectionConfig == null || property == null) {
            return property;
        }
        for (java.lang.reflect.Field field : sectionConfig.getClass().getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                && field.getName().equalsIgnoreCase(property)) {
                return field.getName();
            }
        }
        return property;
    }

    private static IllegalArgumentException unknown(String section, String property, String valid) {
        String became = RetiredSettings.whatBecameOf(section + "." + property);
        if (became != null) {
            return new IllegalArgumentException(section + "." + property
                    + " is no longer a setting: " + became + ".");
        }
        return new IllegalArgumentException("Unknown property " + section + "." + property
                + ". Valid " + section + " properties: " + valid + ".");
    }

    /**
     * A boolean setting is refused unless it is literally true or false.
     *
     * <p>{@code Boolean.parseBoolean} maps everything that is not "true" to false, so
     * {@code config security.readOnlyMode yes} would have quietly turned read-only mode OFF while
     * reporting that it was set.</p>
     */
    private static boolean asBoolean(String property, String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(
                property + " takes true or false, not '" + value + "'.");
    }

    private static int asInt(String property, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException notANumber) {
            throw new IllegalArgumentException(property + " takes a whole number, not '" + value + "'.");
        }
    }

    /**
     * A whole number that has to be at least {@code floor}.
     *
     * <h2>Why the range is checked here rather than where the value is used</h2>
     *
     * <p>A value of the right type can still be one nothing can work with, and every one of these
     * was accepted, saved and reported as set. {@code context.maxFiles 0} reaches Lucene's searcher,
     * which refuses a count of zero, so every request that consults the index fails from then on --
     * for a setting the tool said it had accepted. Refused at the point it is typed, the user is
     * told which value is wrong while they still have it in front of them.</p>
     *
     * @param property the setting's name, for the message
     * @param value    what the user typed
     * @param floor    the smallest value this setting can mean anything at
     * @return the parsed number
     */
    private static int atLeast(String property, String value, int floor) {
        int number = asInt(property, value);
        if (number < floor) {
            throw new IllegalArgumentException(
                    property + " must be at least " + floor + "; '" + value + "' is not.");
        }
        return number;
    }

    /**
     * A number that has to fall between {@code low} and {@code high}, both included.
     *
     * @param property the setting's name, for the message
     * @param value    what the user typed
     * @param low      the smallest value this setting can mean anything at
     * @param high     the largest
     * @return the parsed number
     */
    private static double within(String property, String value, double low, double high) {
        double number = asDouble(property, value);
        if (number < low || number > high) {
            throw new IllegalArgumentException(
                    property + " must be between " + low + " and " + high
                    + "; '" + value + "' is not.");
        }
        return number;
    }

    /**
     * The verbosity level, which is one of three and not a number on a scale.
     *
     * <p>Anything at or above {@code VERBOSE} was read as verbose by the tests that compare
     * ordinals, and as neither by the two that compare for equality -- so {@code 99} did not make
     * the tool louder, it made it inconsistent, and one of the things it switched on prints entire
     * prompts, with their file contents, to the terminal.</p>
     *
     * @param property the setting's name, for the message
     * @param value    what the user typed
     * @return the level
     */
    private static int withinLevels(String property, String value) {
        int highest = Configuration.UiConfig.VERBOSITY.values().length - 1;
        int level   = asInt(property, value);
        if (level < 0 || level > highest) {
            throw new IllegalArgumentException(
                    property + " is 0 (minimal), 1 (normal) or 2 (verbose); '" + value + "' is not.");
        }
        return level;
    }

    /**
     * One of the two names for who approves a shell command.
     *
     * <p>Refused rather than silently defaulted, because the two modes decide the same question
     * differently: a typo that fell back to {@code manual} would look like the model never being
     * asked, and one that fell back to {@code auto} would look like the person never being asked.</p>
     *
     * @param property the setting being written, for the message
     * @param value    what the user typed
     * @return the mode name, lower-cased
     */
    private static String asApprovalMode(String property, String value) {
        String mode = value == null ? "" : value.trim().toLowerCase();
        if (mode.equals("manual") || mode.equals("auto")) {
            return mode;
        }
        throw new IllegalArgumentException(
                property + " is auto (ask the model) or manual (ask the person at the terminal), "
                + "not '" + value + "'.");
    }

    /**
     * A worker count the pool can actually run.
     *
     * <p>Refused here rather than clamped, because this is the moment the user is present to be
     * told. {@code performance.threads 40} is someone expecting forty workers, and silently giving
     * them eight while reporting success is the class of failure this whole file exists to end.</p>
     */
    private static int withinWorkerRange(String property, String value) {
        int threads = asInt(property, value);
        if (threads < 1 || threads > com.eonmux.cadetcoder.agents.WorkerPool.MAX_CONCURRENCY) {
            throw new IllegalArgumentException(property + " is between 1 and "
                    + com.eonmux.cadetcoder.agents.WorkerPool.MAX_CONCURRENCY
                    + " workers at a time; '" + value + "' is not. The ceiling is the provider's "
                    + "rate limit rather than the machine's: every worker is a full agent loop "
                    + "against one account.");
        }
        return threads;
    }

    private static float asFloat(String property, String value) {
        try {
            return Float.parseFloat(value.trim());
        } catch (RuntimeException notANumber) {
            throw new IllegalArgumentException(property + " takes a number, not '" + value + "'.");
        }
    }

    private static double asDouble(String property, String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (RuntimeException notANumber) {
            throw new IllegalArgumentException(property + " takes a number, not '" + value + "'.");
        }
    }

    /**
     * Splits a comma-separated value into a list setting's entries.
     *
     * <p>Splitting only. Trimming and dropping blanks -- which is what makes a trailing comma
     * harmless -- belong to the setter, which has to do them anyway for the entries that arrive
     * from {@code config.json} instead of from here, and doing them twice is two rules that can
     * disagree.</p>
     *
     * @param value the value as typed; {@code null} is an empty list
     * @return the entries, for {@link Configuration} to sanitize
     */
    private static String[] asList(String value) {
        return value == null ? new String[0] : value.split(",");
    }
}
