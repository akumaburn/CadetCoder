package com.eonmux.cadetcoder.ai.templates;

import java.util.List;

/**
 * Llama 3 template implementation.
 * Format: <|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\nsystem<|eot_id|>...
 */
public class Llama3Template extends AbstractChatTemplate {

    public Llama3Template() {
        super("llama3", "Llama 3 instruct format");
    }

    @Override
    public String formatConversation(String systemPrompt, List<Message> messages) {
        StringBuilder prompt = buildConversation();

        // Begin of text
        append(prompt, "<|begin_of_text|>");

        // System prompt
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            append(prompt, "<|start_header_id|>system<|end_header_id|>");
            appendLine(prompt, "");
            appendLine(prompt, "");
            append(prompt, systemPrompt);
            append(prompt, "<|eot_id|>");
        }

        // Format messages
        for (Message message : messages) {
            formatMessage(prompt, message);
        }

        // Start assistant response
        append(prompt, "<|start_header_id|>assistant<|end_header_id|>");
        appendLine(prompt, "");
        appendLine(prompt, "");

        return prompt.toString();
    }

    @Override
    protected void formatMessage(StringBuilder dialogue, Message message) {
        String role    = message.getRole();
        String content = message.getContent();

        append(dialogue, "<|start_header_id|>");
        append(dialogue, role);
        append(dialogue, "<|end_header_id|>");
        appendLine(dialogue, "");
        appendLine(dialogue, "");
        append(dialogue, content);
        append(dialogue, "<|eot_id|>");
    }
}