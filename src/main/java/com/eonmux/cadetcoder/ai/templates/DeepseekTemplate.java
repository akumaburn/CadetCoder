package com.eonmux.cadetcoder.ai.templates;

import java.util.List;

/**
 * Deepseek template implementation.
 * Format: <|User|>user message<|Assistant|>assistant response<|end_of_sentence|>
 */
public class DeepseekTemplate extends AbstractChatTemplate {

    public DeepseekTemplate() {
        super("deepseek", "Deepseek model chat format");
    }

    @Override
    public String formatConversation(String systemPrompt, List<Message> messages) {
        StringBuilder prompt = buildConversation();

        // Add system prompt at the beginning
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            appendLine(prompt, systemPrompt);
            appendLine(prompt, "");
        }

        // Format messages
        for (Message message : messages) {
            formatMessage(prompt, message);
        }

        // Start assistant response
        append(prompt, "<|Assistant|>");

        return prompt.toString();
    }

    @Override
    protected void formatMessage(StringBuilder dialogue, Message message) {
        String role    = message.getRole();
        String content = message.getContent();

        if ("user".equals(role)) {
            append(dialogue, "<|User|>");
            append(dialogue, content);
        } else if ("assistant".equals(role)) {
            append(dialogue, "<|Assistant|>");
            append(dialogue, content);
            append(dialogue, "<|end_of_sentence|>");
        }
    }
}