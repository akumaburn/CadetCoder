package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.net.BoundedBody;
import com.eonmux.cadetcoder.net.OutboundAddress;
import com.eonmux.cadetcoder.net.PinnedConnection;
import com.eonmux.cadetcoder.prompts.TemplatePromptBuilder;
import com.eonmux.cadetcoder.ui.UnifiedOutput;
import com.eonmux.cadetcoder.util.URLUtils;
import com.eonmux.cadetcoder.ui.InteractivePrompts;
import picocli.CommandLine.*;

import java.io.InputStream;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.Callable;

@Command (name = "webfetch", description = "Fetch a web page and answer a question about it")
public class WebFetchCommand extends LoggingCommandSupport implements IterativeCommand, CommandRegistry.InterruptibleCommand, Callable<Integer> {

    // Hard cap on the response body read into memory, counted in bytes taken from the socket
    // rather than characters produced, so the cut is deterministic whatever the page's charset.
    private static final int    MAX_CONTENT_BYTES = 1024 * 1024; // 1MB limit (bytes)
    private static final int    MAX_REDIRECTS     = 5;
    private static final int    DEFAULT_TIMEOUT   = 30;
    // Default analysis prompt used when the caller supplies only a URL ("webfetch <url>"), so the
    // command succeeds non-interactively instead of dead-ending while waiting for a prompt.
    private static final String DEFAULT_PROMPT    = "Summarize this page";
    @Parameters (index = "0", description = "The URL to fetch content from")
    private String url;
    @Parameters (index = "1..*", description = "The prompt to run on the fetched content")
    private       String[] promptParts;
    @Option (names = {"-t", "--timeout"}, description = "Timeout in seconds")
    private Integer  timeout = DEFAULT_TIMEOUT;
    
    private CommandRegistry.InterruptionContext interruptionContext;

    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }

    /**
     * Whether the user has taken this fetch back.
     *
     * <p>The context was stored and never read, and nothing here ever asked. A fetch is the one
     * thing this tool does that can sit still for minutes on end: a socket read does not respond to
     * {@link Thread#interrupt()}, so a {@code webfetch} the user stopped went on dialling, following
     * redirects and printing at them for the whole of its timeout -- up to five more connections
     * after they had been told it had stopped.</p>
     */
    @Override
    public boolean shouldInterrupt() {
        return CommandRegistry.InterruptibleCommand.stopWasAsked(interruptionContext);
    }

    /** What a fetch says when it is taken back, wherever it notices. */
    private IterativeCommand.StepResult takenBack(Map<String, Object> context) {
        OutputFormatter.printWarning("Fetch interrupted by user request");
        return StepResult.interrupted("Fetch interrupted by user", context);
    }
    @Option (names = {"-f", "--force"}, description = "Force fetch without confirmation")
    private       boolean  force;

    @Override
    public StepResult executeStep(String[] args, Map<String, Object> context, String llmResponse) {
        String step = (String) context.getOrDefault("step", "initial");

        switch (step) {
            case "initial":
                if (args.length == 0) {
                    // In non-interactive mode, show usage
                    boolean isInteractive = InteractivePrompts.isOn();
                    if (!isInteractive) {
                        OutputFormatter.printError("No URL provided");
                        OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
                        return StepResult.failure("Error: No URL provided", context);
                    }
                    context.put("step", "get_url");
                    return new StepResult(false, "No URL provided", context,
                            "What URL would you like to fetch content from?");
                }

                // Parse arguments
                String rawTargetUrl = args[0];

                // Validate, normalize, and screen the directly-supplied URL up front so the
                // direct-args path enforces the same guarantees as the interactive get_url
                // branch. Without this, a placeholder or scheme-less URL is stored verbatim
                // and only fails later with a generic "Failed to fetch" instead of clear
                // validation guidance. (The SSRF check in fetch_content remains the final guard.)
                if (URLUtils.isPlaceholderURL(rawTargetUrl)) {
                    String suggestion = URLUtils.substitutePlaceholderURL(rawTargetUrl);
                    logWarning("Placeholder URL", "Detected placeholder URL: " + rawTargetUrl);
                    OutputFormatter.printError(
                            "The URL provided appears to be a placeholder: " + rawTargetUrl +
                            "\nDid you mean: " + suggestion + " ? Please supply a real URL.");
                    return StepResult.failure("Error: placeholder URL provided: " + rawTargetUrl, context);
                }

                String targetUrl = URLUtils.normalizeURL(rawTargetUrl);
                if (!targetUrl.equals(rawTargetUrl)) {
                    logStep("URL normalization", "Normalized URL: " + targetUrl);
                    OutputFormatter.printInfo("Normalized URL: " + targetUrl);
                }

                URLUtils.ValidationResult directValidation = URLUtils.validateURL(targetUrl);
                if (!directValidation.isValid()) {
                    logErrorQuietly("URL validation", "Invalid URL: " + directValidation.getMessage());
                    OutputFormatter.printError("Invalid URL: " + directValidation.getMessage());
                    return StepResult.failure("Error: invalid URL: " + directValidation.getMessage(), context);
                }

                // Single, unified argument parser (shared by the picocli call() entry point, which
                // funnels its bound values back through this same path as a String[]). Keeping one
                // parser avoids the prior divergence between picocli and this manual loop.
                ParsedArgs parsed = parseTrailingArgs(args);
                if (parsed.error != null) {
                    OutputFormatter.printError(parsed.error);
                    return StepResult.failure("Error: " + parsed.error, context);
                }

                context.put("url", targetUrl);
                context.put("forceFlag", parsed.force);
                context.put("timeoutSec", parsed.timeoutSec);

                if (parsed.prompt.isEmpty()) {
                    // No prompt supplied. Interactively, ask for one. Non-interactively, fall back to a
                    // sane default ("Summarize this page") so "webfetch <url>" succeeds instead of
                    // dead-ending while waiting for a prompt that will never arrive.
                    boolean isInteractive = InteractivePrompts.isOn();
                    if (isInteractive) {
                        context.put("step", "get_prompt");
                        return new StepResult(false, "URL: " + targetUrl, context,
                                "What would you like me to analyze or do with the content from this URL?");
                    }
                    context.put("prompt", DEFAULT_PROMPT);
                    OutputFormatter.printInfo("No prompt provided; using default: " + DEFAULT_PROMPT);
                } else {
                    context.put("prompt", parsed.prompt);
                }

                context.put("step", "confirm_fetch");
                return executeStep(args, context, null);

            case "get_url":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    String providedUrl = llmResponse.trim();
                    
                    // Check if this is a placeholder URL
                    if (URLUtils.isPlaceholderURL(providedUrl)) {
                        String suggestion = URLUtils.substitutePlaceholderURL(providedUrl);
                        logWarning("Placeholder URL", "Detected placeholder URL: " + providedUrl);
                        logStep("URL substitution", "Substituting with: " + suggestion);
                        
                        context.put("step", "confirm_placeholder_substitution");
                        context.put("originalUrl", providedUrl);
                        context.put("suggestedUrl", suggestion);
                        return new StepResult(false, "Placeholder URL detected", context,
                                "The URL you provided appears to be a placeholder: " + providedUrl + 
                                "\nWould you like to use this real URL instead: " + suggestion + "? (yes/no)");
                    }
                    
                    // Normalize the URL
                    String normalizedUrl = URLUtils.normalizeURL(providedUrl);
                    if (!normalizedUrl.equals(providedUrl)) {
                        logStep("URL normalization", "Normalized URL: " + normalizedUrl);
                        OutputFormatter.printInfo("Normalized URL: " + normalizedUrl);
                    }
                    
                    // Validate the URL
                    URLUtils.ValidationResult validation = URLUtils.validateURL(normalizedUrl);
                    if (!validation.isValid()) {
                        logError("URL validation", "Invalid URL: " + validation.getMessage());
                        return new StepResult(false, "Invalid URL: " + validation.getMessage(), context,
                                "The URL you provided is invalid: " + validation.getMessage() + 
                                "\nPlease provide a valid URL (must start with http:// or https:// and have a valid domain):");
                    }
                    
                    context.put("url", normalizedUrl);
                    context.put("forceFlag", false);
                    context.put("timeoutSec", 30);
                    context.put("step", "get_prompt");
                    return new StepResult(false, "URL: " + normalizedUrl, context,
                            "What would you like me to analyze or do with the content from this URL?");
                }
                return StepResult.failure("No URL provided, cancelled", context);

            case "confirm_placeholder_substitution":
                String placeholderResponse = llmResponse == null ? "" : llmResponse.trim().toLowerCase();
                boolean useSuggested = placeholderResponse.equals("y") ||
                                       placeholderResponse.equals("yes") ||
                                       placeholderResponse.startsWith("yes ");
                String chosenUrl = useSuggested
                        ? (String) context.get("suggestedUrl")
                        : (String) context.get("originalUrl");
                if (chosenUrl == null || chosenUrl.trim().isEmpty()) {
                    context.put("step", "get_url");
                    return new StepResult(false, "No URL available", context,
                            "Please provide a valid URL (must start with http:// or https:// and have a valid domain):");
                }

                // Normalize the chosen URL
                String normalizedChosenUrl = URLUtils.normalizeURL(chosenUrl);
                if (!normalizedChosenUrl.equals(chosenUrl)) {
                    logStep("URL normalization", "Normalized URL: " + normalizedChosenUrl);
                    OutputFormatter.printInfo("Normalized URL: " + normalizedChosenUrl);
                }

                // Validate the chosen URL
                URLUtils.ValidationResult chosenValidation = URLUtils.validateURL(normalizedChosenUrl);
                if (!chosenValidation.isValid()) {
                    logError("URL validation", "Invalid URL: " + chosenValidation.getMessage());
                    context.put("step", "get_url");
                    return new StepResult(false, "Invalid URL: " + chosenValidation.getMessage(), context,
                            "The URL is invalid: " + chosenValidation.getMessage() +
                            "\nPlease provide a valid URL (must start with http:// or https:// and have a valid domain):");
                }

                context.put("url", normalizedChosenUrl);
                context.put("forceFlag", false);
                context.put("timeoutSec", 30);
                context.put("step", "get_prompt");
                return new StepResult(false, "URL: " + normalizedChosenUrl, context,
                        "What would you like me to analyze or do with the content from this URL?");

            case "get_prompt":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    context.put("prompt", llmResponse.trim());
                    context.put("step", "confirm_fetch");
                    return executeStep(args, context, null);
                }
                return StepResult.failure("No prompt provided, cancelled", context);

            case "confirm_fetch":
                String urlToFetch = (String) context.get("url");
                if (urlToFetch == null) {
                    OutputFormatter.printError("No URL found in context");
                    return StepResult.failure("Error: no URL in context", context);
                }
                Object forceFlagObj = context.get("forceFlag");
                boolean force = forceFlagObj != null && (boolean) forceFlagObj;

                if (!force && com.eonmux.cadetcoder.config.ConfigManager.getInstance()
                                                                        .getConfig()
                                                                        .getSecurity()
                                                                        .isRequireConfirmation()) {
                    context.put("step", "await_confirmation");
                    return new StepResult(false, "Security check", context,
                            "About to fetch content from: " + urlToFetch + "\nDo you want to continue? (yes/no)");
                }

                context.put("step", "fetch_content");
                return executeStep(args, context, null);

            case "await_confirmation":
                String confirmation = llmResponse == null ? "" : llmResponse.trim().toLowerCase();
                boolean confirmed = confirmation.equals("y") ||
                                    confirmation.equals("yes") ||
                                    confirmation.startsWith("yes ");
                if (confirmed) {
                    context.put("step", "fetch_content");
                    return executeStep(args, context, null);
                } else {
                    // Declined, not failed: nothing was fetched because the user said not to.
                    return StepResult.interrupted("Fetch cancelled by user", context);
                }

            case "fetch_content":
                try {
                    String fetchUrl = (String) context.get("url");
                    int    timeout  = (int) context.get("timeoutSec");

                    // Use long arithmetic and clamp so a large timeout (seconds) does not
                    // overflow int when converted to milliseconds and trigger a spurious
                    // "timeout can not be negative" failure.
                    int timeoutMillis = (int) Math.min((long) timeout * 1000L, Integer.MAX_VALUE);

                    // Asked before the URL is dialled, not after: a fetch already taken back must
                    // not open a connection at all.
                    if (shouldInterrupt()) {
                        return takenBack(context);
                    }

                    OutputFormatter.printInfo("Fetching content from: " + fetchUrl);

                    URI uri    = new URI(fetchUrl);
                    URL urlObj = uri.toURL();

                    HttpURLConnection connection = null;
                    StringBuilder     content    = new StringBuilder();
                    int responseCode = 0;
                    long requestDuration = 0;
                    long startTime = System.currentTimeMillis();

                    try {
                        // Follow redirects manually so each hop is re-screened for SSRF.
                        // Automatic redirect following would bypass the SSRF gate and let a public
                        // host redirect us to a private/metadata address.
                        int redirectsRemaining = MAX_REDIRECTS;
                        while (true) {
                            // At the top of every hop, so a redirect chain stops where it is instead
                            // of running out its five remaining connections.
                            if (shouldInterrupt()) {
                                return takenBack(context);
                            }
                            // Resolve the host exactly once and connect to that address, so a record
                            // that flips to a private/metadata IP between the check and the connect
                            // cannot be reached. Loopback stays allowed for local development.
                            OutboundAddress resolved = OutboundAddress.of(urlObj);
                            if (resolved.refusal() != null) {
                                logErrorQuietly("SSRF validation", resolved.refusal());
                                OutputFormatter.printError(resolved.refusal());
                                return StepResult.failure("Error: " + resolved.refusal(), context);
                            }

                            connection = PinnedConnection.open(urlObj, resolved.pinned(), timeoutMillis);

                            long startHop = System.currentTimeMillis();
                            responseCode = connection.getResponseCode();
                            requestDuration = System.currentTimeMillis() - startHop;

                            // Log the protocol operation
                            logProtocolOperation("HTTP", fetchUrl, "GET", responseCode == HttpURLConnection.HTTP_OK, requestDuration);

                            // Each hop is screened and pinned by the next turn of this loop.
                            if (PinnedConnection.isRedirect(responseCode)) {
                                String location = connection.getHeaderField("Location");
                                connection.disconnect();
                                connection = null;

                                if (redirectsRemaining-- <= 0) {
                                    logError("HTTP redirect", "Too many redirects following: " + fetchUrl);
                                    return StepResult.failure("Error: too many redirects", context);
                                }
                                if (location == null || location.trim().isEmpty()) {
                                    logError("HTTP redirect", "Redirect with no Location header from: " + fetchUrl);
                                    return StepResult.failure("Error: redirect missing Location header", context);
                                }

                                // Resolve relative redirects against the current URL. The next loop
                                // iteration re-resolves and re-validates this hop before connecting.
                                URL redirectUrl = new URL(urlObj, location.trim());
                                urlObj   = redirectUrl;
                                fetchUrl = redirectUrl.toString();
                                continue;
                            }

                            break;
                        }

                        if (responseCode != HttpURLConnection.HTTP_OK) {
                            logError("HTTP request", "HTTP " + responseCode + ": " + connection.getResponseMessage());
                            return StepResult.failure("HTTP error code: " + responseCode, context);
                        }

                        // Decode through the charset Content-Type declares, stopping at
                        // MAX_CONTENT_BYTES taken from the socket rather than characters produced,
                        // so an adversarial server cannot stream unbounded data in whatever
                        // encoding it names.
                        // Before the body is pulled off the socket, which is the longest single
                        // blocking span here -- up to a megabyte from a server setting the pace.
                        if (shouldInterrupt()) {
                            return takenBack(context);
                        }

                        Charset charset = BoundedBody.charsetOf(connection.getContentType());
                        boolean truncated;
                        try (InputStream rawIn = connection.getInputStream()) {
                            truncated = BoundedBody.readInto(rawIn, charset, MAX_CONTENT_BYTES, content);
                        }

                        if (truncated) {
                            logWarning("Content size limit", "Content truncated at " + MAX_CONTENT_BYTES + " bytes");
                            OutputFormatter.printWarning("Content truncated at " +
                                                         MAX_CONTENT_BYTES +
                                                         " bytes");
                        }

                        // Log data processing metrics
                        logDataProcessing("Content fetch", "text", content.length(), System.currentTimeMillis() - startTime);
                    } finally {
                        if (connection != null) {
                            connection.disconnect();
                        }
                    }

                    String fetchedContent = content.toString();
                    OutputFormatter.printSuccess("Fetched " + fetchedContent.length() + " characters");
                    
                    // Log fetched web content to debug log
                    logDebug("Web Fetch", String.format("URL: %s", fetchUrl));
                    logDebug("Web Fetch", String.format("RESPONSE_CODE: %d", responseCode));
                    logDebug("Web Fetch", String.format("CONTENT_LENGTH: %d characters", fetchedContent.length()));
                    logDebug("Web Fetch", String.format("FETCH_DURATION: %dms", requestDuration));
                    logDebug("Web Fetch", "=== WEB CONTENT START ===");
                    logDebug("Web Fetch", fetchedContent);
                    logDebug("Web Fetch", "=== WEB CONTENT END ===");
                    
                    context.put("content", fetchedContent);
                    context.put("step", "process_content");
                    return executeStep(args, context, null);

                } catch (Exception e) {
                    // Surface a friendly, bounded message rather than the raw exception text, which
                    // can be a long, type-prefixed, or even null string that is noisy/misleading to
                    // the model. The full detail is preserved in the debug log.
                    String fetchUrl = (String) context.get("url");
                    logError("Web fetch", "Failed to fetch or process " + fetchUrl, e);
                    return StepResult.failure(
                            "Error: Failed to fetch or process: " + describeFetchFailure(e),
                            context);
                }

            case "process_content":
                try {
                    String fetchedUrl     = (String) context.get("url");
                    String userPrompt     = (String) context.get("prompt");
                    String fetchedContent = (String) context.get("content");

                    OutputFormatter.printInfo("Processing with AI...");

                    // Use the template engine for the prompt
                    String fullPrompt = TemplatePromptBuilder.webFetch()
                                                             .with("url", fetchedUrl)
                                                             .with("user_prompt", userPrompt)
                                                             .with("web_content", fetchedContent)
                                                             .build();

                    PromptData promptData = new PromptData("", fullPrompt);
                    // Log AI interaction
                    logAIStart("webfetch-analysis", fullPrompt, Map.of("temperature", 0.7));
                    long aiStartTime = System.currentTimeMillis();
                    String     aiResponse = AIManager.getInstance().complete(promptData, new HashMap<>());
                    long aiDuration = System.currentTimeMillis() - aiStartTime;
                    logAIComplete("webfetch-analysis", aiResponse, aiDuration);

                    // Log AI analysis to debug log
                    logDebug("AI Analysis", String.format("URL: %s", fetchedUrl));
                    logDebug("AI Analysis", String.format("USER_PROMPT: %s", userPrompt));
                    logDebug("AI Analysis", String.format("DURATION: %dms", aiDuration));
                    logDebug("AI Analysis", String.format("RESPONSE_LENGTH: %d characters", aiResponse != null ? aiResponse.length() : 0));
                    logDebug("AI Analysis", "=== AI ANALYSIS START ===");
                    logDebug("AI Analysis", aiResponse != null ? aiResponse : "<null>");
                    logDebug("AI Analysis", "=== AI ANALYSIS END ===");

                    OutputFormatter.printHeader("AI Analysis:");
                    UnifiedOutput.println(aiResponse);

                    context.put("analysis", aiResponse);
                    context.put("step", "ask_follow_up");
                    return new StepResult(false, "", context,
                            "Would you like to ask any follow-up questions about this content? (yes/no)");

                } catch (Exception e) {
                    return StepResult.failure("Error processing content: " + e.getMessage(), context);
                }

            case "ask_follow_up":
                if (llmResponse != null && llmResponse.toLowerCase().contains("yes")) {
                    context.put("step", "get_follow_up");
                    return new StepResult(false, "", context,
                            "What follow-up question would you like to ask?");
                } else {
                    return StepResult.success("Analysis complete", context);
                }

            case "get_follow_up":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    context.put("prompt", llmResponse.trim());
                    context.put("step", "process_content");
                    return executeStep(args, context, null);
                }
                return StepResult.success("No follow-up question provided", context);
        }

        return StepResult.failure("Unknown step: " + step, context);
    }

    /**
     * Produces a friendly, bounded one-line description of a fetch failure for the model, avoiding
     * raw/null/overlong exception text. Full detail is kept in the debug log by the caller.
     */
    private String describeFetchFailure(Throwable e) {
        if (e instanceof UnknownHostException) {
            return "the host could not be resolved";
        }
        if (e instanceof SocketTimeoutException) {
            return "the request timed out";
        }
        if (e instanceof java.net.ConnectException) {
            return "the connection was refused";
        }
        if (e instanceof MalformedURLException || e instanceof URISyntaxException) {
            return "the URL is malformed";
        }
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }
        message = message.trim();
        int max = 200;
        return message.length() > max ? message.substring(0, max) + "..." : message;
    }

    /** Result of parsing the trailing arguments (everything after the URL at index 0). */
    private static final class ParsedArgs {
        final String  prompt;
        final boolean force;
        final int     timeoutSec;
        final String  error; // null when parsing succeeded

        private ParsedArgs(String prompt, boolean force, int timeoutSec, String error) {
            this.prompt     = prompt;
            this.force      = force;
            this.timeoutSec = timeoutSec;
            this.error      = error;
        }
    }

    /**
     * Single source of truth for trailing-argument semantics. Parses {@code -t/--timeout},
     * {@code -f/--force}, and treats every other token as part of the prompt. {@code args[0]} is the
     * URL and is not consulted here.
     *
     * @param args the full argument array (index 0 is the URL)
     * @return parsed values, or a result whose {@code error} field is non-null on invalid input
     */
    private ParsedArgs parseTrailingArgs(String[] args) {
        StringBuilder promptBuilder = new StringBuilder();
        boolean       forceFlag     = false;
        int           timeoutSec    = DEFAULT_TIMEOUT;

        for (int i = 1; i < args.length; i++) {
            if ((args[i].equals("-t") || args[i].equals("--timeout")) && i + 1 < args.length) {
                String raw = args[++i];
                try {
                    int parsedTimeout = Integer.parseInt(raw);
                    if (parsedTimeout <= 0) {
                        return new ParsedArgs(null, false, 0, "Timeout must be a positive integer");
                    }
                    timeoutSec = parsedTimeout;
                } catch (NumberFormatException e) {
                    return new ParsedArgs(null, false, 0, "Invalid timeout value: " + raw);
                }
            } else if (args[i].equals("-f") || args[i].equals("--force")) {
                forceFlag = true;
            } else {
                if (promptBuilder.length() > 0) {
                    promptBuilder.append(" ");
                }
                promptBuilder.append(args[i]);
            }
        }

        return new ParsedArgs(promptBuilder.toString(), forceFlag, timeoutSec, null);
    }

    @Override
    public String getInitialPrompt(String[] args) {
        if (args.length == 0) {
            return "The user wants to fetch web content but didn't specify a URL. What URL should we fetch?";
        }
        // A URL is present. The prompt is optional: when omitted, the step machine supplies a
        // default ("Summarize this page") non-interactively or asks the user interactively. Returning
        // null here lets the executor drive the deterministic step flow instead of round-tripping to
        // the LLM just to obtain a prompt (which dead-ended "webfetch <url>" non-interactively).
        return null;
    }

    @Override
    public boolean supportsIterativeExecution(String[] args) {
        // Always support iterative execution for better interaction
        return true;
    }

    @Override
    public Integer call() throws Exception {
        startCommandLogging("webfetch", new String[]{url != null ? url : "", promptParts != null ? String.join(" ", promptParts) : ""});
        
        try {
            // Reconstruct a canonical String[] from the picocli-bound fields and funnel it through
            // execute() -> the single manual parser in executeStep(). This keeps one parsing path:
            // picocli is only the entry binding; argument semantics (defaults, force, timeout,
            // prompt) live in parseTrailingArgs(), so the two reps can no longer diverge.
            List<String> args = new ArrayList<>();
            if (url != null) {
                args.add(url);
            }
            if (promptParts != null) {
                Collections.addAll(args, promptParts);
            }
            if (timeout != null && timeout != DEFAULT_TIMEOUT) {
                args.add("-t");
                args.add(timeout.toString());
            }
            if (force) {
                args.add("-f");
            }

            int result = execute(args.toArray(new String[0]));
            completeCommandLogging(result);
            return result;
        } catch (Exception e) {
            logError("webfetch", "Command execution failed", e);
            completeCommandLogging(1);
            throw e;
        }
    }

    @Override
    public int execute(String[] args) {
        // Use iterative executor for better multi-step handling
        IterativeExecutor executor = new IterativeExecutor();
        return executor.execute(this, args);
    }


    @Override
    public String getUsage() {
        // The prompt is optional; when omitted, a default ("Summarize this page") is used.
        return "webfetch <url> [<prompt>] [-t|--timeout <seconds>] [-f|--force]";
    }
}
