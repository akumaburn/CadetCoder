package com.eonmux.cadetcoder.ai.templates;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class LlamaTemplateTest {

    private final LlamaTemplate template = new LlamaTemplate();

    @Test
    void testGetName() {
        assertEquals("llama2", template.getName());
    }

    @Test
    void testGetDescription() {
        assertEquals("Llama 2 chat format", template.getDescription());
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
        String input  = "What is the capital of France?";
        String result = template.formatSingleTurn(input);

        assertTrue(result.startsWith("<s>"));
        assertTrue(result.contains("[INST]"));
        assertTrue(result.contains("What is the capital of France?"));
        assertTrue(result.contains("[/INST]"));
        assertTrue(result.endsWith(" "));
    }

    @Test
    void testFormatSingleTurnWithEmptyInput() {
        String input  = "";
        String result = template.formatSingleTurn(input);

        assertTrue(result.startsWith("<s>"));
        assertTrue(result.contains("[INST]"));
        assertTrue(result.contains("[/INST]"));
    }

    @Test
    void testFormatConversationWithSystemPrompt() {
        String systemPrompt = "You are a helpful assistant that answers questions about geography.";
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "What is the capital of Italy?"),
                new ChatTemplate.Message("assistant", "The capital of Italy is Rome.")
                                                           );

        String result = template.formatConversation(systemPrompt, messages);

        assertTrue(result.startsWith("<s>"));
        assertTrue(result.contains("[INST] <<SYS>>"));
        assertTrue(result.contains("You are a helpful assistant that answers questions about geography."));
        assertTrue(result.contains("<</SYS>>"));
        assertTrue(result.contains("What is the capital of Italy?"));
        assertTrue(result.contains("[/INST]"));
        assertTrue(result.contains("The capital of Italy is Rome."));
    }

    @Test
    void testFormatConversationWithoutSystemPrompt() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Hello"),
                new ChatTemplate.Message("assistant", "Hi there!")
                                                           );

        String result = template.formatConversation(null, messages);

        assertTrue(result.startsWith("<s>"));
        assertFalse(result.contains("<<SYS>>"));
        assertFalse(result.contains("<</SYS>>"));
        assertTrue(result.contains("[INST] Hello [/INST]"));
        assertTrue(result.contains("Hi there!"));
    }

    @Test
    void testFormatConversationWithEmptySystemPrompt() {
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "Test message")
                                                     );

        String result = template.formatConversation("", messages);

        assertTrue(result.startsWith("<s>"));
        assertFalse(result.contains("<<SYS>>"));
        assertTrue(result.contains("[INST] Test message [/INST]"));
    }

    @Test
    void testFormatConversationWithMultipleExchanges() {
        String systemPrompt = "Be concise and helpful.";
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "First question"),
                new ChatTemplate.Message("assistant", "First answer"),
                new ChatTemplate.Message("user", "Second question"),
                new ChatTemplate.Message("assistant", "Second answer")
                                                           );

        String result = template.formatConversation(systemPrompt, messages);

        assertTrue(result.startsWith("<s>"));
        assertTrue(result.contains("<<SYS>>"));
        assertTrue(result.contains("Be concise and helpful."));
        assertTrue(result.contains("First question"));
        assertTrue(result.contains("First answer"));
        assertTrue(result.contains("</s>"));
        assertTrue(result.contains("Second question"));
        assertTrue(result.contains("Second answer"));

        // Should have exactly one </s> between exchanges
        int endTokenCount = countOccurrences(result, "</s>");
        assertEquals(1, endTokenCount);
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
    void testFormatConversationEndingWithUser() {
        String systemPrompt = "Answer questions clearly.";
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "First question"),
                new ChatTemplate.Message("assistant", "First answer"),
                new ChatTemplate.Message("user", "Second question")
                                                           );

        String result = template.formatConversation(systemPrompt, messages);

        assertTrue(result.contains("First question"));
        assertTrue(result.contains("First answer"));
        assertTrue(result.contains("</s>"));
        assertTrue(result.contains("Second question"));
        assertTrue(result.endsWith(" "));

        // Should not end with assistant message content
        assertFalse(result.endsWith("First answer"));
    }

    @Test
    void testFormatConversationWithOnlyUserMessage() {
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "Lone user message")
                                                     );

        String result = template.formatConversation("System prompt", messages);

        assertTrue(result.startsWith("<s>"));
        assertTrue(result.contains("<<SYS>>"));
        assertTrue(result.contains("System prompt"));
        assertTrue(result.contains("Lone user message"));
        assertTrue(result.contains("[/INST]"));
        assertTrue(result.endsWith(" "));
    }

    @Test
    void testFormatConversationWithEmptyMessages() {
        String                     systemPrompt = "System instruction";
        List<ChatTemplate.Message> messages     = new ArrayList<>();

        String result = template.formatConversation(systemPrompt, messages);

        assertTrue(result.startsWith("<s>"));
        assertTrue(result.contains("<<SYS>>"));
        assertTrue(result.contains("System instruction"));
        assertTrue(result.contains("<</SYS>>"));
        assertTrue(result.endsWith(" "));
    }

    @Test
    void testFormatConversationWithMultipleUserMessages() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Question 1"),
                new ChatTemplate.Message("user", "Question 2"),
                new ChatTemplate.Message("assistant", "Answer to both questions")
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("[INST] Question 1 [/INST]"));
        assertTrue(result.contains("[INST] Question 2 [/INST]"));
        assertTrue(result.contains("Answer to both questions"));
    }

    @Test
    void testFormatConversationWithSpecialCharacters() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "What about [brackets] and <tags>?"),
                new ChatTemplate.Message("assistant", "Brackets [like these] and tags <like these> are preserved.")
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("[brackets]"));
        assertTrue(result.contains("<tags>"));
        assertTrue(result.contains("[like these]"));
        assertTrue(result.contains("<like these>"));

        // But should not interfere with Llama-specific brackets
        assertTrue(result.contains("[INST]"));
        assertTrue(result.contains("[/INST]"));
    }

    @Test
    void testFormatConversationWithNewlines() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Multi-line\nuser\nmessage"),
                new ChatTemplate.Message("assistant", "Multi-line\nassistant\nresponse")
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("Multi-line\nuser\nmessage"));
        assertTrue(result.contains("Multi-line\nassistant\nresponse"));
    }

    @Test
    void testFormatConversationStructure() {
        String systemPrompt = "Test system";
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Test user"),
                new ChatTemplate.Message("assistant", "Test assistant")
                                                           );

        String result = template.formatConversation(systemPrompt, messages);

        // Verify order of elements
        int startIndex     = result.indexOf("<s>");
        int sysStartIndex  = result.indexOf("<<SYS>>");
        int sysEndIndex    = result.indexOf("<</SYS>>");
        int instStartIndex = result.indexOf("[INST]");
        int instEndIndex   = result.indexOf("[/INST]");

        assertEquals(0, startIndex, "Should start with <s>");
        assertTrue(sysStartIndex > startIndex, "<<SYS>> should come after <s>");
        assertTrue(sysEndIndex > sysStartIndex, "<</SYS>> should come after <<SYS>>");
        // In Llama format, [INST] and <<SYS>> can overlap in the same token sequence
        assertTrue(instStartIndex >= 0, "[INST] should be present");
        assertTrue(sysStartIndex >= 0, "<<SYS>> should be present");
        assertTrue(instEndIndex > instStartIndex, "[/INST] should come after [INST]");
    }

    @Test
    void testFormatConversationSystemPromptIntegration() {
        String systemPrompt = "You are an expert mathematician.";
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "What is 2+2?")
                                                     );

        String result = template.formatConversation(systemPrompt, messages);

        // Verify system prompt is properly enclosed and user message follows
        assertTrue(result.contains(
                "[INST] <<SYS>>\nYou are an expert mathematician.\n<</SYS>>\n\nWhat is 2+2? [/INST]"));
    }

    @Test
    void testFormatConversationWithToolsIgnored() {
        // Llama template should ignore tools since it doesn't support them
        String systemPrompt = "Test";
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "Test message")
                                                     );
        List<ChatTemplate.Tool> tools = List.of(
                new ChatTemplate.Tool("test_tool", "Test tool description", null)
                                               );

        String result       = template.formatConversationWithTools(systemPrompt, messages, tools);
        String normalResult = template.formatConversation(systemPrompt, messages);

        // Should be identical since Llama doesn't support tools
        assertEquals(normalResult, result);
    }
}