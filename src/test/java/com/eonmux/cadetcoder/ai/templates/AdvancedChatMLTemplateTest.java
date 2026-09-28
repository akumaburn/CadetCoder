package com.eonmux.cadetcoder.ai.templates;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test for AdvancedChatMLTemplate to achieve 100% coverage
 */
public class AdvancedChatMLTemplateTest {

    private AdvancedChatMLTemplate template;

    @BeforeEach
    void setUp() {
        template = new AdvancedChatMLTemplate();
    }

    @Test
    void testBasicProperties() {
        assertEquals("chatml-advanced", template.getName());
        assertEquals("Advanced ChatML with tools and reasoning support", template.getDescription());
        assertTrue(template.supportsTools());
        assertTrue(template.supportsReasoning());
    }

    @Test
    void testFormatSingleTurn() {
        String result = template.formatSingleTurn("Hello, world!");
        assertTrue(result.contains("<|im_start|>user"));
        assertTrue(result.contains("Hello, world!"));
        assertTrue(result.contains("<|im_end|>"));
        assertTrue(result.contains("<|im_start|>assistant"));
    }

    @Test
    void testFormatConversationWithoutTools() {
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "What is 2+2?")
                                                     );

        String result = template.formatConversation("You are a helpful assistant", messages);

        assertTrue(result.contains("<|im_start|>system"));
        assertTrue(result.contains("You are a helpful assistant"));
        assertTrue(result.contains("<|im_end|>"));
        assertTrue(result.contains("<|im_start|>user"));
        assertTrue(result.contains("What is 2+2?"));
        assertTrue(result.contains("<|im_start|>assistant"));
    }

    @Test
    void testFormatConversationWithTools() {
        List<ChatTemplate.Tool> tools = Arrays.asList(
                new ChatTemplate.Tool("calculator", "Perform calculations", "{\"type\": \"object\"}"),
                new ChatTemplate.Tool("get_weather", "Get weather information", null)
                                                     );

        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "What is 2+2?")
                                                     );

        String result = template.formatConversationWithTools("You are an assistant", messages, tools);

        assertTrue(result.contains("# Tools"));
        assertTrue(result.contains("You may call one or more functions"));
        assertTrue(result.contains("<tools>"));
        assertTrue(result.contains("calculator"));
        assertTrue(result.contains("get_weather"));
        assertTrue(result.contains("</tools>"));
        assertTrue(result.contains("<tool_call>"));
        assertTrue(result.contains("{\"name\": <function-name>, \"arguments\": <args-json-object>}"));
        assertTrue(result.contains("</tool_call>"));
    }

    @Test
    void testFormatConversationWithEmptyTools() {
        List<ChatTemplate.Tool> tools = new ArrayList<>();
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "Hello")
                                                     );

        String result = template.formatConversationWithTools("System prompt", messages, tools);

        // Should not include tools section
        assertFalse(result.contains("# Tools"));
        assertTrue(result.contains("<|im_start|>system"));
        assertTrue(result.contains("System prompt"));
    }

    @Test
    void testFormatConversationWithNullSystemPrompt() {
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "Hello")
                                                     );

        String result = template.formatConversation(null, messages);

        // Should not include system message
        assertFalse(result.contains("<|im_start|>system"));
        assertTrue(result.contains("<|im_start|>user"));
        assertTrue(result.contains("Hello"));
    }

    @Test
    void testFormatConversationWithEmptySystemPrompt() {
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "Hello")
                                                     );

        String result = template.formatConversation("", messages);

        // Should not include system message
        assertFalse(result.contains("<|im_start|>system"));
        assertTrue(result.contains("<|im_start|>user"));
    }

    @Test
    void testFormatAssistantMessageWithReasoning() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "What is 2+2?"),
                new ChatTemplate.Message("assistant", "The answer is 4", "Let me calculate 2+2", null)
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("<|im_start|>assistant"));
        assertTrue(result.contains("<think>"));
        assertTrue(result.contains("Let me calculate 2+2"));
        assertTrue(result.contains("</think>"));
        assertTrue(result.contains("The answer is 4"));
    }

    @Test
    void testFormatAssistantMessageWithEmbeddedReasoning() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "What is 2+2?"),
                new ChatTemplate.Message("assistant", "<think>Let me calculate</think>The answer is 4", null, null)
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("<think>"));
        assertTrue(result.contains("Let me calculate"));
        assertTrue(result.contains("</think>"));
        assertTrue(result.contains("The answer is 4"));
        assertFalse(result.contains("<think>Let me calculate</think>The answer is 4"));
    }

    @Test
    void testFormatAssistantMessageWithoutReasoning() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Hello"),
                new ChatTemplate.Message("assistant", "Hi there!")
                                                           );

        String result = template.formatConversation("", messages);

        assertFalse(result.contains("<think>"));
        assertTrue(result.contains("Hi there!"));
    }

    @Test
    void testFormatAssistantMessageWithToolCalls() {
        List<ChatTemplate.ToolCall> toolCalls = Arrays.asList(
                new ChatTemplate.ToolCall("calculator", "{\"x\": 2, \"y\": 2}"),
                new ChatTemplate.ToolCall("get_time", null)
                                                             );

        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "What is 2+2 and what time is it?"),
                new ChatTemplate.Message("assistant", "Let me help you.", null, toolCalls)
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("Let me help you."));
        assertTrue(result.contains("<tool_call>"));
        assertTrue(result.contains("{\"name\":\"calculator\",\"arguments\":{\"x\":2,\"y\":2}}"));
        assertTrue(result.contains("{\"name\":\"get_time\",\"arguments\":{}}"));
        assertTrue(result.contains("</tool_call>"));
    }

    @Test
    void testFormatToolResponse() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "What is 2+2?"),
                new ChatTemplate.Message("assistant", "Let me calculate that."),
                new ChatTemplate.Message("tool", "4")
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("<tool_response>"));
        assertTrue(result.contains("4"));
        assertTrue(result.contains("</tool_response>"));
    }

    @Test
    void testFormatMultipleToolResponses() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Calculate 2+2 and 3+3"),
                new ChatTemplate.Message("assistant", "Let me calculate both."),
                new ChatTemplate.Message("tool", "Result: 4"),
                new ChatTemplate.Message("tool", "Result: 6")
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("<tool_response>"));
        assertTrue(result.contains("Result: 4"));
        assertTrue(result.contains("Result: 6"));

        // First tool response should start with user tag
        String firstToolIdx = result.substring(result.indexOf("Result: 4") - 100, result.indexOf("Result: 4"));
        assertTrue(firstToolIdx.contains("<|im_start|>user"));

        // Last tool response should end with im_end
        String afterLastTool = result.substring(result.indexOf("Result: 6"));
        assertTrue(afterLastTool.contains("<|im_end|>"));
    }

    @Test
    void testFormatToolResponseAtStart() {
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("tool", "Initial tool response")
                                                     );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("<|im_start|>user"));
        assertTrue(result.contains("<tool_response>"));
        assertTrue(result.contains("Initial tool response"));
        assertTrue(result.contains("</tool_response>"));
        assertTrue(result.contains("<|im_end|>"));
    }

    @Test
    void testSystemMessageAfterUser() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Hello"),
                new ChatTemplate.Message("system", "System update")
                                                           );

        String result = template.formatConversation("Initial system", messages);

        assertTrue(result.contains("<|im_start|>system"));
        assertTrue(result.contains("System update"));
    }

    @Test
    void testFindLastUserQuery() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "First question"),
                new ChatTemplate.Message("assistant", "First answer"),
                new ChatTemplate.Message("user", "Second question"),
                new ChatTemplate.Message("assistant", "Second answer"),
                new ChatTemplate.Message("tool", "Tool response")
                                                           );

        String result = template.formatConversation("", messages);

        // Reasoning should only appear after the last user query
        List<ChatTemplate.Message> messagesWithReasoning = Arrays.asList(
                new ChatTemplate.Message("user", "First question"),
                new ChatTemplate.Message("assistant", "First answer", "Thinking about first", null),
                new ChatTemplate.Message("user", "Second question"),
                new ChatTemplate.Message("assistant", "Second answer", "Thinking about second", null)
                                                                        );

        String resultWithReasoning = template.formatConversation("", messagesWithReasoning);

        // Only the second assistant message should have reasoning
        assertFalse(resultWithReasoning.substring(0, resultWithReasoning.indexOf("First answer"))
                                       .contains("<think>"));
        assertTrue(resultWithReasoning.substring(resultWithReasoning.indexOf("Second answer") - 100)
                                      .contains("<think>"));
    }

    @Test
    void testToolResponseDetection() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "<tool_response>Not a real tool response</tool_response>"),
                new ChatTemplate.Message("assistant", "I see your message")
                                                           );

        String result = template.formatConversation("", messages);

        // The user message should be treated as a tool response in the detection
        assertTrue(result.contains("<|im_start|>user"));
        assertTrue(result.contains("<tool_response>Not a real tool response</tool_response>"));
    }

    @Test
    void testComplexToolCallWithNonStringArguments() {
        Map<String, Object> args = new HashMap<>();
        args.put("operation", "add");
        args.put("values", Arrays.asList(1, 2, 3));

        List<ChatTemplate.ToolCall> toolCalls = List.of(
                new ChatTemplate.ToolCall("math", args)
                                                       );

        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Add numbers"),
                new ChatTemplate.Message("assistant", "", null, toolCalls)
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("<tool_call>"));
        assertTrue(result.contains("\"name\":\"math\""));
        assertTrue(result.contains("\"operation\":\"add\""),
                   "Arguments that arrive as a map must survive into the prompt, not be dropped");
        assertTrue(result.contains("\"values\":[1,2,3]"));
    }

    @Test
    void testHasSystemMessage() {
        // Test with system message present
        List<ChatTemplate.Tool> tools = List.of(
                new ChatTemplate.Tool("test", "Test tool", null)
                                               );

        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("system", "System message"),
                new ChatTemplate.Message("user", "Hello")
                                                           );

        String result = template.formatConversationWithTools("Another system", messages, tools);

        // Should include the system prompt from parameter since hasSystemMessage is true
        assertTrue(result.contains("Another system"));
    }

    @Test
    void testFormatConversationCallsFormatConversationWithTools() {
        // This test ensures formatConversation delegates to formatConversationWithTools
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "Test delegation")
                                                     );

        String result1 = template.formatConversation("System", messages);
        String result2 = template.formatConversationWithTools("System", messages, null);

        assertEquals(result1, result2);
    }

    @Test
    void testFormatMessageMethod() {
        // Test the protected formatMessage method (called internally)!
        // This is tested through the public methods, but we ensure it doesn't break anything
        StringBuilder        sb  = new StringBuilder();
        ChatTemplate.Message msg = new ChatTemplate.Message("user", "Test");

        // The method is empty in AdvancedChatMLTemplate, so we just ensure it exists
        // and the template works correctly
        String result = template.formatConversation("", List.of(msg));
        assertNotNull(result);
        assertTrue(result.length() > 0);
    }

    /**
     * Supplying tools must ADD to the system prompt, not replace it.
     *
     * <p>The system prompt was emitted only when the message list also contained a system-ROLE
     * message -- a different thing, and one nothing in this codebase produces. So every call that
     * passed tools sent the model the tools block and not a word of its instructions.</p>
     */
    @Test
    void toolsDoNotDisplaceTheSystemPrompt() {
        AdvancedChatMLTemplate template = new AdvancedChatMLTemplate();

        String result = template.formatConversationWithTools(
                "YOU ARE A CODE AGENT",
                java.util.List.of(new ChatTemplate.Message("user", "hello")),
                java.util.List.of(new ChatTemplate.Tool("ls", "list files", null)));

        assertTrue(result.contains("YOU ARE A CODE AGENT"),
                   "the system prompt must survive when tools are supplied");
        assertTrue(result.contains("# Tools"), "and the tools block must still be there");
    }

    @Test
    void withoutToolsTheSystemPromptIsStillPresent() {
        AdvancedChatMLTemplate template = new AdvancedChatMLTemplate();

        String result = template.formatConversation(
                "YOU ARE A CODE AGENT",
                java.util.List.of(new ChatTemplate.Message("user", "hello")));

        assertTrue(result.contains("YOU ARE A CODE AGENT"));
    }
}