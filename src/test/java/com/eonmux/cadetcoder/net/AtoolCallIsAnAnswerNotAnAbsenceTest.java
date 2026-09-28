package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.ai.parsing.ParsedResponse;
import com.eonmux.cadetcoder.ai.parsing.ParsingContext;
import com.eonmux.cadetcoder.ai.parsing.ToolCallParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A reply that carries a tool call and no text is an answer.
 *
 * <h2>The defect</h2>
 *
 * <p>Every protocol has a place for a tool call outside the reply text: {@code tool_calls} on Chat
 * Completions, a {@code tool_use} block on Anthropic, a {@code toolUse} block on Bedrock, a
 * {@code functionCall} part on Gemini. This client declares no tools, so nothing looked in any of
 * them -- but a model does not need tools declared to answer with one. The command catalogue in its
 * prompt reads to a tool-calling model as a list of tools, and that is how such models routinely
 * answer, particularly on the first turn of a session.</p>
 *
 * <p>Read only for text, those replies carried none. The turn failed with "no content found in
 * message: the model answered with a tool call, which this client never asks for", the exception
 * ended the run, and the reply that caused it had named the command and every one of its arguments.
 * The call is handed on as text now, which is the one thing the parsing engine reads, so it is
 * parsed with the rest and dispatched like any other action.</p>
 */
public class AtoolCallIsAnAnswerNotAnAbsenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, Object> body(String json) {
        try {
            @SuppressWarnings ("unchecked")
            Map<String, Object> parsed = MAPPER.readValue(json, Map.class);
            return parsed;
        } catch (Exception notJson) {
            throw new AssertionError("the fixture is not JSON", notJson);
        }
    }

    /**
     * The command the handed-back text dispatches to, which is the whole point of handing it back.
     *
     * @param text what a backend returned as the reply
     * @return the command the parsing engine reads out of it
     */
    private static String commandIn(String text) {
        ParsingContext context = new ParsingContext.Builder("read the main class")
                .setAvailableCommands(new CommandRegistry().getCommands().keySet())
                .build();
        ParsedResponse parsed = new ToolCallParser().parse(text, context);
        assertThat(parsed.getActions()).as("the handed-back text must parse as the call").isNotEmpty();
        return parsed.getActions().get(0).getCommand();
    }

    /** Calls a backend's private {@code extractContent}, which is where the reply is decided. */
    private static String extracted(Object backend, String responseBody) throws Exception {
        Method extract = backend.getClass().getDeclaredMethod("extractContent", String.class);
        extract.setAccessible(true);
        return (String) extract.invoke(backend, responseBody);
    }

    @Test
    public void achatCompletionsToolCallIsHandedBackAsText() {
        String text = ChatCompletionContent.firstChoiceText(body(
                "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":"
                + "{\"name\":\"read\",\"arguments\":\"{\\\"file_path\\\":\\\"src/Main.java\\\"}\"}}]"
                + "}}]}")).orElse(null);

        assertThat(text).isNotNull();
        assertThat(commandIn(text)).isEqualTo("read");
        assertThat(text).contains("src/Main.java");
    }

    /** Text wins whenever there is any: a call beside it is the model's own working. */
    @Test
    public void textStillWinsOverAcallBesideIt() {
        assertThat(ChatCompletionContent.firstChoiceText(body(
                "{\"choices\":[{\"message\":{\"content\":\"the answer\",\"tool_calls\":"
                + "[{\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]}}]}")))
                .contains("the answer");
    }

    /** A call naming no tool is nothing to hand back, and is still reported as an absence. */
    @Test
    public void acallThatNamesNoToolIsStillReportedAsAnAbsence() {
        Map<String, Object> reply = body(
                "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":"
                + "{\"content\":null,\"tool_calls\":[{\"id\":\"c1\"}]}}]}");

        assertThat(ChatCompletionContent.firstChoiceText(reply)).isEmpty();
        assertThat(ChatCompletionContent.absenceDetail(reply)).contains("tool call");
    }

    @Test
    public void ananthropicToolUseBlockIsHandedBackAsText() throws Exception {
        String text = extracted(new AnthropicBackend("claude-sonnet-4-5", null, "a-key"),
                                "{\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"tool_use\","
                                + "\"id\":\"t1\",\"name\":\"read\",\"input\":"
                                + "{\"file_path\":\"src/Main.java\"}}]}");

        assertThat(commandIn(text)).isEqualTo("read");
        assertThat(text).contains("src/Main.java");
    }

    @Test
    public void abedrockToolUseBlockIsHandedBackAsText() throws Exception {
        String text = extracted(new AmazonBedrockBackend("anthropic.claude-3-5-sonnet-20240620-v1:0",
                                                         null, "us-east-1", "a-key", null),
                                "{\"stopReason\":\"tool_use\",\"output\":{\"message\":{\"content\":"
                                + "[{\"toolUse\":{\"toolUseId\":\"t1\",\"name\":\"read\",\"input\":"
                                + "{\"file_path\":\"src/Main.java\"}}}]}}}");

        assertThat(commandIn(text)).isEqualTo("read");
        assertThat(text).contains("src/Main.java");
    }

    @Test
    public void ageminiFunctionCallPartIsHandedBackAsText() throws Exception {
        String text = extracted(new GoogleGenerativeAIBackend("gemini-2.5-pro", null, "a-key"),
                                "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":"
                                + "[{\"functionCall\":{\"name\":\"read\",\"args\":"
                                + "{\"file_path\":\"src/Main.java\"}}}]}}]}");

        assertThat(commandIn(text)).isEqualTo("read");
        assertThat(text).contains("src/Main.java");
    }
}
