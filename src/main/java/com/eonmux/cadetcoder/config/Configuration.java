package com.eonmux.cadetcoder.config;

/**
 * The settings tree, as deserialized from the user-editable {@code config.json}.
 *
 * <h2>Sections are never null</h2>
 *
 * <p>Jackson populates this object by calling the setters with whatever the file contains, so a JSON
 * {@code null} arrives here as a Java {@code null}. Every consumer reads a section directly --
 * {@code getSecurity().isReadOnlyMode()}, {@code getGit().isEnabled()} -- and there are far more
 * readers than writers, so the guard belongs here rather than at each use. Each section setter
 * therefore substitutes a freshly defaulted section for {@code null}, which makes "the section is
 * absent" and "the section is null" mean the same thing, and lets every getter be dereferenced
 * without a check.</p>
 *
 * <p>{@code baseDir} is the same rule for the same reason, and the most important instance of it:
 * {@code ConfigManager} resolves it in its constructor before anything else runs, so a null there
 * failed every invocation -- including the {@code cadet config baseDir <value>} that would have
 * repaired the file. {@code logging.logFile} and {@code indexing.indexLocation} are read the same
 * way and were not covered by it, so {@code {"logging": {"logFile": null}}} in a hand-edited file
 * was a {@link NullPointerException} out of {@code ConfigManager}'s directory setup -- outside the
 * {@code IOException} it catches, so every invocation died at start-up, {@code cadet config}
 * included.</p>
 *
 * <h2>Why a value out of range is corrected here</h2>
 *
 * <p>{@link ConfigOverrides} checks the range of every numeric setting, and explains itself at
 * length about why the check belongs at the moment a person types the value. That is the right
 * place for the typed path and it is only one of the two: {@code ConfigFile} deserializes straight
 * into these setters, so a number written into {@code config.json} -- the file this project
 * documents as hand-edited -- reached the field with nothing looking at it.
 * {@code {"context": {"maxFiles": 0}}} was enough to make every request that consults the index
 * fail, because Lucene refuses a result count of zero, and nothing on the way said so.</p>
 *
 * <p>So the rule lives where the value is set, and both paths get the same answer. It is applied
 * rather than refused: a setter that threw would be answered by {@code ConfigManager} falling back
 * to the shipped defaults for the WHOLE file, which is one bad number costing every other setting
 * in it. A person typing the value is still told, because {@code ConfigOverrides} refuses before it
 * ever reaches a setter; a file gets the nearest value the setting can mean anything at. Where a
 * field's own documentation gives a value outside that range a meaning of its own -- zero lines per
 * file meaning the whole file, a zero size limit meaning the shipped default -- that meaning is
 * kept, because it is the setting's answer rather than the absence of one.</p>
 */
public class Configuration {
    public static String            defaultBaseDir = System.getProperty("user.home") + "/.cadet";
    private       AiConfig          ai             = new AiConfig();
    private       ContextConfig     context        = new ContextConfig();
    private       IndexingConfig    indexing       = new IndexingConfig();
    private       GitConfig         git            = new GitConfig();
    private       UiConfig          ui             = new UiConfig();
    private       PerformanceConfig performance    = new PerformanceConfig();
    private       SecurityConfig    security       = new SecurityConfig();
    private       LoggingConfig     logging        = new LoggingConfig();
    private       CompactionConfig  compaction     = new CompactionConfig();
    private       String            baseDir        = defaultBaseDir;

    // Getters and setters
    public AiConfig getAi() {
        return ai;
    }

    public void setAi(AiConfig ai) {
        this.ai = ai != null ? ai : new AiConfig();
    }

    public ContextConfig getContext() {
        return context;
    }

    public void setContext(ContextConfig context) {
        this.context = context != null ? context : new ContextConfig();
    }

    public IndexingConfig getIndexing() {
        return indexing;
    }

    public void setIndexing(IndexingConfig indexing) {
        this.indexing = indexing != null ? indexing : new IndexingConfig();
    }

    public GitConfig getGit() {
        return git;
    }

    public void setGit(GitConfig git) {
        this.git = git != null ? git : new GitConfig();
    }

    public UiConfig getUi() {
        return ui;
    }

    public void setUi(UiConfig ui) {
        this.ui = ui != null ? ui : new UiConfig();
    }

    public PerformanceConfig getPerformance() {
        return performance;
    }

    public void setPerformance(PerformanceConfig performance) {
        this.performance = performance != null ? performance : new PerformanceConfig();
    }

    public SecurityConfig getSecurity() {
        return security;
    }

    public void setSecurity(SecurityConfig security) {
        this.security = security != null ? security : new SecurityConfig();
    }

    public LoggingConfig getLogging() {
        return logging;
    }

    public void setLogging(LoggingConfig logging) {
        this.logging = logging != null ? logging : new LoggingConfig();
    }

    public String getBaseDir() {
        return baseDir;
    }

    /**
     * @param baseDir where global files live; a leading {@code ~} means the home directory, and
     *                blank means the default location
     */
    public void setBaseDir(String baseDir) {
        this.baseDir = baseDir == null || baseDir.isBlank()
                       ? defaultBaseDir : com.eonmux.cadetcoder.util.UserPath.expanded(baseDir);
    }

    // Configuration sections
    public static class AiConfig {

        /** Coldest and hottest a provider will accept; outside it, every request is a 400. */
        private static final float MIN_TEMPERATURE = 0.0f;
        private static final float MAX_TEMPERATURE = 2.0f;

        /** Shortest deadline that still lets a request happen at all. */
        private static final int MIN_COMPLETION_TIMEOUT_SECONDS = 1;

        public  String localEndpoint            = "http://localhost:8012";
        public  String localModel               = "llama3-8b-q4";
        private float  temperature              = 0.7f;
        /**
         * Tokens the model may GENERATE, sent to the provider as {@code max_tokens}.
         *
         * <p>Not the context window. This field was doing both jobs: the backends send it as the
         * completion limit while the prompt builders read it as the space available for the prompt,
         * so raising one necessarily raised the other and the two have no reason to agree. The
         * context window is {@link #contextTokens}.</p>
         */
        /**
         * Ceiling on what the model may say back, or {@code 0} to ask for none.
         *
         * <p>Nothing is asked for by default. How long an answer needs to be is not knowable before
         * it is written, and a number guessed in advance is only ever wrong in the direction that
         * truncates. Left unasked, a provider falls back to what the model can actually produce.
         * See {@code net/OutputBudget} for the measurements behind this, and for the one wire whose
         * API requires the field.</p>
         */
        private int    maxTokens                = 0;

        /**
         * Tokens the model can take as INPUT, used to budget the prompt.
         *
         * <p>{@code 0} means resolve it automatically: the active model's published context length
         * from the models.dev catalog, or a conservative default when the model is not in the
         * catalog. Set a value to override that. See {@code ai/ContextWindow}.</p>
         */
        private int    contextTokens            = 0;

        /**
         * Input windows given by hand for particular models, keyed {@code provider/model}.
         *
         * <p>For a model the catalog does not describe -- a local server, a private deployment --
         * whose window would otherwise be assumed. Kept per model because a window is a property
         * of the model: {@link #contextTokens} applies to every model and stays wrong after a
         * switch. See {@code ai/ContextWindow} for which one wins.</p>
         */
        private java.util.Map<String, Integer> modelContextTokens = new java.util.LinkedHashMap<>();
        private String apiKey                   = "";
        private String apiEndpoint              = "https://api.openai.com/v1";
        private String model                    = "o1-mini";

        /**
         * The model a stuck run hands over to, empty when there is nobody stronger to ask.
         *
         * <p>A run that stops making progress -- several rounds of thinking that certify nothing new
         * and fix no mismatch -- escalates to this model and tells it what it has walked into. It has
         * to be named rather than guessed: which of two models is the stronger one is a fact about
         * the account paying for them, and a tool that picked one would be spending somebody's money
         * on a judgement it has no way to make.</p>
         *
         * <p>It names a model on the configured {@code provider}, so the credentials, endpoint and
         * options a run already has are the ones it escalates with.</p>
         */
        private String escalateTo = "";
        /**
         * How long one request may take before it is abandoned.
         *
         * <p>This bounds the wait for response headers, and nothing here streams, so for a
         * completion it is the whole generation: a provider sends no headers until the model has
         * finished writing. At the 60 seconds this used to be, a reasoning model working on a large
         * agentic prompt was cut off mid-answer -- and because how long a model thinks varies with
         * what it is asked, the same run worked and then did not. Measured against
         * {@code deepseek/deepseek-v4-flash} through Command Code, one ordinary question took 25
         * seconds.</p>
         *
         * <p>An endpoint that is actually down is still reported in about ten seconds, by
         * {@code AbstractLLMBackend.DEFAULT_CONNECT_TIMEOUT_SECONDS}, so the longer wait is only
         * ever spent on a provider that accepted the connection and is working.</p>
         */
        private int    completionTimeoutSeconds = 300;


        // Chat template configuration
        private String  chatTemplate           = "chatml"; // Default to ChatML template

        // Connector/catalog configuration (opencode-style providers + models.dev model list).
        // When provider is non-empty and names a known connector, requests route through it.
        private String provider = "";
        // Per-provider API keys, keyed by connector id (e.g. "anthropic" -> "sk-...").
        private java.util.Map<String, String> providerApiKeys = new java.util.HashMap<>();
        // Per-provider options, keyed by connector id then option name
        // (e.g. "amazon-bedrock" -> {"region": "us-east-1"}; "azure" -> {"resourceName": "..."}).
        private java.util.Map<String, java.util.Map<String, String>> providerOptions = new java.util.HashMap<>();

        /**
         * Whether a provider that can promise not to retain a request is asked to promise it.
         *
         * <p>Every request carries the files, diffs and prompts of whatever is being worked on, so
         * the interesting default is the private one: this is {@code true} unless it is turned off.
         * A connector with no way to enforce it is unaffected -- the setting adds a header where
         * there is one to add, and changes nothing where there is not, so leaving it on costs
         * nothing on providers that cannot honour it.</p>
         *
         * <p>It can be refused. A gateway reselling a model whose upstream will not agree to zero
         * retention answers 422 rather than quietly retaining the request, which is the right
         * failure: the alternative is a promise silently not kept. The message that reports it names
         * both ways out -- a model whose upstream does agree, or turning this off deliberately.</p>
         */
        private boolean zeroDataRetention = true;

        /**
         * Whether a task is driven to the end rather than to the first plausible stopping point.
         *
         * <p>An agentic loop ends when the model says it is finished, and a model says it is
         * finished when it has done the part of the work it can see. The parts it cannot see from
         * there -- the test it did not run, the second caller it did not update, the half of the
         * request it answered in prose instead of in code -- are exactly the parts that get left
         * behind, and the loop has no way to tell that ending apart from a real one.</p>
         *
         * <p>With this on, a claim of completion is not the end of the run: the model is sent back
         * to check its own claim against the original request, and only an answer that survives
         * that ends the run. It costs turns, which is why it is off unless it is asked for.</p>
         */
        private boolean uberMode = false;

        public String getLocalEndpoint() {
            return localEndpoint;
        }

        public void setLocalEndpoint(String localEndpoint) {
            this.localEndpoint = localEndpoint;
        }

        // Getters and setters
        public int getCompletionTimeoutSeconds() {
            return completionTimeoutSeconds;
        }

        /**
         * @param completionTimeoutSeconds how long one request may take; at least one second,
         *                                 because a deadline of zero abandons every request the
         *                                 moment it is made
         */
        public void setCompletionTimeoutSeconds(int completionTimeoutSeconds) {
            this.completionTimeoutSeconds = atLeast(completionTimeoutSeconds, MIN_COMPLETION_TIMEOUT_SECONDS);
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        /** @return the model a stuck run hands over to, or empty when there is nobody to hand to */
        public String getEscalateTo() {
            return escalateTo;
        }

        /** @param escalateTo a model on the configured provider, or empty for no hand-over */
        public void setEscalateTo(String escalateTo) {
            this.escalateTo = escalateTo == null ? "" : escalateTo.trim();
        }

        public float getTemperature() {
            return temperature;
        }

        /**
         * @param temperature how much the model may vary its answer, between {@code 0.0} and
         *                    {@code 2.0}; a value outside what providers accept is a 400 on every
         *                    request rather than a livelier model
         */
        public void setTemperature(float temperature) {
            this.temperature = within(temperature, MIN_TEMPERATURE, MAX_TEMPERATURE);
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        /** @param maxTokens the ceiling on what the model may say back, or {@code 0} for none */
        public void setMaxTokens(int maxTokens) {
            this.maxTokens = atLeast(maxTokens, 0);
        }

        /** @return the configured context window in tokens, or {@code 0} to resolve it automatically */
        public int getContextTokens() {
            return contextTokens;
        }

        /** @param contextTokens the input window, or {@code 0} to resolve it from the catalog */
        public void setContextTokens(int contextTokens) {
            this.contextTokens = atLeast(contextTokens, 0);
        }

        /** @return the windows given by hand, keyed {@code provider/model}; never {@code null} */
        public java.util.Map<String, Integer> getModelContextTokens() {
            return modelContextTokens;
        }

        /** @param windows the windows given by hand; {@code null} means none */
        public void setModelContextTokens(java.util.Map<String, Integer> windows) {
            this.modelContextTokens = windows != null ? new java.util.LinkedHashMap<>(windows)
                                                      : new java.util.LinkedHashMap<>();
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiEndpoint() {
            return apiEndpoint;
        }

        public void setApiEndpoint(String apiEndpoint) {
            this.apiEndpoint = apiEndpoint;
        }

        public String getLocalModel() {
            return localModel;
        }

        public void setLocalModel(String localModel) {
            this.localModel = localModel;
        }














        public String getChatTemplate() {
            return chatTemplate;
        }

        public void setChatTemplate(String chatTemplate) {
            this.chatTemplate = chatTemplate;
        }



        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public java.util.Map<String, String> getProviderApiKeys() {
            return providerApiKeys;
        }

        public void setProviderApiKeys(java.util.Map<String, String> providerApiKeys) {
            this.providerApiKeys = providerApiKeys != null ? providerApiKeys : new java.util.HashMap<>();
        }

        public boolean isZeroDataRetention() {
            return zeroDataRetention;
        }

        public void setZeroDataRetention(boolean zeroDataRetention) {
            this.zeroDataRetention = zeroDataRetention;
        }

        public boolean isUberMode() {
            return uberMode;
        }

        public void setUberMode(boolean uberMode) {
            this.uberMode = uberMode;
        }

        public java.util.Map<String, java.util.Map<String, String>> getProviderOptions() {
            return providerOptions;
        }

        public void setProviderOptions(java.util.Map<String, java.util.Map<String, String>> providerOptions) {
            this.providerOptions = providerOptions != null ? providerOptions : new java.util.HashMap<>();
        }
    }

    public CompactionConfig getCompaction() {
        return compaction;
    }

    public void setCompaction(CompactionConfig compaction) {
        this.compaction = compaction != null ? compaction : new CompactionConfig();
    }

    /**
     * Returns a list-valued setting as this class is prepared to hold it.
     *
     * <p>{@code config.json} is edited by hand, and Jackson passes on exactly what it finds: a JSON
     * {@code null} arrives here as a null array and a {@code null} inside a list as a null element.
     * No reader of these settings checks for either -- {@code SecurityValidator} asks
     * {@code getAllowedCommands().length} and lowercases every entry, and {@code glob}, {@code grep},
     * {@code ls} and the context indexer each wrap {@code getExcludePatterns()} in
     * {@code Arrays.asList} -- so one null in the file was a NullPointerException on a path with no
     * recovery.</p>
     *
     * <p>Blank entries go the same way, because {@code cadet config} already dropped them on its own
     * path and a setting must not mean one thing typed at the prompt and another written to the file.
     * An empty exclude pattern is the reason it matters: compiled as a regular expression it matches
     * every directory name, so a single {@code ""} excluded the whole project from indexing.</p>
     *
     * <p>The result is a copy, so the setting stays the configuration's rather than the caller's.</p>
     *
     * @param values the setting as it arrived, {@code null} included
     * @return the non-blank entries, trimmed; never {@code null}
     */
    /**
     * A whole-number setting held at or above the smallest value it can mean anything at.
     *
     * @param value the value as it arrived
     * @param floor the smallest usable value
     * @return the value, or {@code floor} when it is below it
     */
    private static int atLeast(int value, int floor) {
        return Math.max(floor, value);
    }

    /**
     * A setting held inside the range it can mean anything in.
     *
     * @param value the value as it arrived
     * @param low   the smallest usable value
     * @param high  the largest
     * @return the value, or the nearest end of the range when it falls outside
     */
    private static int within(int value, int low, int high) {
        return Math.min(high, Math.max(low, value));
    }

    /** As {@link #within(int, int, int)}, for a setting written as a fraction. */
    private static double within(double value, double low, double high) {
        return Math.min(high, Math.max(low, value));
    }

    /** As {@link #within(int, int, int)}, for a setting the provider takes as a float. */
    private static float within(float value, float low, float high) {
        return Math.min(high, Math.max(low, value));
    }

    /**
     * A path setting as this class is prepared to hold it.
     *
     * <p>Absent, null and blank all mean the same thing -- that the user has not named a path --
     * and the answer to all three is the shipped location rather than a null that every reader
     * would have to check for. See the class comment for what a null here used to cost.</p>
     *
     * @param path     the setting as it arrived, {@code null} included
     * @param shipped  where the file lives when nobody has said otherwise
     * @return the path to use, with a leading {@code ~} expanded; never {@code null}
     */
    private static String pathOrShipped(String path, String shipped) {
        return path == null || path.isBlank()
               ? shipped : com.eonmux.cadetcoder.util.UserPath.expanded(path);
    }

    private static String[] sanitizedList(String[] values) {
        if (values == null) {
            return new String[0];
        }
        return java.util.Arrays.stream(values)
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .toArray(String[]::new);
    }

    public static class ContextConfig {

        /** Fewest files a search can return and still be a search; Lucene refuses zero. */
        private static final int MIN_MAX_FILES = 1;

        private int      maxFiles            = 10;
        /**
         * How many lines of any one file are put in front of the model.
         *
         * <p>Applied where a file enters a prompt, so a long file is cut at a line the reader can see
         * ended, with a note saying how many more there were, rather than wherever the token budget
         * happened to run out. Zero or less means the whole file goes in.</p>
         */
        private int      maxLinesPerFile     = 500;
        /**
         * Project files put in front of the model whether or not a request mentions them.
         *
         * <p>An index search matches on what is inside a file, so a document a project has decided
         * always matters -- its coding conventions, its architecture notes -- is returned by no
         * search over the sentence the user typed. Named here, it goes in ahead of what the search
         * did return. Paths are relative to the project, and count against {@code maxFiles}.</p>
         */
        private String[] priorityFiles       = {};

        // Getters and setters
        public int getMaxFiles() {
            return maxFiles;
        }

        /**
         * @param maxFiles how many files a search may put in front of the model; at least one,
         *                 because it is handed to Lucene as a result count and Lucene refuses
         *                 zero -- which failed every request that consults the index, for as long
         *                 as the setting said zero
         */
        public void setMaxFiles(int maxFiles) {
            this.maxFiles = atLeast(maxFiles, MIN_MAX_FILES);
        }

        public int getMaxLinesPerFile() {
            return maxLinesPerFile;
        }

        public void setMaxLinesPerFile(int maxLinesPerFile) {
            this.maxLinesPerFile = maxLinesPerFile;
        }

        public String[] getPriorityFiles() {
            return priorityFiles.clone();
        }

        public void setPriorityFiles(String[] priorityFiles) {
            this.priorityFiles = sanitizedList(priorityFiles);
        }
    }

    public static class IndexingConfig {

        /** Where the index lives when nobody has said otherwise, relative to the project. */
        private static final String DEFAULT_INDEX_LOCATION = ".cadet/index";

        private boolean  enabled                = true;
        private String   indexLocation          = DEFAULT_INDEX_LOCATION;
        /**
         * Directory names skipped while indexing.
         *
         * <p>The default is the shared project-walk list, so the index and the commands that walk
         * the tree agree on what is build output. Written out separately it knew about four names
         * and missed {@code dist}, {@code out}, {@code bin} and {@code obj}, so bundles and
         * compiled output were indexed and served back as project context while {@code grep} and
         * {@code ls} skipped the same directories. Hidden directories are not listed: discovery
         * skips them by the leading dot, as the walk does.</p>
         *
         * <p>Each entry is matched as a regular expression against a directory name, falling back
         * to a literal comparison when it is not valid regex.</p>
         */
        private String[] excludePatterns        = defaultExcludePatterns();

        private static String[] defaultExcludePatterns() {
            return com.eonmux.cadetcoder.util.ProjectTreeWalk.buildOutputDirectories()
                    .stream().sorted().toArray(String[]::new);
        }
        private int      refreshIntervalMinutes = 60;

        // Getters and setters
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getIndexLocation() {
            return indexLocation;
        }

        /**
         * @param indexLocation where the index lives; a leading {@code ~} means the home directory,
         *                      and blank means the default location. Never null: {@code ContextEngine}
         *                      opens this path in its constructor, so a null here was a failure
         *                      every command that needs context shared.
         */
        public void setIndexLocation(String indexLocation) {
            this.indexLocation = pathOrShipped(indexLocation, DEFAULT_INDEX_LOCATION);
        }

        public String[] getExcludePatterns() {
            return excludePatterns.clone();
        }

        public void setExcludePatterns(String[] excludePatterns) {
            this.excludePatterns = sanitizedList(excludePatterns);
        }

        public int getRefreshIntervalMinutes() {
            return refreshIntervalMinutes;
        }

        /**
         * @param refreshIntervalMinutes how long an index stays fresh, or {@code 0} to index once
         *                               per run and then leave it alone
         */
        public void setRefreshIntervalMinutes(int refreshIntervalMinutes) {
            this.refreshIntervalMinutes = atLeast(refreshIntervalMinutes, 0);
        }
    }

    public static class GitConfig {

        /**
         * Fewest commits that may be asked for.
         *
         * <p>Zero, because zero says something: {@code RepositoryContext} carries no history at
         * all for it, which is how a user turns the commit list off without turning off the branch
         * and status lines beside it. Only a negative count means nothing, and that is what this
         * floor refuses.</p>
         */
        private static final int MIN_COMMIT_HISTORY = 0;

        /** Shortest wait between one automatic commit and the next. */
        private static final int MIN_AUTO_COMMIT_INTERVAL_MINUTES = 1;

        private boolean enabled              = true;
        private boolean includeBranches      = false;
        private boolean includeCommitHistory = false;
        private int     maxCommitHistory     = 10;

        private boolean autoCommitEnabled     = false;
        private String  commitMessageTemplate = "Auto-commit on {date}";
        private String  commitTrigger         = "onChange"; // Possible values: onChange, onInterval

        /** How often the "onInterval" trigger commits, in minutes. */
        private int     autoCommitIntervalMinutes = 60;

        // Getters and setters
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isIncludeBranches() {
            return includeBranches;
        }

        public void setIncludeBranches(boolean includeBranches) {
            this.includeBranches = includeBranches;
        }

        public boolean isIncludeCommitHistory() {
            return includeCommitHistory;
        }

        public void setIncludeCommitHistory(boolean includeCommitHistory) {
            this.includeCommitHistory = includeCommitHistory;
        }

        public int getMaxCommitHistory() {
            return maxCommitHistory;
        }

        /** @param maxCommitHistory how many commits go into the context; {@code 0} for none */
        public void setMaxCommitHistory(int maxCommitHistory) {
            this.maxCommitHistory = atLeast(maxCommitHistory, MIN_COMMIT_HISTORY);
        }

        public boolean isAutoCommitEnabled() {
            return autoCommitEnabled;
        }

        public void setAutoCommitEnabled(boolean autoCommitEnabled) {
            this.autoCommitEnabled = autoCommitEnabled;
        }

        public String getCommitMessageTemplate() {
            return commitMessageTemplate;
        }

        public void setCommitMessageTemplate(String commitMessageTemplate) {
            this.commitMessageTemplate = commitMessageTemplate;
        }

        public String getCommitTrigger() {
            return commitTrigger;
        }

        public void setCommitTrigger(String commitTrigger) {
            this.commitTrigger = commitTrigger;
        }

        public int getAutoCommitIntervalMinutes() {
            return autoCommitIntervalMinutes;
        }

        /**
         * @param autoCommitIntervalMinutes how often the interval trigger commits; at least one
         *                                  minute, since an interval of zero is a commit attempt
         *                                  with no wait between one and the next
         */
        public void setAutoCommitIntervalMinutes(int autoCommitIntervalMinutes) {
            this.autoCommitIntervalMinutes = atLeast(autoCommitIntervalMinutes, MIN_AUTO_COMMIT_INTERVAL_MINUTES);
        }
    }

    public static class UiConfig {
        /** The theme a new configuration uses, and the one {@code theme reset} goes back to. */
        public static final String DEFAULT_COLOR_THEME = "modern";

        private boolean colorEnabled       = true;
        private String  colorTheme         = DEFAULT_COLOR_THEME;
        private int     verbosityLevel     = VERBOSITY.NORMAL.ordinal();
        private boolean interactivePrompts = true;

        /**
         * Whether the console echoes the full output of commands the AI runs.
         *
         * <p>Off by default: an agentic turn runs many commands and printing every one's raw output
         * buries the reasoning and the result. The record of what ran, in what order, and whether it
         * worked is always shown. This affects the console only -- the output still reaches the model
         * and the logs either way.</p>
         */
        private boolean showCommandOutput = false;

        // Getters and setters
        public boolean isShowCommandOutput() {
            return showCommandOutput;
        }

        public void setShowCommandOutput(boolean showCommandOutput) {
            this.showCommandOutput = showCommandOutput;
        }

        public boolean isColorEnabled() {
            return colorEnabled;
        }

        public void setColorEnabled(boolean colorEnabled) {
            this.colorEnabled = colorEnabled;
        }

        public String getColorTheme() {
            return colorTheme;
        }

        public void setColorTheme(String colorTheme) {
            this.colorTheme = colorTheme;
        }

        public int getVerbosityLevel() {
            return verbosityLevel;
        }

        /**
         * @param verbosityLevel one of {@link VERBOSITY}'s three levels. A number above the last of
         *                       them does not make the tool louder, it makes it inconsistent: the
         *                       readers that compare ordinals read it as verbose and the ones that
         *                       compare for equality read it as neither.
         */
        public void setVerbosityLevel(int verbosityLevel) {
            this.verbosityLevel = within(verbosityLevel, 0, VERBOSITY.values().length - 1);
        }

        public boolean isInteractivePrompts() {
            return interactivePrompts;
        }

        public void setInteractivePrompts(boolean interactivePrompts) {
            this.interactivePrompts = interactivePrompts;
        }

        public enum VERBOSITY {
            MINIMAL,
            NORMAL,
            VERBOSE
        }
    }

    /**
     * How much of the tool's work happens at once.
     *
     * <p>The only thing this tool does in parallel is run workers, each a full agentic loop, so this
     * is about workers and says so. The binding constraint is the provider rather than the machine:
     * concurrent workers share one account, one rate limit and one retry budget, which is why the
     * default is small and the ceiling is {@code 8}.</p>
     */
    public static class PerformanceConfig {

        /** Workers run at once out of the box. */
        public static final int DEFAULT_THREADS = 3;

        /** Workers allowed to run at once; see {@link #DEFAULT_THREADS}. */
        private int     threads            = DEFAULT_THREADS;
        /** Whether workers run alongside each other at all; false means one at a time. */
        private boolean parallelProcessing = true;

        // Getters and setters
        public int getThreads() {
            return threads;
        }

        /**
         * @param threads workers to run at once, between one and
         *                {@link com.eonmux.cadetcoder.agents.WorkerPool#MAX_CONCURRENCY}; the
         *                ceiling is the provider's rate limit rather than the machine's
         */
        public void setThreads(int threads) {
            this.threads = within(threads, 1, com.eonmux.cadetcoder.agents.WorkerPool.MAX_CONCURRENCY);
        }

        public boolean isParallelProcessing() {
            return parallelProcessing;
        }

        public void setParallelProcessing(boolean parallelProcessing) {
            this.parallelProcessing = parallelProcessing;
        }
    }

    public static class SecurityConfig {

        /** Shipped ceiling on write content, in megabytes. */
        public static final int DEFAULT_MAX_FILE_CONTENT_MB = 10;

        private boolean  allowRemoteExecution = false;
        /**
         * Shell programs a run in sandbox mode may start; empty means the shipped list.
         *
         * <p>This is about {@code bash}: the first word of a command line, matched against the
         * programs sandbox mode is willing to let run. It had two readers that meant different
         * things by it -- one matched it against shell programs and one against the tool's own
         * action names -- so a user who narrowed it to restrict the shell also stopped the agent
         * reading and writing files, while sandbox mode went on consulting a list written in the
         * source.</p>
         */
        private String[] allowedCommands      = {};
        /**
         * Actions a model may ask for; empty means all of them.
         *
         * <p>The tool's own vocabulary -- {@code read}, {@code write}, {@code bash} and the rest --
         * rather than shell programs. Separate from {@link #allowedCommands} because they are
         * different namespaces and one list cannot be both without one of the two readers being
         * wrong about what the user meant.</p>
         */
        private String[] allowedActions       = {};
        private boolean  sandboxMode          = false;
        private boolean  readOnlyMode         = false;
        private boolean  requireConfirmation  = true;
        /**
         * Whether file commands may reach paths outside the working directory.
         *
         * <p>Off by default, so a model working in one project cannot read or change files in
         * another one, or in the user's home directory, unless the user turns this on.</p>
         */
        private boolean  allowOutsideProject  = false;
        /**
         * Who answers when a shell command needs approval: {@code manual} or {@code auto}.
         *
         * <p>{@code manual}, the default, puts the question to the person at the terminal, so no
         * command runs on the model's word alone until the user chooses that. {@code auto} puts
         * it to the model instead. That suits a long agentic run whose commands are routine, and
         * it is the only mode in which a worker can run a command that needs approval, because a
         * worker's question reaches no person. Neither mode can approve what the static screens
         * refuse outright -- see
         * {@link com.eonmux.cadetcoder.security.SecurityValidator.CommandScreening}.</p>
         */
        private String   commandApproval      = "manual";
        /** Largest file content a write may carry, in megabytes. */
        private int      maxFileContentSize   = DEFAULT_MAX_FILE_CONTENT_MB;

        // Getters and setters
        public boolean isAllowRemoteExecution() {
            return allowRemoteExecution;
        }

        public void setAllowRemoteExecution(boolean allowRemoteExecution) {
            this.allowRemoteExecution = allowRemoteExecution;
        }

        public String[] getAllowedCommands() {
            return allowedCommands.clone();
        }

        public void setAllowedCommands(String[] allowedCommands) {
            this.allowedCommands = sanitizedList(allowedCommands);
        }

        public String[] getAllowedActions() {
            return allowedActions.clone();
        }

        public void setAllowedActions(String[] allowedActions) {
            this.allowedActions = sanitizedList(allowedActions);
        }

        public boolean isSandboxMode() {
            return sandboxMode;
        }

        public void setSandboxMode(boolean sandboxMode) {
            this.sandboxMode = sandboxMode;
        }

        public boolean isReadOnlyMode() {
            return readOnlyMode;
        }

        public void setReadOnlyMode(boolean readOnlyMode) {
            this.readOnlyMode = readOnlyMode;
        }

        public boolean isRequireConfirmation() {
            return requireConfirmation;
        }

        public void setRequireConfirmation(boolean requireConfirmation) {
            this.requireConfirmation = requireConfirmation;
        }

        public boolean isAllowOutsideProject() {
            return allowOutsideProject;
        }

        public void setAllowOutsideProject(boolean allowOutsideProject) {
            this.allowOutsideProject = allowOutsideProject;
        }

        /** @return who answers when a shell command needs approval: {@code auto} or {@code manual} */
        public String getCommandApproval() {
            return commandApproval;
        }

        public void setCommandApproval(String commandApproval) {
            // Blank is the setting cleared rather than a third mode, so it means the default.
            this.commandApproval = commandApproval == null || commandApproval.isBlank()
                                   ? "manual" : commandApproval.trim().toLowerCase();
        }

        public int getMaxFileContentSize() {
            return maxFileContentSize;
        }

        public void setMaxFileContentSize(int maxFileContentSize) {
            this.maxFileContentSize = maxFileContentSize;
        }

        /**
         * The same limit in bytes, which is the unit every caller actually compares against.
         *
         * <p>Two callers computed this themselves and disagreed. {@code maxSizeMB * 1024 * 1024}
         * in {@code int} arithmetic overflows at 2048: the limit becomes negative and every write
         * is refused with a message naming a 2048MB limit the user has just raised. A configured
         * value of zero or less is meaningless as a limit and used to have the same effect, so it
         * falls back to the shipped default rather than to "refuse everything" or, worse, to "no
         * limit at all".</p>
         *
         * @return the maximum content size in bytes, always positive
         */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public long getMaxFileContentBytes() {
            int megabytes = maxFileContentSize > 0 ? maxFileContentSize : DEFAULT_MAX_FILE_CONTENT_MB;
            return (long) megabytes * 1024L * 1024L;
        }
    }

    /**
     * When a long run's prompt is folded down to make room to continue.
     *
     * <p>Compaction rewrites the head of the prompt, which discards the provider-side cache the
     * append-only design earns — measured at a 90-96% shared prefix per turn. It is therefore
     * threshold-driven and rare rather than eager: the alternative is worse, because an over-length
     * prompt is a terminal 400 that loses the whole task.</p>
     */
    public static class CompactionConfig {

        /**
         * The narrowest and widest share of the budget a threshold can usefully name.
         *
         * <p>Below the floor a run would fold its transcript on its first turn and every turn
         * after it, paying the cache cost each time; above the ceiling there is no share of the
         * budget left to describe.</p>
         */
        private static final double MIN_SHARE = 0.05;
        private static final double MAX_SHARE = 1.0;

        private boolean enabled          = true;
        private double  trigger          = 0.80;
        private double  target           = 0.45;
        private int     keepHeadEntries  = 6;
        private int     keepTailEntries  = 9;

        /** @return whether a run may fold its transcript to stay inside the window */
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** @return the share of the input budget at which compaction happens */
        public double getTrigger() {
            return trigger;
        }

        /** @param trigger the share of the input budget at which a run folds its transcript */
        public void setTrigger(double trigger) {
            this.trigger = within(trigger, MIN_SHARE, MAX_SHARE);
        }

        /**
         * @return the share of the input budget compaction aims to get back down to; well below
         *         {@link #getTrigger()} so a run does not compact again a turn later
         */
        public double getTarget() {
            return target;
        }

        /** @param target the share of the input budget a fold aims to get back down to */
        public void setTarget(double target) {
            this.target = within(target, MIN_SHARE, MAX_SHARE);
        }

        /**
         * @return transcript entries kept verbatim at the front; caching matches a LEADING prefix,
         *         so keeping a head is what stops a compaction from discarding the cache entirely
         */
        public int getKeepHeadEntries() {
            return keepHeadEntries;
        }

        /** @param keepHeadEntries entries kept verbatim at the front; none is a valid answer */
        public void setKeepHeadEntries(int keepHeadEntries) {
            this.keepHeadEntries = atLeast(keepHeadEntries, 0);
        }

        /** @return transcript entries kept verbatim at the end, where the useful detail is */
        public int getKeepTailEntries() {
            return keepTailEntries;
        }

        /** @param keepTailEntries entries kept verbatim at the end, where the useful detail is */
        public void setKeepTailEntries(int keepTailEntries) {
            this.keepTailEntries = atLeast(keepTailEntries, 0);
        }
    }

    public static class LoggingConfig {

        /** Where the log is written when nobody has said otherwise, relative to the base directory. */
        private static final String DEFAULT_LOG_FILE = "logs/cadet.log";

        /** Fewest rotation slots that leave somewhere to write, and the smallest usable slot. */
        private static final int MIN_LOG_FILES   = 1;
        private static final int MIN_LOG_SIZE_MB = 1;

        private String  level                 = "INFO"; // DEBUG, INFO, WARN, ERROR
        private String  logFile               = DEFAULT_LOG_FILE; // Relative to base directory
        private boolean consoleLoggingEnabled = true;
        private int     maxLogFiles           = 5;
        private int     maxLogSize            = 10; // MB
        private boolean debugEnabled          = false; // Enable detailed debug logging
        private boolean sessionLoggingEnabled = true; // Enable per-session logging
        private boolean structuredLogging     = true; // Enable JSON structured logging
        private int     maxSessionLogSize     = 50; // MB per session log
        private int     maxSessionLogs        = 20; // Max number of session logs to keep

        // Getters and setters
        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }

        public String getLogFile() {
            return logFile;
        }

        /**
         * @param logFile where the log is written; a leading {@code ~} means the home directory,
         *                and blank means the default location. Never null: {@code ConfigManager}
         *                resolves this path while creating the directory tree, before anything has
         *                a chance to report a problem, so a null here ended every invocation.
         */
        public void setLogFile(String logFile) {
            this.logFile = pathOrShipped(logFile, DEFAULT_LOG_FILE);
        }

        public boolean isConsoleLoggingEnabled() {
            return consoleLoggingEnabled;
        }

        public void setConsoleLoggingEnabled(boolean consoleLoggingEnabled) {
            this.consoleLoggingEnabled = consoleLoggingEnabled;
        }

        public int getMaxLogFiles() {
            return maxLogFiles;
        }

        /** @param maxLogFiles rotation slots to keep; at least one, or there is nowhere to write */
        public void setMaxLogFiles(int maxLogFiles) {
            this.maxLogFiles = atLeast(maxLogFiles, MIN_LOG_FILES);
        }

        public int getMaxLogSize() {
            return maxLogSize;
        }

        /**
         * @param maxLogSize megabytes one rotation slot may reach; at least one, since a slot with
         *                   no room in it is full the moment it is opened and rotates for ever
         */
        public void setMaxLogSize(int maxLogSize) {
            this.maxLogSize = atLeast(maxLogSize, MIN_LOG_SIZE_MB);
        }

        public boolean isDebugEnabled() {
            return debugEnabled;
        }

        public void setDebugEnabled(boolean debugEnabled) {
            this.debugEnabled = debugEnabled;
        }

        public boolean isSessionLoggingEnabled() {
            return sessionLoggingEnabled;
        }

        public void setSessionLoggingEnabled(boolean sessionLoggingEnabled) {
            this.sessionLoggingEnabled = sessionLoggingEnabled;
        }

        public boolean isStructuredLogging() {
            return structuredLogging;
        }

        public void setStructuredLogging(boolean structuredLogging) {
            this.structuredLogging = structuredLogging;
        }

        public int getMaxSessionLogSize() {
            return maxSessionLogSize;
        }

        public void setMaxSessionLogSize(int maxSessionLogSize) {
            this.maxSessionLogSize = maxSessionLogSize;
        }

        public int getMaxSessionLogs() {
            return maxSessionLogs;
        }

        public void setMaxSessionLogs(int maxSessionLogs) {
            this.maxSessionLogs = maxSessionLogs;
        }
    }
}
