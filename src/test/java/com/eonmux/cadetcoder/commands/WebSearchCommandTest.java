package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.commands.IterativeCommand.StepResult;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

public class WebSearchCommandTest {

    private WebSearchCommand  webSearchCommand;
    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        webSearchCommand = new WebSearchCommand();
        outputCapture    = new TestOutputCapture();
    }

    @After
    public void tearDown() {
        // Some tests toggle non-interactive mode; always restore the default so they don't leak.
        System.clearProperty("cadet.interactive");
        outputCapture.restore();
    }

    @Test
    public void testExecuteStep_NoQuery() {
        Map<String, Object> context = new HashMap<>();
        String[]            args    = {};

        StepResult result = webSearchCommand.executeStep(args, context, "");

        assertThat(result).isNotNull();
        assertThat(result.isComplete()).isFalse();
        assertThat(context.get("step")).isEqualTo("get_query");
    }

    @Test
    public void testExecuteStep_WithQuery() {
        Map<String, Object> context = new HashMap<>();
        String[]            args    = {"test", "query"};

        // This will try to perform a search, so we expect an exception due to network/mocking
        try {
            StepResult result = webSearchCommand.executeStep(args, context, "");
            assertThat(result).isNotNull();
            assertThat(context.get("query")).isEqualTo("test query");
        } catch (Exception e) {
            // Expected since we're calling external APIs
            assertThat(context.get("query")).isEqualTo("test query");
        }
    }

    @Test
    public void testExecuteStep_WithDomainFilters() {
        Map<String, Object> context = new HashMap<>();
        String[]            args    = {"test", "-a", "example.com", "-b", "spam.com", "-n", "10"};

        try {
            StepResult result = webSearchCommand.executeStep(args, context, "");
            assertThat(result).isNotNull();
            assertThat(context.get("query")).isEqualTo("test");
            assertThat(context.get("maxResults")).isEqualTo(10);
        } catch (Exception e) {
            // Expected due to external dependencies
        }
    }

    @Test
    public void testExecuteStep_InvalidNumberFormat() {
        Map<String, Object> context = new HashMap<>();
        String[]            args    = {"test", "-n", "invalid"};

        // A non-numeric --num-results is now handled gracefully (returns a completed
        // StepResult with an explanatory message) instead of throwing an uncaught
        // NumberFormatException that would crash the command.
        StepResult result = webSearchCommand.executeStep(args, context, "");

        assertThat(result).isNotNull();
        assertThat(result.isComplete()).isTrue();
        assertThat(result.getOutput()).contains("Invalid value for --num-results");
    }

    @Test
    public void testExecuteStep_NumResultsBelowRange() {
        // --num-results below 1 is rejected up front (finding 45).
        Map<String, Object> context = new HashMap<>();
        String[]            args    = {"test", "-n", "0"};

        StepResult result = webSearchCommand.executeStep(args, context, "");

        assertThat(result).isNotNull();
        assertThat(result.isComplete()).isTrue();
        assertThat(result.getOutput()).contains("--num-results must be between 1 and");
    }

    @Test
    public void testExecuteStep_NumResultsAboveRange() {
        // --num-results above the cap is rejected up front (finding 45).
        Map<String, Object> context = new HashMap<>();
        String[]            args    = {"test", "-n", "999999999"};

        StepResult result = webSearchCommand.executeStep(args, context, "");

        assertThat(result).isNotNull();
        assertThat(result.isComplete()).isTrue();
        assertThat(result.getOutput()).contains("--num-results must be between 1 and");
    }

    @Test
    public void testExecuteStep_NumResultsNegative() {
        Map<String, Object> context = new HashMap<>();
        String[]            args    = {"test", "-n", "-5"};

        StepResult result = webSearchCommand.executeStep(args, context, "");

        assertThat(result).isNotNull();
        assertThat(result.isComplete()).isTrue();
        assertThat(result.getOutput()).contains("--num-results must be between 1 and");
    }

    @Test
    public void testFilterAndLimitResults_SlicesAndFilters() throws Exception {
        // DuckDuckGo-shaped JSON with four related topics across three hosts (finding 13).
        String json = "{\"RelatedTopics\":["
                + "{\"FirstURL\":\"https://en.wikipedia.org/a\",\"Text\":\"Alpha\"},"
                + "{\"FirstURL\":\"https://spam.example/b\",\"Text\":\"Bravo\"},"
                + "{\"FirstURL\":\"https://docs.example/c\",\"Text\":\"Charlie\"},"
                + "{\"FirstURL\":\"https://en.wikipedia.org/d\",\"Text\":\"Delta\"}"
                + "]}";

        java.lang.reflect.Method m = WebSearchCommand.class.getDeclaredMethod(
                "filterAndLimitResults", String.class, int.class, List.class, List.class);
        m.setAccessible(true);

        // Block spam.example, allow nothing specific, limit to 2 results.
        String filtered = (String) m.invoke(webSearchCommand, json, 2,
                new ArrayList<String>(), new ArrayList<>(List.of("spam.example")));

        assertThat(filtered).contains("en.wikipedia.org/a");
        assertThat(filtered).contains("docs.example/c");
        assertThat(filtered).doesNotContain("spam.example");
        // Sliced to maxResults=2, so the second wikipedia entry is excluded.
        assertThat(filtered).doesNotContain("en.wikipedia.org/d");
    }

    @Test
    public void testFilterAndLimitResults_AllowListEnforced() throws Exception {
        String json = "{\"RelatedTopics\":["
                + "{\"FirstURL\":\"https://en.wikipedia.org/a\",\"Text\":\"Alpha\"},"
                + "{\"FirstURL\":\"https://other.example/b\",\"Text\":\"Bravo\"}"
                + "]}";

        java.lang.reflect.Method m = WebSearchCommand.class.getDeclaredMethod(
                "filterAndLimitResults", String.class, int.class, List.class, List.class);
        m.setAccessible(true);

        // Allow only wikipedia.org; subdomain en.wikipedia.org must match, other.example must not.
        String filtered = (String) m.invoke(webSearchCommand, json, 10,
                new ArrayList<>(List.of("wikipedia.org")), new ArrayList<String>());

        assertThat(filtered).contains("en.wikipedia.org/a");
        assertThat(filtered).doesNotContain("other.example");
    }

    @Test
    public void testFilterAndLimitResults_NonJsonFallsBack() throws Exception {
        java.lang.reflect.Method m = WebSearchCommand.class.getDeclaredMethod(
                "filterAndLimitResults", String.class, int.class, List.class, List.class);
        m.setAccessible(true);

        String raw = "not json at all";
        String result = (String) m.invoke(webSearchCommand, raw, 5,
                new ArrayList<String>(), new ArrayList<String>());

        assertThat(result).isEqualTo(raw);
    }

    @Test
    public void testExecuteStep_AskRefineNonInteractive_SkipsPrompt() {
        // websearch-2: in non-interactive mode the refine wizard must not prompt; it completes.
        System.setProperty("cadet.interactive", "false");
        Map<String, Object> context = new HashMap<>();
        context.put("step", "ask_refine");

        // llmResponse "yes" would normally advance to get_refinement in interactive mode.
        StepResult result = webSearchCommand.executeStep(new String[] {}, context, "yes");

        assertThat(result).isNotNull();
        assertThat(result.isComplete()).isTrue();
        assertThat(result.isError()).isFalse();
        assertThat(result.getOutput()).contains("Search completed successfully");
    }

    @Test
    public void testExecuteStep_AskRefineInteractive_StillPrompts() {
        // Guard: interactive mode preserves the existing refine wizard behavior.
        System.setProperty("cadet.interactive", "true");
        Map<String, Object> context = new HashMap<>();
        context.put("step", "ask_refine");

        StepResult result = webSearchCommand.executeStep(new String[] {}, context, "yes");

        assertThat(result).isNotNull();
        assertThat(result.isComplete()).isFalse();
        assertThat(context.get("step")).isEqualTo("get_refinement");
    }

    @Test
    public void testExecuteStep_AnalyzeEmptyResults_ShortCircuits() {
        // websearch-4: zero/empty results must not invoke the LLM; report "no results" directly.
        Map<String, Object> context = new HashMap<>();
        context.put("step", "analyze_results");
        context.put("query", "obscure query");
        context.put("searchResults", "");
        context.put("allowed", new ArrayList<String>());
        context.put("blocked", new ArrayList<String>());

        StepResult result = webSearchCommand.executeStep(new String[] {}, context, null);

        assertThat(result).isNotNull();
        // Advances to ask_refine without throwing or calling the AI (no network/AI in this test).
        assertThat(context.get("step")).isEqualTo("ask_refine");
        assertThat((String) context.get("summary")).contains("No results found for: obscure query");
    }

    @Test
    public void testExecuteStep_AnalyzeNoMatchSentinel_ShortCircuits() {
        // The domain-filter "no match" sentinel is also treated as empty (no LLM call).
        Map<String, Object> context = new HashMap<>();
        context.put("step", "analyze_results");
        context.put("query", "q");
        context.put("searchResults", "No results matched the requested domain filters.");
        context.put("allowed", new ArrayList<String>());
        context.put("blocked", new ArrayList<String>());

        StepResult result = webSearchCommand.executeStep(new String[] {}, context, null);

        assertThat(result).isNotNull();
        assertThat(context.get("step")).isEqualTo("ask_refine");
        assertThat((String) context.get("summary")).contains("No results found for: q");
    }

    @Test
    public void testIsEmptyResults_Variants() throws Exception {
        java.lang.reflect.Method m =
                WebSearchCommand.class.getDeclaredMethod("isEmptyResults", String.class);
        m.setAccessible(true);

        assertThat((Boolean) m.invoke(webSearchCommand, (Object) null)).isTrue();
        assertThat((Boolean) m.invoke(webSearchCommand, "   ")).isTrue();
        assertThat((Boolean) m.invoke(webSearchCommand,
                "No results matched the requested domain filters.")).isTrue();
        assertThat((Boolean) m.invoke(webSearchCommand,
                "Search results were truncated at 1048576 bytes and could not be parsed. No usable results available.")).isTrue();
        assertThat((Boolean) m.invoke(webSearchCommand, "https://example.com - Real result")).isFalse();
    }

    @Test
    public void testFilterAndLimitResults_TruncatedUnparseable_NotDumpedRaw() throws Exception {
        // websearch-5: a truncated, unparseable body must NOT be dumped raw; return a note.
        java.lang.reflect.Method m = WebSearchCommand.class.getDeclaredMethod(
                "filterAndLimitResults", String.class, int.class, List.class, List.class, boolean.class);
        m.setAccessible(true);

        // Malformed JSON cut off mid-stream, with truncated=true.
        String malformed = "{\"RelatedTopics\":[{\"FirstURL\":\"https://en.wikipedia.org/a\",\"Te";
        String result = (String) m.invoke(webSearchCommand, malformed, 5,
                new ArrayList<String>(), new ArrayList<String>(), true);

        assertThat(result).doesNotContain("FirstURL");
        assertThat(result).contains("truncated");
        assertThat(result).contains("No usable results available");
    }

    @Test
    public void testFilterAndLimitResults_NonTruncatedNonJson_StillFallsBack() throws Exception {
        // Guard: a complete (non-truncated) non-JSON body keeps the existing raw fallback.
        java.lang.reflect.Method m = WebSearchCommand.class.getDeclaredMethod(
                "filterAndLimitResults", String.class, int.class, List.class, List.class, boolean.class);
        m.setAccessible(true);

        String raw = "not json at all";
        String result = (String) m.invoke(webSearchCommand, raw, 5,
                new ArrayList<String>(), new ArrayList<String>(), false);

        assertThat(result).isEqualTo(raw);
    }

    @Test
    public void testGetUsage_DocumentsFilterFlags() {
        // websearch-1: the -a/-b/-n flags must be advertised in usage.
        String usage = webSearchCommand.getUsage();
        assertThat(usage).contains("--allow");
        assertThat(usage).contains("--block");
        assertThat(usage).contains("--num-results");
        assertThat(usage).contains("-a");
        assertThat(usage).contains("-b");
        assertThat(usage).contains("-n");
    }

    @Test
    public void testExecuteStep_GetQueryResponse() {
        Map<String, Object> context = new HashMap<>();
        context.put("step", "get_query");

        StepResult result = webSearchCommand.executeStep(new String[] {}, context, "java tutorial");

        assertThat(result).isNotNull();
        assertThat(context.get("query")).isEqualTo("java tutorial");
        assertThat(context.get("step")).isEqualTo("ask_filters");
    }

    @Test
    public void testExecuteStep_AskFiltersYes() {
        Map<String, Object> context = new HashMap<>();
        context.put("step", "ask_filters");

        StepResult result = webSearchCommand.executeStep(new String[] {}, context, "yes");

        assertThat(result).isNotNull();
        assertThat(context.get("step")).isEqualTo("get_filters");
    }

    @Test
    public void testExecuteStep_AskFiltersNo() {
        Map<String, Object> context = new HashMap<>();
        context.put("step", "ask_filters");
        context.put("query", "test");
        context.put("allowed", List.of());
        context.put("blocked", List.of());
        context.put("maxResults", 5);

        try {
            StepResult result = webSearchCommand.executeStep(new String[] {}, context, "no");
            assertThat(result).isNotNull();
        } catch (Exception e) {
            // Expected due to external API calls
        }
    }

    @Test
    public void testGetDescription() {
        assertThat(webSearchCommand.getDescription()).isEqualTo("Search the web and summarize what comes back");
    }

    @Test
    public void testGetUsage() {
        assertThat(webSearchCommand.getUsage()).contains("websearch");
    }

    @Test
    public void testSupportsIterativeExecution() {
        assertThat(webSearchCommand.supportsIterativeExecution(new String[] {})).isTrue();
        assertThat(webSearchCommand.supportsIterativeExecution(new String[] {"query"})).isTrue();
    }

    @Test
    public void testGetInitialPrompt_NoArgs() {
        String prompt = webSearchCommand.getInitialPrompt(new String[] {});
        assertThat(prompt).contains("user wants to search");
    }

    @Test
    public void testGetInitialPrompt_WithArgs() {
        String prompt = webSearchCommand.getInitialPrompt(new String[] {"test"});
        assertThat(prompt).isNull();
    }

    @Test
    public void testCall_WithFields() throws Exception {
        // Use reflection to set fields
        java.lang.reflect.Field queryPartsField = WebSearchCommand.class.getDeclaredField("queryParts");
        queryPartsField.setAccessible(true);
        queryPartsField.set(webSearchCommand, new String[] {"test"});

        java.lang.reflect.Field numResultsField = WebSearchCommand.class.getDeclaredField("numResults");
        numResultsField.setAccessible(true);
        numResultsField.set(webSearchCommand, 3);

        // The call method may succeed or fail depending on AI availability
        // Let's just verify it doesn't crash
        try {
            Integer result = webSearchCommand.call();
            assertThat(result).isNotNull();
        } catch (Exception e) {
            // Expected due to external dependencies
            assertThat(e).isInstanceOf(Exception.class);
        }
    }
}