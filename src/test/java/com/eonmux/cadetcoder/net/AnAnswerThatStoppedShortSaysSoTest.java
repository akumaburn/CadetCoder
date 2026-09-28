package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Three wires that never read the provider's own account of why it stopped.
 *
 * <h2>The defect</h2>
 *
 * <p>Every one of these APIs says why a turn ended -- {@code finish_reason} on Chat Completions,
 * {@code finishReason} on Gemini, {@code stopReason} on Converse -- and three of the six backends
 * shipped here threw the field away. A model that spent its whole output budget before writing
 * anything came back as "no content found in message", or as "no completion choices returned", or,
 * on the local wire, as a null completion handed to the caller as the model's answer. The one fact
 * worth reporting -- that the budget ran out, and which setting raises it -- was the one fact
 * dropped, and the user was left looking at a message that named nothing they could do.</p>
 *
 * <p>{@code AnthropicBackend} already did this properly, naming {@code stop_reason} in the failure;
 * these three now say the same thing in each protocol's own spelling.</p>
 *
 * <h2>Why the reply is stubbed rather than fetched</h2>
 *
 * <p>The payloads below are the shapes these providers really answer a truncated turn with, and no
 * provider can be asked for one from a test suite. What is under test is this project's reading of
 * them, which is the part this project owns.</p>
 */
class AnAnswerThatStoppedShortSaysSoTest {

    /** A client that answers every request with one canned 200. */
    private static HttpClient answering(String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        HttpClient client = mock(HttpClient.class);
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
        return client;
    }

    private static Throwable failureFrom(AbstractLLMBackend backend) {
        return catchThrowable(() -> backend.complete(new PromptData("sys", "hi"), new HashMap<>()));
    }

    // ------------------------------------------------------------------ llama-server

    @Test
    void theLocalWireSaysWhenTheOutputLimitEndedTheTurn() {
        String body = "{\"choices\":[{\"finish_reason\":\"length\","
                      + "\"message\":{\"role\":\"assistant\",\"content\":\"\"}}]}";
        LlamaServerBackend backend = new LlamaServerBackend("local-x", "http://127.0.0.1:8080") {
            { this.httpClient = answering(body); }
        };

        assertThat(failureFrom(backend))
                .isInstanceOf(LLMProtocolException.class)
                .hasMessageContaining("finish_reason: length")
                .hasMessageContaining("ai.maxTokens");
    }

    @Test
    void theLocalWireReadsAreasoningModelsReplyRatherThanCallingItEmpty() throws Exception {
        // llama-server leaves content null for the models that emit a separate reasoning field, and
        // the cast that used to read this handed back that null as the completion.
        String body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                      + "\"content\":null,\"reasoning_content\":\"The answer is 42.\"}}]}";
        LlamaServerBackend backend = new LlamaServerBackend("local-x", "http://127.0.0.1:8080") {
            { this.httpClient = answering(body); }
        };

        assertThat(backend.complete(new PromptData("sys", "hi"), new HashMap<>()))
                .isEqualTo("The answer is 42.");
    }

    // ------------------------------------------------------------------ Google Generative AI

    @Test
    void googleSaysWhenTheOutputLimitEndedTheTurn() {
        // A candidate with a finishReason and no content key at all is exactly what Gemini answers
        // with here, and reading it only for its parts reported a protocol error naming nothing.
        String body = "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"index\":0}]}";
        GoogleGenerativeAIBackend backend =
                new GoogleGenerativeAIBackend("gemini-x", "https://example.invalid/v1beta", "key") {
                    { this.httpClient = answering(body); }
                };

        assertThat(failureFrom(backend))
                .isInstanceOf(LLMProtocolException.class)
                .hasMessageContaining("finishReason: MAX_TOKENS")
                .hasMessageContaining("ai.maxTokens");
    }

    @Test
    void googleSaysWhenTheReplyWasFiltered() {
        String body = "{\"candidates\":[{\"finishReason\":\"SAFETY\",\"index\":0}]}";
        GoogleGenerativeAIBackend backend =
                new GoogleGenerativeAIBackend("gemini-x", "https://example.invalid/v1beta", "key") {
                    { this.httpClient = answering(body); }
                };

        assertThat(failureFrom(backend))
                .isInstanceOf(LLMProtocolException.class)
                .hasMessageContaining("filtered")
                .hasMessageContaining("SAFETY");
    }

    // ------------------------------------------------------------------ Bedrock Converse

    @Test
    void bedrockSaysWhenTheOutputLimitEndedTheTurn() {
        String body = "{\"stopReason\":\"max_tokens\",\"output\":{\"message\":"
                      + "{\"role\":\"assistant\",\"content\":[]}}}";
        AmazonBedrockBackend backend =
                new AmazonBedrockBackend("model-x", "https://example.invalid", "us-east-1",
                                         "bearer", null) {
                    { this.httpClient = answering(body); }
                };

        assertThat(failureFrom(backend))
                .isInstanceOf(LLMProtocolException.class)
                .hasMessageContaining("stopReason: max_tokens")
                .hasMessageContaining("ai.maxTokens");
    }

    @Test
    void bedrockNamesWhateverElseItStoppedFor() {
        String body = "{\"stopReason\":\"guardrail_intervened\",\"output\":{\"message\":"
                      + "{\"role\":\"assistant\",\"content\":[]}}}";
        AmazonBedrockBackend backend =
                new AmazonBedrockBackend("model-x", "https://example.invalid", "us-east-1",
                                         "bearer", null) {
                    { this.httpClient = answering(body); }
                };

        assertThat(failureFrom(backend))
                .isInstanceOf(LLMProtocolException.class)
                .hasMessageContaining("guardrail_intervened");
    }

    @Test
    void areplyThatArrivedIsStillTheReplyOnEveryWire() throws Exception {
        AmazonBedrockBackend bedrock =
                new AmazonBedrockBackend("model-x", "https://example.invalid", "us-east-1",
                                         "bearer", null) {
                    {
                        this.httpClient = answering(
                                "{\"stopReason\":\"max_tokens\",\"output\":{\"message\":{\"role\":"
                                + "\"assistant\",\"content\":[{\"text\":\"half an ans\"}]}}}");
                    }
                };

        // A truncated ANSWER is still the answer, and the loop can act on the part that arrived.
        // What is withheld is an absent reply reported as though nothing had gone wrong.
        assertThat(bedrock.complete(new PromptData("sys", "hi"), new HashMap<>()))
                .isEqualTo("half an ans");
    }
}
