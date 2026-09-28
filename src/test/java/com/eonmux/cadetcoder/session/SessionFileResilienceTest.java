package com.eonmux.cadetcoder.session;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What happens to a session file the running build did not write.
 *
 * <h2>Why this matters more than it looks</h2>
 *
 * <p>{@code session.json} is the only copy of a conversation, and the loader's response to any
 * failure to read it was to start a fresh session — after which the very next save wrote over it.
 * So every way of failing to read the file was a way of destroying it, and one of those ways was
 * simply "a property this build does not declare": Jackson's default is to refuse the whole
 * document, which made every future change to the shape of {@link SessionState} a data-loss event
 * for the sessions already on disk.</p>
 */
public class SessionFileResilienceTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final ObjectMapper mapper = new ObjectMapper();

    private MockedStatic<ConfigManager> configMock;
    private Path                        baseDir;

    @Before
    public void setUp() throws Exception {
        resetSessionManager();
        baseDir = tempFolder.getRoot().toPath();
        Files.createDirectories(baseDir.resolve("sessions"));

        ConfigManager mockConfigManager = mock(ConfigManager.class);
        Configuration mockConfig        = new Configuration();
        mockConfig.setBaseDir(baseDir.toString());
        when(mockConfigManager.getConfig()).thenReturn(mockConfig);

        configMock = mockStatic(ConfigManager.class);
        configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
    }

    @After
    public void tearDown() throws Exception {
        if (configMock != null) {
            configMock.close();
        }
        resetSessionManager();
    }

    private void resetSessionManager() throws Exception {
        java.lang.reflect.Field instance = SessionManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private Path sessionFile() {
        return baseDir.resolve("session.json");
    }

    @Test
    public void aPropertyThisBuildDoesNotKnowDoesNotCostTheConversation() throws Exception {
        // Exactly the shape of a session written by a build with one field more than this one, or
        // one field this one has since dropped.
        Files.writeString(sessionFile(), """
                {"sessionId":"session-42","createdAt":1,"lastModified":2,
                 "conversationHistory":["User: remember this","AI: noted"],
                 "somethingThisBuildHasNeverHeardOf":{"nested":true},
                 "anotherOne":[1,2,3]}""");

        SessionManager manager = SessionManager.getInstance();

        assertThat(manager.getCurrentSessionId()).isEqualTo("session-42");
        assertThat(manager.getConversationHistory())
                .containsExactly("User: remember this", "AI: noted");
    }

    @Test
    public void aSessionCarryingTheFieldsThisBuildRemovedStillLoads() throws Exception {
        // Every session file written before those four fields were removed looks like this, which
        // is to say every session file on every machine that has run this tool.
        Files.writeString(sessionFile(), """
                {"sessionId":"session-44","createdAt":1,"lastModified":2,
                 "openFiles":["Main.java"],
                 "cursorPositions":{"Main.java":42},
                 "currentCommand":"plan: do the thing",
                 "lastAIResponse":"done",
                 "conversationHistory":["User: hello","AI: hi"],
                 "todoList":[],
                 "transcript":[{"kind":"COMMAND","depth":0,"title":"ls src","status":"OK",
                                "lines":["Main.java"]}]}""");

        SessionManager manager = SessionManager.getInstance();

        assertThat(manager.getCurrentSessionId()).isEqualTo("session-44");
        assertThat(manager.getConversationHistory()).containsExactly("User: hello", "AI: hi");
        assertThat(manager.getTranscript())
                .extracting(TranscriptEntry::getTitle)
                .containsExactly("ls src");
    }

    @Test
    public void aTranscriptRegionCarryingAnUnknownFieldStillLoads() throws Exception {
        // The same promise one level down: a session's nested types outlive the code that wrote
        // them too, and refusing the document over one of them costs the whole conversation.
        Files.writeString(sessionFile(), """
                {"sessionId":"session-45","createdAt":1,"lastModified":2,
                 "conversationHistory":["User: hello"],
                 "transcript":[{"kind":"COMMAND","depth":0,"title":"grep TODO","status":"OK",
                                "lines":["found 3"],"somethingNew":{"a":1}}]}""");

        SessionManager manager = SessionManager.getInstance();

        assertThat(manager.getTranscript())
                .extracting(TranscriptEntry::getTitle)
                .containsExactly("grep TODO");
        assertThat(manager.getConversationHistory()).containsExactly("User: hello");
    }

    @Test
    public void aSessionMissingHalfItsFieldsLoadsWithEmptyCollectionsRatherThanNulls() throws Exception {
        Files.writeString(sessionFile(), """
                {"sessionId":"session-43","createdAt":1}""");

        SessionManager manager = SessionManager.getInstance();

        // The turn after a restore is what used to throw: getConversationHistory().isEmpty().
        assertThat(manager.getConversationHistory()).isEmpty();
        assertThat(manager.getTodoList()).isEmpty();
        assertThat(manager.getTranscript()).isEmpty();

        manager.addUserRequest("still works");
        assertThat(manager.getConversationHistory()).containsExactly("User: still works");
    }

    @Test
    public void anUnreadableSessionIsKeptRatherThanOverwritten() throws Exception {
        Files.writeString(sessionFile(), "{ this is not json at all");

        SessionManager manager = SessionManager.getInstance();
        manager.addUserRequest("a new conversation");
        manager.saveSession();   // this is the write that used to destroy the old file

        try (Stream<Path> kept = Files.list(baseDir)) {
            List<String> names = kept.map(Path::getFileName).map(Object::toString).toList();
            assertThat(names)
                    .as("the only copy of a conversation is not deleted because it would not parse")
                    .anySatisfy(name -> assertThat(name).startsWith("session.json.unreadable-"));
        }
        assertThat(Files.readString(sessionFile())).contains("a new conversation");
    }

    @Test
    public void aDocumentThatIsNotASessionIsNotReadAsOne() throws Exception {
        // A SessionLogger document: it names a session, and it is not one.
        Files.writeString(sessionFile(), """
                {"logType":"session","version":"1.0","sessionId":"session-foreign",
                 "startTime":1750000000000,"entries":[]}""");

        SessionManager manager = SessionManager.getInstance();

        assertThat(manager.getCurrentSessionId())
                .as("a fresh session, not the log's identifier")
                .isNotEqualTo("session-foreign");
    }

    @Test
    public void theSavedSessionRecordsWhenItWasSaved() throws Exception {
        SessionManager manager = SessionManager.getInstance();
        manager.addUserRequest("something");

        // A sentinel rather than a clock comparison: the two writes can land in the same
        // millisecond, and what is being asserted is the ORDER of the stamp and the write.
        manager.getSessionState().setLastModified(0L);
        manager.saveSession();

        SessionState written = mapper.readValue(sessionFile().toFile(), SessionState.class);

        // The stamp used to be applied while archiving, which happens AFTER session.json has been
        // written, so this file permanently carried the previous save's timestamp.
        assertThat(written.getLastModified()).isNotZero();
        assertThat(written.getLastModified())
                .as("the session and its archive are the same document")
                .isEqualTo(mapper.readValue(baseDir.resolve("sessions")
                                                   .resolve(written.getSessionId() + ".json")
                                                   .toFile(),
                                            SessionState.class).getLastModified());
    }

    @Test
    public void theConversationHandedOutIsACopy() {
        SessionManager manager = SessionManager.getInstance();
        manager.addUserRequest("kept");

        manager.getConversationHistory().clear();

        assertThat(manager.getConversationHistory())
                .as("the caller got a copy, so it walks a list nobody else is appending to")
                .containsExactly("User: kept");
    }
}
