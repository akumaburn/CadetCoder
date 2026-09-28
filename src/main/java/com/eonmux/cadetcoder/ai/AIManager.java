package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.metrics.RequestMetrics;
import com.eonmux.cadetcoder.ai.metrics.ReportedUsage;
import com.eonmux.cadetcoder.ai.metrics.RequestMetricsRecorder;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.error.ErrorHandler;
import com.eonmux.cadetcoder.logging.DebugLogger;
import com.eonmux.cadetcoder.ui.CollapsedOutput;
import com.eonmux.cadetcoder.net.LLMEmptyResponseException;
import com.eonmux.cadetcoder.commands.LoopPass;
import com.eonmux.cadetcoder.net.LLMException;
import com.eonmux.cadetcoder.session.SessionManager;

import java.util.*;

/**
 * Manages AI clients and handles fallback between local and API clients.
 */
public class AIManager {
    private static AIManager       instance;
    private static AIClientFactory clientFactory = new DefaultAIClientFactory();

    private LocalAIClient localClient;
    private APIClient     apiClient;
    private AIClient      activeClient;
    private boolean       initialized = false;

    private AIManager() {
        // Clients will be lazily initialized when first needed
    }

    /**
     * Sets the AI client factory for testing purposes.
     * This method is package-private to limit access.
     */
    static synchronized void setClientFactory(AIClientFactory factory) {
        clientFactory = factory;
        // Reset instance to force re-initialization with new factory
        instance = null;
    }

    /**
     * Retrieves the singleton instance of AIManager.
     *
     * @return the AIManager instance
     */
    public static synchronized AIManager getInstance() {
        if (instance == null) {
            instance = new AIManager();
        }
        return instance;
    }

    /**
     * Prints the per-request metrics line.
     *
     * <p>Shown by default because it is the only feedback a user gets about what a request cost and
     * how long it took -- there is no streaming, so the terminal is otherwise silent for the whole
     * duration of a call. Suppress it with {@code -Dcadet.metrics.show=false} for scripted runs whose
     * output is parsed.</p>
     *
     * @param requestMetrics the completed request's figures
     * @param recorder       the recorder, consulted for the session-wide rate
     */
    private static void reportMetrics(RequestMetrics requestMetrics, RequestMetricsRecorder recorder) {
        if (!Boolean.parseBoolean(System.getProperty("cadet.metrics.show", "true"))) {
            return;
        }
        try {
            // Hidden with the rest of a turn's bookkeeping in the shell: what a request cost is
            // worth having and is not what the run is doing, and one of these lines per iteration
            // pushed the work itself off the screen. A focused result carries it.
            CollapsedOutput.hiding(() -> OutputFormatter.printInfo(requestMetrics.toSummaryLine(
                    recorder.windowTokensPerSecond(), recorder.windowSeconds())));
        } catch (RuntimeException e) {
            // Reporting is strictly informational. A formatting or output-configuration problem must
            // never turn a completed request into a failed one, so it is logged and swallowed.
            DebugLogger.getInstance().debug("AIManager", "Could not print request metrics: " + e);
        }
    }

    /**
     * Completes a prompt using the active AI client with default parameters.
     *
     * @param promptData The prompt to complete
     * @return The AI model's response
     */
    public String complete(PromptData promptData) {
        return complete(promptData, new HashMap<>());
    }

    /**
     * Completes a prompt using the active AI client with lightweight validation.
     *
     * <p>Returns only what the model actually said. Any failure of the underlying call — transport,
     * HTTP status, unusable payload, or a run of empty completions — is raised as an
     * {@link LLMException} instead of being handed back as ordinary response text. Callers can
     * therefore tell "the model replied" from "the call failed"; before this, a failed provider call
     * was returned as a string such as {@code "Error: Request failed with status 403"}, was parsed as
     * if it were a completion, and let the command report success and exit 0.</p>
     *
     * @param promptData the prompt to complete
     * @param parameters additional parameters for the model
     * @return the AI model's response, never null and never blank
     * @throws IllegalStateException when no AI client is available at all
     * @throws LLMException          when the completion call failed
     */
    public String complete(PromptData promptData, Map<String, Object> parameters) {
        return completeMeasured(promptData, parameters).text();
    }

    /**
     * Completes a prompt and hands back what saying it cost along with what was said.
     *
     * <p>The same call as {@link #complete(PromptData, Map)} -- the retries, the manual offer and
     * the typed failures are all identical -- differing only in that the request's figures survive
     * it. They were built and printed here already; returning them costs nothing and spares every
     * caller that has to know what a run has spent from re-counting the characters it sent.</p>
     *
     * @param promptData the prompt to complete
     * @param parameters additional parameters for the model
     * @return what the model said and what saying it cost
     * @throws IllegalStateException when no AI client is available at all
     * @throws LLMException          when the completion call failed
     */
    public Completion completeMeasured(PromptData promptData, Map<String, Object> parameters) {
        return completeMeasured(null, promptData, parameters);
    }

    /**
     * Completes a prompt through a model of the caller's choosing rather than the active one.
     *
     * <p>The only thing this changes is who answers. The retries, the manual offer, the composed
     * system prompt, the measurement and the typed failures are the ones every other request gets:
     * a second model reached through a path of its own would be a second policy, and the first time
     * the two disagreed the run would be unaccountable for what it had actually spent.</p>
     *
     * <p>A request that names its own client does not need this tool to be connected to anything,
     * and deliberately does not fall back to the active model when the named one fails. A run that
     * handed its work to a stronger reasoner and was quietly answered by the weaker one it had
     * already given up on would report an escalation that never happened.</p>
     *
     * @param client     what to ask; {@code null} asks whichever model this tool is connected to
     * @param promptData the prompt to complete
     * @param parameters additional parameters for the model
     * @return what the model said and what saying it cost
     * @throws IllegalStateException when no client is named and none is available
     * @throws LLMException          when the completion call failed
     */
    public Completion completeMeasured(AIClient client, PromptData promptData,
                                       Map<String, Object> parameters) {
        OutageWait outage       = OutageWait.fromSystemProperties();
        Long       firstFailure = null;
        while (true) {
            try {
                return attemptCompletion(client, promptData, parameters);
            } catch (LLMException failure) {
                // The backend has already exhausted its automatic retries. Rather than ending the
                // run there, offer the person at the terminal one more go: a provider that was rate
                // limiting or briefly unreachable is very often fine a few seconds later, and the
                // alternative is losing the whole task and retyping the request. A loop is not
                // watched, so it waits out an outage by itself and never asks.
                if (LoopPass.isInAPass()) {
                    // Timed from the first failure, so the attempts count against the budget as
                    // well as the waits between them.
                    firstFailure = firstFailure != null ? firstFailure : System.nanoTime();
                    long outageSoFar = (System.nanoTime() - firstFailure) / 1_000_000L;
                    if (outage.waitOut(failure, outageSoFar) == OutageWait.GIVE_UP) {
                        throw failure;
                    }
                } else if (!ManualRetry.offer(failure)) {
                    throw failure;
                }
            }
        }
    }

    /**
     * Completes a prompt with default parameters and hands back what saying it cost.
     *
     * @param promptData the prompt to complete
     * @return what the model said and what saying it cost
     */
    public Completion completeMeasured(PromptData promptData) {
        return completeMeasured(promptData, new HashMap<>());
    }

    /**
     * Completes a prompt through a named model with default parameters.
     *
     * @param client     what to ask; {@code null} asks whichever model this tool is connected to
     * @param promptData the prompt to complete
     * @return what the model said and what saying it cost
     */
    public Completion completeMeasured(AIClient client, PromptData promptData) {
        return completeMeasured(client, promptData, new HashMap<>());
    }

    /**
     * One full completion attempt, including the backend's own retry policy.
     *
     * @param chosen     the model to ask, or {@code null} to ask the active one
     * @param promptData the prompt to complete
     * @param parameters additional parameters for the model
     * @return the model's response, with the figures the request produced
     */
    private Completion attemptCompletion(AIClient chosen, PromptData promptData,
                                         Map<String, Object> parameters) {
        // Only a request that has not named its own model needs this tool to have chosen one, and
        // choosing one probes endpoints. A run that brought a model of its own would otherwise pay
        // for -- and report -- a provider search whose answer it was never going to use.
        AIClient asked = chosen != null ? chosen : activeClient();
        if (asked == null) {
            // Worded so it still reads correctly after a caller prefixes it -- ChatCommand reports
            // this as "Error analyzing request: <message>", and "No AI clients available" made that
            // sentence name an internal collection the reader has no way to act on. This is the
            // single report of an absent provider: the remedy is here, stated once, rather than
            // announced again during initialisation and once more by every caller.
            String prefix = com.eonmux.cadetcoder.ui.OutputRouter.getInstance().commandPrefix();
            throw new IllegalStateException(
                    "no AI provider is reachable; run '" + prefix + "login' to connect one,"
                    + " or '" + prefix + "models select' to pick a model"
                    + " ('--debug' records what each endpoint answered)");
        }

        final int maxAttempts   = 2; // Reduced from 3 to 2
        String    response      = "";
        Exception lastException = null;
        // A legacy backend's "Error: ..." sentinel from the final attempt, kept so the failure tail
        // can report the real cause instead of a generic empty-response error.
        String    lastBackendErrorText = null;
        // How much of the wall clock went on pauses between attempts, reported separately from the
        // attempt that answered rather than folded into it.
        long      waitedMillis         = 0;

        // Lead the system prompt with the operating principles. Composed here, at the one point all
        // callers funnel through, rather than in each command: six commands send no system prompt of
        // their own at all, and a command added later would otherwise have to remember to include it.
        PromptData request = promptData.withSystemPrompt(
                SystemPromptProvider.compose(promptData.getSystemPrompt()));

        // And attach whatever the turn brought with it, here for the same reason: an image dropped
        // on the prompt has to reach the request built by whichever command the line named, and no
        // command composes one of its own. See PromptAttachments for why the first request of the
        // turn is the only one that carries it.
        if (!request.hasImages() && !PromptAttachments.pending().isEmpty()) {
            request = request.withImages(PromptAttachments.pending());
        }
        // Whether those images will actually reach the model, asked of the client rather than
        // assumed from having attached them. A wire with nowhere to put a picture, or a model that
        // does not read one, drops them inside the client -- and the turn was still recorded as
        // having delivered its attachment, so the one request that was going to carry it had gone
        // and nothing was left to send. See PromptAttachments, whose contract is "once delivered,
        // not once read", and ImageChannel, which makes the decision this asks about.
        final boolean carriesAttachment = request.hasImages() && asked.deliversImages(request);

        // And fill in what the caller did not say from what the user configured, here for the same
        // reason: almost every caller passes an empty map, and a backend answering an empty map with
        // a literal of its own is how three documented settings came to change nothing.
        Map<String, Object> settings = RequestSettings.filledIn(parameters);

        // Measure every request here, at the one point all callers funnel through, so the chat loop,
        // the agent loop and every one-shot command are instrumented identically. Measuring the
        // composed prompt, not the caller's, keeps the cached/new split describing what is sent.
        RequestMetricsRecorder metrics = RequestMetricsRecorder.getInstance();

        // Emptied once for the request, not once per attempt. Every attempt of this request is
        // billed to it -- a reply the provider generated and this layer then refused was paid for
        // just the same -- and clearing between them credited the request with the last attempt
        // alone. See ReportedUsage, which now adds each attempt to what is already there.
        ReportedUsage.clear();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // Started per attempt, so what is reported is how long the call that answered took.
            // Started once outside the loop, it also covered every attempt that did not answer and
            // every pause between them: a 1.2s call that succeeded on the fourth try was reported
            // as an eleven-second one, which is the number a user reads to decide whether a model
            // is slow. What the retries cost in wall clock is said separately, below.
            RequestMetricsRecorder.InFlight inFlight =
                    metrics.begin(request.getSystemPrompt(), request.getUserPrompt());
            try {
                // Strip any chat-template control tokens the model echoed (e.g. <|im_start|>assistant
                // ... <|im_end|>, [INST], <s>). Done here, at the single point every caller funnels
                // through, so the parsing engine, the chat/agent harness, and the session history all
                // see clean text instead of executing a token-wrapped blob as a bogus command.
                response = ResponseSanitizer.sanitize(asked.complete(request, settings));

                // A failure reported by a legacy backend that RETURNS "Error: ..." rather than
                // throwing. Anchored to that sentinel: a completion's text is arbitrary, and
                // searching it for failure wording discarded a correct answer about timeouts and
                // silently paid for it twice.
                //
                // On the last attempt this must NOT fall through to the "response is non-empty"
                // branch below. That branch returned the sentinel as the model's completion -- and
                // it already knew better, since it refuses to record the same text in the
                // conversation history precisely because the model never produced it. The caller
                // then parsed "Error: Request failed with status 403" as an answer. It now leaves
                // on the typed failure channel with everything else that failed.
                if (isErrorText(response)) {
                    metrics.abandon(inFlight);
                    if (attempt < maxAttempts) {
                        OutputFormatter.printWarning(
                                (response.contains("timed out") ? "Request timed out" : "Request failed")
                                + ", retrying...");
                        waitedMillis += pauseBeforeNextAttempt(attempt);
                        continue;
                    }
                    lastBackendErrorText = response;
                    break;
                }

                // Lightweight validation - just check if response is not null and not empty
                if (response != null && !response.trim().isEmpty()) {
                    // Only genuine model output belongs in the conversation history. Recording a
                    // backend error string here polluted the context of every following turn with
                    // text the model never produced.
                    if (!isErrorText(response)) {
                        // Marked as the model's turn so a restored conversation reads as one. The
                        // user's half is recorded by the command that knows the actual request; at
                        // this layer the "user prompt" is the whole rendered transcript.
                        SessionManager.getInstance().addToConversationHistory("AI: " + response);
                    }
                    // Provider-reported counts win over the estimate whenever the backend
                    // parsed a usage block; see TokenUsage for why the arithmetic differs per
                    // provider, and RequestMetrics#isEstimated for how the two are told apart.
                    final String completion = response;
                    RequestMetrics measured =
                            ReportedUsage.take()
                                    .map(usage -> metrics.completeWithUsage(
                                            inFlight, usage.cachedInputTokens(),
                                            usage.newInputTokens(), usage.outputTokens()))
                                    .orElseGet(() -> metrics.complete(inFlight, completion));
                    reportMetrics(measured, metrics);
                    reportWhatTheRetriesCost(attempt, waitedMillis);
                    if (carriesAttachment) {
                        // The model has now seen them, and the conversation carries what it said
                        // about them from here on.
                        PromptAttachments.delivered();
                    }
                    return Completion.of(response, measured);
                } else {
                    metrics.abandon(inFlight);
                    if (attempt < maxAttempts) {
                        OutputFormatter.printWarning("Empty response received, retrying...");
                        waitedMillis += pauseBeforeNextAttempt(attempt);
                        continue;
                    }
                }
            } catch (InterruptedException ie) {
                metrics.abandon(inFlight);
                throw interrupted(ie, asked);
            } catch (LLMException le) {
                // The backend already applied its own retry policy (honouring Retry-After and
                // refusing to repeat 400/401/403), so retrying here would only multiply the delay
                // before the user sees the real cause. The message is already actionable and the
                // caller reports it, so the stack trace goes to the debug log rather than the
                // terminal, and ErrorHandler's generic "recovery" is skipped.
                DebugLogger.getInstance().error("AIManager", "AI completion failed: " + le.getMessage(), le);
                metrics.abandon(inFlight);
                throw le;
            } catch (Exception e) {
                lastException = e;
                metrics.abandon(inFlight);
                if (attempt < maxAttempts) {
                    OutputFormatter.printWarning("Request failed: " + e.getMessage() + ", retrying...");
                    try {
                        waitedMillis += pauseBeforeNextAttempt(attempt);
                    } catch (InterruptedException ie) {
                        throw interrupted(ie, asked);
                    }
                } else {
                    OutputFormatter.printError("Request failed: " + e.getMessage());
                }
            }
        }

        // Nothing is left in flight by the time the loop ends: each attempt ends its own handle,
        // on every way out. The handle is what the elapsed-time counter in the shell reads, and one
        // left behind is a request the user watches tick for the rest of the session.

        // Previously this returned the seed value of `response` — an empty string, which is
        // non-null, so the intended "AI completion failed" fallback below was unreachable and a
        // total failure looked like a successful blank answer. Both outcomes now leave on the
        // typed failure channel.
        String client = asked.getModelName();
        if (lastBackendErrorText != null) {
            throw new LLMException(LLMException.Kind.UNKNOWN,
                                   "AI completion failed after " + maxAttempts + " attempts: "
                                           + lastBackendErrorText,
                                   client, null, null, 0, null, null, null);
        }
        if (lastException != null) {
            ErrorHandler.getInstance().handleException(lastException);
            throw new LLMException(LLMException.Kind.UNKNOWN,
                                   "AI completion failed after " + maxAttempts + " attempts: "
                                           + lastException.getMessage(),
                                   client, null, null, 0, null, null, lastException);
        }
        throw new LLMEmptyResponseException(client, null, maxAttempts);
    }

    /**
     * Pause before the next attempt at this layer.
     *
     * <p>Configurable rather than hardcoded for two reasons. A fixed second per attempt is wrong for
     * a local model that answers in milliseconds, and it is wrong for a test, which pays it in wall
     * clock to observe a retry it already knows will happen. {@code 0} disables the pause.</p>
     *
     * @param attempt 1-based number of the attempt that just failed
     * @return milliseconds to wait
     */
    static long retryBackoffMillis(int attempt) {
        long base = DEFAULT_RETRY_BACKOFF_MS;
        String configured = System.getProperty(RETRY_BACKOFF_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            try {
                long value = Long.parseLong(configured.trim());
                if (value >= 0) {
                    base = value;
                }
            } catch (NumberFormatException ignored) {
                // An unparseable override falls back to the default rather than failing the run.
            }
        }
        return base * Math.max(1, attempt);
    }

    /**
     * Waits before the next attempt at this layer.
     *
     * @param attempt 1-based number of the attempt that just failed
     * @return how long was waited, so the caller can report it apart from the attempt that answered
     * @throws InterruptedException when the caller is interrupted while waiting
     */
    private static long pauseBeforeNextAttempt(int attempt) throws InterruptedException {
        long delay = retryBackoffMillis(attempt);
        if (delay > 0) {
            Thread.sleep(delay);
        }
        return delay;
    }

    /**
     * Says what the attempts that did not answer cost in wall clock.
     *
     * <h2>Why it is a separate line</h2>
     *
     * <p>The figures above it describe one call: the one that produced the text being returned.
     * Folding the failed attempts and the pauses between them into that duration made a fast model
     * look slow on exactly the requests where something had already gone wrong, and there was no
     * way to tell the two apart afterwards. Said separately, the duration stays a duration and the
     * retries stay visible.</p>
     *
     * @param attempt      the attempt that answered, 1-based
     * @param waitedMillis how long was spent pausing between attempts
     */
    private static void reportWhatTheRetriesCost(int attempt, long waitedMillis) {
        if (attempt <= 1 || !Boolean.parseBoolean(System.getProperty("cadet.metrics.show", "true"))) {
            return;
        }
        int earlier = attempt - 1;
        String line = "Answered on attempt " + attempt + "; the " + earlier + " attempt"
                      + (earlier == 1 ? "" : "s") + " before it and "
                      + RequestMetrics.formatDuration(waitedMillis)
                      + " of waiting are not in the time above.";
        try {
            CollapsedOutput.hiding(() -> OutputFormatter.printInfo(line));
        } catch (RuntimeException e) {
            // Reporting is strictly informational; see reportMetrics.
            DebugLogger.getInstance().debug("AIManager", "Could not print the retry note: " + e);
        }
    }

    /** System property scaling the pause between attempts at this layer; {@code 0} removes it. */
    public static final String RETRY_BACKOFF_PROPERTY = "cadet.ai.retryBackoffMs";

    /** Default pause, multiplied by the attempt number. */
    public static final long DEFAULT_RETRY_BACKOFF_MS = 1000L;

    /**
     * Whether a completion is one of the backends' legacy {@code "Error: ..."} sentinels rather than
     * model output. Such text is never returned as a completion and never recorded in the
     * conversation history.
     *
     * @param response what the backend answered
     * @return whether it is a failure report wearing a completion's clothes
     */
    private static boolean isErrorText(String response) {
        return response != null && response.trim().startsWith("Error:");
    }

    /**
     * The failure an interrupted request leaves with, having re-raised the interrupt.
     *
     * <h2>Why an interrupt is not a completion</h2>
     *
     * <p>Being told to stop is not something the model said. Handing back the sentence
     * {@code "AI completion interrupted."} let a caller parse a control-flow event as model output
     * -- the same defect the {@code "Error: ..."} sentinels had, with the added twist that the run
     * carried on afterwards as though it had been answered. It leaves on the typed failure channel
     * with everything else that produced no reply, and its kind is one no retry can help, so the
     * manual offer is never made for it.</p>
     *
     * <h2>Why the flag is set again first</h2>
     *
     * <p>Catching {@link InterruptedException} clears it. Without re-raising it, a thread that has
     * been asked to stop would block again on the next request or the next read from the terminal
     * with nothing left to say that it had been.</p>
     *
     * @param interruption what was caught
     * @param asked        the model the interrupted request was going to
     * @return the failure to throw
     */
    private LLMException interrupted(InterruptedException interruption, AIClient asked) {
        Thread.currentThread().interrupt();
        return LLMException.stopped(null, asked == null ? null : asked.getModelName(), null,
                                    interruption);
    }

    /**
     * The model this tool is connected to, choosing one if nothing has yet.
     *
     * <h2>Why the choice and the read are one operation</h2>
     *
     * <p>Choosing is what {@code models select} and {@code login} change, and they change it from
     * the shell thread while a worker is in the middle of a request. Reading the field outside the
     * lock that writes it let a worker see a client chosen before the selection and keep using it
     * for the rest of its run -- a request billed to, and answered by, a provider the user had
     * already switched away from, with nothing to say it had happened.</p>
     *
     * @return the active client, or {@code null} when no provider answered
     */
    private synchronized AIClient activeClient() {
        ensureInitialized();
        return activeClient;
    }

    /**
     * Lazily initializes AI clients and selects the active one.
     *
     * <p>Synchronized, like every other reader and writer of {@link #activeClient} and
     * {@link #initialized}: the two fields are one decision, and a selection that lands between
     * them leaves the tool connected to one provider and reporting another.</p>
     */
    private synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        try {
            // Prefer an explicitly configured remote connector. When one is configured and
            // reachable we use it directly and DO NOT construct the local/API clients, whose
            // constructors eagerly probe the local llama-server endpoint (e.g.
            // http://localhost:8012). That probing is the spurious ConnectException users saw
            // before the configured remote provider was finally selected.
            if (trySelectConnector()) {
                initialized = true;
                return;
            }

            // Legacy selection: construct the local and API clients (this probes their
            // endpoints) and pick whichever is available.
            this.localClient = clientFactory.createLocalAIClient();
            this.apiClient   = clientFactory.createAPIClient();
            selectActiveClient();
            warnIfRetentionCannotBeAskedFor();
            initialized = true;
        } catch (Exception e) {
            OutputFormatter.printError("Failed to initialize AI clients: " + e.getMessage());
            // Set initialized to true to prevent repeated initialization attempts
            initialized  = true;
            activeClient = null;
        }
    }

    /**
     * Attempts to select an explicitly configured opencode-style connector as the active client.
     *
     * @return {@code true} (with {@link #activeClient} set) when a configured provider is reachable,
     *         so the caller can skip constructing — and probing — the local/API clients entirely;
     *         {@code false} when no provider is configured or it is not ready.
     */
    /**
     * Says so when zero data retention is on and there is nothing that can be asked to honour it.
     *
     * <h2>Why this is worth a line</h2>
     *
     * <p>Zero data retention is a promise made by a provider, and asking for it is something only a
     * connector knows how to do -- a header on the ones that take a header, a body field on the ones
     * that take a field. The legacy client talks to whatever endpoint {@code ai.apiEndpoint} names
     * and has no such vocabulary, so on that path the setting is read by nothing. It defaults to on,
     * and {@code /config} reports it as on, which is precisely the situation in which a user believes
     * a promise is being made on their behalf and it is not.</p>
     */
    private void warnIfRetentionCannotBeAskedFor() {
        if (activeClient == null) {
            return;
        }
        try {
            Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
            if (ai == null || !ai.isZeroDataRetention()) {
                return;
            }
            if (ai.getProvider() != null && !ai.getProvider().isBlank()) {
                return;
            }
            OutputFormatter.printWarning(
                    "ai.zeroDataRetention is on, but no provider is configured, so nothing is being "
                    + "asked of this endpoint. Run 'login' or 'models select' to connect one that "
                    + "can be asked.");
        } catch (RuntimeException unreadable) {
            // Nothing to say about a configuration that cannot be read; the run has larger problems
            // and they are reported where the file is read.
        }
    }

    private boolean trySelectConnector() {
        Configuration.AiConfig aiConfig = ConfigManager.getInstance().getConfig().getAi();
        if (aiConfig == null || aiConfig.getProvider() == null || aiConfig.getProvider().isBlank()) {
            return false;
        }
        try {
            ConnectorAIClient connectorClient = ConnectorAIClient.fromConfig(aiConfig);
            if (connectorClient != null && connectorClient.isAvailable()) {
                activeClient = connectorClient;
                // Which model is answering is a fact worth stating once, but it is not a SUCCESS:
                // the tick is reserved for something having completed, so that a run's ticks can be
                // read as "these things worked".
                OutputFormatter.printInfo("Using connector: " + connectorClient.getModelName());
                return true;
            }
            if (connectorClient != null) {
                OutputFormatter.printWarning("Configured provider '" + aiConfig.getProvider()
                        + "' is not ready (missing credentials?); falling back to default selection.");
            }
        } catch (Exception e) {
            OutputFormatter.printWarning("Failed to initialize connector '" + aiConfig.getProvider()
                    + "': " + e.getMessage());
        }
        return false;
    }

    /**
     * Selects the active AI client based on configuration and availability.
     */
    private void selectActiveClient() {
        // The configured connector (if any) was already attempted in ensureInitialized();
        // this method only performs the legacy local/API selection.

        // Null safety checks
        if (localClient == null && apiClient == null) {
            OutputFormatter.printError("Both AI clients are null");
            activeClient = null;
            return;
        }

        // A reachable local model wins. It is the one the user chose to run, it costs nothing per
        // request, and when it is not running the API is tried immediately below. There is no
        // setting that reverses this order: the flag that used to stand here was derived from
        // whether an AI config object existed, which is to say it was always true, and the
        // "falling back to local" branch it guarded could never be reached.
        if (localClient != null && localClient.isAvailable()) {
            activeClient = localClient;
            OutputFormatter.printInfo("Using local AI model: " + localClient.getModelName());
            return;
        }

        // Try API client
        if (apiClient != null && apiClient.isAvailable()) {
            activeClient = apiClient;
            OutputFormatter.printInfo("Using API client: " + apiClient.getModelName());
            return;
        }

        // Nothing answered. Recorded, not announced: reaching here is not yet a failure the user
        // has run into. Initialisation also happens behind a plain question ("is a provider
        // available?"), and answering a question with an error line and a remedy is wrong twice
        // over -- nothing has been attempted, and when a request DOES follow, attemptCompletion
        // reports the same thing again, so one absent provider was announced twice in three lines.
        DebugLogger.getInstance().debug("AIManager",
                "No AI provider answered; the next request will report it.");
        activeClient = null;
    }


    /**
     * Gets the active AI client.
     *
     * @return The active AI client
     */
    public AIClient getActiveClient() {
        return activeClient();
    }

    /**
     * Checks if any AI client is available.
     *
     * @return true if at least one client is available, false otherwise
     */
    public boolean isAvailable() {
        return activeClient() != null;
    }

    /**
     * Resets the active AI client, allowing for re-selection based on current availability.
     *
     * <p>Synchronized because this is the write half of the race described on {@link #activeClient()}:
     * {@code models select} calls it from the shell thread while workers are reading the same two
     * fields, and an unsynchronized write need never become visible to them at all.</p>
     */
    public synchronized void resetActiveClient() {
        this.activeClient = null;
        this.initialized  = false;
    }
}
