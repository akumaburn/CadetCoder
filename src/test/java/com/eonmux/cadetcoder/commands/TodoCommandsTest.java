package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class TodoCommandsTest {

    private TodoReadCommand   todoReadCommand;
    private TodoWriteCommand  todoWriteCommand;
    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        todoReadCommand  = new TodoReadCommand();
        todoWriteCommand = new TodoWriteCommand();
        outputCapture    = new TestOutputCapture();
        outputCapture.startCapture();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
    }

    @Test
    public void testTodoRead_EmptyList() {
        // Mock SessionManager
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            when(mockManager.getTodoList()).thenReturn(new ArrayList<>());
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);

            // Execute command
            int exitCode = todoReadCommand.execute(new String[] {});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            assertThat(outputCapture.getStdout()).contains("No todos in the current session");
        }
    }

    @Test
    public void testTodoRead_WithTodos() {
        // Mock SessionManager with todos
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager                 mockManager = mock(SessionManager.class);
            List<TodoReadCommand.TodoItem> todos       = new ArrayList<>();
            todos.add(new TodoReadCommand.TodoItem("1", "Complete feature",
                    TodoReadCommand.TodoItem.Status.IN_PROGRESS,
                    TodoReadCommand.TodoItem.Priority.HIGH));
            todos.add(new TodoReadCommand.TodoItem("2", "Write tests",
                    TodoReadCommand.TodoItem.Status.PENDING,
                    TodoReadCommand.TodoItem.Priority.MEDIUM));
            todos.add(new TodoReadCommand.TodoItem("3", "Review code",
                    TodoReadCommand.TodoItem.Status.COMPLETED,
                    TodoReadCommand.TodoItem.Priority.LOW));

            when(mockManager.getTodoList()).thenReturn(todos);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);

            // Execute command
            int exitCode = todoReadCommand.execute(new String[] {});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getStdout();
            assertThat(output).contains("Current Todo List");
            assertThat(output).contains("Complete feature");
            assertThat(output).contains("Write tests");
            assertThat(output).contains("Review code");
            assertThat(output).contains("Summary: 1 completed, 1 in progress, 1 pending");
        }
    }

    @Test
    public void testTodoWrite_AddTodo() {
        // Mock SessionManager
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager                 mockManager = mock(SessionManager.class);
            List<TodoReadCommand.TodoItem> todos       = new ArrayList<>();
            when(mockManager.getTodoList()).thenReturn(todos);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);

            // Execute add command
            int exitCode = todoWriteCommand.execute(new String[] {"add", "New task to complete"});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            assertThat(outputCapture.getStdout()).contains("Added todo");
            assertThat(outputCapture.getStdout()).contains("New task to complete");
            verify(mockManager).setTodoList(argThat(list ->
                            list.size() == 1 &&
                            list.get(0).getContent().equals("New task to complete")
                                                   ));
        }
    }

    @Test
    public void testTodoWrite_AddWithPriority() {
        // Mock SessionManager
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            when(mockManager.getTodoList()).thenReturn(new ArrayList<>());
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);

            // Execute add command with priority
            int exitCode = todoWriteCommand.execute(new String[] {"add", "Urgent task", "-p", "high"});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            verify(mockManager).setTodoList(argThat(list ->
                            list.size() == 1 &&
                            list.get(0).getPriority() == TodoReadCommand.TodoItem.Priority.HIGH
                                                   ));
        }
    }

    @Test
    public void testTodoWrite_UpdateStatus() {
        // Mock SessionManager with existing todo
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager                 mockManager = mock(SessionManager.class);
            List<TodoReadCommand.TodoItem> todos       = new ArrayList<>();
            TodoReadCommand.TodoItem todo = new TodoReadCommand.TodoItem("123", "Existing task",
                    TodoReadCommand.TodoItem.Status.PENDING,
                    TodoReadCommand.TodoItem.Priority.MEDIUM);
            todos.add(todo);
            when(mockManager.getTodoList()).thenReturn(todos);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);

            // Execute update command
            int exitCode = todoWriteCommand.execute(new String[] {"update", "123", "-s", "completed"});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            assertThat(outputCapture.getStdout()).contains("Updated todo");
            // The fix commits a replacement TodoItem (immutability) rather than mutating the live
            // item: the new status is in the committed list, and the original instance is untouched.
            verify(mockManager).setTodoList(argThat(list ->
                    list.size() == 1
                    && list.get(0).getId().equals("123")
                    && list.get(0).getStatus() == TodoReadCommand.TodoItem.Status.COMPLETED));
            assertThat(todo.getStatus()).isEqualTo(TodoReadCommand.TodoItem.Status.PENDING);
        }
    }

    @Test
    public void testTodoWrite_RemoveTodo() {
        // Mock SessionManager with existing todo
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager                 mockManager = mock(SessionManager.class);
            List<TodoReadCommand.TodoItem> todos       = new ArrayList<>();
            todos.add(new TodoReadCommand.TodoItem("456", "Task to remove",
                    TodoReadCommand.TodoItem.Status.PENDING,
                    TodoReadCommand.TodoItem.Priority.LOW));
            when(mockManager.getTodoList()).thenReturn(todos);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);

            // Execute remove command
            int exitCode = todoWriteCommand.execute(new String[] {"remove", "456"});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            assertThat(outputCapture.getStdout()).contains("Removed todo");
            verify(mockManager).setTodoList(argThat(List::isEmpty));
        }
    }

    @Test
    public void testTodoWrite_ClearAll() {
        // Mock SessionManager
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);

            // Execute clear command
            int exitCode = todoWriteCommand.execute(new String[] {"clear"});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            assertThat(outputCapture.getStdout()).contains("Cleared all todos");
            verify(mockManager).setTodoList(argThat(List::isEmpty));
        }
    }

    @Test
    public void testTodoWrite_NoAction() {
        // Execute command without action
        int exitCode = todoWriteCommand.execute(new String[] {});

        // Verify
        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("No action provided");
    }
}