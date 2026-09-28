package com.eonmux.cadetcoder.ai.templates;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the chat template system
 */
public class ChatTemplateTest {

    private ChatTemplateRegistry registry;

    @BeforeEach
    void setUp() {
        registry = ChatTemplateRegistry.getInstance();
    }

    @Test
    void testRegistryHasTemplates() {
        List<String> templates = registry.listTemplateNames();
        assertNotNull(templates);
        assertTrue(templates.size() > 0);
        assertTrue(templates.contains("plain"));
        assertTrue(templates.contains("chatml"));
        assertTrue(templates.contains("alpaca"));
    }

    @Test
    void testPlainTemplate() {
        ChatTemplate template = registry.getTemplate("plain");
        assertNotNull(template);
        assertEquals("plain", template.getName());

        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "Hello")
                                                     );

        String result = template.formatConversation("System prompt", messages);
        assertTrue(result.contains("System: System prompt"));
        assertTrue(result.contains("User: Hello"));
        assertTrue(result.contains("Assistant: "));
    }

    @Test
    void testChatMLTemplate() {
        ChatTemplate template = registry.getTemplate("chatml");
        assertNotNull(template);
        assertEquals("chatml", template.getName());

        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "Hello")
                                                     );

        String result = template.formatConversation("System prompt", messages);
        assertTrue(result.contains("<|im_start|>system"));
        assertTrue(result.contains("System prompt"));
        assertTrue(result.contains("<|im_end|>"));
        assertTrue(result.contains("<|im_start|>user"));
        assertTrue(result.contains("Hello"));
        assertTrue(result.contains("<|im_start|>assistant"));
    }

    @Test
    void testDefaultTemplate() {
        ChatTemplate defaultTemplate = registry.getDefaultTemplate();
        assertNotNull(defaultTemplate);
        assertEquals("chatml", defaultTemplate.getName());
    }

    @Test
    void testUnknownTemplate() {
        ChatTemplate template = registry.getTemplate("unknown-template");
        assertNotNull(template);
        assertEquals("chatml", template.getName()); // Should return default
    }

    @Test
    void testToolClass() {
        // Test Tool constructor and getters
        ChatTemplate.Tool tool = new ChatTemplate.Tool("test_tool", "A test tool", "{\"type\": \"object\"}");

        assertEquals("test_tool", tool.getName());
        assertEquals("A test tool", tool.getDescription());
        assertEquals("{\"type\": \"object\"}", tool.getParameters());
    }

    @Test
    void testToolToJson() {
        ChatTemplate.Tool tool = new ChatTemplate.Tool("calculate", "Perform calculations", null);
        String            json = tool.toJson();

        assertNotNull(json);
        assertTrue(json.contains("\"name\":\"calculate\""));
        assertTrue(json.contains("\"description\":\"Perform calculations\""));
    }

    @Test
    void testToolCallClass() {
        // Test ToolCall constructor and getters
        String                args     = "{\"x\": 5, \"y\": 10}";
        ChatTemplate.ToolCall toolCall = new ChatTemplate.ToolCall("add", args);

        assertEquals("add", toolCall.getName());
        assertEquals(args, toolCall.getArguments());
    }

    @Test
    void testToolCallWithComplexArguments() {
        // Test with complex object arguments
        java.util.Map<String, Object> args = new java.util.HashMap<>();
        args.put("operation", "multiply");
        args.put("values", Arrays.asList(2, 3, 4));

        ChatTemplate.ToolCall toolCall = new ChatTemplate.ToolCall("math", args);

        assertEquals("math", toolCall.getName());
        assertEquals(args, toolCall.getArguments());

        @SuppressWarnings ("unchecked")
        java.util.Map<String, Object> retrievedArgs = (java.util.Map<String, Object>) toolCall.getArguments();
        assertEquals("multiply", retrievedArgs.get("operation"));
    }

    @Test
    void testMessageWithToolCalls() {
        // Test Message with tool calls
        List<ChatTemplate.ToolCall> toolCalls = Arrays.asList(
                new ChatTemplate.ToolCall("get_weather", "{\"city\": \"New York\"}"),
                new ChatTemplate.ToolCall("get_time", "{\"timezone\": \"EST\"}")
                                                             );

        ChatTemplate.Message message =
                new ChatTemplate.Message("assistant", "Let me check that for you.", null, toolCalls);

        assertEquals("assistant", message.getRole());
        assertEquals("Let me check that for you.", message.getContent());
        assertNull(message.getReasoningContent());
        assertNotNull(message.getToolCalls());
        assertEquals(2, message.getToolCalls().size());
        assertEquals("get_weather", message.getToolCalls().get(0).getName());
        assertEquals("get_time", message.getToolCalls().get(1).getName());
    }

    @Test
    void testMessageWithReasoning() {
        // Test Message with reasoning content
        ChatTemplate.Message message = new ChatTemplate.Message(
                "assistant",
                "The answer is 42.",
                "I need to calculate 6 * 7 to get the answer.",
                null
        );

        assertEquals("assistant", message.getRole());
        assertEquals("The answer is 42.", message.getContent());
        assertEquals("I need to calculate 6 * 7 to get the answer.", message.getReasoningContent());
        assertNull(message.getToolCalls());
    }

    @Test
    void testDefaultTemplateToolSupport() {
        // Test default tool support (should be false for most templates)
        ChatTemplate template = registry.getTemplate("plain");
        assertFalse(template.supportsTools());
        assertFalse(template.supportsReasoning());

        // Test formatConversationWithTools defaults to formatConversation
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "What's the weather?")
                                                     );
        List<ChatTemplate.Tool> tools = List.of(
                new ChatTemplate.Tool("get_weather", "Get weather information", null)
                                               );

        String withTools    = template.formatConversationWithTools("System", messages, tools);
        String withoutTools = template.formatConversation("System", messages);

        // Default implementation should ignore tools
        assertEquals(withoutTools, withTools);
    }

    @Test
    void testToolWithNullParameters() {
        ChatTemplate.Tool tool = new ChatTemplate.Tool("simple_tool", "No parameters needed", null);

        assertEquals("simple_tool", tool.getName());
        assertEquals("No parameters needed", tool.getDescription());
        assertNull(tool.getParameters());

        String json = tool.toJson();
        assertNotNull(json);
        assertTrue(json.contains("simple_tool"));
    }

    @Test
    void testToolCallWithNullArguments() {
        ChatTemplate.ToolCall toolCall = new ChatTemplate.ToolCall("no_args_tool", null);

        assertEquals("no_args_tool", toolCall.getName());
        assertNull(toolCall.getArguments());
    }
}