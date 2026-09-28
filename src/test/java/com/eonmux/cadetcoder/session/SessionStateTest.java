package com.eonmux.cadetcoder.session;

import com.eonmux.cadetcoder.commands.TodoReadCommand;
import com.eonmux.cadetcoder.commands.TodoReadCommand.TodoItem;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a session carries: what was said, what is left to do, and what was on screen.
 */
public class SessionStateTest {

    private SessionState sessionState;

    @Before
    public void setUp() {
        sessionState = new SessionState();
    }

    /**
     * A session that was never given collections still has them.
     *
     * <p>This asserted the opposite until a session file missing one of those properties was
     * followed to its conclusion: the state loads with the field null, and the next turn calls
     * {@code getConversationHistory().isEmpty()} on it. Empty is what "no entries" means; null is a
     * crash on the turn after a session was restored.</p>
     */
    @Test
    public void testDefaultConstructor() {
        SessionState state = new SessionState();

        assertThat(state.getConversationHistory()).isNotNull().isEmpty();
        assertThat(state.getTranscript()).isNotNull().isEmpty();
        assertThat(state.getTodoList()).isNotNull().isEmpty();
    }

    /**
     * A session carries only state that is both written and read.
     *
     * <p>It used to also carry {@code openFiles}, {@code cursorPositions}, {@code currentCommand}
     * and {@code lastAIResponse}: saved on every write, restored on every read, and — for the first
     * two — never written by anything, while the last two were never read. Persisted state that
     * cannot affect anything still has to be understood by whoever reads the class next, so it is
     * gone. Named here so re-adding one is a decision rather than a habit.</p>
     */
    @Test
    public void testCarriesOnlyStateThatIsUsed() {
        List<String> properties = new ArrayList<>();
        for (java.lang.reflect.Field field : SessionState.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                properties.add(field.getName());
            }
        }

        assertThat(properties).containsExactlyInAnyOrder(
                "sessionId", "createdAt", "lastModified",
                "conversationHistory", "todoList", "transcript");
    }

    @Test
    public void testConversationHistory() {
        List<String> history = Arrays.asList("Hello", "Hi there", "How are you?", "I'm fine");
        sessionState.setConversationHistory(history);

        assertThat(sessionState.getConversationHistory()).isEqualTo(history);
        assertThat(sessionState.getConversationHistory()).hasSize(4);
        assertThat(sessionState.getConversationHistory()).containsExactly("Hello",
                "Hi there",
                "How are you?",
                "I'm fine");
    }

    @Test
    public void testTodoList() {
        TodoItem                       item1 =
                new TodoItem("1", "task1", TodoItem.Status.PENDING, TodoItem.Priority.HIGH);
        TodoItem                       item2 =
                new TodoItem("2", "task2", TodoItem.Status.COMPLETED, TodoItem.Priority.LOW);
        List<TodoReadCommand.TodoItem> todos = Arrays.asList(item1, item2);

        sessionState.setTodoList(todos);

        assertThat(sessionState.getTodoList()).isEqualTo(todos);
        assertThat(sessionState.getTodoList()).hasSize(2);
        assertThat(sessionState.getTodoList().get(0).getContent()).isEqualTo("task1");
        assertThat(sessionState.getTodoList().get(1).getStatus()).isEqualTo(TodoItem.Status.COMPLETED);
    }

    @Test
    public void testTodoList_LazyInitialization() {
        // getTodoList should initialize an empty list if null
        assertThat(sessionState.getTodoList()).isNotNull();
        assertThat(sessionState.getTodoList()).isEmpty();

        // Add an item to verify it's a real list
        TodoItem item = new TodoItem("1", "test", TodoItem.Status.PENDING, TodoItem.Priority.MEDIUM);
        sessionState.getTodoList().add(item);

        assertThat(sessionState.getTodoList()).hasSize(1);
        assertThat(sessionState.getTodoList().get(0)).isEqualTo(item);
    }

    @Test
    public void testTranscript_LazyInitialization() {
        assertThat(sessionState.getTranscript()).isNotNull().isEmpty();

        sessionState.getTranscript()
                    .add(new TranscriptEntry("COMMAND", 0, "ls src", "OK", List.of("Main.java")));

        assertThat(sessionState.getTranscript()).hasSize(1);
        assertThat(sessionState.getTranscript().get(0).getTitle()).isEqualTo("ls src");
    }

    @Test
    public void testSessionId() {
        // Default session ID should be generated lazily
        String sessionId = sessionState.getSessionId();
        assertThat(sessionId).isNotNull();
        assertThat(sessionId).isNotEmpty();

        // Should return the same ID on subsequent calls
        assertThat(sessionState.getSessionId()).isEqualTo(sessionId);

        // Should be able to set a custom ID
        sessionState.setSessionId("custom-id-123");
        assertThat(sessionState.getSessionId()).isEqualTo("custom-id-123");
    }

    @Test
    public void testSessionId_LazyInitialization() {
        SessionState newState = new SessionState();
        String       id1      = newState.getSessionId();
        String       id2      = newState.getSessionId();

        assertThat(id1).isNotNull();
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    public void testTimestamps() {
        SessionState state = new SessionState();

        assertThat(state.getCreatedAt()).isZero();
        assertThat(state.getLastModified()).isZero();

        long newTimestamp = System.currentTimeMillis();
        state.setCreatedAt(newTimestamp);
        state.setLastModified(newTimestamp + 500);

        assertThat(state.getCreatedAt()).isEqualTo(newTimestamp);
        assertThat(state.getLastModified()).isEqualTo(newTimestamp + 500);
    }

    @Test
    public void testCompleteSessionLifecycle() {
        SessionState session   = new SessionState();
        session.setCreatedAt(System.currentTimeMillis());
        String       sessionId = session.getSessionId();

        // Simulate session activity
        session.getConversationHistory().add("User: read Main.java");
        session.getConversationHistory().add("AI: File contents displayed");
        session.getTodoList().add(
                new TodoItem("1", "Fix bug", TodoItem.Status.PENDING, TodoItem.Priority.HIGH));
        session.getTranscript()
               .add(new TranscriptEntry("COMMAND", 0, "read Main.java", "OK",
                                        List.of("1  class Main {}")));

        session.setLastModified(session.getCreatedAt() + 1);

        // Verify final state
        assertThat(session.getSessionId()).isEqualTo(sessionId);
        assertThat(session.getConversationHistory()).hasSize(2);
        assertThat(session.getTodoList()).hasSize(1);
        assertThat(session.getTranscript()).hasSize(1);
        assertThat(session.getLastModified()).isGreaterThan(session.getCreatedAt());
    }
}
