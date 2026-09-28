package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.metrics.ReportedUsage;
import com.eonmux.cadetcoder.ai.metrics.TokenUsage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every backend must publish the token counts its provider reported.
 *
 * <p>This is the test the absence of which let the gap exist: the parsing and the arithmetic were
 * unit-tested in isolation while no backend actually called them, so every figure the tool showed
 * stayed an estimate with nothing failing. These drive real backends over a stubbed transport with
 * real provider response bodies, and assert the counts come out the other side.</p>
 */
public class ProviderUsageReportingTest {

    private HttpClient transport;

    @Before
    public void setUp() {
        System.setProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY, "1");
        System.setProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY, "5");
        transport = mock(HttpClient.class);
        ReportedUsage.clear();
    }

    @After
    public void tearDown() {
        System.clearProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY);
        System.clearProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY);
        ReportedUsage.clear();
    }

    @SuppressWarnings("unchecked")
    private void answerWith(String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(java.util.Map.of(), (a, b) -> true));
        when(response.uri()).thenReturn(URI.create("http://localhost:1/v1"));
        when(transport.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private static PromptData prompt() {
        return new PromptData("system", "user");
    }

    @Test
    public void llamaServerPublishesOpenAiStyleUsage() throws Exception {
        answerWith("""
                {"choices": [{"message": {"content": "done"}}],
                 "usage": {"prompt_tokens": 1000, "completion_tokens": 40,
                           "prompt_tokens_details": {"cached_tokens": 750}}}""");
        LlamaServerBackend backend = new LlamaServerBackend("m", "http://localhost:1") {
            { this.httpClient = transport; }
        };

        assertThat(backend.complete(prompt(), new HashMap<>())).isEqualTo("done");

        TokenUsage usage = ReportedUsage.take().orElseThrow();
        assertThat(usage.cachedInputTokens()).isEqualTo(750);
        assertThat(usage.newInputTokens()).isEqualTo(250);
        assertThat(usage.outputTokens()).isEqualTo(40);
    }

    @Test
    public void openAiPublishesUsage() throws Exception {
        answerWith("""
                {"choices": [{"message": {"content": "done"}}],
                 "usage": {"prompt_tokens": 80, "completion_tokens": 9}}""");
        OpenAIBackend backend = new OpenAIBackend("m", "http://localhost:1/v1", "k") {
            { this.httpClient = transport; }
        };

        assertThat(backend.complete(prompt(), new HashMap<>())).isEqualTo("done");
        assertThat(ReportedUsage.take().orElseThrow().inputTokens()).isEqualTo(80);
    }

    @Test
    public void anthropicPublishesUsageWithCacheFiguresAddedToTheInput() throws Exception {
        answerWith("""
                {"content": [{"type": "text", "text": "done"}],
                 "usage": {"input_tokens": 30, "output_tokens": 500,
                           "cache_read_input_tokens": 900,
                           "cache_creation_input_tokens": 70}}""");
        AnthropicBackend backend = new AnthropicBackend("m", "http://localhost:1/v1", "k") {
            { this.httpClient = transport; }
        };

        assertThat(backend.complete(prompt(), new HashMap<>())).isEqualTo("done");

        TokenUsage usage = ReportedUsage.take().orElseThrow();
        assertThat(usage.inputTokens()).isEqualTo(1000);
        assertThat(usage.cachedInputTokens()).isEqualTo(900);
    }

    @Test
    public void geminiPublishesUsageMetadata() throws Exception {
        answerWith("""
                {"candidates": [{"content": {"parts": [{"text": "done"}]}}],
                 "usageMetadata": {"promptTokenCount": 2048, "candidatesTokenCount": 64,
                                   "cachedContentTokenCount": 2000}}""");
        GoogleGenerativeAIBackend backend =
                new GoogleGenerativeAIBackend("m", "http://localhost:1/v1beta", "k") {
                    { this.httpClient = transport; }
                };

        assertThat(backend.complete(prompt(), new HashMap<>())).isEqualTo("done");

        TokenUsage usage = ReportedUsage.take().orElseThrow();
        assertThat(usage.cachedInputTokens()).isEqualTo(2000);
        assertThat(usage.newInputTokens()).isEqualTo(48);
    }

    @Test
    public void aProviderThatReportsNoUsageLeavesTheSlotEmpty() throws Exception {
        // The fallback matters as much as the reporting: an absent usage block must leave the
        // caller free to estimate, not hand it a request that apparently cost nothing.
        answerWith("{\"choices\": [{\"message\": {\"content\": \"done\"}}]}");
        LlamaServerBackend backend = new LlamaServerBackend("m", "http://localhost:1") {
            { this.httpClient = transport; }
        };

        assertThat(backend.complete(prompt(), new HashMap<>())).isEqualTo("done");
        assertThat(ReportedUsage.take()).isEmpty();
    }

    @Test
    public void usageFromAnEarlierCallIsNotReadTwice() throws Exception {
        answerWith("""
                {"choices": [{"message": {"content": "done"}}],
                 "usage": {"prompt_tokens": 12, "completion_tokens": 3}}""");
        LlamaServerBackend backend = new LlamaServerBackend("m", "http://localhost:1") {
            { this.httpClient = transport; }
        };
        backend.complete(prompt(), new HashMap<>());

        Optional<TokenUsage> first  = ReportedUsage.take();
        Optional<TokenUsage> second = ReportedUsage.take();

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
    }

    @Test
    public void bedrockConverseUsageIsRecognised() {
        // No backend call here: Bedrock signs every request through the credential chain, which is
        // not available under test. The wire shape is what this asserts.
        Optional<TokenUsage> usage = TokenUsage.from(java.util.Map.of(
                "usage", java.util.Map.of("inputTokens", 40, "outputTokens", 8,
                                          "cacheReadInputTokens", 160)));

        assertThat(usage.orElseThrow().cachedInputTokens()).isEqualTo(160);
        assertThat(usage.orElseThrow().newInputTokens()).isEqualTo(40);
        assertThat(usage.orElseThrow().outputTokens()).isEqualTo(8);
    }

    @Test
    public void listedProvidersAllReportUsage() {
        // A backend added later that forgets to publish usage is the regression this guards: the
        // symptom is a silent return to estimates, which nothing else here would notice.
        List<String> backends = List.of("OpenAIBackend", "LlamaServerBackend",
                                        "AnthropicBackend", "GoogleGenerativeAIBackend",
                                        "OpenAICompatibleBackend", "AmazonBedrockBackend");
        for (String name : backends) {
            java.nio.file.Path source = java.nio.file.Path.of(
                    "src/main/java/com/eonmux/cadetcoder/net/" + name + ".java");
            String text;
            try {
                text = java.nio.file.Files.readString(source);
            } catch (java.io.IOException e) {
                throw new AssertionError("cannot read " + source, e);
            }
            assertThat(text)
                    .as(name + " must publish the provider's reported usage")
                    .contains("ReportedUsage.report(");
        }
    }
}
