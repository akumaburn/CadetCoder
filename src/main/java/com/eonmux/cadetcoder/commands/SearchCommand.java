package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.context.ContextEngine;
import com.eonmux.cadetcoder.context.ContextMatch;
import com.eonmux.cadetcoder.error.ErrorHandler;
import org.apache.lucene.queryparser.classic.ParseException;
import picocli.CommandLine.Command;

import java.io.File;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Command (name = "search", description = "Find code by meaning, using the project index")
public class SearchCommand extends LoggingCommandSupport implements IterativeCommand {

    private static final DateTimeFormatter INDEX_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    public int execute(String[] args) {
        try {
            startCommandLogging("search", args);
            logStep("Initializing search command");
            
            // Check if we should use iterative execution
            if (args == null || args.length == 0) {
                logStep("No query provided, starting iterative mode");
                // No query provided, use iterative mode to ask for it
                IterativeExecutor executor = new IterativeExecutor();
                int result = executor.execute(this, args);
                completeCommandLogging(result);
                return result;
            }

            // Direct execution if query is provided and clear
            String searchQuery = String.join(" ", args);
            logStep("Direct search execution", String.format("Query: '%s'", searchQuery));

            long searchStartTime = System.currentTimeMillis();
            List<ContextMatch> matches = runSearch(searchQuery);
            // The clock is stopped where the search stops. Read after the freshness report instead,
            // the figure logged as "Context search" also carried a config read, a directory listing
            // and a lastModified() per index file -- work the user did not ask this number about.
            long searchDuration = System.currentTimeMillis() - searchStartTime;
            // Reported AFTER the search, and only when it can say something true. The search itself
            // brings the index up to date, so asking beforehand answered "Index not yet built;
            // results may be incomplete" and then returned complete results.
            printIndexFreshness();

            logPerformance("Context search", searchDuration);
            logDataProcessing("search", "results", matches.size(), searchDuration);

            if (matches.isEmpty()) {
                logWarning("No search results", String.format("No snippets found for query: '%s'", searchQuery));
                OutputFormatter.printWarning("No relevant snippets found.");
            } else {
                logStep("Search completed successfully", String.format("Found %d matches", matches.size()));
                SearchReport.show(matches);
            }

            completeCommandLogging(0);
            return 0;
        } catch (Exception e) {
            logError("execute", "Search execution failed", e);
            ErrorHandler.getInstance().handleException(e);
            completeCommandLogging(1);
            return 1;
        }
    }


    @Override
    public String getUsage() {
        return "search \"<query>\"";
    }

    @Override
    public StepResult executeStep(String[] args, Map<String, Object> context, String llmResponse) {
        String step = (String) context.getOrDefault("step", "initial");

        switch (step) {
            case "initial":
                if (args == null || args.length == 0) {
                    context.put("step", "get_query");
                    return new StepResult(false, "No search query provided", context,
                            "What would you like to search for in the codebase?");
                }
                // Perform initial search
                return performSearch(String.join(" ", args), context);

            case "get_query":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    return performSearch(llmResponse.trim(), context);
                }
                return StepResult.failure("No query provided, search cancelled", context);

            case "refine_search":
                if (llmResponse != null) {
                    String response = llmResponse.trim().toLowerCase();
                    boolean negated = response.matches(".*\\b(no|not|don't|do not|never)\\b.*");
                    boolean affirmative = response.equals("y") || response.equals("yes")
                            || response.startsWith("yes")
                            || response.matches(".*\\brefine\\b.*");
                    if (affirmative && !negated) {
                        context.put("step", "get_refined_query");
                        Object resultsObj = context.get("results");
                        StringBuilder promptBuilder = new StringBuilder();
                        promptBuilder.append("Based on these results:\n");
                        if (resultsObj instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<String> previousResults = (List<String>) resultsObj;
                            for (int i = 0; i < Math.min(3, previousResults.size()); i++) {
                                promptBuilder.append("- ").append(previousResults.get(i)).append("\n");
                            }
                        }
                        promptBuilder.append("\nWhat refined search query would help find more relevant results?");
                        return new StepResult(false, "Refining search...", context, promptBuilder.toString());
                    }
                }
                return StepResult.success("Search completed", context);

            case "get_refined_query":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    return performSearch(llmResponse.trim(), context);
                }
                return StepResult.failure("No refined query provided", context);
        }

        return StepResult.failure("Unknown step: " + step, context);
    }

    private StepResult performSearch(String query, Map<String, Object> context) {
        try {
            logStep("Performing iterative search", String.format("Query: '%s'", query));
            
            long searchStartTime = System.currentTimeMillis();
            List<ContextMatch> matches = runSearch(query);
            long searchDuration = System.currentTimeMillis() - searchStartTime;

            logPerformance("Iterative context search", searchDuration);
            logDataProcessing("iterative_search", "results", matches.size(), searchDuration);

            if (matches.isEmpty()) {
                logWarning("No iterative search results", String.format("No snippets found for query: '%s'", query));
                context.put("step", "refine_search");
                context.put("lastQuery", query);
                context.put("results", java.util.Collections.<String>emptyList());
                return new StepResult(false, "No results found for: " + query, context,
                        "No snippets found. Would you like to refine your search query?");
            }

            // Store results for potential refinement. Rendered for the model here, because the
            // refinement step quotes them back and a ContextMatch is not what that prompt carries.
            List<String> rendered = new ArrayList<>();
            for (ContextMatch match : matches) {
                rendered.add(match.forPrompt());
            }
            context.put("results", rendered);
            context.put("lastQuery", query);
            context.put("step", "refine_search");

            logStep("Iterative search completed", String.format("Found %d matches", matches.size()));

            StringBuilder output = new StringBuilder();
            output.append("Found ").append(matches.size())
                  .append(matches.size() == 1 ? " result:\n\n" : " results:\n\n");
            for (String snippet : rendered) {
                output.append(snippet).append("\n");
            }

            return new StepResult(false, output.toString(), context,
                    "Found " + matches.size() + (matches.size() == 1 ? " result." : " results.")
                    + " Would you like to refine your search?");

        } catch (Exception e) {
            logError("performSearch", "Iterative search failed", e);
            return StepResult.failure("Search error: " + e.getMessage(), context);
        }
    }

    /**
     * Runs a context search.
     *
     * <p>The query goes in as the user typed it. {@link ContextEngine} builds the query from the
     * words the analyzer finds in the text and never hands it to a query parser, so no character in
     * it is syntax. Escaping here would put backslashes into the text that the analyzer would then
     * have to make terms of.</p>
     *
     * <p>A query that still cannot be parsed is reported as no results rather than as an error. The
     * command's answer to "nothing matched" is already a sentence the user can act on, and a parser
     * message is not.</p>
     *
     * @param rawQuery the query text, as the user typed it
     * @return the matching files and the lines that matched, or an empty list when the query
     *         cannot be parsed
     * @throws Exception if the search fails for a reason unrelated to query parsing
     */
    private List<ContextMatch> runSearch(String rawQuery) throws Exception {
        try {
            return ContextEngine.getInstance().find(rawQuery == null ? "" : rawQuery);
        } catch (ParseException e) {
            logWarning("Unparseable search query",
                    String.format("Query could not be parsed: '%s'", rawQuery));
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Surfaces when the on-disk index was last built (index-search-4) so the caller can judge the
     * freshness of the results. This is best-effort: any failure to read the index location is
     * swallowed so it never blocks a search.
     */
    private void printIndexFreshness() {
        try {
            Configuration.IndexingConfig indexing =
                    ConfigManager.getInstance().getConfig().getIndexing();
            if (indexing.isEnabled()) {
                // The search refreshed the index on the way in, so there is no staleness to warn
                // about and a build time would only invite the reader to wonder whether it matters.
                return;
            }
            String indexLocation = indexing.getIndexLocation();
            if (indexLocation == null || indexLocation.trim().isEmpty()) {
                return;
            }
            File indexDir = new File(indexLocation);
            if (!indexDir.exists()) {
                OutputFormatter.printInfo("No index has been built, and automatic indexing is off; "
                        + "run '" + CommandUsage.prefix() + "index' to build one.");
                return;
            }
            FileTime lastBuilt = newestModified(indexDir);
            if (lastBuilt != null) {
                OutputFormatter.printInfo("Automatic indexing is off; these results are from the "
                        + "index built at "
                        + INDEX_TIME_FORMAT.format(Instant.ofEpochMilli(lastBuilt.toMillis())) + ".");
            }
        } catch (Exception e) {
            // Freshness reporting is advisory only; never fail the search because of it.
            logWarning("Index freshness", "Could not determine index build time");
        }
    }

    /**
     * Finds the most recent last-modified time across an index directory and its immediate files.
     *
     * @param indexDir the index directory (or file) to inspect
     * @return the newest modification time found, or {@code null} if none could be read
     */
    private FileTime newestModified(File indexDir) {
        long newest = indexDir.lastModified();
        File[] children = indexDir.listFiles();
        if (children != null) {
            for (File child : children) {
                newest = Math.max(newest, child.lastModified());
            }
        }
        return newest > 0 ? FileTime.fromMillis(newest) : null;
    }

    @Override
    public String getInitialPrompt(String[] args) {
        if (args == null || args.length == 0) {
            return "The user wants to search the codebase but didn't specify what to search for. What should we search for?";
        }
        return null;
    }

    @Override
    public boolean supportsIterativeExecution(String[] args) {
        // Support iterative execution if no query provided or for refinement
        return true;
    }
}
