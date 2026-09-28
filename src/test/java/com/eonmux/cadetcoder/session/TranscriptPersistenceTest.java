package com.eonmux.cadetcoder.session;

import com.eonmux.cadetcoder.commands.ShellTranscript;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A saved session has to bring the scrollback back as it was.
 *
 * <p>Only {@code conversationHistory} was persisted — flat {@code "User: ..."} / {@code "AI: ..."}
 * strings, which is the model's memory and was never the screen. Reopening a session therefore
 * showed the conversation with every command and its output cut out of the middle, and none of the
 * structure that made it navigable.</p>
 */
class TranscriptPersistenceTest {

    private static ShellTranscript populated() {
        ShellTranscript t = new ShellTranscript(500, 4000);
        t.beginSegment(ShellTranscript.Kind.COMMAND, "chat explain sessions");
        t.append("Sessions live under ~/.cadet.\n");
        t.append("▸ read src/Session.java\n");   // opens a SECTION
        t.append("  1  package com.example;\n");
        t.beginSegment(ShellTranscript.Kind.COMMAND, "ls src");
        t.append("Session.java\n");
        return t;
    }

    @Test
    @DisplayName("A restored transcript holds the same regions and lines as the saved one")
    void aRoundTripKeepsTheScrollback() {
        List<TranscriptEntry> saved = populated().persistable(40, 2_000);

        ShellTranscript restored = new ShellTranscript(500, 4000);
        restored.restore(saved);

        List<ShellTranscript.Snapshot> before = populated().snapshot();
        List<ShellTranscript.Snapshot> after  = restored.snapshot();

        assertThat(after).hasSameSizeAs(before);
        for (int i = 0; i < before.size(); i++) {
            assertThat(after.get(i).kind()).isEqualTo(before.get(i).kind());
            assertThat(after.get(i).title()).isEqualTo(before.get(i).title());
            assertThat(after.get(i).depth()).isEqualTo(before.get(i).depth());
            assertThat(after.get(i).lines()).isEqualTo(before.get(i).lines());
        }
    }

    @Test
    @DisplayName("Lines come back RAW, so they render at the current width and theme")
    void linesAreKeptUnrendered() {
        List<TranscriptEntry> saved = populated().persistable(40, 2_000);

        // Storing anything pre-rendered would freeze a restored session at the width and theme of
        // the one that wrote it. The markers are the shell's own, kept verbatim.
        assertThat(saved).anySatisfy(entry ->
                assertThat(entry.getLines()).contains("▸ read src/Session.java"));
        assertThat(saved).anySatisfy(entry ->
                assertThat(entry.getLines()).contains("  1  package com.example;"));
    }

    @Test
    @DisplayName("Command output is kept, which the conversation history never held")
    void commandOutputSurvives() {
        List<TranscriptEntry> saved = populated().persistable(40, 2_000);

        String everything = saved.stream()
                .flatMap(e -> e.getLines().stream())
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(everything).contains("Session.java");
        assertThat(everything).contains("package com.example;");
    }

    @Test
    @DisplayName("It survives the JSON round trip the session file actually does")
    void itSerialisesThroughJackson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        SessionState state = new SessionState();
        state.setTranscript(populated().persistable(40, 2_000));
        state.setConversationHistory(List.of("User: hi", "AI: hello"));

        SessionState back = mapper.readValue(mapper.writeValueAsString(state), SessionState.class);

        assertThat(back.getTranscript()).hasSameSizeAs(state.getTranscript());
        assertThat(back.getTranscript().get(0).getTitle()).isEqualTo("chat explain sessions");
        // The model's memory is still its own thing, untouched by the display copy.
        assertThat(back.getConversationHistory()).containsExactly("User: hi", "AI: hello");
    }

    @Test
    @DisplayName("A session saved before transcripts existed loads without one, not with a crash")
    void anOlderSessionFileStillLoads() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String legacy = """
                {"sessionId":"s-1","createdAt":1,"lastModified":2,
                 "openFiles":[],"cursorPositions":{},
                 "conversationHistory":["User: hi","AI: hello"],"todoList":[]}""";

        SessionState state = mapper.readValue(legacy, SessionState.class);

        assertThat(state.getTranscript()).isEmpty();
        assertThat(state.getConversationHistory()).containsExactly("User: hi", "AI: hello");
    }

    @Test
    @DisplayName("A saved region carrying a property this build dropped still reads, on any mapper")
    void aRegionWithAnUnknownPropertyStillReads() throws Exception {
        // Deliberately a plain ObjectMapper rather than SessionManager's: the promise that a session
        // outlives the code that wrote it has to belong to the TYPE, or it holds only for whichever
        // reader happens to have been configured for it.
        TranscriptEntry back = new ObjectMapper().readValue("""
                {"kind":"COMMAND","depth":0,"title":"ls src","status":"OK",
                 "lines":["Main.java"],"aPropertyFromAnotherBuild":123}""",
                TranscriptEntry.class);

        assertThat(back.getTitle()).isEqualTo("ls src");
        assertThat(back.getLines()).containsExactly("Main.java");
    }

    @Test
    @DisplayName("What is persisted is bounded, and keeps the NEWEST regions")
    void persistenceIsBoundedToTheMostRecent() {
        ShellTranscript t = new ShellTranscript(500, 4000);
        for (int i = 1; i <= 100; i++) {
            t.beginSegment(ShellTranscript.Kind.COMMAND, "command " + i);
            t.append("output " + i + "\n");
        }

        List<TranscriptEntry> saved = t.persistable(10, 2_000);

        assertThat(saved).hasSize(10);
        assertThat(saved.get(saved.size() - 1).getTitle()).isEqualTo("command 100");
        assertThat(saved.get(0).getTitle()).isEqualTo("command 91");
    }

    @Test
    @DisplayName("A line budget smaller than one region keeps that region's tail")
    void anOversizedRegionKeepsItsEnd() {
        ShellTranscript t = new ShellTranscript(500, 4000);
        t.beginSegment(ShellTranscript.Kind.COMMAND, "big");
        for (int i = 1; i <= 50; i++) {
            t.append("line " + i + "\n");
        }

        List<TranscriptEntry> saved = t.persistable(40, 5);

        // The end of a command's output is the part that says how it went.
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getLines()).containsExactly(
                "line 46", "line 47", "line 48", "line 49", "line 50");
    }

    @Test
    @DisplayName("An unknown kind or status restores as neutral rather than failing the whole session")
    void unknownValuesDegradeGracefully() {
        ShellTranscript restored = new ShellTranscript(500, 4000);
        restored.restore(List.of(new TranscriptEntry(
                "SOMETHING_THIS_VERSION_DROPPED", 0, "old", "MYSTERY", List.of("still here"))));

        List<ShellTranscript.Snapshot> snap = restored.snapshot();
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).kind()).isEqualTo(ShellTranscript.Kind.SYSTEM);
        assertThat(snap.get(0).status()).isEqualTo(ShellTranscript.Status.NONE);
        assertThat(snap.get(0).lines()).containsExactly("still here");
    }

    @Test
    @DisplayName("The shell's own banner is shown but not carried across a restart")
    void chromeThatIsReprintedEveryStartIsNotPersisted() {
        // The welcome banner and the "session resumes here" marker are written afresh at every
        // start AND were saved with the scrollback, so each resume stacked another copy on the ones
        // already there: three resumes in, the top of the transcript was three welcome banners.
        ShellTranscript t = new ShellTranscript(500, 4000);
        t.beginEphemeralSegment(ShellTranscript.Kind.SYSTEM, "Welcome");
        t.append("CadetCoder v1.0\n");
        t.beginSegment(ShellTranscript.Kind.COMMAND, "ls src");
        t.append("Session.java\n");
        t.beginEphemeralSegment(ShellTranscript.Kind.SYSTEM, "Resumed");
        t.append("Session resumes here.\n");

        // Still on screen: it is the shell introducing itself, and that is worth seeing.
        assertThat(t.snapshot()).extracting(ShellTranscript.Snapshot::title)
                                .containsExactly("Welcome", "ls src", "Resumed");

        assertThat(t.persistable(40, 2_000))
                .extracting(TranscriptEntry::getTitle)
                .containsExactly("ls src");
    }

    @Test
    @DisplayName("A restored section stays under a command rather than becoming one")
    void restoringASectionDoesNotMakeItTheTopSegment() {
        // Sections are opened by the sub-headers a command prints, and only ever while the top
        // segment is a COMMAND. Restoring one as the top segment meant the rest of that session's
        // sub-headers could not open sections at all -- they landed as ordinary body text, and the
        // status line had nothing to report as the current activity.
        ShellTranscript saved = new ShellTranscript(500, 4000);
        saved.beginSegment(ShellTranscript.Kind.COMMAND, "chat review this");
        saved.append("▸ Reading\n");
        saved.append("  src/Main.java\n");

        ShellTranscript restored = new ShellTranscript(500, 4000);
        restored.restore(saved.persistable(40, 2_000));

        // The newest restored region is the section; a further sub-header must still open one.
        restored.append("▸ Writing\n");
        restored.append("  src/Main.java\n");

        assertThat(restored.snapshot()).extracting(ShellTranscript.Snapshot::title)
                                       .containsExactly("chat review this", "Reading", "Writing");
    }

    @Test
    @DisplayName("Restoring nothing changes nothing")
    void restoringNothingIsANoOp() {
        ShellTranscript t = new ShellTranscript(500, 4000);
        t.beginSegment(ShellTranscript.Kind.COMMAND, "kept");
        t.append("line\n");

        t.restore(null);
        t.restore(List.of());

        assertThat(t.snapshot()).hasSize(1);
        assertThat(t.snapshot().get(0).title()).isEqualTo("kept");
    }

    @Test
    @DisplayName("The session file is written whole, and only its owner can read it")
    void theSessionFileIsWrittenSafely() throws Exception {
        // Both properties matter more now that this file carries the scrollback: it is bigger, so
        // the truncate-and-fill window is wider, and it holds command output and file contents
        // rather than only a list of turns.
        java.nio.file.Path home = java.nio.file.Files.createTempDirectory("session-write");
        String originalHome    = System.getProperty("user.home");
        String originalBaseDir = com.eonmux.cadetcoder.config.Configuration.defaultBaseDir;
        java.lang.reflect.Field managerInstance =
                SessionManager.class.getDeclaredField("instance");
        java.lang.reflect.Field configInstance =
                com.eonmux.cadetcoder.config.ConfigManager.class.getDeclaredField("instance");
        managerInstance.setAccessible(true);
        configInstance.setAccessible(true);
        Object savedManager = managerInstance.get(null);

        try {
            System.setProperty("user.home", home.toString());
            com.eonmux.cadetcoder.config.Configuration.defaultBaseDir =
                    home.resolve(".cadet").toString();
            configInstance.set(null, null);
            managerInstance.set(null, null);

            SessionManager manager = SessionManager.getInstance();
            manager.addToConversationHistory("User: hello");
            manager.saveSession();

            java.nio.file.Path file = home.resolve(".cadet").resolve("session.json");
            assertThat(file).exists();

            // Whole: it parses, which a truncated write would not.
            SessionState reloaded = new ObjectMapper().readValue(file.toFile(), SessionState.class);
            assertThat(reloaded.getConversationHistory()).contains("User: hello");

            if (java.nio.file.Files.getFileStore(file).supportsFileAttributeView(
                    java.nio.file.attribute.PosixFileAttributeView.class)) {
                assertThat(java.nio.file.attribute.PosixFilePermissions.toString(
                        java.nio.file.Files.getPosixFilePermissions(file)))
                        .isEqualTo("rw-------");
            }

            // No temporary left behind on the happy path.
            try (java.util.stream.Stream<java.nio.file.Path> entries =
                         java.nio.file.Files.list(file.getParent())) {
                assertThat(entries.map(java.nio.file.Path::getFileName)
                                  .map(Object::toString)
                                  .filter(name -> name.endsWith(".tmp")))
                        .isEmpty();
            }
        } finally {
            managerInstance.set(null, savedManager);
            configInstance.set(null, null);
            com.eonmux.cadetcoder.config.Configuration.defaultBaseDir = originalBaseDir;
            if (originalHome != null) {
                System.setProperty("user.home", originalHome);
            }
        }
    }
}
