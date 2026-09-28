package com.eonmux.cadetcoder.ai.templates;

import java.util.List;

/**
 * Alpaca template implementation.
 * Uses instruction/input/response format.
 */
public class AlpacaTemplate extends AbstractChatTemplate {

    public AlpacaTemplate() {
        super("alpaca", "Alpaca instruction format for fine-tuned models");
    }

    @Override
    public String formatConversation(String systemPrompt, List<Message> messages) {
        StringBuilder prompt = buildConversation();

        // Alpaca header
        appendLine(prompt,
                "Below is an instruction that describes a task, paired with an input that provides further context.");
        appendLine(prompt, "Write a response that appropriately completes the request.");
        appendLine(prompt, "");

        // System prompt as instruction
        appendLine(prompt, "### Instruction:");
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            appendLine(prompt, systemPrompt);
        }
        appendLine(prompt, "");

        // Format messages
        boolean hasInput = false;
        for (Message message : messages) {
            if ("user".equals(message.getRole())) {
                if (!hasInput) {
                    appendLine(prompt, "### Input:");
                    hasInput = true;
                }
                appendLine(prompt, message.getContent());
            } else if ("assistant".equals(message.getRole())) {
                appendLine(prompt, "");
                appendLine(prompt, "### Response:");
                appendLine(prompt, message.getContent());
            }
        }

        // If no response yet, add response header
        if (!hasAssistantMessage(messages)) {
            appendLine(prompt, "");
            appendLine(prompt, "### Response:");
        }

        return prompt.toString();
    }

    private boolean hasAssistantMessage(List<Message> messages) {
        return messages.stream().anyMatch(m -> "assistant".equals(m.getRole()));
    }

    @Override
    protected void formatMessage(StringBuilder dialogue, Message message) {
        // Handled in formatConversation for Alpaca
    }
}