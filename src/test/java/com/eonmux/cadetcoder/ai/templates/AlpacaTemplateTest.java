package com.eonmux.cadetcoder.ai.templates;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class AlpacaTemplateTest {

    private final AlpacaTemplate template = new AlpacaTemplate();

    @Test
    void testGetName() {
        assertEquals("alpaca", template.getName());
    }

    @Test
    void testGetDescription() {
        assertEquals("Alpaca instruction format for fine-tuned models", template.getDescription());
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
        String input  = "What is machine learning?";
        String result = template.formatSingleTurn(input);

        assertTrue(result.contains("Below is an instruction that describes a task"));
        assertTrue(result.contains("### Instruction:"));
        assertTrue(result.contains("### Input:"));
        assertTrue(result.contains("What is machine learning?"));
        assertTrue(result.contains("### Response:"));
    }

    @Test
    void testFormatSingleTurnWithEmptyInput() {
        String input  = "";
        String result = template.formatSingleTurn(input);

        assertTrue(result.contains("### Instruction:"));
        assertTrue(result.contains("### Input:"));
        assertTrue(result.contains("### Response:"));
    }

    @Test
    void testFormatConversationWithSystemPrompt() {
        String systemPrompt = "You are an expert in computer science.";
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Explain algorithms"),
                new ChatTemplate.Message("assistant", "Algorithms are step-by-step procedures for solving problems.")
                                                           );

        String result = template.formatConversation(systemPrompt, messages);

        assertTrue(result.contains("Below is an instruction that describes a task"));
        assertTrue(result.contains("### Instruction:"));
        assertTrue(result.contains("You are an expert in computer science."));
        assertTrue(result.contains("### Input:"));
        assertTrue(result.contains("Explain algorithms"));
        assertTrue(result.contains("### Response:"));
        assertTrue(result.contains("Algorithms are step-by-step procedures for solving problems."));
    }

    @Test
    void testFormatConversationWithoutSystemPrompt() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Hello world"),
                new ChatTemplate.Message("assistant", "Hello! How can I help you today?")
                                                           );

        String result = template.formatConversation(null, messages);

        assertTrue(result.contains("### Instruction:"));
        assertTrue(result.contains("### Input:"));
        assertTrue(result.contains("Hello world"));
        assertTrue(result.contains("### Response:"));
        assertTrue(result.contains("Hello! How can I help you today?"));
    }

    @Test
    void testFormatConversationWithEmptySystemPrompt() {
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "Test input")
                                                     );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("### Instruction:"));
        assertTrue(result.contains("### Input:"));
        assertTrue(result.contains("Test input"));
        assertTrue(result.contains("### Response:"));
    }

    @Test
    void testFormatConversationWithMultipleUserMessages() {
        String systemPrompt = "Answer questions about science.";
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "What is physics?"),
                new ChatTemplate.Message("user", "What is chemistry?"),
                new ChatTemplate.Message("assistant",
                        "Physics studies matter and energy. Chemistry studies substances and reactions.")
                                                           );

        String result = template.formatConversation(systemPrompt, messages);

        assertTrue(result.contains("### Instruction:"));
        assertTrue(result.contains("Answer questions about science."));
        assertTrue(result.contains("### Input:"));
        assertTrue(result.contains("What is physics?"));
        assertTrue(result.contains("What is chemistry?"));
        assertTrue(result.contains("### Response:"));
        assertTrue(result.contains("Physics studies matter and energy. Chemistry studies substances and reactions."));
    }

    @Test
    void testFormatConversationWithOnlyUserMessages() {
        String systemPrompt = "Provide helpful answers.";
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "First question"),
                new ChatTemplate.Message("user", "Second question")
                                                           );

        String result = template.formatConversation(systemPrompt, messages);

        assertTrue(result.contains("### Instruction:"));
        assertTrue(result.contains("Provide helpful answers."));
        assertTrue(result.contains("### Input:"));
        assertTrue(result.contains("First question"));
        assertTrue(result.contains("Second question"));
        assertTrue(result.endsWith("### Response:\n"));
    }

    @Test
    void testFormatConversationWithEmptyMessages() {
        String                     systemPrompt = "Be helpful and informative.";
        List<ChatTemplate.Message> messages     = new ArrayList<>();

        String result = template.formatConversation(systemPrompt, messages);

        assertTrue(result.contains("### Instruction:"));
        assertTrue(result.contains("Be helpful and informative."));
        assertTrue(result.endsWith("### Response:\n"));
        // Should not contain Input section if no user messages
        assertFalse(result.contains("### Input:"));
    }

    @Test
    void testFormatConversationWithMultipleAssistantMessages() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Tell me about cats"),
                new ChatTemplate.Message("assistant", "Cats are domestic animals."),
                new ChatTemplate.Message("assistant", "They are popular pets worldwide.")
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("Tell me about cats"));
        assertTrue(result.contains("Cats are domestic animals."));
        assertTrue(result.contains("They are popular pets worldwide."));

        // Should have multiple Response sections
        int responseCount = countOccurrences(result, "### Response:");
        assertEquals(2, responseCount);
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
    void testFormatConversationWithMixedRoles() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Question 1"),
                new ChatTemplate.Message("assistant", "Answer 1"),
                new ChatTemplate.Message("user", "Question 2"),
                new ChatTemplate.Message("assistant", "Answer 2"),
                new ChatTemplate.Message("user", "Question 3")
                                                           );

        String result = template.formatConversation("System instruction", messages);

        assertTrue(result.contains("Question 1"));
        assertTrue(result.contains("Answer 1"));
        assertTrue(result.contains("Question 2"));
        assertTrue(result.contains("Answer 2"));
        assertTrue(result.contains("Question 3"));

        // Should end with the last user message since no final response is needed
        assertTrue(result.contains("Question 3"));
        assertFalse(result.endsWith("### Response:\n"));
    }

    @Test
    void testFormatConversationWithSpecialCharacters() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "What about <tags> and \"quotes\"?"),
                new ChatTemplate.Message("assistant", "Tags like <html> and quotes like \"text\" are preserved.")
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("<tags>"));
        assertTrue(result.contains("\"quotes\""));
        assertTrue(result.contains("<html>"));
        assertTrue(result.contains("\"text\""));
    }

    @Test
    void testFormatConversationWithNewlines() {
        List<ChatTemplate.Message> messages = Arrays.asList(
                new ChatTemplate.Message("user", "Line 1\nLine 2\nLine 3"),
                new ChatTemplate.Message("assistant", "Response:\nPoint 1\nPoint 2")
                                                           );

        String result = template.formatConversation("", messages);

        assertTrue(result.contains("Line 1\nLine 2\nLine 3"));
        assertTrue(result.contains("Response:\nPoint 1\nPoint 2"));
    }

    @Test
    void testFormatConversationStructure() {
        String systemPrompt = "Test system prompt";
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "Test user message")
                                                     );

        String result = template.formatConversation(systemPrompt, messages);

        // Verify the order of sections
        int headerIndex      = result.indexOf("Below is an instruction");
        int instructionIndex = result.indexOf("### Instruction:");
        int inputIndex       = result.indexOf("### Input:");
        int responseIndex    = result.indexOf("### Response:");

        assertTrue(headerIndex < instructionIndex, "Header should come first");
        assertTrue(instructionIndex < inputIndex, "Instruction should come before Input");
        assertTrue(inputIndex < responseIndex, "Input should come before Response");
    }

    @Test
    void testFormatConversationWithToolsIgnored() {
        // Alpaca template should ignore tools since it doesn't support them
        String systemPrompt = "Test";
        List<ChatTemplate.Message> messages = List.of(
                new ChatTemplate.Message("user", "Test message")
                                                     );
        List<ChatTemplate.Tool> tools = List.of(
                new ChatTemplate.Tool("test_tool", "Test tool description", null)
                                               );

        String result       = template.formatConversationWithTools(systemPrompt, messages, tools);
        String normalResult = template.formatConversation(systemPrompt, messages);

        // Should be identical since Alpaca doesn't support tools
        assertEquals(normalResult, result);
    }
}