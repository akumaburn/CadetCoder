package com.eonmux.cadetcoder.ai.templates;

import java.util.List;

/**
 * ChatML template implementation.
 * Format: <|im_start|>role\ncontent<|im_end|>
 */
public class ChatMLTemplate extends AbstractChatTemplate {

    public ChatMLTemplate() {
        super("chatml", "ChatML format used by models like GPT-4");
    }

    @Override
    protected void formatMessage(StringBuilder dialogue, Message message) {
        String role    = message.getRole();
        String content = message.getContent();

        // Handle reasoning content if present
        if ("assistant".equals(role) && message.getReasoningContent() != null) {
            appendLine(dialogue, "<|im_start|>" + role);
            appendLine(dialogue, "<think>");
            appendLine(dialogue, message.getReasoningContent());
            appendLine(dialogue, "</think>");
            appendLine(dialogue, "");
            append(dialogue, content);
            appendLine(dialogue, "<|im_end|>");
        } else {
            appendLine(dialogue, "<|im_start|>" + role);
            append(dialogue, content);
            appendLine(dialogue, "<|im_end|>");
        }

        // Handle tool calls if present
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            for (ToolCall toolCall : message.getToolCalls()) {
                appendLine(dialogue, "<tool_call>");
                appendLine(dialogue, ToolJson.toolCall(toolCall.getName(), toolCall.getArguments()));
                appendLine(dialogue, "</tool_call>");
            }
        }
    }

    @Override
    public String formatConversationWithTools(String systemPrompt, List<Message> messages, List<Tool> tools) {
        StringBuilder prompt = buildConversation();

        // Build enhanced system prompt with tools
        appendLine(prompt, "<|im_start|>system");
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            appendLine(prompt, systemPrompt);
            appendLine(prompt, "");
        }

        // Add tools section
        appendLine(prompt, "# Tools");
        appendLine(prompt, "");
        appendLine(prompt, "You may call one or more functions to assist with the user query.");
        appendLine(prompt, "");
        appendLine(prompt, "You are provided with function signatures within <tools></tools> XML tags:");
        appendLine(prompt, "<tools>");

        for (Tool tool : tools) {
            appendLine(prompt, tool.toJson());
        }

        appendLine(prompt, "</tools>");
        appendLine(prompt, "");
        appendLine(prompt,
                "For each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:");
        appendLine(prompt, "<tool_call>");
        appendLine(prompt, "{\"name\": <function-name>, \"arguments\": <args-json-object>}");
        append(prompt, "</tool_call>");
        appendLine(prompt, "<|im_end|>");

        // Add messages
        append(prompt, formatDialogue(messages));

        // Add generation prompt
        append(prompt, "<|im_start|>assistant\n");

        return prompt.toString();
    }

    @Override
    public String formatConversation(String systemPrompt, List<Message> messages) {
        StringBuilder prompt = buildConversation();

        // Add system prompt if provided
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            appendLine(prompt, "<|im_start|>system");
            append(prompt, systemPrompt);
            appendLine(prompt, "<|im_end|>");
        }

        // Add messages
        append(prompt, formatDialogue(messages));

        // Add generation prompt
        append(prompt, "<|im_start|>assistant\n");

        return prompt.toString();
    }

    @Override
    public boolean supportsTools() {
        return true;
    }

    @Override
    public boolean supportsReasoning() {
        return true;
    }
}