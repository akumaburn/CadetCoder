package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.session.TranscriptEntry;
import com.eonmux.cadetcoder.ui.CollapsedOutput;
import com.eonmux.cadetcoder.ui.OutputLineStyler;
import com.eonmux.cadetcoder.ui.ProgramOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An ordered, thread-safe transcript of interactive-shell output, split into discrete
 * <em>segments</em>. Each command the user runs becomes one top-level segment; while a command is
 * producing output, any sub-header line it prints (classified as {@link OutputLineStyler.Kind#SUBHEADER};
 * note {@link OutputLineStyler.Kind#ITERATION} looks the same but deliberately does not open one,
 * e.g. an AI agent's {@code -- Step 2 of 5 --}) automatically opens a nested <em>section</em>
 * segment. This is what lets the TUI scroll an individual result in isolation and tab between an
 * agent's sub-steps.
 *
 * <p>Output text may arrive in arbitrary chunks (with or without trailing newlines); the transcript
 * buffers a trailing partial line and only commits — and classifies — complete lines, mirroring the
 * semantics of {@link ShellOutputBuffer}, which each segment uses for its own bounded line store.</p>
 *
 * <p>All public methods are {@code synchronized}; {@link #snapshot()} returns an immutable,
 * point-in-time copy safe to read from the render thread while command threads append.</p>
 */
public final class ShellTranscript {

    /** The role of a segment, used for styling and navigation. */
    public enum Kind {
        /** Shell-generated content (welcome banner, notices, history dumps). */
        SYSTEM,
        /** One user command and its captured output. */
        COMMAND,
        /** A sub-section opened by a sub-header within a command (e.g. an agent step). */
        SECTION
    }

    /** Completion state of a segment, surfaced as a status marker in focus mode. */
    public enum Status {
        NONE, RUNNING, OK, ERROR
    }

    private final int maxSegments;
    private final int perSegmentLines;

    private final List<Segment> segments = new ArrayList<>();
    private Segment activeTop;   // current top-level (COMMAND/SYSTEM) segment
    private Segment activeSink;  // where committed lines are routed (a section, or the top segment)

    /**
     * Whether the lines arriving now are a command's hidden output.
     *
     * <p>Held here rather than read back off the buffer because a run can be long and the answer is
     * needed for every line. Cleared whenever a new top-level segment opens: a run whose closing
     * marker never arrived must not swallow the sections of the command after it.</p>
     */
    private boolean insideHiddenOutput;

    /**
     * Whether the lines arriving now are what a program printed.
     *
     * <p>Held and cleared like {@link #insideHiddenOutput}, and for the same reason.</p>
     */
    private boolean insideProgramOutput;
    private String  pending = "";
    private int     nextId  = 0;

    /**
     * The id of the oldest segment the console shows; every older one was cleared from view.
     *
     * <p>A mark rather than a removal, because the console is not the only reader. The scrollback
     * saved with the session is read from here too, and a resumed session replays it: removing the
     * segments to clear the screen also removed everything before the clear from the saved
     * session. {@link #persistable} ignores the mark.</p>
     */
    private int shownFrom = 0;

    public ShellTranscript(int maxSegments, int perSegmentLines) {
        this.maxSegments     = Math.max(1, maxSegments);
        this.perSegmentLines = Math.max(1, perSegmentLines);
    }

    /**
     * Begin a new top-level segment, making it the active sink for subsequent output.
     *
     * @return the created segment (its mutable {@link Segment} handle, e.g. to set status later)
     */
    public synchronized Segment beginSegment(Kind kind, String title) {
        return beginSegment(kind, title, false);
    }

    /**
     * Begin a segment the shell writes afresh every time it starts.
     *
     * <p>The welcome banner and the "session resumes here" marker are printed on every start and
     * were also saved with the scrollback, so each restore stacked another copy on the ones already
     * there: after three resumes the top of the transcript was three welcome banners deep. They are
     * not a record of anything that happened — they are the shell introducing itself — so they are
     * shown and then not carried across.</p>
     *
     * @return the created segment
     */
    public synchronized Segment beginEphemeralSegment(Kind kind, String title) {
        return beginSegment(kind, title, true);
    }

    private synchronized Segment beginSegment(Kind kind, String title, boolean ephemeral) {
        flushPendingToSink();
        insideHiddenOutput = false;
        insideProgramOutput = false;
        Segment s = new Segment(nextId++, kind, 0, title == null ? "" : title, perSegmentLines,
                                ephemeral);
        segments.add(s);
        activeTop  = s;
        activeSink = s;
        trim();
        return s;
    }

    /**
     * Where lines are being filed right now: the top-level region and the sink inside it.
     *
     * <p>Both, because they are not always the same segment -- a command with sub-sections open
     * routes to the newest section while the command remains the region -- and putting only one of
     * them back would leave the transcript describing a shape it is not in.</p>
     *
     * @param top  the top-level segment, or {@code null} if nothing is open
     * @param sink the segment lines are appended to, or {@code null}
     */
    public record Where(Segment top, Segment sink) { }

    /**
     * Where lines are being filed right now, so a caller can put it back afterwards.
     *
     * @return the current routing
     */
    public synchronized Where activeWhere() {
        return new Where(activeTop, activeSink);
    }

    /**
     * Routes lines where they were going before, after something wrote elsewhere in between.
     *
     * <p>{@link #beginSegment} is one-way: it makes the new segment the sink and there was no way
     * back. Anything the shell prints of its own accord WHILE a command is running -- the history
     * dump F3 produces is the one that does -- therefore captured the rest of that command's output
     * permanently.</p>
     *
     * @param where what {@link #activeWhere()} returned; segments no longer retained, or cleared
     *              from view, are ignored
     */
    public synchronized void resumeWhere(Where where) {
        if (where == null || where.sink() == null || !segments.contains(where.sink())
            || where.sink().id < shownFrom) {
            // Also a segment cleared from view: output sent back there would never be seen.
            return;
        }
        flushPendingToSink();
        activeSink = where.sink();
        activeTop  = segments.contains(where.top()) ? where.top() : where.sink();
    }

    /** Append raw text (possibly multi-line and/or partial) to the active segment. */
    public synchronized void append(String text) {
        if (text == null) {
            return;
        }
        String combined = pending + text;
        pending = "";
        int start = 0;
        for (int i = 0; i < combined.length(); i++) {
            if (combined.charAt(i) == '\n') {
                routeCompleteLine(combined.substring(start, i));
                start = i + 1;
            }
        }
        pending = combined.substring(start);
    }

    /** Append a single complete line. */
    public synchronized void appendLine(String line) {
        flushPendingToSink();
        routeCompleteLine(line == null ? "" : line);
    }

    /** Remove every segment and reset, keeping the monotonic id counter. */
    public synchronized void clear() {
        segments.clear();
        activeTop  = null;
        activeSink = null;
        pending    = "";
    }

    /**
     * Hides everything so far from the console, and keeps it for the saved session.
     *
     * <p>A partial line is committed first, into the segment it belongs to, so it is kept rather
     * than carried into whatever is printed next. Output after this opens a region of its own.</p>
     */
    public synchronized void clearView() {
        flushPendingToSink();
        shownFrom  = nextId;
        activeTop  = null;
        activeSink = null;
    }

    /** Number of segments the console shows. */
    public synchronized int segmentCount() {
        return shown().size();
    }

    /** Ordered ids of the segments the console shows (cheap; for focus navigation). */
    public synchronized List<Integer> segmentIds() {
        List<Integer> ids = new ArrayList<>();
        for (Segment s : shown()) {
            ids.add(s.id);
        }
        return ids;
    }

    /** The segments not cleared from view, oldest first. */
    private List<Segment> shown() {
        List<Segment> out = new ArrayList<>(segments.size());
        for (Segment s : segments) {
            if (s.id >= shownFrom) {
                out.add(s);
            }
        }
        return out;
    }

    /** Title of the segment with the given id, or {@code ""} if it is no longer retained. */
    public synchronized String titleOf(int id) {
        for (Segment s : segments) {
            if (s.id == id) {
                return s.title;
            }
        }
        return "";
    }

    /**
     * Title of the section currently receiving output within {@code owner}, or {@code ""}.
     *
     * <p>This is "what is happening right now": every sub-header a command prints opens a section
     * titled with that text, and output keeps landing there until the next sub-header arrives. The
     * commands already publish their activity in the right order relative to the work, so nothing
     * new has to be threaded through them.</p>
     *
     * <p>Scoped to {@code owner} deliberately. Output capture is global, so an interrupted or nested
     * command could otherwise leave its last section as the answer and paint a stale activity over
     * whatever is running now.</p>
     *
     * @param owner the segment the caller believes is running
     * @return the active section's title, or {@code ""} when {@code owner} is not the active segment
     *         or has not opened a section
     */
    public synchronized String activeSectionTitleFor(Segment owner) {
        if (owner == null || activeTop != owner || activeSink == null || activeSink == activeTop) {
            return "";
        }
        return activeSink.title;
    }

    /** Committed line count across the segments shown (excludes the uncommitted partial). */
    public synchronized int totalLines() {
        int n = 0;
        for (Segment s : shown()) {
            n += s.buffer.size();
        }
        return n;
    }

    /**
     * Immutable, point-in-time view of what the console shows. The active segment's uncommitted
     * partial line (if any) is included as its trailing line so streaming output is visible.
     */
    public synchronized List<Snapshot> snapshot() {
        List<Snapshot> out = new ArrayList<>(segments.size());
        for (Segment s : shown()) {
            List<String> lines = s.buffer.snapshot();
            if (s == activeSink && !pending.isEmpty()) {
                lines = new ArrayList<>(lines);
                lines.add(pending);
            }
            out.add(new Snapshot(s.id, s.kind, s.depth, s.title, s.status,
                    Collections.unmodifiableList(lines)));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * The most recent part of the scrollback, in a form that can be saved.
     *
     * <p>Bounded from the END, because recency is what a restored session is for and because the
     * unbounded form is far too large to write on every turn: this store holds up to
     * {@code maxSegments} regions of {@code perSegmentLines} lines each, and a session is saved
     * after every exchange. Walking backwards and stopping at a line budget keeps the cost
     * proportional to what is kept rather than to what is held.</p>
     *
     * <p>Regions the shell reprints at every start are left out; see
     * {@link #beginEphemeralSegment(Kind, String)}.</p>
     *
     * @param maxSegments how many regions to keep at most, newest first
     * @param maxLines    the total line budget across those regions
     * @return the regions to persist, oldest first
     */
    public synchronized List<TranscriptEntry> persistable(int maxSegments, int maxLines) {
        List<TranscriptEntry> kept = new ArrayList<>();
        int lineBudget = Math.max(0, maxLines);
        for (int i = segments.size() - 1; i >= 0 && kept.size() < maxSegments && lineBudget > 0; i--) {
            Segment s = segments.get(i);
            if (s.ephemeral) {
                continue; // reprinted on every start; see beginEphemeralSegment
            }
            List<String> lines = s.buffer.snapshot();
            if (lines.size() > lineBudget) {
                // A single region larger than what is left keeps its TAIL: the end of a command's
                // output is the part that says how it went.
                lines = new ArrayList<>(lines.subList(lines.size() - lineBudget, lines.size()));
            }
            lineBudget -= lines.size();
            kept.add(new TranscriptEntry(s.kind.name(), s.depth, s.title, s.status.name(), lines));
        }
        Collections.reverse(kept);
        return kept;
    }

    /**
     * Replaces the scrollback with a saved one.
     *
     * <p>Unknown kinds and statuses fall back to neutral values rather than failing the restore: a
     * session file outlives the code that wrote it, and losing a whole conversation because one
     * segment names a role this version dropped would be the wrong trade.</p>
     *
     * @param entries the saved regions, oldest first; {@code null} or empty clears nothing
     */
    public synchronized void restore(List<TranscriptEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        for (TranscriptEntry entry : entries) {
            Segment s = new Segment(nextId++, kindOf(entry.getKind()), Math.max(0, entry.getDepth()),
                                    entry.getTitle() == null ? "" : entry.getTitle(), perSegmentLines);
            s.status = statusOf(entry.getStatus());
            for (String line : entry.getLines()) {
                s.buffer.appendLine(line == null ? "" : line);
            }
            segments.add(s);
            // A section belongs UNDER a command, so restoring one must not make it the top segment:
            // routeCompleteLine only opens a new section while the top is a COMMAND, so a restored
            // session whose newest region happened to be a section could not open another one, and
            // the rest of that session's sub-headers landed as ordinary body text.
            if (s.kind != Kind.SECTION || activeTop == null) {
                activeTop = s;
            }
            activeSink = s;
        }
        trim();
    }

    private static Kind kindOf(String name) {
        for (Kind k : Kind.values()) {
            if (k.name().equals(name)) {
                return k;
            }
        }
        return Kind.SYSTEM;
    }

    private static Status statusOf(String name) {
        for (Status v : Status.values()) {
            if (v.name().equals(name)) {
                return v;
            }
        }
        return Status.NONE;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * Files one committed line, opening a section for a sub-header that is not inside hidden output.
     *
     * <h2>Why the collapsed run is excluded</h2>
     *
     * <p>A collapsed run is one command's output, marked so the live console can leave it out and a
     * focused result can show it. The text inside it is whatever the command printed, and a command
     * prints sub-headers of its own: {@code multiread} heads every file it read with one. Each of
     * those opened a section, which took the rest of the output out of the segment that holds the
     * opening marker -- so the body went on screen in full while the marker sat in the segment
     * behind it with nothing left to hide. Hidden output belongs to the result that produced it,
     * whatever shape the text is.</p>
     *
     * <p>Program output still opens sections, because a step's output holds the tool's own
     * sub-headers. The run of program output then goes on in the new section, so that section
     * begins with an opening marker of its own.</p>
     */
    private void routeCompleteLine(String line) {
        ensureActive();
        if (CollapsedOutput.opens(line)) {
            insideHiddenOutput = true;
        } else if (CollapsedOutput.closes(line)) {
            insideHiddenOutput = false;
        } else if (ProgramOutput.opens(line)) {
            insideProgramOutput = true;
        } else if (ProgramOutput.closes(line)) {
            insideProgramOutput = false;
        }
        if (!insideHiddenOutput && activeTop.kind == Kind.COMMAND
                && OutputLineStyler.classify(line) == OutputLineStyler.Kind.SUBHEADER) {
            Segment section = new Segment(nextId++, Kind.SECTION, 1, cleanSubheader(line), perSegmentLines);
            segments.add(section);
            activeSink = section;
            trim();
            if (insideProgramOutput) {
                section.buffer.appendLine(ProgramOutput.OPEN);
            }
        }
        activeSink.buffer.appendLine(line);
    }

    private void ensureActive() {
        if (activeTop == null) {
            Segment s = new Segment(nextId++, Kind.SYSTEM, 0, "", perSegmentLines);
            segments.add(s);
            activeTop  = s;
            activeSink = s;
            trim();
        }
    }

    /** Commit any buffered partial line as a real line in its sink (used before a hard boundary). */
    private void flushPendingToSink() {
        if (!pending.isEmpty()) {
            ensureActive();
            activeSink.buffer.appendLine(pending);
            pending = "";
        }
    }

    private void trim() {
        // Never drop the active segment; only retire old, completed ones.
        while (segments.size() > maxSegments) {
            Segment oldest = segments.get(0);
            if (oldest == activeTop || oldest == activeSink) {
                break;
            }
            segments.remove(0);
        }
    }

    /**
     * Derives a section title from the sub-header line that opened it, by stripping the marker.
     *
     * <p>Both marker vocabularies are handled, matching {@link OutputLineStyler#classify}: the
     * single-glyph Unicode form ({@code ▸ Thinking}) and the bracketed ASCII fallback
     * ({@code -- Thinking --}). Leaving a delimiter in the title would carry it into the focus-mode
     * console title and the status bar, which both render this string verbatim.</p>
     */
    private static String cleanSubheader(String line) {
        String t = line.strip();
        if (t.startsWith(OutputLineStyler.SUBHEADER_GLYPH)) {
            t = t.substring(OutputLineStyler.SUBHEADER_GLYPH.length()).strip();
        } else if (t.startsWith(OutputLineStyler.ITERATION_GLYPH)) {
            t = t.substring(OutputLineStyler.ITERATION_GLYPH.length()).strip();
        } else if (t.startsWith(OutputLineStyler.ITERATION_GLYPH_ASCII)) {
            t = t.substring(OutputLineStyler.ITERATION_GLYPH_ASCII.length()).strip();
        } else if (t.startsWith("--") && t.endsWith("--") && t.length() >= 4) {
            t = t.substring(2, t.length() - 2).strip();
        }
        return t.isEmpty() ? "section" : t;
    }

    /** Mutable per-segment store. Exposed only as an opaque handle for status updates. */
    public static final class Segment {
        private final int               id;
        private final Kind              kind;
        private final int               depth;
        private final String            title;
        private final ShellOutputBuffer buffer;
        private final boolean           ephemeral;
        private volatile Status         status = Status.NONE;

        private Segment(int id, Kind kind, int depth, String title, int maxLines) {
            this(id, kind, depth, title, maxLines, false);
        }

        private Segment(int id, Kind kind, int depth, String title, int maxLines,
                        boolean ephemeral) {
            this.id        = id;
            this.kind      = kind;
            this.depth     = depth;
            this.title     = title;
            this.buffer    = new ShellOutputBuffer(maxLines);
            this.ephemeral = ephemeral;
        }

        public int id() {
            return id;
        }

        public void setStatus(Status status) {
            this.status = status == null ? Status.NONE : status;
        }

        public Status status() {
            return status;
        }
    }

    /** Immutable render-time view of a single segment. */
    public static final class Snapshot {
        private final int          id;
        private final Kind         kind;
        private final int          depth;
        private final String       title;
        private final Status       status;
        private final List<String> lines;

        Snapshot(int id, Kind kind, int depth, String title, Status status, List<String> lines) {
            this.id     = id;
            this.kind   = kind;
            this.depth  = depth;
            this.title  = title;
            this.status = status;
            this.lines  = lines;
        }

        public int id() {
            return id;
        }

        public Kind kind() {
            return kind;
        }

        public int depth() {
            return depth;
        }

        public String title() {
            return title;
        }

        public Status status() {
            return status;
        }

        public List<String> lines() {
            return lines;
        }
    }
}
