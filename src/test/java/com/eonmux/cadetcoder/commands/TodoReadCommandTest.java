package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.commands.TodoReadCommand.TodoItem;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.mockito.MockedStatic;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class TodoReadCommandTest {

    private TodoReadCommand   command;
    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        command       = new TodoReadCommand();
        outputCapture = new TestOutputCapture();
    }

    @After
    public void tearDown() {
        outputCapture.restore();
    }

    @Test
    public void testGetDescription() {
        assertThat(command.getDescription()).isEqualTo("Read the current to-do list for the session");
    }

    @Test
    public void testGetUsage() {
        assertThat(command.getUsage()).contains("todoread");
    }

    @Test
    public void testExecute_EmptyList() {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);
            when(mockManager.getTodoList()).thenReturn(Collections.emptyList());

            int result = command.execute(new String[0]);

            assertThat(result).isEqualTo(0);
            assertThat(outputCapture.getOutput()).contains("No todos in the current session");
        }
    }

    @Test
    public void testExecute_WithTodos() {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);

            List<TodoItem> todos = Arrays.asList(
                    new TodoItem("1", "Completed task", TodoItem.Status.COMPLETED, TodoItem.Priority.HIGH),
                    new TodoItem("2", "In progress task", TodoItem.Status.IN_PROGRESS, TodoItem.Priority.MEDIUM),
                    new TodoItem("3", "Pending task", TodoItem.Status.PENDING, TodoItem.Priority.LOW)
                                                );
            when(mockManager.getTodoList()).thenReturn(todos);

            int result = command.execute(new String[0]);

            assertThat(result).isEqualTo(0);
            String output = outputCapture.getOutput();
            assertThat(output).contains("Current Todo List");
            assertThat(output).contains("In Progress:");
            assertThat(output).contains("In progress task");
            assertThat(output).contains("Pending:");
            assertThat(output).contains("Pending task");
            assertThat(output).contains("Completed:");
            assertThat(output).contains("Completed task");
            assertThat(output).contains("Summary: 1 completed, 1 in progress, 1 pending");
        }
    }

    @Test
    public void testExecute_GroupByPriority() {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);

            List<TodoItem> todos = Arrays.asList(
                    new TodoItem("1", "High priority", TodoItem.Status.PENDING, TodoItem.Priority.HIGH),
                    new TodoItem("2", "Medium priority", TodoItem.Status.PENDING, TodoItem.Priority.MEDIUM),
                    new TodoItem("3", "Low priority", TodoItem.Status.PENDING, TodoItem.Priority.LOW)
                                                );
            when(mockManager.getTodoList()).thenReturn(todos);

            int result = command.execute(new String[0]);

            assertThat(result).isEqualTo(0);
            String output = outputCapture.getOutput();
            assertThat(output).contains("HIGH - High priority");
            assertThat(output).contains("MEDIUM - Medium priority");
            assertThat(output).contains("LOW - Low priority");
        }
    }

    @Test
    public void testExecute_Exception() {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);
            when(mockManager.getTodoList()).thenThrow(new RuntimeException("Test error"));

            int result = command.execute(new String[0]);

            assertThat(result).isEqualTo(1);
            assertThat(outputCapture.getStderr()).contains("Error reading todo list: Test error");
        }
    }

    @Test
    public void testCall() throws Exception {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockManager);
            when(mockManager.getTodoList()).thenReturn(Collections.emptyList());

            Integer result = command.call();

            assertThat(result).isEqualTo(0);
        }
    }
}