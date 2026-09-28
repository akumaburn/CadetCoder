package com.eonmux.cadetcoder.session;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.ContextWindow;
import com.eonmux.cadetcoder.ai.metrics.TokenEstimator;
import com.eonmux.cadetcoder.logging.DebugLogger;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * Singleton class to manage session state, including loading and saving session data.
 */
public class SessionManager {
    /**
     * Maximum number of archived session files kept in the session history directory.
     * The oldest sessions beyond this cap are pruned whenever a session is archived,
     * so the history directory cannot grow without bound. The currently active session
     * is never pruned.
     * <p>
     * There is no session section in {@link com.eonmux.cadetcoder.config.Configuration}
     * to host this value, so it is kept as a named constant here.
     */
    public static final int MAX_SESSION_HISTORY_FILES = 50;

    /** Extension used for archived session-state documents. */
    private static final String SESSION_FILE_EXTENSION = ".json";

    /**
     * Deterministic recency ordering for session-history candidates: newest modification
     * time first, with the file name (which embeds the session creation timestamp) as a
     * stable tie-breaker so equal-timestamp files never order arbitrarily.
     */
    private static final Comparator<File> RECENCY_ORDER =
            Comparator.comparingLong(File::lastModified).reversed()
                      .thenComparing(File::getName, Comparator.reverseOrder());

    private static SessionManager instance;
    private final  ObjectMapper   mapper;
    private        String         sessionFilePath;
    private        String         sessionHistoryPath;

    /** Set once a session is restored, so only a continued run reopens its conversation. */
    private volatile boolean resumed = false;

    /**
     * Where the current scrollback is fetched from when a session is saved.
     *
     * <p>Pulled rather than pushed. A session is written after every exchange, and copying the whole
     * scrollback into this object on each of those — most of which are never saved to disk before
     * the next one replaces them — would be work done over and over for one eventual read. The shell
     * registers a source; saving asks it for a bounded snapshot at the moment it is needed.</p>
     *
     * <p>Absent outside the interactive shell, where there is no scrollback to keep.</p>
     */
    private volatile java.util.function.Supplier<List<TranscriptEntry>> transcriptSource;
    private        SessionState   sessionState;

    private SessionManager() {
        mapper = newMapper();
        initializePaths();
        loadSession();
    }

    /**
     * The reader/writer for session documents.
     *
     * <h2>Why unknown properties are ignored</h2>
     *
     * <p>Jackson's default is to fail on a property the class does not declare, and failing to read
     * a session is not a small failure here: {@link #loadSession()} responds to it by starting a
     * fresh one, which is to say by discarding the conversation. That turns any change to the shape
     * of a session — a field renamed, a field removed, a field written by a newer build and read by
     * an older one — into silent data loss for every session already on disk.</p>
     *
     * <p>{@link SessionState} and {@link TranscriptEntry} say this for themselves, so any reader is
     * safe with them. This covers the rest of the document: a session also carries types this
     * package does not own, and a field added to one of those must not cost a conversation
     * either.</p>
     *
     * @return the configured mapper
     */
    private static ObjectMapper newMapper() {
        return new ObjectMapper()
                .disable(com.fasterxml.jackson.databind.DeserializationFeature
                                 .FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private void initializePaths() {
        try {
            String baseDir = com.eonmux.cadetcoder.config.ConfigManager.getInstance().getConfig().getBaseDir();
            sessionFilePath    = java.nio.file.Paths.get(baseDir, "session.json").toString();
            sessionHistoryPath = java.nio.file.Paths.get(baseDir, "sessions").toString();
        } catch (Exception e) {
            // Fallback to user home directory if config not available
            String userHome = System.getProperty("user.home");
            String cadetDir = java.nio.file.Paths.get(userHome, ".cadet").toString();
            sessionFilePath    = java.nio.file.Paths.get(cadetDir, "session.json").toString();
            sessionHistoryPath = java.nio.file.Paths.get(cadetDir, "sessions").toString();
        }
    }

    /**
     * Loads the session from the session file. Initializes a default session if the file does not exist or fails to load.
     */
    private void loadSession() {
        File sessionFile = new File(sessionFilePath);
        File parentDir   = sessionFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        if (sessionFile.exists()) {
            sessionState = readSessionState(sessionFile);
            if (sessionState == null) {
                OutputFormatter.printError("Failed to load session from " + sessionFilePath);
                preserveUnreadableSession(sessionFile);
                initializeDefaultSession();
            } else {
                // Housekeeping, not news: this fired on EVERY command, so a one-line `ls` was
                // bracketed by three lines about session bookkeeping the user never asked about.
                // It stays in the debug log, where it is useful when a session does go wrong.
                DebugLogger.getInstance().debug("SessionManager", "Session loaded from " + sessionFilePath);
            }
        } else {
            initializeDefaultSession();
        }
    }

    /**
     * A name no other session is using.
     *
     * <h2>Why the timestamp is not enough on its own</h2>
     *
     * <p>The identifier is also the archive's file name, so two sessions sharing one means the
     * second silently writes over the first. A millisecond used to be sufficient by accident: a
     * session was minted only when {@code session.json} was absent, which happens about once per
     * install. {@code /session new} makes it something a person can do twice in a second, and the
     * whole point of that command is that the session being left is kept.</p>
     *
     * <p>The timestamp stays at the front, so the names still sort into the order the sessions were
     * created — which is what {@code RECENCY_ORDER} falls back on when two files share a
     * modification time.</p>
     *
     * @return the identifier for a new session
     */
    private String newSessionId() {
        for (int attempt = 0; attempt < 100; attempt++) {
            String candidate = String.format("session-%d-%04x", System.currentTimeMillis(),
                    java.util.concurrent.ThreadLocalRandom.current().nextInt(0x10000));
            if (!new File(sessionHistoryPath, candidate + SESSION_FILE_EXTENSION).exists()) {
                return candidate;
            }
        }
        // Unreachable in practice; a name that cannot collide at all is better than looping.
        return "session-" + java.util.UUID.randomUUID();
    }

    /**
     * Moves a session file that could not be read out of the way, under a name that says why.
     *
     * <p>Without this the recovery was destructive: an unreadable file was replaced by a fresh
     * session, and the very next save wrote over it. A session file is unreadable for reasons that
     * are often recoverable by hand — a partial write from a machine that lost power, a document
     * from a build with a different shape — and none of them is a reason to destroy the only copy
     * of a conversation. The failure is already reported; this makes it undoable.</p>
     *
     * @param sessionFile the file that would not parse
     */
    private void preserveUnreadableSession(File sessionFile) {
        java.nio.file.Path source = sessionFile.toPath();
        java.nio.file.Path kept   = source.resolveSibling(
                sessionFile.getName() + ".unreadable-" + System.currentTimeMillis());
        try {
            java.nio.file.Files.move(source, kept);
            OutputFormatter.printInfo("Kept the unreadable session as " + kept.getFileName()
                                      + "; starting a new one.");
        } catch (IOException | SecurityException e) {
            // Nothing further to try: the new session will replace it, and the parse failure has
            // already been reported.
            debug("Could not set aside the unreadable session file: " + e.getMessage());
        }
    }

    /**
     * Initializes a default session state.
     */
    private void initializeDefaultSession() {
        String sessionId = newSessionId();
        sessionState = new SessionState();
        sessionState.setSessionId(sessionId);
        sessionState.setCreatedAt(System.currentTimeMillis());
        sessionState.setLastModified(System.currentTimeMillis());
        sessionState.setConversationHistory(new ArrayList<>());
        sessionState.setTodoList(new ArrayList<>());
        sessionState.setTranscript(new ArrayList<>());
    }

    /**
     * Retrieves the singleton instance of SessionManager.
     *
     * @return the SessionManager instance
     */
    public static synchronized SessionManager getInstance() {
        if (instance == null) {
            instance = new SessionManager();
        }
        return instance;
    }

    /**
     * Saves the current session state.
     */
    public void saveState() {
        saveSession();
    }

    /**
     * Saves the current session state to the session file.
     */
    /**
     * Registers where to read the scrollback from when saving.
     *
     * @param source supplies the regions to persist; {@code null} stops the transcript being saved
     */
    public void setTranscriptSource(java.util.function.Supplier<List<TranscriptEntry>> source) {
        this.transcriptSource = source;
    }

    /**
     * The scrollback restored with this session.
     *
     * <p>A copy, for the reason {@link #getConversationHistory()} gives: the session's own list is
     * written from command threads under this monitor and read by callers that are not holding it,
     * so handing out the live one let a reader walk it while a save was replacing it.</p>
     *
     * @return the saved regions, empty when the session had none
     */
    public synchronized List<TranscriptEntry> getTranscript() {
        return sessionState == null ? List.of() : new ArrayList<>(sessionState.getTranscript());
    }

    public synchronized void saveSession() {
        if (sessionState == null) {
            return;
        }
        captureTranscript();
        persist();
    }

    /** Takes a copy of the shell's scrollback, if there is a shell to take it from. */
    private void captureTranscript() {
        java.util.function.Supplier<List<TranscriptEntry>> source = transcriptSource;
        if (source == null) {
            return;
        }
        try {
            sessionState.setTranscript(source.get());
        } catch (RuntimeException e) {
            // The scrollback is a convenience; failing to capture it must never cost the
            // conversation, the todo list, or anything else this file carries.
            DebugLogger.getInstance().debug("SessionManager",
                    "Could not capture the transcript for saving: " + e);
        }
    }

    /**
     * Writes the session as it currently stands.
     *
     * <p>Separate from {@link #saveSession()} because switching sessions must NOT take the screen
     * on the way past: the scrollback on display belongs to the session being left, and pulling it
     * while the incoming one is in place would write the old session's screen into the new
     * session's file — losing exactly the thing the incoming session was opened to show.</p>
     */
    private void persist() {
        // Stamped BEFORE the current session is written rather than inside the archiving step that
        // follows it, which left session.json permanently one save behind its own archive.
        sessionState.setLastModified(System.currentTimeMillis());
        try {
            File sessionFile = new File(sessionFilePath);
            File parentDir   = sessionFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            writeSessionFile(sessionFile);
            DebugLogger.getInstance().debug("SessionManager", "Session saved to " + sessionFilePath);
        } catch (IOException e) {
            OutputFormatter.printError("Failed to save session: " + e.getMessage());
        }
        // Keep the session history in step with the current session so that
        // --continue / --resume have an up-to-date archive to restore from.
        archiveCurrentSession(false);
    }

    /**
     * Closes the current session and opens an empty one.
     *
     * <p>Every run continues whatever is in {@code session.json}, which is right for a tool invoked
     * mostly one command at a time — and leaves no way to draw a line under a conversation. Without
     * one, a session accumulates for as long as the install lasts, and the quarter of the context
     * window a resume spends on history goes on turns from a different piece of work entirely.</p>
     *
     * <p>The session being left is written out first and stays in the archive, so it is still there
     * for {@code --resume}.</p>
     *
     * @return the identifier of the new session
     */
    public synchronized String startNewSession() {
        saveSession();
        stopTheOldSessionsReminders();
        initializeDefaultSession();
        resumed = false;
        persist();
        return sessionState.getSessionId();
    }

    /**
     * Stops the timers the session being left had standing.
     *
     * <p>A timer is a note the run left itself -- "check whether the build finished" -- and it is
     * legible only beside the conversation that wrote it. The registry is keyed by scope and
     * everything the person at the terminal drives shares one, so without this every standing timer
     * followed the user into the next conversation: check-ins about work it had never heard of,
     * counted against a limit it had not set, addressed by ids it had never been shown.</p>
     */
    private static void stopTheOldSessionsReminders() {
        com.eonmux.cadetcoder.timers.TimerRegistry.cancelAll();
    }

    /**
     * Whether this process restored a previous session rather than starting one.
     *
     * <p>Read when a run seeds its transcript: restored history belongs in the prompt of a run the
     * user asked to continue, and nowhere else. Seeding it unconditionally would drag the previous
     * invocation's conversation into every unrelated command that followed it.</p>
     *
     * @return {@code true} after a successful {@code --continue} or {@code --resume}
     */
    public synchronized boolean isResumed() {
        return resumed;
    }

    /** Clears the resumed flag; used by tests so one case cannot leak into the next. */
    synchronized void clearResumed() {
        resumed = false;
    }

    /**
     * Forgets the conversation.
     *
     * <p>Used by tests so one case cannot leak into the next. It exists because
     * {@link #getConversationHistory()} hands back a copy: emptying the list it returns used to
     * empty the session's own, which is the same reason the copy is now made.</p>
     */
    synchronized void clearConversationHistory() {
        sessionState.getConversationHistory().clear();
    }

    /**
     * The conversation so far.
     *
     * <p>A copy. The list itself is appended to from command threads under this monitor, and handing
     * it out live let a caller walk it while another turn was being recorded — which is a
     * {@link java.util.ConcurrentModificationException} in whichever thread happened to be reading,
     * and the readers are exactly the paths that rebuild a resumed prompt.</p>
     *
     * @return the entries, oldest first
     */
    public synchronized List<String> getConversationHistory() {
        return new ArrayList<>(sessionState.getConversationHistory());
    }

    /**
     * Appends one interaction to the session log.
     *
     * <p>Synchronized on the same monitor as {@link #saveSession()}, which serialises the whole
     * session to JSON. Appending from a command thread while the shutdown hook or another command
     * was mid-serialisation was a concurrent modification of the very list being written.</p>
     *
     * @param interaction the text to record
     */
    public synchronized void addToConversationHistory(String interaction) {
        if (interaction == null || interaction.isBlank()) {
            return;
        }
        List<String> history = sessionState.getConversationHistory();
        // A retried or reformatted turn produces the same text again. Recorded verbatim, one
        // exchange became nine identical lines in a real run -- restoring which tells a resumed
        // model the same sentence nine times and crowds out the turns that differ.
        if (!history.isEmpty() && interaction.equals(history.get(history.size() - 1))) {
            return;
        }
        history.add(interaction);
        trimConversationHistory(history);
    }

    /**
     * Records the user's own turn.
     *
     * <p>Only model responses were ever recorded, so a restored "conversation" was one half of one:
     * a list of answers with none of the questions. A model resuming saw what it had said and no
     * indication of what it had been asked.</p>
     *
     * <p>Taken from the command layer rather than from {@code AIManager}, because at the point a
     * completion is sent the "user prompt" is the whole rendered transcript — recording that would
     * store the history inside itself and grow quadratically.</p>
     *
     * @param request what the user actually asked for
     */
    public synchronized void addUserRequest(String request) {
        if (request == null || request.isBlank()) {
            return;
        }
        addToConversationHistory("User: " + request.strip());
    }

    /**
     * How much of a session's conversation is worth keeping on disk.
     *
     * <p>Derived, not chosen. {@link ResumedContext} reopens at most
     * {@link ResumedContext#BUDGET_SHARE} of the prompt budget, so history beyond that can never be
     * handed back to a model — keeping it stores bytes that by construction cannot be read.</p>
     *
     * <p>The multiplier is the one part that is a judgement: a session outlives the model it was
     * started under, and someone who switches to a larger context window should find history there
     * to restore rather than a file already trimmed to the smaller model's shape. Four times the
     * current model's restorable share covers that without being unbounded.</p>
     *
     * <p>A bound is needed at all only because a session is never recreated — every run continues the
     * one in {@code session.json}, and the whole file is rewritten on every save. Unbounded, that is
     * unbounded disk and per-save work that grows with the age of the session.</p>
     *
     * @return the character ceiling for the stored conversation
     */
    static int maxHistoryChars() {
        int restorableTokens = (int) (ContextWindow.tokens() * ResumedContext.BUDGET_SHARE);
        return Math.max(MINIMUM_HISTORY_CHARS,
                        TokenEstimator.charsFor(restorableTokens) * MODEL_CHANGE_HEADROOM);
    }

    /** Room for a later move to a larger-context model; see {@link #maxHistoryChars()}. */
    private static final int MODEL_CHANGE_HEADROOM = 4;

    /**
     * Floor for {@link #maxHistoryChars()}.
     *
     * <p>An uncatalogued endpoint reports the 8k default, and trimming a session to what that model
     * could restore would throw away context the user's real model can hold.</p>
     */
    private static final int MINIMUM_HISTORY_CHARS = 65_536;

    private static void trimConversationHistory(List<String> history) {
        long total = 0;
        for (String entry : history) {
            total += entry == null ? 0 : entry.length();
        }
        int ceiling = maxHistoryChars();
        while (total > ceiling && history.size() > 1) {
            String removed = history.remove(0);
            total -= removed == null ? 0 : removed.length();
        }
    }

    /**
     * The session's todo list.
     *
     * <h2>Why this is a copy, and under the monitor</h2>
     *
     * <p>Everything else that reaches into the session takes this monitor, because
     * {@link #persist()} serialises the whole session to JSON under it and a list being appended to
     * while Jackson walks it is a {@link java.util.ConcurrentModificationException} -- which is not
     * an {@link IOException}, so it escapes {@code saveSession} and ends whatever asked for the
     * save. These four accessors took no monitor at all and handed out the session's own objects:
     * {@code TodoWriteCommand} synchronizes on this manager by hand precisely because of it, the
     * agent's todo tools and {@code ChatCommand} do not, and every worker in a run writes todos.
     * {@link #getConversationHistory()} has copied since the same thing happened to the
     * conversation.</p>
     *
     * <p>Every reader builds a new list rather than editing the one it is given, so the copy costs
     * nothing and makes the session's state the session's.</p>
     *
     * @return the todo items, in order; never {@code null}
     */
    public synchronized List<com.eonmux.cadetcoder.commands.TodoReadCommand.TodoItem> getTodoList() {
        return new ArrayList<>(sessionState.getTodoList());
    }

    /**
     * Replaces the session's todo list.
     *
     * @param todoList the items to keep; stored as a copy so the caller's list stays the caller's
     */
    public synchronized void setTodoList(
            List<com.eonmux.cadetcoder.commands.TodoReadCommand.TodoItem> todoList) {
        sessionState.setTodoList(todoList == null ? new ArrayList<>() : new ArrayList<>(todoList));
    }

    public synchronized String getCurrentSessionId() {
        return sessionState.getSessionId();
    }

    /**
     * The session as it stands, as a snapshot.
     *
     * <h2>Why the live object is not handed out</h2>
     *
     * <p>It is the object {@link #persist()} serialises, and handing it to a caller that is not
     * holding this monitor let that caller read -- or a worker write -- a list Jackson was part-way
     * through. A snapshot answers what the callers actually ask it: how the session is identified,
     * when it began, and how much is in it.</p>
     *
     * @return a copy of the session state; changes to it do not reach the session
     */
    public synchronized SessionState getSessionState() {
        SessionState snapshot = new SessionState();
        snapshot.setSessionId(sessionState.getSessionId());
        snapshot.setCreatedAt(sessionState.getCreatedAt());
        snapshot.setLastModified(sessionState.getLastModified());
        snapshot.setConversationHistory(new ArrayList<>(sessionState.getConversationHistory()));
        snapshot.setTodoList(new ArrayList<>(sessionState.getTodoList()));
        snapshot.setTranscript(new ArrayList<>(sessionState.getTranscript()));
        return snapshot;
    }

    /**
     * Archives the current session into the session history directory and reports the
     * outcome to the user.
     */
    public synchronized void saveSessionToHistory() {
        if (sessionState != null) {
            // saveSession() stamps its own; this is the one entry that archives without it.
            sessionState.setLastModified(System.currentTimeMillis());
        }
        archiveCurrentSession(true);
    }

    /**
     * Writes the current session state into the session history directory and prunes the
     * history down to {@link #MAX_SESSION_HISTORY_FILES} entries.
     *
     * @param verbose when {@code true} the outcome is reported through {@link OutputFormatter};
     *                when {@code false} it is only recorded at debug level, so that the
     *                implicit archive performed on every save stays quiet
     */
    /**
     * Writes the session to {@code target} without ever leaving a half-written file there.
     *
     * <h2>Why not write straight to it</h2>
     *
     * <p>{@code writeValue} truncates the target and then fills it, so a crash, a kill, or a full
     * disk during that window leaves unparseable JSON where the session used to be — and the loader
     * responds to unparseable JSON by starting a fresh session, which is to say by discarding the
     * conversation. The file is written beside the target and moved onto it instead, so a reader
     * sees either the old session or the new one.</p>
     *
     * <h2>Why the temporary file is forced to disk first</h2>
     *
     * <p>A rename is atomic with respect to other readers and says nothing about what has reached
     * the platter. Writing and then renaming without a flush leaves the filesystem free to persist
     * the rename before the data, so the promise above -- explicitly a promise about "a crash, a
     * kill, or a full disk" -- did not hold for the case it names: after a power loss the session
     * file could be present, current by name, and empty or half-written inside, which the loader
     * answers by starting a fresh conversation. Forcing the contents out before the move is what
     * makes the rename a choice between two complete files.</p>
     *
     * <h2>Why the permissions</h2>
     *
     * <p>Owner-only, for the same reason {@code config.json} is: this file holds the conversation
     * and the scrollback, which routinely carry file contents, command output and whatever was being
     * discussed. At a typical umask it would otherwise be readable by every local account.</p>
     *
     * @param target where the session belongs
     * @throws IOException when the session could not be written
     */
    private void writeSessionFile(File target) throws IOException {
        java.nio.file.Path destination = target.toPath();
        java.nio.file.Path directory   = destination.getParent();
        java.nio.file.Path temporary   = directory == null
                ? java.nio.file.Files.createTempFile("session", ".tmp")
                : java.nio.file.Files.createTempFile(directory, "session", ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), sessionState);
            forceToDisk(temporary);
            restrictToOwnerOnly(temporary);
            try {
                java.nio.file.Files.move(temporary, destination,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Some filesystems cannot promise it. A replacing move is still better than
                // truncating the destination and writing into it.
                java.nio.file.Files.move(temporary, destination,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            java.nio.file.Files.deleteIfExists(temporary);
        }
    }

    /**
     * Makes sure what was written is on the disk and not only in the page cache.
     *
     * <p>Reported rather than raised, like the permissions below it: a filesystem that will not say
     * whether the write has landed is a weaker durability guarantee, not a reason to lose the save
     * that is otherwise complete.</p>
     *
     * @param path the file just written
     */
    private static void forceToDisk(java.nio.file.Path path) {
        try (java.nio.channels.FileChannel channel =
                     java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE)) {
            channel.force(true);
        } catch (IOException | RuntimeException e) {
            DebugLogger.getInstance().debug("SessionManager",
                    "Could not force " + path + " to disk before replacing the session: "
                    + e.getMessage());
        }
    }

    /** Holds a file at owner read/write, where the filesystem supports saying so. */
    private static void restrictToOwnerOnly(java.nio.file.Path path) {
        try {
            if (!java.nio.file.Files.getFileStore(path)
                    .supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class)) {
                return; // Non-POSIX filesystem; access control is handled elsewhere.
            }
            java.nio.file.Files.setPosixFilePermissions(path,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Reported rather than raised: failing to tighten permissions must not cost the save.
            DebugLogger.getInstance().debug("SessionManager",
                    "Could not restrict permissions on " + path + ": " + e.getMessage());
        }
    }

    private synchronized void archiveCurrentSession(boolean verbose) {
        if (sessionState == null) {
            return;
        }
        try {
            File historyDir = new File(sessionHistoryPath);
            if (!historyDir.exists() && !historyDir.mkdirs() && !historyDir.isDirectory()) {
                throw new IOException("Unable to create session history directory: " + sessionHistoryPath);
            }

            String sessionId   = sessionState.getSessionId();
            File   sessionFile = new File(historyDir, sessionId + SESSION_FILE_EXTENSION);
            writeSessionFile(sessionFile);

            if (verbose) {
                OutputFormatter.printSuccess("Session saved with ID: " + sessionId);
            } else {
                debug("Session archived with ID: " + sessionId);
            }

            pruneSessionHistory(sessionId);
        } catch (IOException e) {
            if (verbose) {
                OutputFormatter.printError("Failed to save session to history: " + e.getMessage());
            } else {
                debug("Failed to archive session to history: " + e.getMessage());
            }
        }
    }

    /**
     * Deletes the oldest archived sessions so that at most {@link #MAX_SESSION_HISTORY_FILES}
     * remain.
     * <p>
     * Only documents that actually deserialize as a {@link SessionState} are removed, so
     * unrelated files that happen to share the directory are never destroyed, and the
     * currently active session is always retained. Deletion failures are tolerated: they
     * are recorded at debug level and pruning continues with the next candidate.
     *
     * @param activeSessionId identifier of the session that must never be pruned
     */
    private void pruneSessionHistory(String activeSessionId) {
        List<File> candidates = listSessionHistoryFiles();
        if (candidates.size() <= MAX_SESSION_HISTORY_FILES) {
            return;
        }

        String activeFileName = activeSessionId == null ? null : activeSessionId + SESSION_FILE_EXTENSION;
        for (File candidate : candidates.subList(MAX_SESSION_HISTORY_FILES, candidates.size())) {
            if (activeFileName != null && activeFileName.equals(candidate.getName())) {
                continue;
            }
            if (readSessionState(candidate) == null) {
                // Not one of ours (or unreadable) - leave it alone rather than deleting
                // a file this component does not own.
                continue;
            }
            try {
                if (Files.deleteIfExists(candidate.toPath())) {
                    debug("Pruned old session file: " + candidate.getName());
                }
            } catch (IOException | SecurityException e) {
                debug("Failed to prune session file " + candidate.getName() + ": " + e.getMessage());
            }
        }
    }

    public boolean loadSessionById(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            OutputFormatter.printError("Session not found: " + sessionId);
            return false;
        }
        File sessionFile = new File(sessionHistoryPath, sessionId + SESSION_FILE_EXTENSION);
        if (!sessionFile.exists()) {
            OutputFormatter.printError("Session not found: " + sessionId);
            return false;
        }

        // Note what readSessionState does NOT do here: replace the session in memory with a default
        // one. Failing to read the session that was asked for is no reason to discard the session
        // already open, which is what the old recovery did on its way to returning false.
        SessionState loaded = readSessionState(sessionFile);
        if (loaded == null) {
            OutputFormatter.printError("Failed to load session: " + sessionId
                                       + " contains no session data");
            return false;
        }
        synchronized (this) {
            // The session being left keeps its own scrollback; see persist().
            saveSession();
            stopTheOldSessionsReminders();
            sessionState = loaded;
            resumed      = true;
            persist(); // make it the current session
        }
        OutputFormatter.printSuccess("Loaded session: " + sessionId);
        return true;
    }

    /**
     * Returns the most recently modified session that can be read back as a
     * {@link SessionState}.
     * <p>
     * Files in the history directory that do not parse as a session (for example
     * structured logs written by another component, or a truncated document) are skipped
     * with a debug-level note and the scan continues with the next candidate, so a single
     * foreign file can no longer prevent a session from being restored.
     *
     * @return the most recent readable session, or {@code null} if there is none
     */
    public SessionState getMostRecentSession() {
        for (File candidate : listSessionHistoryFiles()) {
            SessionState session = readSessionState(candidate);
            if (session != null) {
                return session;
            }
        }
        return null;
    }

    /**
     * Lists the most recent readable sessions, newest first.
     * <p>
     * Unreadable or foreign documents are skipped and the scan continues, so the returned
     * list contains up to {@code limit} genuine sessions even when the directory also
     * holds files written by other components.
     *
     * @param limit maximum number of sessions to return; values below one yield an empty list
     * @return the sessions, newest first
     */
    public List<SessionState> listRecentSessions(int limit) {
        List<SessionState> sessions = new ArrayList<>();
        if (limit < 1) {
            return sessions;
        }

        for (File candidate : listSessionHistoryFiles()) {
            SessionState session = readSessionState(candidate);
            if (session != null) {
                sessions.add(session);
                if (sessions.size() >= limit) {
                    break;
                }
            }
        }

        return sessions;
    }

    /**
     * Returns every JSON document in the session history directory, ordered newest first.
     * Existing archives written by earlier versions are still picked up, because the
     * directory and naming scheme are unchanged.
     *
     * @return the candidate files, newest first; never {@code null}
     */
    private List<File> listSessionHistoryFiles() {
        File historyDir = new File(sessionHistoryPath);
        if (!historyDir.exists() || !historyDir.isDirectory()) {
            return Collections.emptyList();
        }

        File[] sessionFiles = historyDir.listFiles(
                (dir, name) -> name.endsWith(SESSION_FILE_EXTENSION) && new File(dir, name).isFile());
        if (sessionFiles == null || sessionFiles.length == 0) {
            return Collections.emptyList();
        }

        List<File> candidates = new ArrayList<>(Arrays.asList(sessionFiles));
        candidates.sort(RECENCY_ORDER);
        return candidates;
    }

    /**
     * Reads a session document defensively.
     *
     * @param file the file to read
     * @return the deserialized session, or {@code null} if the file is not a session document
     */
    private SessionState readSessionState(File file) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(file);
            if (root == null) {
                debug("Skipping empty session file: " + file.getName());
                return null;
            }
            if (!isSessionDocument(root)) {
                debug("Skipping file that is not a session: " + file.getName());
                return null;
            }
            return mapper.treeToValue(root, SessionState.class);
        } catch (IOException e) {
            debug("Skipping unreadable session file " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Whether a JSON document is one of this component's.
     *
     * <h2>Why the question is asked directly</h2>
     *
     * <p>It used to be answered by Jackson refusing to bind an unknown property: a session log or
     * any other document sharing the directory failed to deserialize, and the failure was read as
     * "not a session". That worked, and it made a second promise nobody wanted — that a session
     * document must contain nothing this build does not recognise. Since an unreadable session is
     * treated as a reason to start a fresh one, that promise turned every change to the shape of
     * {@link SessionState} into silent loss of the conversations already on disk.</p>
     *
     * <p>So the question is asked here instead, of the fields a session actually has: it names a
     * session and records when that session began. {@code SessionLogger}'s documents — the ones
     * that share this directory in practice — carry a {@code logType} and a {@code startTime} and
     * satisfy neither.</p>
     *
     * @param root the parsed document
     * @return whether it should be read as a session
     */
    private static boolean isSessionDocument(com.fasterxml.jackson.databind.JsonNode root) {
        if (!root.isObject()) {
            return false;
        }
        com.fasterxml.jackson.databind.JsonNode id = root.get("sessionId");
        return id != null && id.isTextual() && !id.asText().isBlank()
               && root.hasNonNull("createdAt") && root.get("createdAt").isNumber();
    }

    /**
     * Records a debug-level note without ever propagating a logging failure to the caller.
     *
     * @param message the message to record
     */
    private void debug(String message) {
        try {
            DebugLogger.getInstance().debug("SessionManager", message);
        } catch (Exception e) {
            // Logging must never break session handling.
        }
    }
}
