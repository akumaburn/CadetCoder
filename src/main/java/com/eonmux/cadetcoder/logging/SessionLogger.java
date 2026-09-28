package com.eonmux.cadetcoder.logging;

import com.eonmux.cadetcoder.security.OwnerOnlyFile;
import com.eonmux.cadetcoder.security.SecretRedactor;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.ui.CollapsedOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive session logger that captures all user interactions, commands, 
 * and outputs for each session in a structured, readable format.
 * Session logs are stored in ~/.cadet/sessions/logs/.
 * <p>
 * The logs live in their own sub-directory so that they cannot be confused with the
 * session-state archives that {@link com.eonmux.cadetcoder.session.SessionManager} keeps
 * in ~/.cadet/sessions/: both used to be plain {@code .json} files in the same folder with
 * incompatible schemas, which broke session restore. Logs written by earlier versions are
 * migrated into the sub-directory on start-up.
 */
public class SessionLogger {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** Name of the sessions folder owned by SessionManager. */
    private static final String SESSIONS_DIR_NAME = "sessions";
    /** Sub-directory of {@link #SESSIONS_DIR_NAME} that holds this logger's output. */
    private static final String SESSION_LOGS_DIR_NAME = "logs";
    /** Marker field that identifies a structured session log document. */
    private static final String LOG_TYPE_FIELD = "logType";
    /** Number of bytes inspected when deciding whether a legacy JSON file is a session log. */
    private static final int LOG_TYPE_PROBE_BYTES = 512;
    /** Fallback retention when the logging configuration is unavailable. */
    private static final int DEFAULT_MAX_SESSION_LOGS = 20;
    /** How long the writer waits for the next entry before looking at whether it should stop. */
    private static final long WAIT_FOR_NEXT_MILLIS = 10L;
    /** How long shutdown gives the writer to finish what is already queued. */
    private static final long DRAIN_MILLIS = 5_000L;
    /** How many entries may wait to be written before further ones are lost. */
    private static final int PENDING_ENTRY_LIMIT = 5_000;
    
    private static SessionLogger instance;
    
    private final PendingEntries pending = new PendingEntries(PENDING_ENTRY_LIMIT);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong entrySequence = new AtomicLong(0);
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private Thread loggerThread;
    private BoundedLog sessionLogWriter;
    private BoundedLog structuredLogWriter;
    private Path sessionLogFile;
    private Path structuredLogFile;
    private String currentSessionId;
    private long sessionStartTime;
    private boolean sessionLoggingEnabled;

    /** Whether {@code logging.structuredLogging} asked for the JSON copy beside the readable log. */
    private boolean structuredLoggingEnabled = true;

    /** The bound {@code logging.maxSessionLogSize} puts on EACH of this session's log files. */
    private long maxSessionLogBytes = DEFAULT_MAX_SESSION_LOG_MB * 1024L * 1024L;

    /** Matches {@code Configuration.LoggingConfig}'s own default, for when no config is readable. */
    private static final int DEFAULT_MAX_SESSION_LOG_MB = 50;
    
    private SessionLogger() {
        initializeSessionLogging();
    }
    
    public static synchronized SessionLogger getInstance() {
        if (instance == null) {
            instance = new SessionLogger();
        }
        return instance;
    }
    
    private void initializeSessionLogging() {
        try {
            Configuration config = ConfigManager.getInstance().getConfig();
            Configuration.LoggingConfig loggingConfig = config.getLogging();
            
            // Check if session logging is enabled (default to true)
            this.sessionLoggingEnabled = loggingConfig == null || loggingConfig.isSessionLoggingEnabled();
            readLogSettings(loggingConfig);
            
            String baseDir = config.getBaseDir();
            Path sessionsDir = Paths.get(baseDir, SESSIONS_DIR_NAME);
            Path sessionLogsDir = sessionsDir.resolve(SESSION_LOGS_DIR_NAME);

            // Move logs written by earlier versions out of the session-state directory so
            // they can neither shadow a session archive nor escape log retention. This runs
            // even when logging is disabled, because the stale files still need relocating.
            migrateLegacySessionLogs(sessionsDir, sessionLogsDir);

            if (!sessionLoggingEnabled) {
                return;
            }
            
            // Create session logs directory in user home under .cadet/sessions
            if (!Files.exists(sessionLogsDir)) {
                Files.createDirectories(sessionLogsDir);
            }
            
            // Start new session log
            startNewSessionLog(sessionLogsDir);

            // Keep the log directory bounded
            pruneSessionLogs(sessionLogsDir, resolveMaxSessionLogs(loggingConfig));
            
            // Start logger thread
            running.set(true);
            loggerThread = new Thread(this::runLoggerThread, "SessionLogger");
            loggerThread.setDaemon(true);
            loggerThread.start();
            
            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
            
            // Log session start
            logSessionStart();
            
        } catch (Exception e) {
            System.err.println("Failed to initialize session logging: " + e.getMessage());
            sessionLoggingEnabled = false;
        }
    }
    
    /**
     * Reads the two settings that decide what this logger writes and how much of it.
     *
     * <p>Both existed, were documented and were settable, and neither was read anywhere: the JSON
     * copy was written whether or not {@code logging.structuredLogging} asked for it, and a session
     * log grew without bound beside a {@code logging.maxSessionLogSize} that said it had one.</p>
     *
     * @param loggingConfig the logging settings, possibly {@code null}
     */
    private void readLogSettings(Configuration.LoggingConfig loggingConfig) {
        this.structuredLoggingEnabled = loggingConfig == null || loggingConfig.isStructuredLogging();
        int megabytes = loggingConfig == null || loggingConfig.getMaxSessionLogSize() <= 0
                        ? DEFAULT_MAX_SESSION_LOG_MB
                        : loggingConfig.getMaxSessionLogSize();
        this.maxSessionLogBytes = megabytes * 1024L * 1024L;
    }

    /**
     * Resolves how many session logs to retain from the logging configuration.
     *
     * @param loggingConfig the logging configuration, possibly {@code null}
     * @return a retention count of at least one
     */
    private int resolveMaxSessionLogs(Configuration.LoggingConfig loggingConfig) {
        int configured = loggingConfig == null ? DEFAULT_MAX_SESSION_LOGS : loggingConfig.getMaxSessionLogs();
        return configured > 0 ? configured : DEFAULT_MAX_SESSION_LOGS;
    }

    /**
     * Relocates session logs written by earlier versions from the session-state directory
     * into the dedicated log sub-directory.
     * <p>
     * Only this logger's own artefacts are moved: {@code .log} files, and {@code .json}
     * files whose leading bytes carry the {@code logType} marker that session-state
     * documents never contain. Every move is individually failure tolerant so that a
     * single unreadable or locked file cannot stop start-up.
     *
     * @param sessionsDir    directory shared with the session-state archives
     * @param sessionLogsDir destination directory for session logs
     */
    private void migrateLegacySessionLogs(Path sessionsDir, Path sessionLogsDir) {
        if (!Files.isDirectory(sessionsDir)) {
            return;
        }

        List<Path> legacyLogs = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(sessionsDir)) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry) && isSessionLogArtefact(entry)) {
                    legacyLogs.add(entry);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to scan for legacy session logs: " + e.getMessage());
            return;
        }

        if (legacyLogs.isEmpty()) {
            return;
        }

        try {
            if (!Files.exists(sessionLogsDir)) {
                Files.createDirectories(sessionLogsDir);
            }
        } catch (IOException e) {
            System.err.println("Failed to create session log directory: " + e.getMessage());
            return;
        }

        for (Path legacyLog : legacyLogs) {
            Path target = sessionLogsDir.resolve(legacyLog.getFileName().toString());
            try {
                if (Files.exists(target)) {
                    // A log with this name was already migrated; drop the stale duplicate
                    // rather than leaving it where it breaks session listing.
                    Files.deleteIfExists(legacyLog);
                } else {
                    Files.move(legacyLog, target);
                }
            } catch (IOException | SecurityException e) {
                System.err.println("Failed to migrate legacy session log " + legacyLog.getFileName()
                                   + ": " + e.getMessage());
            }
        }
    }

    /**
     * Determines whether a file in the sessions directory was produced by this logger.
     *
     * @param file the candidate file
     * @return {@code true} for a human-readable session log or a structured session log
     */
    private boolean isSessionLogArtefact(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".log")) {
            return true;
        }
        if (!name.endsWith(".json")) {
            return false;
        }
        try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            char[] probe = new char[LOG_TYPE_PROBE_BYTES];
            int read = reader.read(probe);
            if (read <= 0) {
                return false;
            }
            return new String(probe, 0, read).contains("\"" + LOG_TYPE_FIELD + "\"");
        } catch (IOException | SecurityException e) {
            // Unreadable: leave it untouched rather than guessing.
            return false;
        }
    }

    /**
     * Deletes the oldest session logs so that at most {@code maxSessionLogs} log sets
     * remain. Each session contributes a {@code .log} and a {@code .json} file which are
     * retained or removed together, and the log files of the session currently being
     * written are never removed. Deletion failures are reported and skipped.
     *
     * @param sessionLogsDir  directory holding the session logs
     * @param maxSessionLogs  number of session log sets to keep
     */
    private void pruneSessionLogs(Path sessionLogsDir, int maxSessionLogs) {
        List<Path> logs = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(sessionLogsDir, "*.log")) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)) {
                    logs.add(entry);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to list session logs for pruning: " + e.getMessage());
            return;
        }

        if (logs.size() <= maxSessionLogs) {
            return;
        }

        // Newest first; the file name carries the session id and start timestamp, which
        // keeps the ordering deterministic when modification times collide.
        logs.sort(Comparator.comparingLong(SessionLogger::lastModifiedOrZero).reversed()
                            .thenComparing(path -> path.getFileName().toString(), Comparator.reverseOrder()));

        for (Path stale : logs.subList(maxSessionLogs, logs.size())) {
            if (sessionLogFile != null && stale.equals(sessionLogFile)) {
                continue;
            }
            deleteQuietly(stale);
            deleteQuietly(siblingWithExtension(stale, ".json"));
        }
    }

    /**
     * @param path the file to inspect
     * @return the modification time, or {@code 0} when it cannot be read
     */
    private static long lastModifiedOrZero(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException | SecurityException e) {
            return 0L;
        }
    }

    /**
     * @param path      a session log file
     * @param extension the replacement extension, including the leading dot
     * @return the sibling path carrying the given extension
     */
    private static Path siblingWithExtension(Path path, String extension) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        return path.resolveSibling(stem + extension);
    }

    /**
     * Deletes a file, tolerating any failure so that pruning cannot break the run.
     *
     * @param path the file to delete
     */
    private void deleteQuietly(Path path) {
        if (path == null || (structuredLogFile != null && path.equals(structuredLogFile))) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException | SecurityException e) {
            System.err.println("Failed to prune session log " + path.getFileName() + ": " + e.getMessage());
        }
    }

    private void startNewSessionLog(Path sessionLogsDir) throws IOException {
        // Get current session ID from SessionManager
        SessionManager sessionManager = SessionManager.getInstance();
        currentSessionId = sessionManager.getCurrentSessionId();
        if (currentSessionId == null) {
            currentSessionId = "session-" + System.currentTimeMillis();
        }
        
        sessionStartTime = System.currentTimeMillis();
        String timestamp = LocalDateTime.now().format(FILE_DATE_FORMAT);
        
        // Create human-readable session log file
        sessionLogFile = sessionLogsDir.resolve(currentSessionId + "_" + timestamp + ".log");
        sessionLogWriter = openRestricted(sessionLogFile);
        
        // Create structured JSON session log file for programmatic analysis, when asked for
        if (!structuredLoggingEnabled) {
            structuredLogFile   = null;
            structuredLogWriter = null;
            return;
        }
        structuredLogFile = sessionLogsDir.resolve(currentSessionId + "_" + timestamp + ".json");
        structuredLogWriter = openRestricted(structuredLogFile);
        
        // The first line of the document, in the same one-object-per-line form as everything after
        // it. See BoundedLog for why the file is JSON Lines rather than one pretty-printed object.
        ObjectNode logHeader = objectMapper.createObjectNode();
        logHeader.put(LOG_TYPE_FIELD, "session");
        logHeader.put("version", LOG_FORMAT_VERSION);
        logHeader.put("sessionId", currentSessionId);
        logHeader.put("startTime", sessionStartTime);
        logHeader.put("startTimeFormatted", LocalDateTime.now().format(TIMESTAMP_FORMAT));

        structuredLogWriter.write(objectMapper.writeValueAsString(logHeader),
                                  this::structuredClosingNotice);
    }

    /**
     * The version of the on-disk shape, which readers key off.
     *
     * <p>Raised to 2 because the shape changed: the file is now JSON Lines rather than a
     * pretty-printed header followed by pretty-printed objects, which was neither one JSON document
     * nor a document per line and therefore could not be parsed by anything at all -- including the
     * tools it exists for.</p>
     */
    private static final String LOG_FORMAT_VERSION = "2.0";

    /**
     * Opens a log file that exists before anything is written into it, readable by its owner alone.
     *
     * <h2>Why these files are restricted and the umask is not trusted</h2>
     *
     * <p>{@code config.json} and {@code session.json} are both made owner-only because of what is
     * in them, and what is in them is also in here: this logger is handed every line a command
     * prints, the whole of every prompt and the whole of every request payload. Created through a
     * bare {@code FileWriter} they arrived at whatever the umask allowed, which on a great many
     * machines is world-readable -- so the most revealing file of the three was the only one any
     * local account could read.</p>
     *
     * <p>The file is brought into existence with the permissions already on it rather than narrowed
     * afterwards, because a umask can only take permissions away: there is then no moment at which
     * the file exists, holds a prompt, and is readable by everyone.</p>
     *
     * @param file where the log belongs
     * @return the writer to use
     * @throws IOException if the file cannot be opened
     */
    private BoundedLog openRestricted(Path file) throws IOException {
        String unprotected = OwnerOnlyFile.createOwnerOnly(file);
        if (unprotected != null) {
            System.err.println("Failed to create " + file + " restricted to this account - it holds "
                               + "prompts, file contents and command output and may be readable by "
                               + "other users: " + unprotected);
        }
        return new BoundedLog(new PrintWriter(new BufferedWriter(
                new FileWriter(file.toFile(), StandardCharsets.UTF_8, true))), maxSessionLogBytes);
    }
    
    private void runLoggerThread() {
        while (running.get() || !pending.isEmpty()) {
            try {
                SessionLogEntry entry = pending.next(WAIT_FOR_NEXT_MILLIS, TimeUnit.MILLISECONDS);
                recordWhatWasLost();
                if (entry != null) {
                    writeLogEntry(entry);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Error in session logger thread: " + e.getMessage());
            }
        }
    }
    
    private void writeLogEntry(SessionLogEntry entry) {
        try {
            // Write human-readable format
            if (sessionLogWriter != null) {
                sessionLogWriter.write(String.format("[%s] %s",
                        entry.timestamp.format(TIMESTAMP_FORMAT),
                        SessionLogLine.from(entry)),
                        SessionLogger::readableClosingNotice);
            }
            
            // Write structured JSON format
            if (structuredLogWriter != null) {
                ObjectNode jsonEntry = objectMapper.createObjectNode();
                jsonEntry.put("sequence", entry.sequence);
                jsonEntry.put("timestamp", entry.timestamp.format(TIMESTAMP_FORMAT));
                jsonEntry.put("type", entry.type.name());
                jsonEntry.put("source", entry.source);
                jsonEntry.put("message", entry.message);
                
                if (entry.metadata != null && !entry.metadata.isEmpty()) {
                    ObjectNode metadataNode = objectMapper.createObjectNode();
                    entry.metadata.forEach(metadataNode::put);
                    jsonEntry.set("metadata", metadataNode);
                }
                
                if (entry.failure != null) {
                    ObjectNode exceptionNode = objectMapper.createObjectNode();
                    exceptionNode.put("class", entry.failure.type);
                    exceptionNode.put("message", entry.failure.message);
                    exceptionNode.put("stackTrace", entry.failure.stackTrace);
                    
                    jsonEntry.set("exception", exceptionNode);
                }
                
                structuredLogWriter.write(objectMapper.writeValueAsString(jsonEntry),
                                          this::structuredClosingNotice);
            }
            
        } catch (Exception e) {
            System.err.println("Failed to write session log entry: " + e.getMessage());
        }
    }

    /** What the readable log says, in its own form, when it has no room left. */
    private static String readableClosingNotice(long megabytes) {
        return String.format("[%s] This session log has reached logging.maxSessionLogSize (%d MB); "
                             + "nothing further is recorded in it.",
                             LocalDateTime.now().format(TIMESTAMP_FORMAT), megabytes);
    }

    /** The same, as one more line of JSON, so the file stays readable by whatever parses it. */
    private String structuredClosingNotice(long megabytes) {
        ObjectNode closing = objectMapper.createObjectNode();
        closing.put("timestamp", LocalDateTime.now().format(TIMESTAMP_FORMAT));
        closing.put("type", LogEntryType.ERROR.name());
        closing.put("source", "SessionLogger");
        closing.put("message", "This session log has reached logging.maxSessionLogSize ("
                               + megabytes + " MB); nothing further is recorded in it.");
        return closing.toString();
    }
    
    /**
     * One of this session's two log files, and the bound it is held to.
     *
     * <h2>Why it stops rather than rotating</h2>
     *
     * <p>A session log is one session's record read from the top; rotating it would discard the
     * beginning, which is the part that says what the session was asked to do. Stopping keeps a
     * complete account of a session up to the point the bound was reached, and the log says where
     * that was -- so a reader is never left believing a truncated record is a whole one.</p>
     *
     * <h2>Why both files are bounded and not just the readable one</h2>
     *
     * <p>{@code logging.maxSessionLogSize} was applied to the readable log alone, and the JSON copy
     * beside it is the LARGER of the two: it carries the whole of every prompt and the whole of
     * every request payload, where the readable log carries five hundred characters of each. So the
     * setting that says how big a session's logs may get bounded the smaller half and left the half
     * that actually grows to do so without limit. Each file is held to the bound, which is what the
     * setting has always said.</p>
     *
     * <h2>Why a line at a time</h2>
     *
     * <p>The JSON copy is written as JSON Lines -- one complete document per line -- rather than as
     * a pretty-printed header followed by pretty-printed objects. What it used to be was neither a
     * single JSON document nor a document per line, so nothing could parse the file whose whole
     * purpose is being parsed; and stopping part-way through, which a bound must be able to do,
     * would have left a half-written structure behind. A line is a document, so a file that stops
     * is a file that is still readable to its last complete line.</p>
     */
    private static final class BoundedLog {

        private final PrintWriter writer;
        private final long        maxBytes;
        private       long        written;
        private       boolean     full;

        BoundedLog(PrintWriter writer, long maxBytes) {
            this.writer   = writer;
            this.maxBytes = maxBytes;
        }

        /**
         * Writes one line, or the closing notice when there is no longer room for one.
         *
         * @param line          the line, in this file's own form, without its terminator
         * @param closingNotice what to say, in that same form, when the bound has been reached; it
         *                      is given the bound in megabytes, which is how the setting is written
         */
        void write(String line, java.util.function.LongFunction<String> closingNotice) {
            if (full) {
                return;
            }
            if (written >= maxBytes) {
                full = true;
                writer.println(closingNotice.apply(maxBytes / (1024L * 1024L)));
                writer.flush();
                return;
            }
            writer.println(line);
            writer.flush();
            written += line.getBytes(StandardCharsets.UTF_8).length
                       + System.lineSeparator().length();
        }

        void close() {
            writer.close();
        }
    }

    /**
     * Ends the session and leaves the log complete.
     *
     * <p>The order matters: the closing record is queued, the writer is asked to stop, it is given
     * time to reach the end of the queue, and only then are the files closed. Anything the writer
     * did not reach is written here rather than dropped.</p>
     */
    public void shutdown() {
        if (!sessionLoggingEnabled) return;

        try {
            logSessionEnd();

            running.set(false);
            awaitWriter();
            writeWhatIsLeft();
            closeLogFiles();
        } catch (Exception e) {
            System.err.println("Error during session logger shutdown: " + e.getMessage());
        }
    }

    /**
     * Waits for the writer to reach the end of the queue.
     *
     * <h2>Why it is not interrupted first</h2>
     *
     * <p>The writer spends nearly all its life waiting for the next entry, so an interrupt almost
     * always landed in that wait, ended the loop through the {@link InterruptedException} and left
     * everything still queued unwritten -- at shutdown, the "Session ended" record that had been
     * queued one line earlier. It is asked to stop through the flag it checks each time round, and
     * interrupted only if it is still going long afterwards, which means it is stuck rather than
     * busy.</p>
     */
    private void awaitWriter() {
        if (loggerThread == null) {
            return;
        }
        try {
            loggerThread.join(DRAIN_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (loggerThread.isAlive()) {
            System.err.println("Warning: session logger did not finish within " + DRAIN_MILLIS
                               + "ms; the " + pending.size() + " entries it had left are being "
                               + "written by the thread that is shutting it down");
            loggerThread.interrupt();
        }
        loggerThread = null;
    }

    /** Writes whatever the writer did not reach, so the end of a session is not lost with it. */
    private void writeWhatIsLeft() {
        for (SessionLogEntry entry = pending.nextIfWaiting(); entry != null;
             entry = pending.nextIfWaiting()) {
            writeLogEntry(entry);
        }
        recordWhatWasLost();
    }

    private void closeLogFiles() {
        if (sessionLogWriter != null) {
            sessionLogWriter.close();
            sessionLogWriter = null;
        }
        if (structuredLogWriter != null) {
            structuredLogWriter.close();
            structuredLogWriter = null;
        }
    }
    
    // Public logging methods
    
    public void logUserInput(String input) {
        if (!sessionLoggingEnabled) return;
        log(LogEntryType.USER_INPUT, "InteractiveShell", input, null, null);
    }
    
    public void logCommandStart(String command, String[] args) {
        if (!sessionLoggingEnabled) return;
        Map<String, String> metadata = new HashMap<>();
        if (args != null && args.length > 0) {
            metadata.put("args", String.join(" ", args));
            metadata.put("argCount", String.valueOf(args.length));
        }
        log(LogEntryType.COMMAND_START, "CommandRegistry", command, metadata, null);
    }
    
    /**
     * Records what a command printed.
     *
     * <p>The display markers that tell the shell which output to collapse are removed first. They
     * are instructions to one renderer, not part of what the command said, and a log is read by
     * people and by tools that know nothing about them.</p>
     *
     * @param output the text as it was printed
     */
    public void logCommandOutput(String output) {
        if (!sessionLoggingEnabled) return;
        String said = CollapsedOutput.strip(output);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("outputLength", String.valueOf(said != null ? said.length() : 0));
        log(LogEntryType.COMMAND_OUTPUT, "CommandRegistry", said, metadata, null);
    }
    
    public void logCommandEnd(String command, int exitCode, long duration) {
        if (!sessionLoggingEnabled) return;
        Map<String, String> metadata = new HashMap<>();
        metadata.put("exitCode", String.valueOf(exitCode));
        metadata.put("duration", String.valueOf(duration));
        metadata.put("success", String.valueOf(exitCode == 0));
        log(LogEntryType.COMMAND_END, "CommandRegistry", command, metadata, null);
    }
    
    public void logAIRequest(String source, String model, String prompt) {
        if (!sessionLoggingEnabled) return;
        Map<String, String> metadata = new HashMap<>();
        metadata.put("model", model);
        metadata.put("promptLength", String.valueOf(prompt != null ? prompt.length() : 0));
        // Truncate prompt for display but preserve full prompt in structured log
        String truncatedPrompt = prompt != null && prompt.length() > 500 ? 
            prompt.substring(0, 500) + "... [truncated]" : prompt;
        metadata.put("prompt", prompt); // Full prompt in metadata
        log(LogEntryType.AI_REQUEST, source, "AI request sent: " + truncatedPrompt, metadata, null);
    }
    
    public void logLLMRequest(String source, String model, String endpoint, String fullPayload, Map<String, Object> parameters) {
        if (!sessionLoggingEnabled) return;
        // The size is measured before redaction, so it still describes what was actually sent.
        int payloadSize = fullPayload != null ? fullPayload.length() : 0;
        fullPayload = SecretRedactor.redact(fullPayload);

        Map<String, String> metadata = new HashMap<>();
        metadata.put("model", model);
        metadata.put("endpoint", endpoint);
        metadata.put("payloadSize", String.valueOf(payloadSize));
        metadata.put("fullPayload", fullPayload); // Complete request payload
        
        // Add request parameters
        if (parameters != null) {
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                metadata.put("param_" + entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        
        String truncatedPayload = fullPayload != null && fullPayload.length() > 200 ? 
            fullPayload.substring(0, 200) + "... [truncated]" : fullPayload;
        log(LogEntryType.LLM_REQUEST, source, "LLM request to " + endpoint + ": " + truncatedPayload, metadata, null);
    }
    
    public void logLLMResponse(String source, String model, String responseBody, long duration, int statusCode, Map<String, String> responseHeaders) {
        if (!sessionLoggingEnabled) return;
        // Response bodies are logged whatever the status code, and a rejected request is exactly
        // when a provider is most likely to quote the offending key back. The size is measured
        // before redaction so it still describes what actually arrived.
        Map<String, String> metadata = responseMetadata(model, responseBody, duration, statusCode,
                                                        responseHeaders);
        String redactedBody = metadata.get("fullResponse");

        String truncatedResponse = redactedBody != null && redactedBody.length() > 200 ?
            redactedBody.substring(0, 200) + "... [truncated]" : redactedBody;
        log(LogEntryType.LLM_RESPONSE, source, "LLM response (HTTP " + statusCode + "): " + truncatedResponse, metadata, null);
    }

    /**
     * Builds what a response log entry records, with every credential removed.
     *
     * <p>Package-private so the redaction can be asserted directly. It is the only thing standing
     * between a provider's 401 -- the response most likely to quote the offending key back -- and a
     * file on disk that, unlike {@code config.json}, is not owner-restricted.</p>
     *
     * @return the metadata map, safe to write
     */
    static Map<String, String> responseMetadata(String model, String responseBody, long duration,
                                                int statusCode, Map<String, String> responseHeaders) {
        // Measured before redaction so it still describes what actually arrived.
        int responseSize = responseBody != null ? responseBody.length() : 0;

        Map<String, String> metadata = new HashMap<>();
        metadata.put("model", model);
        metadata.put("duration", String.valueOf(duration));
        metadata.put("statusCode", String.valueOf(statusCode));
        metadata.put("responseSize", String.valueOf(responseSize));
        metadata.put("fullResponse", SecretRedactor.redact(responseBody));

        // Headers are redacted more bluntly than the body: a header named authorization or
        // set-cookie IS a credential whatever its value looks like, so there is nothing to
        // recognise and no reason to gamble on a shape that does not match.
        if (responseHeaders != null) {
            for (Map.Entry<String, String> entry : responseHeaders.entrySet()) {
                metadata.put("header_" + entry.getKey().toLowerCase(),
                             SecretRedactor.redactHeader(entry.getKey(), entry.getValue()));
            }
        }
        return metadata;
    }
    
    public void logAIResponse(String source, String response, long duration) {
        if (!sessionLoggingEnabled) return;
        Map<String, String> metadata = new HashMap<>();
        metadata.put("responseLength", String.valueOf(response != null ? response.length() : 0));
        metadata.put("duration", String.valueOf(duration));
        log(LogEntryType.AI_RESPONSE, source, "AI response received", metadata, null);
    }
    
    public void logFileOperation(String operation, String path, boolean success) {
        if (!sessionLoggingEnabled) return;
        Map<String, String> metadata = new HashMap<>();
        metadata.put("path", path);
        metadata.put("success", String.valueOf(success));
        log(LogEntryType.FILE_OPERATION, "FileSystem", operation, metadata, null);
    }
    
    public void logError(String source, String message, Throwable exception) {
        if (!sessionLoggingEnabled) return;
        log(LogEntryType.ERROR, source, message, null, exception);
    }
    
    public void logSessionEvent(String event, String details) {
        if (!sessionLoggingEnabled) return;
        log(LogEntryType.SESSION_EVENT, "SessionManager", event + ": " + details, null, null);
    }
    
    public void logSecurityEvent(String event, String details, boolean allowed) {
        if (!sessionLoggingEnabled) return;
        Map<String, String> metadata = new HashMap<>();
        metadata.put("allowed", String.valueOf(allowed));
        log(LogEntryType.SECURITY_EVENT, "Security", event + ": " + details, metadata, null);
    }
    
    public void logPerformance(String operation, long duration, String details) {
        if (!sessionLoggingEnabled) return;
        Map<String, String> metadata = new HashMap<>();
        metadata.put("duration", String.valueOf(duration));
        if (details != null) {
            metadata.put("details", details);
        }
        log(LogEntryType.PERFORMANCE, "Performance", operation, metadata, null);
    }
    
    private void logSessionStart() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("sessionId", currentSessionId);
        metadata.put("startTime", String.valueOf(sessionStartTime));
        log(LogEntryType.SESSION_EVENT, "SessionLogger", "Session started", metadata, null);
    }
    
    private void logSessionEnd() {
        long endTime = System.currentTimeMillis();
        long duration = endTime - sessionStartTime;
        
        Map<String, String> metadata = new HashMap<>();
        metadata.put("sessionId", currentSessionId);
        metadata.put("endTime", String.valueOf(endTime));
        metadata.put("totalDuration", String.valueOf(duration));
        metadata.put("totalEntries", String.valueOf(entrySequence.get()));
        
        log(LogEntryType.SESSION_EVENT, "SessionLogger", "Session ended", metadata, null);
    }
    
    /**
     * Records an event, or counts it as lost if the writer is too far behind to take it.
     *
     * <p>What is masked, and where, is settled in {@link SessionLogEntry}. This logger is handed
     * every line a command prints and the file it writes is on by default, readable by anyone who
     * can read the home directory, so the rule holds for every caller rather than for the ones
     * somebody audited.</p>
     */
    private void log(LogEntryType type, String source, String message,
                     Map<String, String> metadata, Throwable exception) {
        if (!sessionLoggingEnabled) return;

        try {
            pending.add(SessionLogEntry.of(entrySequence.incrementAndGet(), type, source, message,
                                           metadata, exception));
        } catch (Exception e) {
            System.err.println("Failed to queue session log entry: " + e.getMessage());
        }
    }

    /**
     * Writes down what the queue could not take, where it could not take it.
     *
     * <p>Done by the writer rather than by the thread that lost the entry: the writer is the only
     * one that touches the files, and the record is worth having only where the missing entries
     * would have been. It is written straight out rather than queued, because the queue being full
     * is the whole of what there is to say.</p>
     */
    private void recordWhatWasLost() {
        pending.whatWasLost().ifPresent(loss -> writeLogEntry(
                SessionLogEntry.of(entrySequence.incrementAndGet(), LogEntryType.ERROR,
                                   "SessionLogger", loss, null, null)));
    }
    
    // Public accessors
    public String getCurrentSessionId() {
        return currentSessionId;
    }
    
    public Path getSessionLogFile() {
        return sessionLogFile;
    }
    
    public Path getStructuredLogFile() {
        return structuredLogFile;
    }
    
    public boolean isSessionLoggingEnabled() {
        return sessionLoggingEnabled;
    }
}
