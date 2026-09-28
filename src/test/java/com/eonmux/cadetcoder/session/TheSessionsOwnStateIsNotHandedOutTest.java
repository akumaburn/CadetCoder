package com.eonmux.cadetcoder.session;

import com.eonmux.cadetcoder.commands.TodoReadCommand;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What the session lends out must not be the session.
 *
 * <p><b>The defect</b>: {@code getConversationHistory} takes this manager's monitor and hands back
 * a copy, and says exactly why -- the list is appended to from command threads while
 * {@code persist()} serialises the whole session to JSON under the same monitor, so a caller
 * holding the live list could be walking it, or writing to it, while Jackson was. The todo list,
 * the scrollback and the session state itself were handed out live and with no monitor at all.
 * {@code TodoWriteCommand} synchronizes on the manager by hand because of it; the agent's own todo
 * tools and {@code ChatCommand} do not, and every worker in a run writes todos. The result is a
 * {@link java.util.ConcurrentModificationException} from inside {@code writeValue} -- which is not
 * an {@link java.io.IOException}, so it goes straight past the handler in {@code persist} and ends
 * whatever asked for the save.</p>
 */
public class TheSessionsOwnStateIsNotHandedOutTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private MockedStatic<ConfigManager> configMock;
    private SessionManager              sessions;

    @Before
    public void setUp() throws Exception {
        forgetSessionManager();

        ConfigManager manager = mock(ConfigManager.class);
        Configuration config  = new Configuration();
        config.setBaseDir(folder.getRoot().getAbsolutePath());
        when(manager.getConfig()).thenReturn(config);

        configMock = mockStatic(ConfigManager.class);
        configMock.when(ConfigManager::getInstance).thenReturn(manager);

        sessions = SessionManager.getInstance();
    }

    @After
    public void tearDown() throws Exception {
        if (configMock != null) {
            configMock.close();
        }
        forgetSessionManager();
    }

    private static void forgetSessionManager() throws Exception {
        java.lang.reflect.Field instance = SessionManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static TodoReadCommand.TodoItem todo(String id) {
        return new TodoReadCommand.TodoItem(id, "do " + id,
                                            TodoReadCommand.TodoItem.Status.PENDING,
                                            TodoReadCommand.TodoItem.Priority.MEDIUM);
    }

    @Test
    public void thetodoListHandedOutIsNotTheOneTheSessionIsSaving() {
        sessions.setTodoList(new ArrayList<>(List.of(todo("one"))));

        sessions.getTodoList().add(todo("two"));

        assertThat(sessions.getTodoList())
                .as("a caller writing into the list it was lent is writing into the document "
                    + "persist() is serialising")
                .hasSize(1);
    }

    @Test
    public void thelistGivenToSetTodoListStaysTheCallersOwn() {
        List<TodoReadCommand.TodoItem> mine = new ArrayList<>(List.of(todo("one")));
        sessions.setTodoList(mine);

        mine.add(todo("two"));

        assertThat(sessions.getTodoList()).hasSize(1);
    }

    @Test
    public void thescrollbackHandedOutIsNotTheSessionsOwn() {
        List<TranscriptEntry> lent = sessions.getTranscript();
        int before = lent.size();
        lent.add(new TranscriptEntry());

        assertThat(sessions.getTranscript()).hasSize(before);
    }

    @Test
    public void thesessionStateHandedOutIsASnapshot() {
        sessions.setTodoList(new ArrayList<>(List.of(todo("one"))));

        SessionState lent = sessions.getSessionState();
        lent.getTodoList().add(todo("two"));
        lent.getConversationHistory().add("not really said");

        assertThat(sessions.getTodoList()).hasSize(1);
        assertThat(sessions.getConversationHistory()).isEmpty();
    }

    /**
     * The failure as it actually happens: a worker writing todos while a save is under way.
     *
     * <p>The exception surfaces on the thread doing the SAVE, which is whatever command asked for
     * it, and names a class the caller has no reason to have heard of.</p>
     */
    @Test
    public void aworkerWritingTodosWhileTheSessionIsSavedDoesNotBreakTheSave() throws Exception {
        List<TodoReadCommand.TodoItem> initial = new ArrayList<>();
        for (int item = 0; item < 2_000; item++) {
            initial.add(todo("item-" + item));
        }
        sessions.setTodoList(initial);

        AtomicBoolean              keepWriting = new AtomicBoolean(true);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            int added = 0;
            while (keepWriting.get() && added < 20_000) {
                try {
                    // What an agent's todo tool does: read the list, put something on it.
                    sessions.getTodoList().add(todo("worker-" + added++));
                } catch (Throwable failed) {
                    workerFailure.compareAndSet(null, failed);
                    return;
                }
            }
        }, "todo-writing-worker");
        worker.setDaemon(true);
        worker.start();

        try {
            for (int save = 0; save < 30; save++) {
                sessions.saveSession();
            }
        } finally {
            keepWriting.set(false);
            worker.join(30_000);
        }

        assertThat(workerFailure.get()).isNull();
    }
}
