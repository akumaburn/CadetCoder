package com.eonmux.cadetcoder.ai.templates;

import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base class for chat templates providing common functionality.
 */
public abstract class AbstractChatTemplate implements ChatTemplate {
    protected final String name;
    protected final String description;

    protected AbstractChatTemplate(String name, String description) {
        this.name        = name;
        this.description = description;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String formatSingleTurn(String input) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("user", input));
        return formatConversation("", messages);
    }

    /**
     * Helper method to build conversation with proper line breaks.
     * Avoids long strings on single lines.
     */
    protected StringBuilder buildConversation() {
        return new StringBuilder();
    }

    /**
     * Helper to append with newline.
     */
    protected void appendLine(StringBuilder sb, String line) {
        sb.append(line).append("\n");
    }

    /**
     * Helper to append without newline.
     */
    protected void append(StringBuilder sb, String text) {
        sb.append(text);
    }

    /**
     * Helper to format the interleaved dialogue between user and assistant.
     */
    protected String formatDialogue(List<Message> messages) {
        StringBuilder dialogue = new StringBuilder();

        for (Message message : messages) {
            formatMessage(dialogue, message);
        }

        return dialogue.toString();
    }

    /**
     * Format a single message according to the template's rules.
     * To be implemented by concrete templates.
     */
    protected abstract void formatMessage(StringBuilder dialogue, Message message);
}