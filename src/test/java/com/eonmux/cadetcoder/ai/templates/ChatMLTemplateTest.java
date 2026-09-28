package com.eonmux.cadetcoder.ai.templates;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ChatMLTemplateTest {

    private final ChatMLTemplate template = new ChatMLTemplate();

    @Test
    void testGetName() {
        assertEquals("chatml", template.getName());
    }

    @Test
    void testGetDescription() {
        assertEquals("ChatML format used by models like GPT-4", template.getDescription());
    }

    @Test
    void testSupportsTools() {
        assertTrue(template.supportsTools());
    }

    @Test
    void testSupportsReasoning() {
        assertTrue(template.supportsReasoning());
    }

    @Test
    void testFormatSingleTurn() {
        String input  = "Hello, how are you?";
        String result = template.formatSingleTurn(input);

        assertTrue(result.contains("<|im_start|>user"));
        assertTrue(result.contains("Hello, how are you?"));
        assertTrue(result.contains("<|im_end|>"));
        assertTrue(result.contains("<|im_start|>assistant"));
    }

    @Test
    void testFormatSingleTurnWithEmptyInput() {
        String input  = "";
        String result = template.formatSingleTurn(input);

        assertTrue(result.contains("<|im_start|>user"));
        assertTrue(result.contains("<|im_end|>"));
        assertTrue(result.contains("<|im_start|>assistant"));
    }

    @Test
    void testFormatConversationWithSystemPrompt() {
        String systemPrompt = "You are a helpful assistant.";
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "What is 2+2?"),
                new ChatTemplate.Message("assistant", "The answer is 4.")
                                                           );

        String result = template.formatConversation(systemPrompt, messages);

        assertTrue(result.contains("<|im_start|>system"));
        assertTrue(result.contains("You are a helpful assistant."));
        assertTrue(result.contains("<|im_start|>user"));
        assertTrue(result.contains("What is 2+2?"));
        assertTrue(result.contains("<|im_start|>assistant"));
        assertTrue(result.contains("The answer is 4."));
        assertTrue(result.endsWith("<|im_start|>assistant\n"));
    }

    @Test
    void testFormatConversationWithoutSystemPrompt() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Hello"),
                new ChatTemplate.Message("assistant", "Hi there!")
                                                           );

        String result = template.formatConversation(null, messages);

        assertFalse(result.contains("<|im_start|>system"));
        assertTrue(result.contains("<|im_start|>user"));
        assertTrue(result.contains("Hello"));
        assertTrue(result.contains("<|im_start|>assistant"));
        assertTrue(result.contains("Hi there!"));
    }

    @Test
    void testFormatConversationWithEmptySystemPrompt() {
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "Test message")
                                                     );

        String result = template.formatConversation("", messages);

        assertFalse(result.contains("<|im_start|>system"));
        assertTrue(result.contains("<|im_start|>user"));
        assertTrue(result.contains("Test message"));
    }

    @Test
    void testFormatConversationWithReasoningContent() {
        String systemPrompt = "You are a math teacher.";
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "What is 15 * 7?"),
                new ChatTemplate.Message("assistant",
                        "The answer is 105.",
                        "Let me calculate: 15 * 7 = (10 + 5) * 7 = 70 + 35 = 105",
                        null)
                                                           );

        String result = template.formatConversation(systemPrompt, messages);

        assertTrue(result.contains("<think>"));
        assertTrue(result.contains("Let me calculate: 15 * 7 = (10 + 5) * 7 = 70 + 35 = 105"));
        assertTrue(result.contains("</think>"));
        assertTrue(result.contains("The answer is 105."));
    }

    @Test
    void testFormatConversationWithToolCalls() {
        List<ChatTemplate.ToolCall> toolCalls = List.of(
                new ChatTemplate.ToolCall("calculate", "{\"expression\": \"2+2\"}")
                                                       );

        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "What is 2+2?"),
                new ChatTemplate.Message("assistant", "Let me calculate that for you.", null, toolCalls)
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("<tool_call>"));
        assertTrue(result.contains("\"name\":\"calculate\""));
        assertTrue(result.contains("\"arguments\":{\"expression\":\"2+2\"}"));
        assertTrue(result.contains("</tool_call>"));
    }

    @Test
    void testFormatConversationWithTools() {
        String systemPrompt = "You can use tools to help answer questions.";
        List<ChatTemplate.Tool> tools = Arrays.asList(
                new ChatTemplate.Tool("calculator", "Perform mathematical calculations", null),
                new ChatTemplate.Tool("search", "Search for information", null)
                                                     );
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "What is the square root of 16?")
                                                     );

        String result = template.formatConversationWithTools(systemPrompt, messages, tools);

        assertTrue(result.contains("# Tools"));
        assertTrue(result.contains("You may call one or more functions"));
        assertTrue(result.contains("<tools>"));
        assertTrue(result.contains("\"name\":\"calculator\""));
        assertTrue(result.contains("\"name\":\"search\""));
        assertTrue(result.contains("</tools>"));
        assertTrue(result.contains("<tool_call>"));
        assertTrue(result.contains("What is the square root of 16?"));
    }

    @Test
    void theToolsSectionCarriesEachParameterSchema() {
        List<ChatTemplate.Tool> tools = List.of(
                new ChatTemplate.Tool("read", "Read a file",
                                      "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}}}")
                                               );

        String result = template.formatConversationWithTools("", List.of(
                new ChatTemplate.Message("user", "Read Main.java")), tools);

        assertTrue(result.contains("\"parameters\":{\"type\":\"object\""),
                   "The prompt calls these function signatures, so the schema has to be in them");
        assertTrue(result.contains("\"path\":{\"type\":\"string\"}"));
    }

    @Test
    void aQuoteInAToolDescriptionLeavesTheToolsSectionParseable() throws Exception {
        List<ChatTemplate.Tool> tools = List.of(
                new ChatTemplate.Tool("quote", "Wraps the \"value\" in quotes", null));

        String result = template.formatConversationWithTools("", List.of(
                new ChatTemplate.Message("user", "hi")), tools);

        String line = result.lines()
                            .filter(l -> l.startsWith("{\"name\":\"quote\""))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("No tool line in:\n" + result));

        assertEquals("Wraps the \"value\" in quotes",
                     new com.fasterxml.jackson.databind.ObjectMapper()
                             .readTree(line).get("description").asText());
    }

    @Test
    void testFormatConversationWithEmptyMessages() {
        String                     systemPrompt = "System prompt";
        List<ChatTemplate.Message> messages     = new ArrayList<>();

        String result = template.formatConversation(systemPrompt, messages);

        assertTrue(result.contains("<|im_start|>system"));
        assertTrue(result.contains("System prompt"));
        assertTrue(result.endsWith("<|im_start|>assistant\n"));
    }

    @Test
    void testFormatConversationWithMultipleRoles() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Hello"),
                new ChatTemplate.Message("assistant", "Hi! How can I help?"),
                new ChatTemplate.Message("user", "I need help with math"),
                new ChatTemplate.Message("assistant", "I'd be happy to help with math!"),
                new ChatTemplate.Message("system", "Remember to be patient"),
                new ChatTemplate.Message("user", "What is 5 + 3?")
                                                           );

        String result = template.formatConversation("", messages);

        // Count occurrences of each role
        int userCount      = countOccurrences(result, "<|im_start|>user");
        int assistantCount = countOccurrences(result, "<|im_start|>assistant");
        int systemCount    = countOccurrences(result, "<|im_start|>system");

        assertEquals(3, userCount);
        assertEquals(3, assistantCount); // 2 from messages + 1 final prompt
        assertEquals(1, systemCount);
    }

    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }

    @Test
    void testFormatConversationWithSpecialCharacters() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Can you explain <tags> and \"quotes\"?"),
                new ChatTemplate.Message("assistant", "Sure! <tags> are used for markup and \"quotes\" for strings.")
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("<tags>"));
        assertTrue(result.contains("\"quotes\""));
        assertTrue(result.contains("<|im_start|>user"));
        assertTrue(result.contains("<|im_start|>assistant"));
    }

    @Test
    void testFormatConversationWithNewlines() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Line 1\nLine 2\nLine 3"),
                new ChatTemplate.Message("assistant", "Response line 1\nResponse line 2")
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("Line 1\nLine 2\nLine 3"));
        assertTrue(result.contains("Response line 1\nResponse line 2"));
    }
}