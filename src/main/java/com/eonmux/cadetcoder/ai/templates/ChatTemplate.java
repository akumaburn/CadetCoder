package com.eonmux.cadetcoder.ai.templates;

import java.util.List;

/**
 * Interface for chat templates that format conversations for different LLM architectures.
 * Each template implementation handles the specific formatting requirements of its target model.
 */
public interface ChatTemplate {
    /**
     * Get the name of this template.
     *
     * @return The template name (e.g., "chatml", "alpaca", "llama3")
     */
    String getName();

    /**
     * Get a description of this template.
     *
     * @return A human-readable description
     */
    String getDescription();

    /**
     * Format a single user input for immediate response.
     *
     * @param input The user's input text
     * @return The formatted prompt ready for the model
     */
    String formatSingleTurn(String input);

    /**
     * Format a conversation with optional tool/function definitions.
     *
     * @param systemPrompt The system instruction/context
     * @param messages     The conversation messages
     * @param tools        Optional tool/function definitions
     * @return The formatted prompt ready for the model
     */
    default String formatConversationWithTools(String systemPrompt, List<Message> messages, List<Tool> tools) {
        // Default implementation ignores tools
        return formatConversation(systemPrompt, messages);
    }

    /**
     * Format a complete conversation with system prompt and message history.
     *
     * @param systemPrompt The system instruction/context
     * @param messages     The conversation messages
     * @return The formatted prompt ready for the model
     */
    String formatConversation(String systemPrompt, List<Message> messages);

    /**
     * Check if this template supports function/tool calling.
     *
     * @return true if tools are supported
     */
    default boolean supportsTools() {
        return false;
    }

    /**
     * Check if this template supports reasoning/thinking tags.
     *
     * @return true if reasoning is supported
     */
    default boolean supportsReasoning() {
        return false;
    }

    /**
     * Represents a message in the conversation.
     */
    class Message {
        private final String         role;
        private final String         content;
        private final String         reasoningContent;
        private final List<ToolCall> toolCalls;

        public Message(String role, String content) {
            this(role, content, null, null);
        }

        public Message(String role, String content, String reasoningContent, List<ToolCall> toolCalls) {
            this.role             = role;
            this.content          = content;
            this.reasoningContent = reasoningContent;
            this.toolCalls        = toolCalls;
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }

        public String getReasoningContent() {
            return reasoningContent;
        }

        public List<ToolCall> getToolCalls() {
            return toolCalls;
        }
    }

    /**
     * Represents a tool/function definition.
     */
    class Tool {
        private final String name;
        private final String description;
        private final Object parameters; // JSON schema or similar

        public Tool(String name, String description, Object parameters) {
            this.name        = name;
            this.description = description;
            this.parameters  = parameters;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public Object getParameters() {
            return parameters;
        }

        /**
         * Renders this tool as the JSON signature a prompt embeds for a model to read.
         *
         * @return a JSON object carrying the name, the description and, when one was supplied,
         *         the parameter schema
         */
        public String toJson() {
            return ToolJson.toolDefinition(name, description, parameters);
        }
    }

    /**
     * Represents a tool/function call in a message.
     */
    class ToolCall {
        private final String name;
        private final Object arguments;

        public ToolCall(String name, Object arguments) {
            this.name      = name;
            this.arguments = arguments;
        }

        public String getName() {
            return name;
        }

        public Object getArguments() {
            return arguments;
        }
    }
}