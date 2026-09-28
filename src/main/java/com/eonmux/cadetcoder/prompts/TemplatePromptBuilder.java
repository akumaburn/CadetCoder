package com.eonmux.cadetcoder.prompts;

import java.util.HashMap;
import java.util.Map;

/**
 * A fluent builder for constructing prompts with variable substitution.
 */
public class TemplatePromptBuilder {
    private final String               promptName;
    private final Map<String, String>  variables = new HashMap<>();
    private final PromptTemplateEngine templateEngine;

    public TemplatePromptBuilder(String promptName) {
        this.promptName     = promptName;
        this.templateEngine = PromptTemplateEngine.getInstance();
    }

    public static TemplatePromptBuilder chat() {
        return new TemplatePromptBuilder("chat");
    }

    public static TemplatePromptBuilder edit() {
        return new TemplatePromptBuilder("edit");
    }

    public static TemplatePromptBuilder analyze() {
        return new TemplatePromptBuilder("analyze");
    }

    public static TemplatePromptBuilder explain() {
        return new TemplatePromptBuilder("explain");
    }

    // Static factory methods for common prompts

    public static TemplatePromptBuilder suggest() {
        return new TemplatePromptBuilder("suggest");
    }

    public static TemplatePromptBuilder refactor() {
        return new TemplatePromptBuilder("refactor");
    }

    public static TemplatePromptBuilder agent() {
        return new TemplatePromptBuilder("agent");
    }

    public static TemplatePromptBuilder webFetch() {
        return new TemplatePromptBuilder("webfetch");
    }

    public static TemplatePromptBuilder webSearch() {
        return new TemplatePromptBuilder("websearch");
    }

    /**
     * Add a variable substitution.
     *
     * @param name  The variable name (without curly braces)
     * @param value The value to substitute
     * @return This builder for chaining
     */
    public TemplatePromptBuilder with(String name, String value) {
        if (name != null && value != null) {
            variables.put(name, value);
        }
        return this;
    }

    /**
     * Add multiple variables at once.
     *
     * @param vars Map of variable names to values
     * @return This builder for chaining
     */
    public TemplatePromptBuilder withAll(Map<String, String> vars) {
        if (vars != null) {
            variables.putAll(vars);
        }
        return this;
    }

    /**
     * Build the final prompt with all substitutions applied.
     *
     * @return The processed prompt text
     */
    public String build() {
        return templateEngine.getPrompt(promptName, variables);
    }

    /**
     * Get the raw template without substitutions.
     *
     * @return The raw template text
     */
    public String getRawTemplate() {
        return templateEngine.getRawPrompt(promptName);
    }
}