package com.eonmux.cadetcoder.prompts;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class PromptLoadingTest {

    @Test
    public void testAllPromptsLoadSuccessfully() {
        PromptTemplateEngine engine = PromptTemplateEngine.getInstance();

        String[] promptNames = {
                "chat", "edit", "analyze", "explain", "suggest",
                "refactor", "agent", "webfetch", "websearch"
        };

        for (String promptName : promptNames) {
            String prompt = engine.getRawPrompt(promptName);
            assertNotNull("Prompt " + promptName + " should not be null", prompt);
            assertFalse("Prompt " + promptName + " should not be empty", prompt.isEmpty());
            assertFalse("Prompt " + promptName + " should not contain error message",
                    prompt.contains("Failed to load prompt content"));
            assertFalse("Prompt " + promptName + " should not contain 'No template found'",
                    prompt.contains("No template found"));

            System.out.println("[OK] " + promptName + " prompt loaded successfully (" + prompt.length() + " chars)");
        }
    }

    @Test
    public void testPromptWithVariableSubstitution() {
        PromptTemplateEngine engine = PromptTemplateEngine.getInstance();

        Map<String, String> variables = new HashMap<>();
        variables.put("code_content", "public class Test {}");
        variables.put("analysis_request", "Check for bugs");

        String prompt = engine.getPrompt("analyze", variables);

        assertNotNull("Analyze prompt should not be null", prompt);
        assertTrue("Prompt should contain substituted code content",
                prompt.contains("public class Test {}"));
        assertTrue("Prompt should contain substituted analysis request",
                prompt.contains("Check for bugs"));
        assertFalse("Prompt should not contain unsubstituted variables",
                prompt.contains("{{code_content}}"));
    }
}