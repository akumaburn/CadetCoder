package com.eonmux.cadetcoder.ai.templates;

import java.util.List;

/**
 * Intel NeuralChat template implementation.
 * Format: ### System:\nsystem\n### User:\nuser\n### Assistant:\n
 */
public class IntelNeuralChatTemplate extends AbstractChatTemplate {

    public IntelNeuralChatTemplate() {
        super("intel-neural", "Intel NeuralChat format");
    }

    @Override
    public String formatConversation(String systemPrompt, List<Message> messages) {
        StringBuilder prompt = buildConversation();

        // System prompt
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            appendLine(prompt, "### System:");
            appendLine(prompt, systemPrompt);
            appendLine(prompt, "");
        }

        // Format messages
        for (Message message : messages) {
            formatMessage(prompt, message);
        }

        // Start assistant response
        appendLine(prompt, "### Assistant:");

        return prompt.toString();
    }

    @Override
    protected void formatMessage(StringBuilder dialogue, Message message) {
        String role    = message.getRole();
        String content = message.getContent();

        if ("user".equals(role)) {
            appendLine(dialogue, "### User:");
            appendLine(dialogue, content);
            appendLine(dialogue, "");
        } else if ("assistant".equals(role)) {
            appendLine(dialogue, "### Assistant:");
            appendLine(dialogue, content);
            appendLine(dialogue, "");
        }
    }
}