package com.eonmux.cadetcoder.ai.templates;

import java.util.List;

/**
 * Llama 2 template implementation.
 * Format: <s>[INST] <<SYS>>\nsystem\n<</SYS>>\n\nuser [/INST] assistant </s>
 */
public class LlamaTemplate extends AbstractChatTemplate {

    public LlamaTemplate() {
        super("llama2", "Llama 2 chat format");
    }

    @Override
    public String formatConversation(String systemPrompt, List<Message> messages) {
        StringBuilder prompt = buildConversation();

        // Start sequence
        append(prompt, "<s>");

        // System prompt
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            appendLine(prompt, "[INST] <<SYS>>");
            appendLine(prompt, systemPrompt);
            appendLine(prompt, "<</SYS>>");
            appendLine(prompt, "");
        }

        // Format messages
        boolean firstUser = true;
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);

            if ("user".equals(message.getRole())) {
                if (!firstUser || (systemPrompt == null || systemPrompt.isEmpty())) {
                    append(prompt, "[INST] ");
                }
                append(prompt, message.getContent());
                append(prompt, " [/INST]");
                firstUser = false;
            } else if ("assistant".equals(message.getRole())) {
                append(prompt, " ");
                append(prompt, message.getContent());

                // Add end token if not the last message
                if (i < messages.size() - 1) {
                    append(prompt, " </s>");
                }
            }
        }

        // Add space for assistant response
        if (messages.isEmpty() || !"assistant".equals(messages.get(messages.size() - 1).getRole())) {
            append(prompt, " ");
        }

        return prompt.toString();
    }

    @Override
    protected void formatMessage(StringBuilder dialogue, Message message) {
        // Handled in formatConversation for Llama
    }
}