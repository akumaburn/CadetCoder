package com.eonmux.cadetcoder.net;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Chat Completions reply is read wherever the provider actually put it.
 *
 * <p><b>The defect</b>: both backends on this protocol read {@code choices[0].message.content} and
 * nothing else. A run against {@code deepseek/deepseek-v4-flash} on the Command Code gateway died
 * on "Provider 'commandcode' returned an unusable response model 'deepseek/deepseek-v4-flash': no
 * content found in message", having billed 32,718 input tokens for the request. The model answered.
 * It answered under {@code reasoning_content}, which is where DeepSeek's own API documents a
 * reasoning model's output, and {@code content} was null beside it. The same two backends turned a
 * content parts array -- the shape this client itself sends for a cache breakpoint -- into the text
 * {@code [{type=text, text=hi}]} through {@code toString()}, which is not an error and so reached
 * the model as its own reply.</p>
 *
 * <p>The third failure was the message. "no content found in message" is true of an exhausted
 * output budget, a filtered reply and a tool call alike, and those want three different actions
 * from whoever reads it.</p>
 */
public class AreplyThatArrivedUnderAnotherFieldNameIsStillTheReplyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, Object> body(String json) {
        try {
            @SuppressWarnings ("unchecked")
            Map<String, Object> parsed = MAPPER.readValue(json, Map.class);
            return parsed;
        } catch (Exception e) {
            throw new AssertionError("the fixture is not JSON", e);
        }
    }

    private static String textOf(String json) {
        return ChatCompletionContent.firstChoiceText(body(json)).orElse(null);
    }

    @Test
    public void aPlainStringIsTheReply() {
        assertThat(textOf("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                          + "\"content\":\"hello\"}}]}"))
                .isEqualTo("hello");
    }

    @Test
    public void contentPartsAreJoinedIntoTheirText() {
        assertThat(textOf("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                          + "[{\"type\":\"text\",\"text\":\"one \"},"
                          + "{\"type\":\"text\",\"text\":\"two\"}]}}]}"))
                .isEqualTo("one two");
    }

    @Test
    public void aPartsArrayIsNeverStringifiedAsAJavaList() {
        // The failure this replaces produced no exception, which is why it had to be asserted on
        // the text: a reply of "[{type=text, text=hi}]" is a reply as far as every caller is
        // concerned.
        assertThat(textOf("{\"choices\":[{\"message\":{\"content\":"
                          + "[{\"type\":\"text\",\"text\":\"hi\"}]}}]}"))
                .doesNotContain("type=text")
                .isEqualTo("hi");
    }

    @Test
    public void deepseekReasoningContentIsTheReplyWhenContentIsNull() {
        assertThat(textOf("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":"
                          + "{\"role\":\"assistant\",\"content\":null,"
                          + "\"reasoning_content\":\"the answer\"}}]}"))
                .isEqualTo("the answer");
    }

    @Test
    public void theGatewaySpellingOfTheSameFieldIsReadToo() {
        assertThat(textOf("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                          + "\"reasoning\":\"the answer\"}}]}"))
                .isEqualTo("the answer");
    }

    @Test
    public void realContentWinsOverReasoning() {
        // A reasoning model that finished normally sends both. Its conclusion is the reply; its
        // working out is not, and handing back the working out would answer with a draft.
        assertThat(textOf("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                          + "\"content\":\"the answer\","
                          + "\"reasoning_content\":\"first I should check\"}}]}"))
                .isEqualTo("the answer");
    }

    @Test
    public void blankContentBesideReasoningFallsBackToTheReasoning() {
        // The shape the failing run produced: the field is present, and empty.
        assertThat(textOf("{\"choices\":[{\"message\":{\"content\":\"\","
                          + "\"reasoning\":\"the answer\"}}]}"))
                .isEqualTo("the answer");
    }

    @Test
    public void aDeliberatelyEmptyAnswerIsStillAnAnswer() {
        // Nothing to fall back to, so the empty string is what the model said. Reporting a protocol
        // failure here would turn a terse reply into a dead run.
        assertThat(textOf("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"}}]}"))
                .isEqualTo("");
    }

    @Test
    public void aBarePartStringIsReadAsText() {
        // Some proxies flatten a part to its text rather than keep the wrapper object.
        assertThat(textOf("{\"choices\":[{\"message\":{\"content\":[\"one \",\"two\"]}}]}"))
                .isEqualTo("one two");
    }

    @Test
    public void asinglePartObjectIsReadWithoutItsList() {
        assertThat(textOf("{\"choices\":[{\"message\":{\"content\":"
                          + "{\"type\":\"text\",\"text\":\"the answer\"}}}]}"))
                .isEqualTo("the answer");
    }

    @Test
    public void apartsListWithNoTextPartCarriesNothing() {
        // An image or tool part on its own is not a reply, so it falls through to the reasoning
        // fields rather than count as an empty string the caller would hand on as the answer.
        assertThat(textOf("{\"choices\":[{\"message\":{\"content\":"
                          + "[{\"type\":\"image_url\",\"image_url\":{\"url\":\"x\"}}],"
                          + "\"reasoning\":\"the answer\"}}]}"))
                .isEqualTo("the answer");
    }

    @Test
    public void aMessageOfNothingButItsRoleNamesNoFields() {
        assertThat(ChatCompletionContent.absenceDetail(
                body("{\"choices\":[{\"message\":{\"role\":\"assistant\"}}]}")))
                .isEqualTo("no content found in message");
    }

    @Test
    public void aBlankFinishReasonIsNotReportedAsOne() {
        assertThat(ChatCompletionContent.absenceDetail(
                body("{\"choices\":[{\"finish_reason\":\"  \",\"message\":{\"role\":\"assistant\"}}]}")))
                .doesNotContain("finish_reason");
    }

    @Test
    public void nothingAnywhereIsAbsent() {
        assertThat(ChatCompletionContent.firstChoiceText(
                body("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null}}]}")))
                .isEmpty();
    }

    @Test
    public void anExhaustedOutputBudgetSaysSoAndNamesTheSetting() {
        String detail = ChatCompletionContent.absenceDetail(
                body("{\"choices\":[{\"finish_reason\":\"length\","
                     + "\"message\":{\"role\":\"assistant\",\"content\":null}}]}"));

        assertThat(detail).contains("output limit");
        assertThat(detail).contains("ai.maxTokens");
    }

    @Test
    public void aFilteredReplyIsNotReportedAsAnEmptyOne() {
        assertThat(ChatCompletionContent.absenceDetail(
                body("{\"choices\":[{\"finish_reason\":\"content_filter\","
                     + "\"message\":{\"content\":null}}]}")))
                .contains("filtered");
    }

    @Test
    public void aToolCallIsNamedRatherThanCalledEmpty() {
        assertThat(ChatCompletionContent.absenceDetail(
                body("{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":"
                     + "{\"content\":null,\"tool_calls\":[{\"id\":\"c1\"}]}}]}")))
                .contains("tool call");
    }

    @Test
    public void anUnexplainedEmptyMessageReportsWhatItDidCarry() {
        // The one case with no known cause still has to hand the reader something to act on, so it
        // names the fields that were there and the finish reason that came with them.
        String detail = ChatCompletionContent.absenceDetail(
                body("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":"
                     + "{\"role\":\"assistant\",\"content\":null,\"refusal\":null}}]}"));

        assertThat(detail).startsWith("no content found in message");
        assertThat(detail).contains("refusal");
        assertThat(detail).contains("finish_reason: stop");
        assertThat(detail).doesNotContain("role");
    }

    @Test
    public void aResponseWithNoChoicesIsNotAMessageAtAll() {
        assertThat(ChatCompletionContent.hasChoices(body("{\"choices\":[]}"))).isFalse();
        assertThat(ChatCompletionContent.hasChoices(body("{}"))).isFalse();
        assertThat(ChatCompletionContent.hasChoices(
                body("{\"choices\":[{\"message\":{\"content\":\"x\"}}]}"))).isTrue();
    }

    @Test
    public void aChoiceWithoutAMessageIsAbsentRatherThanAnError() {
        assertThat(ChatCompletionContent.firstChoiceText(body("{\"choices\":[{\"index\":0}]}")))
                .isEmpty();
        assertThat(ChatCompletionContent.absenceDetail(body("{\"choices\":[{\"index\":0}]}")))
                .startsWith("no content found in message");
    }

    // The other protocol a gateway can put the same model on. ModelWire routes claude-* to it, so a
    // reasoning reply arrives here as a thinking block rather than under a reasoning field.

    /** Drives the Anthropic backend's own extraction against one canned reply. */
    private static String anthropicText(String json) throws Exception {
        AnthropicBackend backend = new AnthropicBackend("claude-sonnet-4", null, "a-key");
        java.lang.reflect.Method extract =
                AnthropicBackend.class.getDeclaredMethod("extractContent", String.class);
        extract.setAccessible(true);
        try {
            return (String) extract.invoke(backend, json);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    public void aThinkingBlockBesideTextIsNotTheReply() throws Exception {
        assertThat(anthropicText("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"working\"},"
                                 + "{\"type\":\"text\",\"text\":\"the answer\"}]}"))
                .isEqualTo("the answer");
    }

    @Test
    public void aReplyOfThinkingAloneIsHandedBackRatherThanFailed() throws Exception {
        assertThat(anthropicText("{\"stop_reason\":\"max_tokens\","
                                 + "\"content\":[{\"type\":\"thinking\",\"thinking\":\"working\"}]}"))
                .isEqualTo("working");
    }

    @Test
    public void anAnthropicReplyWithNoReadableBlockNamesWhatItCarried() {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> anthropicText("{\"stop_reason\":\"max_tokens\","
                                    + "\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\"}]}"));

        assertThat(thrown).isInstanceOf(LLMProtocolException.class);
        assertThat(thrown).hasMessageContaining("output limit");
        assertThat(thrown).hasMessageContaining("ai.maxTokens");
    }
}
