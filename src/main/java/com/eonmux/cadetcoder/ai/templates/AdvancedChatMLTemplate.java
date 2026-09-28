package com.eonmux.cadetcoder.ai.templates;

import java.util.List;

/**
 * Advanced ChatML template with support for tools and reasoning.
 * This is a Java implementation of the complex Jinja2 template provided.
 */
public class AdvancedChatMLTemplate extends AbstractChatTemplate {

    public AdvancedChatMLTemplate() {
        super("chatml-advanced", "Advanced ChatML with tools and reasoning support");
    }

    @Override
    protected void formatMessage(StringBuilder dialogue, Message message) {
        // Handled in formatConversationWithTools
    }    @Override
    public boolean supportsTools() {
        return true;
    }

    @Override
    public boolean supportsReasoning() {
        return true;
    }

    @Override
    public String formatConversation(String systemPrompt, List<Message> messages) {
        return formatConversationWithTools(systemPrompt, messages, null);
    }

    @Override
    public String formatConversationWithTools(String systemPrompt, List<Message> messages, List<Tool> tools) {
        StringBuilder prompt = buildConversation();

        // Handle tools in system prompt
        if (tools != null && !tools.isEmpty()) {
            appendLine(prompt, "<|im_start|>system");

            // The caller's system prompt, whenever there is one. It used to additionally require a
            // system-ROLE message in the list, which is a different thing entirely and which
            // nothing here produces -- so supplying tools silently replaced the system prompt with
            // the tools block instead of adding to it.
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
            appendLine(prompt, "</tool_call><|im_end|>");
        } else if (systemPrompt != null && !systemPrompt.isEmpty()) {
            // Regular system prompt without tools
            appendLine(prompt, "<|im_start|>system");
            append(prompt, systemPrompt);
            appendLine(prompt, "<|im_end|>");
        }

        // Process messages with multi-step tool handling
        boolean multiStepTool  = true;
        int     lastQueryIndex = findLastUserQuery(messages);

        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);

            if ("user".equals(message.getRole()) ||
                ("system".equals(message.getRole()) && i > 0)) {
                // Regular user/system message
                appendLine(prompt, "<|im_start|>" + message.getRole());
                append(prompt, message.getContent());
                appendLine(prompt, "<|im_end|>");
            } else if ("assistant".equals(message.getRole())) {
                // Assistant message with potential reasoning
                formatAssistantMessage(prompt, message, i > lastQueryIndex);
            } else if ("tool".equals(message.getRole())) {
                // Tool response handling
                formatToolResponse(prompt, message, messages, i);
            }
        }

        // Add generation prompt
        append(prompt, "<|im_start|>assistant\n");

        return prompt.toString();
    }

    private void formatAssistantMessage(StringBuilder prompt, Message message, boolean afterLastQuery) {
        // Assistant messages may carry only tool calls and no textual content; normalize
        // null to "" so the </think> extraction and isEmpty() guards below never NPE.
        String content          = message.getContent() != null ? message.getContent() : "";
        String reasoningContent = message.getReasoningContent();

        // Extract reasoning from content if not provided separately
        if (reasoningContent == null && content.contains("</think>")) {
            int thinkEnd   = content.indexOf("</think>");
            int thinkStart = content.indexOf("<think>");

            if (thinkStart >= 0 && thinkEnd > thinkStart) {
                reasoningContent = content.substring(thinkStart + 7, thinkEnd).trim();
                content          = content.substring(thinkEnd + 8).trim();
            }
        }

        appendLine(prompt, "<|im_start|>assistant");

        // Add reasoning if appropriate
        if (afterLastQuery && reasoningContent != null && !reasoningContent.isEmpty()) {
            appendLine(prompt, "<think>");
            appendLine(prompt, reasoningContent);
            appendLine(prompt, "</think>");
            appendLine(prompt, "");
        }

        append(prompt, content);

        // Handle tool calls
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            for (ToolCall toolCall : message.getToolCalls()) {
                if (!content.isEmpty()) {
                    appendLine(prompt, "");
                }
                appendLine(prompt, "<tool_call>");
                append(prompt, ToolJson.toolCall(toolCall.getName(), toolCall.getArguments()));
                appendLine(prompt, "</tool_call>");
            }
        }

        appendLine(prompt, "<|im_end|>");
    }

    private void formatToolResponse(StringBuilder prompt, Message message, List<Message> messages, int index) {
        // Check if this is the first tool response or if previous was not a tool
        boolean isFirst = index == 0 || !"tool".equals(messages.get(index - 1).getRole());

        if (isFirst) {
            append(prompt, "<|im_start|>user");
        }

        appendLine(prompt, "");
        appendLine(prompt, "<tool_response>");
        appendLine(prompt, message.getContent());
        append(prompt, "</tool_response>");

        // Check if this is the last tool response or if next is not a tool
        boolean isLast = index == messages.size() - 1 ||
                         !"tool".equals(messages.get(index + 1).getRole());

        if (isLast) {
            appendLine(prompt, "<|im_end|>");
        }
    }

    private int findLastUserQuery(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if ("user".equals(message.getRole()) &&
                !isToolResponse(message.getContent())) {
                return i;
            }
        }
        return messages.size() - 1;
    }

    private boolean isToolResponse(String content) {
        return content != null &&
               content.trim().startsWith("<tool_response>") &&
               content.trim().endsWith("</tool_response>");
    }

}