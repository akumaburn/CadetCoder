package com.eonmux.cadetcoder.session;

import com.eonmux.cadetcoder.commands.TodoReadCommand;

import java.util.List;

/**
 * What a session is: what was said, what is left to do, and what was on screen.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>This also carried {@code openFiles}, {@code cursorPositions}, {@code currentCommand} and
 * {@code lastAIResponse}. All four were saved on every write and restored on every read, and
 * nothing ever set the first two or read the last two — so every session file carried four fields
 * that could not affect anything, and the two readers that existed ("Recently opened files:" in the
 * chat prompt, and a {@code current_files} parsing-context key) were reading a list that was empty
 * by construction. State that is persisted but never both written and read is worse than absent: it
 * reads as a feature, and the next person to touch it has to prove it does nothing before they can
 * change it.</p>
 *
 * <p>Sessions already written keep those properties and load fine: the class is annotated to ignore
 * what it does not recognise, so the guarantee belongs to the type rather than to whichever mapper
 * happens to be reading it.</p>
 *
 * <h2>Why the collection getters never return {@code null}</h2>
 *
 * <p>Only the fields a fresh session happens to initialise were non-null; a session read back from
 * JSON has whatever the document contained, and every absent field stayed {@code null}. That is not
 * a hypothetical: a document written before a field existed, or by a build that has since dropped
 * it, loads with the field unset — and the first thing the next turn does is
 * {@code getConversationHistory().isEmpty()}, which throws. Losing a session to a
 * {@code NullPointerException} on the turn AFTER it was restored is the worst available outcome,
 * because the failure looks like a bug in the conversation rather than in the file.</p>
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class SessionState {
    private String                         sessionId;
    private long                           createdAt;
    private long                           lastModified;
    private List<String>                   conversationHistory;
    private List<TodoReadCommand.TodoItem> todoList;

    /**
     * The shell's scrollback as it stood when the session was saved.
     *
     * <p>Kept apart from {@link #conversationHistory}, which is the MODEL's memory: that list has to
     * stay clean and token-bounded, and using it for the display meant a reopened session showed the
     * conversation with every command and its output removed from the middle.</p>
     */
    private List<TranscriptEntry>          transcript;

    public SessionState() {
    }

    /** @return the saved scrollback; never {@code null} */
    public List<TranscriptEntry> getTranscript() {
        if (transcript == null) {
            transcript = new java.util.ArrayList<>();
        }
        return transcript;
    }

    public void setTranscript(List<TranscriptEntry> transcript) {
        this.transcript = transcript;
    }

    /** @return the conversation, oldest first; never {@code null} */
    public List<String> getConversationHistory() {
        if (conversationHistory == null) {
            conversationHistory = new java.util.ArrayList<>();
        }
        return conversationHistory;
    }

    public void setConversationHistory(List<String> conversationHistory) {
        this.conversationHistory = conversationHistory;
    }

    public List<TodoReadCommand.TodoItem> getTodoList() {
        if (todoList == null) {
            todoList = new java.util.ArrayList<>();
        }
        return todoList;
    }

    public void setTodoList(List<TodoReadCommand.TodoItem> todoList) {
        this.todoList = todoList;
    }

    public String getSessionId() {
        if (sessionId == null) {
            sessionId = java.util.UUID.randomUUID().toString();
        }
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }
}
