package com.eonmux.cadetcoder.ai.templates;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class PlainTemplateTest {

    private final PlainTemplate template = new PlainTemplate();

    @Test
    void testGetName() {
        assertEquals("plain", template.getName());
    }

    @Test
    void testGetDescription() {
        assertEquals("Plain text format without special tokens", template.getDescription());
    }

    @Test
    void testSupportsTools() {
        assertFalse(template.supportsTools());
    }

    @Test
    void testSupportsReasoning() {
        assertFalse(template.supportsReasoning());
    }

    @Test
    void testFormatSingleTurn() {
        String input  = "What is the weather like today?";
        String result = template.formatSingleTurn(input);

        assertTrue(result.contains("User: What is the weather like today?"));
        assertTrue(result.endsWith("Assistant: "));
    }

    @Test
    void testFormatSingleTurnWithEmptyInput() {
        String input  = "";
        String result = template.formatSingleTurn(input);

        assertTrue(result.contains("User: "));
        assertTrue(result.endsWith("Assistant: "));
    }

    @Test
    void testFormatConversationWithSystemPrompt() {
        String systemPrompt = "You are a helpful weather assistant.";
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "What's the temperature?"),
                new ChatTemplate.Message("assistant", "I don't have access to current weather data.")
                                                           );

        String result = template.formatConversation(systemPrompt, messages);

        assertTrue(result.contains("System: You are a helpful weather assistant."));
        assertTrue(result.contains("User: What's the temperature?"));
        assertTrue(result.contains("Assistant: I don't have access to current weather data."));
        assertTrue(result.endsWith("Assistant: "));
    }

    @Test
    void testFormatConversationWithoutSystemPrompt() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Hello"),
                new ChatTemplate.Message("assistant", "Hi there!")
                                                           );

        String result = template.formatConversation(null, messages);

        assertFalse(result.contains("System:"));
        assertTrue(result.contains("User: Hello"));
        assertTrue(result.contains("Assistant: Hi there!"));
        assertTrue(result.endsWith("Assistant: "));
    }

    @Test
    void testFormatConversationWithEmptySystemPrompt() {
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "Test message")
                                                     );

        String result = template.formatConversation("", messages);

        assertFalse(result.contains("System:"));
        assertTrue(result.contains("User: Test message"));
        assertTrue(result.endsWith("Assistant: "));
    }

    @Test
    void testFormatConversationWithMultipleExchanges() {
        String systemPrompt = "Be helpful and concise.";
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "First question"),
                new ChatTemplate.Message("assistant", "First answer"),
                new ChatTemplate.Message("user", "Second question"),
                new ChatTemplate.Message("assistant", "Second answer"),
                new ChatTemplate.Message("user", "Third question")
                                                           );

        String result = template.formatConversation(systemPrompt, messages);

        assertTrue(result.contains("System: Be helpful and concise."));
        assertTrue(result.contains("User: First question"));
        assertTrue(result.contains("Assistant: First answer"));
        assertTrue(result.contains("User: Second question"));
        assertTrue(result.contains("Assistant: Second answer"));
        assertTrue(result.contains("User: Third question"));
        assertTrue(result.endsWith("Assistant: "));
    }

    @Test
    void testFormatConversationWithOnlyUserMessages() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "First message"),
                new ChatTemplate.Message("user", "Second message")
                                                           );

        String result = template.formatConversation("System prompt", messages);

        assertTrue(result.contains("System: System prompt"));
        assertTrue(result.contains("User: First message"));
        assertTrue(result.contains("User: Second message"));
        assertTrue(result.endsWith("Assistant: "));
    }

    @Test
    void testFormatConversationWithEmptyMessages() {
        String                     systemPrompt = "System instruction";
        List<ChatTemplate.Message> messages     = new ArrayList<>();

        String result = template.formatConversation(systemPrompt, messages);

        assertTrue(result.contains("System: System instruction"));
        assertTrue(result.endsWith("Assistant: "));
    }

    @Test
    void testFormatConversationWithSpecialCharacters() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Special chars: <tags>, \"quotes\", [brackets], {braces}"),
                new ChatTemplate.Message("assistant", "All preserved: <html>, \"text\", [array], {json}")
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("<tags>"));
        assertTrue(result.contains("\"quotes\""));
        assertTrue(result.contains("[brackets]"));
        assertTrue(result.contains("{braces}"));
        assertTrue(result.contains("<html>"));
        assertTrue(result.contains("\"text\""));
        assertTrue(result.contains("[array]"));
        assertTrue(result.contains("{json}"));
    }

    @Test
    void testFormatConversationWithNewlines() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Multi-line\nuser\nmessage"),
                new ChatTemplate.Message("assistant", "Multi-line\nassistant\nresponse")
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("User: Multi-line\nuser\nmessage"));
        assertTrue(result.contains("Assistant: Multi-line\nassistant\nresponse"));
    }

    @Test
    void testFormatConversationStructure() {
        String systemPrompt = "Test system";
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Test user"),
                new ChatTemplate.Message("assistant", "Test assistant")
                                                           );

        String result = template.formatConversation(systemPrompt, messages);

        // Verify order
        int systemIndex         = result.indexOf("System: Test system");
        int userIndex           = result.indexOf("User: Test user");
        int assistantIndex      = result.indexOf("Assistant: Test assistant");
        int finalAssistantIndex = result.lastIndexOf("Assistant: ");

        assertTrue(systemIndex < userIndex, "System should come first");
        assertTrue(userIndex < assistantIndex, "User should come before assistant");
        assertTrue(finalAssistantIndex > assistantIndex, "Final assistant prompt should be at the end");
    }

    @Test
    void testFormatConversationSpacing() {
        String systemPrompt = "Test";
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "Message")
                                                     );

        String result = template.formatConversation(systemPrompt, messages);

        // Should have proper spacing between sections
        assertTrue(result.contains("System: Test\n\n"));
        assertTrue(result.contains("User: Message\n\n"));
    }

    @Test
    void testFormatConversationIgnoresUnknownRoles() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "User message"),
                new ChatTemplate.Message("unknown_role", "Unknown message"),
                new ChatTemplate.Message("assistant", "Assistant message")
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("User: User message"));
        assertTrue(result.contains("Assistant: Assistant message"));
        // Unknown role should be ignored
        assertFalse(result.contains("unknown_role"));
        assertFalse(result.contains("Unknown message"));
    }

    @Test
    void testFormatConversationWithSystemRole() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "User message"),
                new ChatTemplate.Message("system", "System message in conversation"),
                new ChatTemplate.Message("assistant", "Assistant message")
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("User: User message"));
        assertTrue(result.contains("Assistant: Assistant message"));
        // System role in messages should be ignored (different from system prompt)
        assertFalse(result.contains("System message in conversation"));
    }

    @Test
    void testFormatConversationWithToolsIgnored() {
        // Plain template should ignore tools since it doesn't support them
        String systemPrompt = "Test";
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "Test message")
                                                     );
        List<ChatTemplate.Tool> tools = List.of(
                new ChatTemplate.Tool("test_tool", "Test tool description", null)
                                               );

        String result       = template.formatConversationWithTools(systemPrompt, messages, tools);
        String normalResult = template.formatConversation(systemPrompt, messages);

        // Should be identical since Plain doesn't support tools
        assertEquals(normalResult, result);
    }

    @Test
    void testMinimalConversation() {
        String result = template.formatConversation(null, new ArrayList<>());
        assertEquals("Assistant: ", result);
    }

    @Test
    void testConversationEndsWithAssistantPrompt() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Question"),
                new ChatTemplate.Message("assistant", "Answer"),
                new ChatTemplate.Message("user", "Follow-up")
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.endsWith("Assistant: "), "Should always end with Assistant: ");
    }
}