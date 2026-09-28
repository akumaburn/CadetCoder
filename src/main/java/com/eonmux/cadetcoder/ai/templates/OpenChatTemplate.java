package com.eonmux.cadetcoder.ai.templates;

import java.util.List;

/**
 * OpenChat template implementation.
 * Format: GPT4 User: {prompt}<|end_of_turn|>GPT4 Assistant:
 */
public class OpenChatTemplate extends AbstractChatTemplate {

    public OpenChatTemplate() {
        super("openchat", "OpenChat format for GPT4-like models");
    }

    @Override
    public String formatConversation(String systemPrompt, List<Message> messages) {
        StringBuilder prompt = buildConversation();

        // Add system message as first user message if provided
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            append(prompt, "GPT4 System: ");
            append(prompt, systemPrompt);
            appendLine(prompt, "<|end_of_turn|>");
        }

        // Format messages
        for (Message message : messages) {
            formatMessage(prompt, message);
        }

        // Start assistant response
        append(prompt, "GPT4 Assistant:");

        return prompt.toString();
    }

    @Override
    protected void formatMessage(StringBuilder dialogue, Message message) {
        String role    = message.getRole();
        String content = message.getContent();

        if ("user".equals(role)) {
            append(dialogue, "GPT4 User: ");
            append(dialogue, content);
            appendLine(dialogue, "<|end_of_turn|>");
        } else if ("assistant".equals(role)) {
            append(dialogue, "GPT4 Assistant: ");
            append(dialogue, content);
            appendLine(dialogue, "<|end_of_turn|>");
        } else if ("system".equals(role)) {
            append(dialogue, "GPT4 System: ");
            append(dialogue, content);
            appendLine(dialogue, "<|end_of_turn|>");
        }
    }
}