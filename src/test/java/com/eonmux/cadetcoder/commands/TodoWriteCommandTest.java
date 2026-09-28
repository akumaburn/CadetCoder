package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.commands.TodoReadCommand.TodoItem;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class TodoWriteCommandTest {

    private TodoWriteCommand  command;
    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        command       = new TodoWriteCommand();
        outputCapture = new TestOutputCapture();
    }

    @After
    public void tearDown() {
        outputCapture.restore();
    }

    @Test
    public void testGetDescription() {
        assertThat(command.getDescription()).isEqualTo("Create and manage a structured task list");
    }

    @Test
    public void testGetUsage() {
        assertThat(command.getUsage()).contains("todowrite add");
    }

    @Test
    public void anUnknownOptionIsReportedRatherThanBecomingTheTaskText() {
        // "todowrite --clear-completed" used to create a task literally named "--clear-completed",
        // so a mistyped flag became a to-do item nobody asked for.
        int result = command.execute(new String[] {"--clear-completed"});

        assertThat(result).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("Unknown option: --clear-completed");
    }

    @Test
    public void anUnknownOptionAfterAnActionIsAlsoReported() {
        int result = command.execute(new String[] {"add", "--update", "1"});

        assertThat(result).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("Unknown option: --update");
    }

    @Test
    public void testExecute_NoAction() {
        int result = command.execute(new String[0]);

        assertThat(result).isEqualTo(1);
        assertThat(outputCapture.getStderr()).contains("No action provided");
    }

    @Test
    public void testExecute_AddTodo() {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);
            when(mockManager.getTodoList()).thenReturn(new ArrayList<>());

            int result = command.execute(new String[] {"add", "New", "task", "to", "do"});

            assertThat(result).isEqualTo(0);
            ArgumentCaptor<List<TodoItem>> captor = ArgumentCaptor.forClass(List.class);
            verify(mockManager).setTodoList(captor.capture());

            List<TodoItem> todos = captor.getValue();
            assertThat(todos).hasSize(1);
            assertThat(todos.get(0).getContent()).isEqualTo("New task to do");
            assertThat(todos.get(0).getStatus()).isEqualTo(TodoItem.Status.PENDING);
            assertThat(todos.get(0).getPriority()).isEqualTo(TodoItem.Priority.MEDIUM);

            assertThat(outputCapture.getOutput()).contains("Added todo [").contains("]: New task to do");
        }
    }

    @Test
    public void testExecute_AddTodoWithOptions() {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);
            when(mockManager.getTodoList()).thenReturn(new ArrayList<>());

            int result = command.execute(new String[] {"add", "High", "priority", "task",
                                                       "-s", "in_progress", "-p", "high"});

            assertThat(result).isEqualTo(0);
            ArgumentCaptor<List<TodoItem>> captor = ArgumentCaptor.forClass(List.class);
            verify(mockManager).setTodoList(captor.capture());

            List<TodoItem> todos = captor.getValue();
            assertThat(todos).hasSize(1);
            assertThat(todos.get(0).getContent()).isEqualTo("High priority task");
            assertThat(todos.get(0).getStatus()).isEqualTo(TodoItem.Status.IN_PROGRESS);
            assertThat(todos.get(0).getPriority()).isEqualTo(TodoItem.Priority.HIGH);
        }
    }

    @Test
    public void testExecute_UpdateTodo() {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);

            TodoItem existingTodo = new TodoItem("test-id", "Old content",
                    TodoItem.Status.PENDING, TodoItem.Priority.LOW);
            when(mockManager.getTodoList()).thenReturn(new ArrayList<>(List.of(existingTodo)));

            int result = command.execute(new String[] {"update", "-i", "test-id", "-s", "completed"});

            assertThat(result).isEqualTo(0);
            ArgumentCaptor<List<TodoItem>> captor = ArgumentCaptor.forClass(List.class);
            verify(mockManager).setTodoList(captor.capture());

            List<TodoItem> todos = captor.getValue();
            assertThat(todos).hasSize(1);
            assertThat(todos.get(0).getId()).isEqualTo("test-id");
            assertThat(todos.get(0).getContent()).isEqualTo("Old content");
            assertThat(todos.get(0).getStatus()).isEqualTo(TodoItem.Status.COMPLETED);
            assertThat(todos.get(0).getPriority()).isEqualTo(TodoItem.Priority.LOW);

            // Immutability (finding 34): the committed list must contain a NEW replacement item,
            // and the original shared TodoItem instance must NOT have been mutated in place.
            assertThat(todos.get(0)).isNotSameAs(existingTodo);
            assertThat(existingTodo.getStatus()).isEqualTo(TodoItem.Status.PENDING);

            assertThat(outputCapture.getOutput()).contains("Updated todo [test-id] to COMPLETED");
        }
    }

    @Test
    public void testExecute_UpdateTodo_NotFound() {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);
            when(mockManager.getTodoList()).thenReturn(new ArrayList<>());

            int result = command.execute(new String[] {"update", "-i", "missing-id", "-s", "completed"});

            assertThat(result).isEqualTo(1);
            assertThat(outputCapture.getStderr()).contains("Todo not found: missing-id");
        }
    }

    @Test
    public void testExecute_RemoveTodo() {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);

            TodoItem todo1 = new TodoItem("id1", "Task 1", TodoItem.Status.PENDING, TodoItem.Priority.HIGH);
            TodoItem todo2 = new TodoItem("id2", "Task 2", TodoItem.Status.PENDING, TodoItem.Priority.LOW);
            when(mockManager.getTodoList()).thenReturn(new ArrayList<>(Arrays.asList(todo1, todo2)));

            int result = command.execute(new String[] {"remove", "id1"});

            assertThat(result).isEqualTo(0);
            ArgumentCaptor<List<TodoItem>> captor = ArgumentCaptor.forClass(List.class);
            verify(mockManager).setTodoList(captor.capture());

            List<TodoItem> todos = captor.getValue();
            assertThat(todos).hasSize(1);
            assertThat(todos.get(0).getId()).isEqualTo("id2");

            assertThat(outputCapture.getOutput()).contains("Removed todo [id1]: Task 1");
        }
    }

    @Test
    public void testExecute_ClearTodos() {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);

            int result = command.execute(new String[] {"clear"});

            assertThat(result).isEqualTo(0);
            verify(mockManager).setTodoList(new ArrayList<>());
            assertThat(outputCapture.getOutput()).contains("Cleared all todos");
        }
    }

    // Finding todo-1: a bare "todowrite <text>" where the first token is not a known
    // subcommand is treated as an implicit "add <text>" rather than rejected.
    @Test
    public void testExecute_ImplicitAdd_SingleWord() {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);
            when(mockManager.getTodoList()).thenReturn(new ArrayList<>());

            int result = command.execute(new String[] {"refactor"});

            assertThat(result).isEqualTo(0);
            ArgumentCaptor<List<TodoItem>> captor = ArgumentCaptor.forClass(List.class);
            verify(mockManager).setTodoList(captor.capture());

            List<TodoItem> todos = captor.getValue();
            assertThat(todos).hasSize(1);
            assertThat(todos.get(0).getContent()).isEqualTo("refactor");
            assertThat(todos.get(0).getStatus()).isEqualTo(TodoItem.Status.PENDING);
            assertThat(outputCapture.getOutput()).contains("Added todo [").contains("]: refactor");
        }
    }

    // Finding todo-1: the implicit-add path must keep the first token as part of the content
    // (parsing starts at index 0) and still honour options like -p.
    @Test
    public void testExecute_ImplicitAdd_MultiWordWithPriority() {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);
            when(mockManager.getTodoList()).thenReturn(new ArrayList<>());

            int result = command.execute(new String[] {"write", "the", "report", "-p", "high"});

            assertThat(result).isEqualTo(0);
            ArgumentCaptor<List<TodoItem>> captor = ArgumentCaptor.forClass(List.class);
            verify(mockManager).setTodoList(captor.capture());

            List<TodoItem> todos = captor.getValue();
            assertThat(todos).hasSize(1);
            assertThat(todos.get(0).getContent()).isEqualTo("write the report");
            assertThat(todos.get(0).getPriority()).isEqualTo(TodoItem.Priority.HIGH);
        }
    }

    // Finding todo-3: updating by content that matches more than one todo must report an
    // ambiguity error and must NOT modify or commit anything.
    @Test
    public void testExecute_UpdateByContent_Ambiguous() {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);

            TodoItem dup1 = new TodoItem("id1", "Same content", TodoItem.Status.PENDING, TodoItem.Priority.LOW);
            TodoItem dup2 = new TodoItem("id2", "Same content", TodoItem.Status.PENDING, TodoItem.Priority.LOW);
            when(mockManager.getTodoList()).thenReturn(new ArrayList<>(Arrays.asList(dup1, dup2)));

            int result = command.execute(new String[] {"update", "Same", "content", "-s", "completed"});

            assertThat(result).isEqualTo(1);
            assertThat(outputCapture.getStderr()).contains("Ambiguous match");
            verify(mockManager, never()).setTodoList(any());
            verify(mockManager, never()).saveSession();
        }
    }

    // Finding todo-3: when content is duplicated, an exact id selector must still resolve
    // unambiguously to the right todo.
    @Test
    public void testExecute_UpdateById_PrefersIdOverDuplicateContent() {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);

            TodoItem dup1 = new TodoItem("id1", "Same content", TodoItem.Status.PENDING, TodoItem.Priority.LOW);
            TodoItem dup2 = new TodoItem("id2", "Same content", TodoItem.Status.PENDING, TodoItem.Priority.LOW);
            when(mockManager.getTodoList()).thenReturn(new ArrayList<>(Arrays.asList(dup1, dup2)));

            int result = command.execute(new String[] {"update", "-i", "id2", "-s", "completed"});

            assertThat(result).isEqualTo(0);
            ArgumentCaptor<List<TodoItem>> captor = ArgumentCaptor.forClass(List.class);
            verify(mockManager).setTodoList(captor.capture());

            List<TodoItem> todos = captor.getValue();
            assertThat(todos).hasSize(2);
            TodoItem updated = todos.stream().filter(t -> t.getId().equals("id2")).findFirst().orElseThrow();
            TodoItem untouched = todos.stream().filter(t -> t.getId().equals("id1")).findFirst().orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(TodoItem.Status.COMPLETED);
            assertThat(untouched.getStatus()).isEqualTo(TodoItem.Status.PENDING);
        }
    }

    // Finding todo-3: removing by ambiguous content must report ambiguity and not commit.
    @Test
    public void testExecute_RemoveByContent_Ambiguous() {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);

            TodoItem dup1 = new TodoItem("id1", "Dup", TodoItem.Status.PENDING, TodoItem.Priority.LOW);
            TodoItem dup2 = new TodoItem("id2", "Dup", TodoItem.Status.PENDING, TodoItem.Priority.LOW);
            when(mockManager.getTodoList()).thenReturn(new ArrayList<>(Arrays.asList(dup1, dup2)));

            int result = command.execute(new String[] {"remove", "Dup"});

            assertThat(result).isEqualTo(1);
            assertThat(outputCapture.getStderr()).contains("Ambiguous match");
            verify(mockManager, never()).setTodoList(any());
            verify(mockManager, never()).saveSession();
        }
    }

    // Finding todo-6: TodoItem is immutable; withStatus rebuilds a new instance and leaves
    // the original untouched.
    @Test
    public void testTodoItem_WithStatus_IsImmutable() {
        TodoItem original = new TodoItem("id", "content", TodoItem.Status.PENDING, TodoItem.Priority.HIGH);

        TodoItem updated = original.withStatus(TodoItem.Status.COMPLETED);

        assertThat(updated).isNotSameAs(original);
        assertThat(updated.getStatus()).isEqualTo(TodoItem.Status.COMPLETED);
        assertThat(updated.getId()).isEqualTo("id");
        assertThat(updated.getContent()).isEqualTo("content");
        assertThat(updated.getPriority()).isEqualTo(TodoItem.Priority.HIGH);
        // Original is unchanged.
        assertThat(original.getStatus()).isEqualTo(TodoItem.Status.PENDING);
    }

    @Test
    public void testExecute_InvalidStatus() {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);
            when(mockManager.getTodoList()).thenReturn(new ArrayList<>());

            int result = command.execute(new String[] {"add", "Task", "-s", "invalid"});

            assertThat(result).isEqualTo(1);
            assertThat(outputCapture.getStderr()).contains("Invalid status or priority value");
        }
    }

    @Test
    public void testExecute_InvalidPriority() {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);
            when(mockManager.getTodoList()).thenReturn(new ArrayList<>());

            int result = command.execute(new String[] {"add", "Task", "-p", "invalid"});

            assertThat(result).isEqualTo(1);
            assertThat(outputCapture.getStderr()).contains("Invalid status or priority value");
        }
    }

    @Test
    public void testCall() throws Exception {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);

            Integer result = command.call();

            assertThat(result).isEqualTo(1); // No args provided
        }
    }
}