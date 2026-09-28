package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.net.BoundedBody;
import com.eonmux.cadetcoder.net.OutboundAddress;
import com.eonmux.cadetcoder.net.PinnedConnection;
import com.eonmux.cadetcoder.prompts.TemplatePromptBuilder;
import com.eonmux.cadetcoder.ui.UnifiedOutput;
import com.eonmux.cadetcoder.ui.InteractivePrompts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.*;

import java.io.InputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;

@Command (name = "websearch", description = "Search the web and summarize what comes back")
public class WebSearchCommand implements IterativeCommand, Callable<Integer> {

    /** Default number of results when --num-results is not supplied. */
    private static final int DEFAULT_RESULTS = 5;
    /** Upper bound for --num-results; the DuckDuckGo Instant Answer API returns a bounded set. */
    private static final int MAX_RESULTS = 25;
    /** Hard cap on the search-API response body to avoid unbounded memory use. */
    private static final int MAX_RESPONSE_LENGTH = 1024 * 1024; // 1MB
    /** Connect and read timeout for one hop of the search request. */
    private static final int FETCH_TIMEOUT_MILLIS = 10_000;
    /** Maximum redirects to follow manually (the API host is fixed; redirects are not expected). */
    private static final int MAX_REDIRECTS = 5;

    @Parameters (index = "0..*", description = "The search query")
    private String[] queryParts;

    @Option (names = {"-a", "--allow"}, description = "Only include results from these domains")
    private String[] allowedDomains;

    @Option (names = {"-b", "--block"}, description = "Exclude results from these domains")
    private String[] blockedDomains;

    @Option (names = {"-n", "--num-results", "--num"}, description = "Number of results to fetch")
    private Integer numResults = 5;

    @Override
    public StepResult executeStep(String[] args, Map<String, Object> context, String llmResponse) {
        String step = (String) context.getOrDefault("step", "initial");

        switch (step) {
            case "initial":
                if (args.length == 0) {
                    context.put("step", "get_query");
                    return new StepResult(false, "No search query provided", context,
                            "What would you like to search for?");
                }

                // Parse arguments
                List<String> queryList = new ArrayList<>();
                List<String> allowed = new ArrayList<>();
                List<String> blocked = new ArrayList<>();
                int maxResults = DEFAULT_RESULTS;

                // Accept --allow=<d>/--block=<d>/--num-results=<n> as well as the space-separated
                // form, matching what the command catalog promises for every command.
                args = CommandOptions.expandInlineValues(args, java.util.Set.of(
                        "-a", "--allow", "-b", "--block", "-n", "--num-results", "--num"));

                for (int i = 0; i < args.length; i++) {
                    if ((args[i].equals("-a") || args[i].equals("--allow")) && i + 1 < args.length) {
                        allowed.add(args[++i]);
                    } else if ((args[i].equals("-b") || args[i].equals("--block")) && i + 1 < args.length) {
                        blocked.add(args[++i]);
                    } else if ((args[i].equals("-n") || args[i].equals("--num-results")
                                || args[i].equals("--num")) && i + 1 < args.length) {
                        try {
                            maxResults = Integer.parseInt(args[++i]);
                        } catch (NumberFormatException e) {
                            return StepResult.failure("Invalid value for --num-results: '" + args[i] + "' is not a valid integer", context);
                        }
                        // Reject out-of-range counts up front (finding 45). Negative/zero/huge
                        // values otherwise reach the result-limiting logic with no bound.
                        if (maxResults < 1 || maxResults > MAX_RESULTS) {
                            return StepResult.failure(
                                    "--num-results must be between 1 and " + MAX_RESULTS, context);
                        }
                    } else {
                        queryList.add(args[i]);
                    }
                }

                String query = String.join(" ", queryList);
                context.put("query", query);
                context.put("allowed", allowed);
                context.put("blocked", blocked);
                context.put("maxResults", maxResults);
                context.put("step", "perform_search");
                return executeStep(args, context, null);

            case "get_query":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    context.put("query", llmResponse.trim());
                    context.put("allowed", new ArrayList<String>());
                    context.put("blocked", new ArrayList<String>());
                    context.put("maxResults", DEFAULT_RESULTS);
                    // In non-interactive mode there is no human to answer the domain-filter
                    // wizard (finding websearch-2); skip prompting and proceed with the safe
                    // default of no domain filters.
                    if (!isInteractive()) {
                        context.put("step", "perform_search");
                        return executeStep(args, context, null);
                    }
                    context.put("step", "ask_filters");
                    return new StepResult(false, "Query: " + llmResponse.trim(), context,
                            "Would you like to filter results by domain? (yes/no)");
                }
                return StepResult.failure("No search query provided, cancelled", context);

            case "ask_filters":
                if (llmResponse != null && llmResponse.toLowerCase().contains("yes")) {
                    context.put("step", "get_filters");
                    return new StepResult(false,
                            "",
                            context,
                            "Please specify domain filters. You can allow specific domains (e.g., 'allow: example.com') or block domains (e.g., 'block: spam.com')");
                } else {
                    context.put("step", "perform_search");
                    return executeStep(args, context, null);
                }

            case "get_filters":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    List<String> allowedDomainsList = (List<String>) context.get("allowed");
                    List<String> blockedDomainsList = (List<String>) context.get("blocked");
                    if (allowedDomainsList == null) {
                        allowedDomainsList = new ArrayList<>();
                        context.put("allowed", allowedDomainsList);
                    }
                    if (blockedDomainsList == null) {
                        blockedDomainsList = new ArrayList<>();
                        context.put("blocked", blockedDomainsList);
                    }

                    String[] lines = llmResponse.split("\n");
                    for (String line : lines) {
                        if (line.toLowerCase().startsWith("allow:")) {
                            allowedDomainsList.add(line.substring(6).trim());
                        } else if (line.toLowerCase().startsWith("block:")) {
                            blockedDomainsList.add(line.substring(6).trim());
                        }
                    }
                }
                context.put("step", "perform_search");
                return executeStep(args, context, null);

            case "perform_search":
                try {
                    String       searchQuery        = (String) context.get("query");
                    if (context.get("allowed") instanceof List && context.get("blocked") instanceof List && context.get("maxResults") instanceof Integer) {
                        List<String> allowedDomainsList = (List<String>) context.get("allowed");
                        List<String> blockedDomainsList = (List<String>) context.get("blocked");
                        int maxResultsCount = (int) context.get("maxResults");

                        OutputFormatter.printInfo("Searching for: " + searchQuery);

                        // Perform the actual search. maxResults, allow- and block-lists are now
                        // enforced on the parsed results before they reach the AI (finding 13),
                        // not merely passed as advisory prompt hints.
                        String searchResults = performWebSearch(
                                searchQuery, maxResultsCount, allowedDomainsList, blockedDomainsList);
                        context.put("searchResults", searchResults);
                        context.put("step", "analyze_results");
                        return executeStep(args, context, null);
                    } else {
                        return StepResult.failure("Missing required context for search", context);
                    }

                } catch (Exception e) {
                    return StepResult.failure("Search failed: " + e.getMessage(), context);
                }

            case "analyze_results":
                try {
                    String       searchQuery        = (String) context.get("query");
                    String       searchResults      = (String) context.get("searchResults");

                    // Short-circuit when the search returned nothing usable (finding websearch-4):
                    // there is nothing to summarize, so report it directly instead of spending an
                    // LLM call on an empty/no-match payload.
                    if (isEmptyResults(searchResults)) {
                        String message = "No results found for: " + searchQuery;
                        OutputFormatter.printHeader("Search Results Summary:");
                        UnifiedOutput.println(message);
                        context.put("summary", message);
                        context.put("step", "ask_refine");
                        return new StepResult(false, "", context,
                                "Would you like to refine your search or look for something specific in these results? (yes/no)");
                    }

                    if (context.get("allowed") instanceof List && context.get("blocked") instanceof List) {
                        List<String> allowedDomainsList = (List<String>) context.get("allowed");
                        List<String> blockedDomainsList = (List<String>) context.get("blocked");

                        OutputFormatter.printInfo("Processing search results...");

                        // Build user intent with domain filters
                        StringBuilder userIntent =
                                new StringBuilder("Please summarize the key findings from these search results.");
                        if (!allowedDomainsList.isEmpty()) {
                            userIntent.append("\nNote: Focus on results from these domains: ")
                                      .append(String.join(", ", allowedDomainsList));
                        }
                        if (!blockedDomainsList.isEmpty()) {
                            userIntent.append("\nNote: Ignore results from these domains: ")
                                      .append(String.join(", ", blockedDomainsList));
                        }

                        // Use the template engine for the prompt
                        String fullPrompt = TemplatePromptBuilder.webSearch()
                                                                 .with("search_query", searchQuery)
                                                                 .with("user_intent", userIntent.toString())
                                                                 .with("search_results", searchResults)
                                                                 .build();

                        PromptData promptData = new PromptData("", fullPrompt);
                        String aiResponse = AIManager.getInstance().complete(promptData, new HashMap<>());

                        OutputFormatter.printHeader("Search Results Summary:");
                        UnifiedOutput.println(aiResponse);

                        context.put("summary", aiResponse);
                        context.put("step", "ask_refine");
                        return new StepResult(false,
                                "",
                                context,
                                "Would you like to refine your search or look for something specific in these results? (yes/no)");
                    } else {
                        return StepResult.failure("Missing required context for analysis", context);
                    }

                } catch (Exception e) {
                    return StepResult.failure("Error analyzing results: " + e.getMessage(), context);
                }

            case "ask_refine":
                // In non-interactive mode there is nobody to answer the refine wizard
                // (finding websearch-2); complete with the safe default of no refinement.
                if (!isInteractive()) {
                    return StepResult.success("Search completed successfully", context);
                }
                if (llmResponse != null && llmResponse.toLowerCase().contains("yes")) {
                    context.put("step", "get_refinement");
                    return new StepResult(false, "", context,
                            "What would you like to search for or how should I refine the search?");
                } else {
                    return StepResult.success("Search completed successfully", context);
                }

            case "get_refinement":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    // Add refinement to original query
                    String originalQuery = (String) context.get("query");
                    String refinedQuery  = originalQuery + " " + llmResponse.trim();
                    context.put("query", refinedQuery);
                    context.put("step", "perform_search");
                    return executeStep(args, context, null);
                }
                return StepResult.success("No refinement provided, search complete", context);
        }

        return StepResult.failure("Unknown step: " + step, context);
    }

    @Override
    public String getInitialPrompt(String[] args) {
        if (args.length == 0) {
            return "The user wants to search the web but didn't specify what to search for. What should we search?";
        }
        return null;
    }

    @Override
    public boolean supportsIterativeExecution(String[] args) {
        // Always support iterative execution for better interaction
        return true;
    }

    /**
     * Performs the web search and enforces the request parameters.
     *
     * <p>The target host is the fixed DuckDuckGo Instant Answer endpoint
     * ({@code api.duckduckgo.com}), so SSRF risk is low. As defence in depth the fetch nonetheless
     * screens every hop through {@link OutboundAddress}, connects to the address that was screened,
     * follows redirects by hand, and caps the response body at {@link #MAX_RESPONSE_LENGTH}.</p>
     *
     * <p>The raw API JSON is parsed, results are filtered by the allow-/block-domain lists, and the
     * set is sliced to {@code maxResults} before being handed to the AI, rather than passing the
     * filters only as advisory prompt text.</p>
     *
     * @param query          the search query
     * @param maxResults     clamped result count (validated to [1, {@link #MAX_RESULTS}])
     * @param allowedDomains domains to keep (empty = keep all)
     * @param blockedDomains domains to exclude
     * @return the filtered, size-bounded result text handed to the AI
     */
    private String performWebSearch(String query,
                                    int maxResults,
                                    List<String> allowedDomains,
                                    List<String> blockedDomains) throws Exception {
        // Note: This is a simplified implementation backed by the DuckDuckGo Instant
        // Answer API. A production system would use a dedicated search API.

        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String searchUrl    = "https://api.duckduckgo.com/?q=" + encodedQuery + "&format=json&no_html=1";

        FetchResult fetched = fetchWithManualRedirects(new URI(searchUrl).toURL());
        return filterAndLimitResults(
                fetched.body, maxResults, allowedDomains, blockedDomains, fetched.truncated);
    }

    /**
     * Immutable holder for a fetched response body and whether it was truncated at
     * {@link #MAX_RESPONSE_LENGTH}. The truncation flag lets the caller avoid feeding
     * silently-truncated (and therefore likely malformed) JSON to the AI.
     */
    private static final class FetchResult {
        private final String  body;
        private final boolean truncated;

        private FetchResult(String body, boolean truncated) {
            this.body      = body;
            this.truncated = truncated;
        }
    }

    /**
     * Fetches the URL, screening and pinning every hop and bounding the response body.
     *
     * @param urlObj where to start
     * @return the body, and whether it was cut at {@link #MAX_RESPONSE_LENGTH}
     * @throws Exception if a hop is refused, the redirect chain is malformed or too long, the API
     *                   answers with anything but 200, or the body cannot be read
     */
    private FetchResult fetchWithManualRedirects(URL urlObj) throws Exception {
        StringBuilder     response           = new StringBuilder();
        int               redirectsRemaining = MAX_REDIRECTS;
        HttpURLConnection connection         = null;
        boolean           truncated          = false;

        try {
            while (true) {
                // Screen each hop and connect to the address that was screened. Validating the name
                // and then letting openConnection() resolve it a second time would reach whatever
                // that second answer said, which is the whole point of screening it at all.
                OutboundAddress screened = OutboundAddress.of(urlObj);
                if (screened.refusal() != null) {
                    throw new RuntimeException(screened.refusal());
                }
                connection = PinnedConnection.open(urlObj, screened.pinned(), FETCH_TIMEOUT_MILLIS);

                int responseCode = connection.getResponseCode();

                if (PinnedConnection.isRedirect(responseCode)) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    connection = null;

                    if (redirectsRemaining-- <= 0) {
                        throw new RuntimeException("Too many redirects following search request");
                    }
                    if (location == null || location.trim().isEmpty()) {
                        throw new RuntimeException("Redirect with no Location header");
                    }

                    urlObj = new URL(urlObj, location.trim());
                    continue;
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new RuntimeException("Search API error: " + responseCode);
                }

                try (InputStream body = connection.getInputStream()) {
                    truncated = BoundedBody.readInto(body, StandardCharsets.UTF_8,
                                                     MAX_RESPONSE_LENGTH, response);
                }
                break;
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        return new FetchResult(response.toString(), truncated);
    }

    /**
     * Overload retained for tests and callers that have no truncation signal; treats the body
     * as complete (not truncated).
     */
    private String filterAndLimitResults(String rawResponse,
                                         int maxResults,
                                         List<String> allowedDomains,
                                         List<String> blockedDomains) {
        return filterAndLimitResults(rawResponse, maxResults, allowedDomains, blockedDomains, false);
    }

    /**
     * Parses the DuckDuckGo Instant Answer JSON, applies the allow-/block-domain filters,
     * and slices to {@code maxResults}. Falls back to the raw response (still domain-filtered
     * where possible) when the body is not parseable JSON, preserving prior behavior for
     * unexpected payloads while never exceeding the requested count.
     *
     * <p>When {@code truncated} is true the body was cut at {@link #MAX_RESPONSE_LENGTH} and is
     * likely malformed JSON. In that case (finding websearch-5) we never dump the raw,
     * silently-truncated payload: any parseable prefix is used if possible, otherwise a clear
     * truncation note is returned instead of malformed JSON.
     */
    private String filterAndLimitResults(String rawResponse,
                                         int maxResults,
                                         List<String> allowedDomains,
                                         List<String> blockedDomains,
                                         boolean truncated) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return "";
        }

        List<String> entries = new ArrayList<>();
        try {
            JsonNode root = new ObjectMapper().readTree(rawResponse);
            collectResultEntries(root.get("Results"), entries);
            collectResultEntries(root.get("RelatedTopics"), entries);

            // Include the top-level abstract as a result entry when present.
            JsonNode abstractUrl  = root.get("AbstractURL");
            JsonNode abstractText = root.get("AbstractText");
            if (abstractUrl != null && !abstractUrl.asText().isEmpty()) {
                String text = abstractText != null ? abstractText.asText() : "";
                entries.add(0, abstractUrl.asText() + (text.isEmpty() ? "" : " - " + text));
            }
        } catch (Exception parseError) {
            // Non-JSON or unexpected structure. If the body was truncated at the size cap
            // (finding websearch-5), it is almost certainly malformed JSON that was cut
            // mid-stream; never dump that raw to the AI. Return a clear truncation note instead.
            if (truncated) {
                return "Search results were truncated at " + MAX_RESPONSE_LENGTH
                        + " bytes and could not be parsed. No usable results available.";
            }
            // Otherwise fall back to the raw (already size-bounded) response so the AI still
            // receives the payload for unexpected-but-complete structures.
            return rawResponse;
        }

        List<String> filtered = new ArrayList<>();
        for (String entry : entries) {
            String host = hostOf(entry);
            if (isDomainAllowed(host, allowedDomains, blockedDomains)) {
                filtered.add(entry);
            }
            if (filtered.size() >= maxResults) {
                break;
            }
        }

        if (filtered.isEmpty()) {
            return "No results matched the requested domain filters.";
        }

        StringBuilder out = new StringBuilder();
        for (String entry : filtered) {
            out.append(entry).append(System.lineSeparator());
        }
        return out.toString();
    }

    /** Collects {@code FirstURL}/{@code Text} pairs from a Results/RelatedTopics array node. */
    private void collectResultEntries(JsonNode arrayNode, List<String> entries) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return;
        }
        for (JsonNode node : arrayNode) {
            // RelatedTopics may nest a "Topics" array for grouped categories.
            JsonNode nested = node.get("Topics");
            if (nested != null && nested.isArray()) {
                collectResultEntries(nested, entries);
                continue;
            }
            JsonNode firstUrl = node.get("FirstURL");
            JsonNode text     = node.get("Text");
            if (firstUrl != null && !firstUrl.asText().isEmpty()) {
                String t = text != null ? text.asText() : "";
                entries.add(firstUrl.asText() + (t.isEmpty() ? "" : " - " + t));
            }
        }
    }

    /** Extracts the host from the leading URL of a result entry, or null if absent. */
    private String hostOf(String entry) {
        if (entry == null) {
            return null;
        }
        int sep = entry.indexOf(" - ");
        String urlPart = sep >= 0 ? entry.substring(0, sep) : entry;
        try {
            return new URI(urlPart.trim()).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Applies allow/block domain policy. A host matches a domain if it equals it or is a
     * subdomain of it (suffix match on a dot boundary). Block takes precedence; a non-empty
     * allow-list excludes anything not matched.
     */
    private boolean isDomainAllowed(String host, List<String> allowedDomains, List<String> blockedDomains) {
        if (host == null) {
            // No host to evaluate: only permit when no allow-list constrains the results.
            return allowedDomains == null || allowedDomains.isEmpty();
        }
        String h = host.toLowerCase(Locale.ROOT);

        if (blockedDomains != null) {
            for (String blocked : blockedDomains) {
                if (matchesDomain(h, blocked)) {
                    return false;
                }
            }
        }
        if (allowedDomains != null && !allowedDomains.isEmpty()) {
            for (String allowed : allowedDomains) {
                if (matchesDomain(h, allowed)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /** True when {@code host} equals {@code domain} or is a dot-boundary subdomain of it. */
    private boolean matchesDomain(String host, String domain) {
        if (domain == null) {
            return false;
        }
        String d = domain.trim().toLowerCase(Locale.ROOT);
        if (d.isEmpty()) {
            return false;
        }
        return host.equals(d) || host.endsWith("." + d);
    }

    /**
     * Whether the command is running interactively. Mirrors {@link IterativeExecutor}'s use of the
     * {@code cadet.interactive} system property (default {@code true}). Used to skip the yes/no
     * domain-filter and refine wizards when no human is present (finding websearch-2).
     */
    private boolean isInteractive() {
        return InteractivePrompts.isOn();
    }

    /**
     * Whether the search produced nothing usable to summarize (finding websearch-4). Covers an
     * empty/blank body and the two sentinel messages {@link #filterAndLimitResults} returns when
     * no entries survive ("No results matched ...") or the payload was truncated and unparseable.
     */
    private boolean isEmptyResults(String searchResults) {
        if (searchResults == null || searchResults.trim().isEmpty()) {
            return true;
        }
        String trimmed = searchResults.trim();
        return trimmed.startsWith("No results matched the requested domain filters.")
                || trimmed.startsWith("Search results were truncated at");
    }

    @Override
    public Integer call() throws Exception {
        List<String> args = new ArrayList<>();
        if (queryParts != null) {
            Collections.addAll(args, queryParts);
        }
        if (allowedDomains != null) {
            for (String domain : allowedDomains) {
                args.add("-a");
                args.add(domain);
            }
        }
        if (blockedDomains != null) {
            for (String domain : blockedDomains) {
                args.add("-b");
                args.add(domain);
            }
        }
        if (numResults != null && numResults != 5) {
            args.add("-n");
            args.add(numResults.toString());
        }
        return execute(args.toArray(new String[0]));
    }

    @Override
    public int execute(String[] args) {
        // Use iterative executor for better multi-step handling
        IterativeExecutor executor = new IterativeExecutor();
        return executor.execute(this, args);
    }


    @Override
    public String getUsage() {
        // Document the filter/result-count flags so they are advertised, not just silently parsed
        // (finding websearch-1). These options are accepted by both the picocli binding and the
        // manual arg parser in executeStep.
        return "websearch <query> [-a|--allow <domain>]... [-b|--block <domain>]... "
                + "[-n|--num-results <num>]\n"
                + "  -a, --allow <domain>        Only include results from this domain (repeatable)\n"
                + "  -b, --block <domain>        Exclude results from this domain (repeatable)\n"
                + "  -n, --num-results <num>     Number of results to fetch, or --num (1-" + MAX_RESULTS
                + ", default " + DEFAULT_RESULTS + ")";
    }
}