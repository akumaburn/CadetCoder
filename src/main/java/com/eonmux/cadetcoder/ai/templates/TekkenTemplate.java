package com.eonmux.cadetcoder.ai.templates;

import java.util.List;

/**
 * Tekken (Mistral V7) template implementation.
 * Format: <s>[SYSTEM_PROMPT]system[/SYSTEM_PROMPT][INST]user[/INST]assistant</s>
 */
public class TekkenTemplate extends AbstractChatTemplate {

    public TekkenTemplate() {
        super("tekken", "Mistral V7 Tekken format");
    }

    @Override
    public String formatConversation(String systemPrompt, List<Message> messages) {
        StringBuilder prompt = buildConversation();

        // Start sequence
        append(prompt, "<s>");

        // System prompt
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            appendLine(prompt, "[SYSTEM_PROMPT]");
            appendLine(prompt, systemPrompt);
            appendLine(prompt, "[/SYSTEM_PROMPT]");
        }

        // Format messages
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            formatMessage(prompt, message);
        }

        return prompt.toString();
    }

    @Override
    protected void formatMessage(StringBuilder dialogue, Message message) {
        String role    = message.getRole();
        String content = message.getContent();

        if ("user".equals(role)) {
            append(dialogue, "[INST]");
            append(dialogue, content);
            append(dialogue, "[/INST]");
        } else if ("assistant".equals(role)) {
            append(dialogue, content);
            append(dialogue, "</s>");
        }
    }
}