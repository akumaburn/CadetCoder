package com.eonmux.cadetcoder.ai.templates;

import java.util.List;

/**
 * Plain template implementation for models that don't require special formatting.
 * Simply concatenates system prompt and messages with clear separators.
 */
public class PlainTemplate extends AbstractChatTemplate {

    public PlainTemplate() {
        super("plain", "Plain text format without special tokens");
    }

    @Override
    public String formatConversation(String systemPrompt, List<Message> messages) {
        StringBuilder prompt = buildConversation();

        // System prompt
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            appendLine(prompt, "System: " + systemPrompt);
            appendLine(prompt, "");
        }

        // Format messages
        for (Message message : messages) {
            formatMessage(prompt, message);
        }

        // Start assistant response
        append(prompt, "Assistant: ");

        return prompt.toString();
    }

    @Override
    protected void formatMessage(StringBuilder dialogue, Message message) {
        String role    = message.getRole();
        String content = message.getContent();

        if ("user".equals(role)) {
            appendLine(dialogue, "User: " + content);
            appendLine(dialogue, "");
        } else if ("assistant".equals(role)) {
            appendLine(dialogue, "Assistant: " + content);
            appendLine(dialogue, "");
        }
    }
}