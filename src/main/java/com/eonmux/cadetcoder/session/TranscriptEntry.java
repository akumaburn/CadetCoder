package com.eonmux.cadetcoder.session;

import java.util.ArrayList;
import java.util.List;

/**
 * One saved region of the shell's scrollback: a command and its output, or a section within one.
 *
 * <h2>Why the transcript is persisted separately from the conversation</h2>
 *
 * <p>A session already stored {@code conversationHistory}, a flat list of {@code "User: ..."} and
 * {@code "AI: ..."} strings, and that list was asked to serve two jobs it cannot both do. It is the
 * model's memory, so it must stay clean and token-bounded — and it was also the only thing a resumed
 * session could put back on screen, so everything else was simply lost: what commands ran, what they
 * printed, where one turn ended and the next began. Reopening a session showed a conversation with
 * every action removed from the middle of it.</p>
 *
 * <p>This is the other half, and it is deliberately the RAW lines rather than anything rendered.
 * The shell stores lines raw and renders them at draw time, so keeping them in that form is what
 * makes a restored session look exactly like the one that was closed — at the terminal's current
 * width, in the theme in force now, rather than re-inflated from a snapshot of how it happened to
 * look before.</p>
 *
 * <p>Plain accessors and a no-argument constructor, because this is a Jackson-mapped record of what
 * was on screen and nothing more. Unknown properties are ignored for the same reason
 * {@link SessionState} ignores them: a session file outlives the code that wrote it, and refusing
 * to read one because of a property this build has never heard of costs the whole conversation.</p>
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class TranscriptEntry {

    private String       kind;
    private int          depth;
    private String       title;
    private String       status;
    private List<String> lines;

    public TranscriptEntry() {
    }

    /**
     * @param kind   the segment role, as named by the shell's own segment kinds
     * @param depth  nesting level; {@code 0} for a top-level command or system block
     * @param title  what the region is called
     * @param status the completion state, as named by the shell's own status values
     * @param lines  the raw output lines, in order
     */
    public TranscriptEntry(String kind, int depth, String title, String status, List<String> lines) {
        this.kind   = kind;
        this.depth  = depth;
        this.title  = title;
        this.status = status;
        this.lines  = lines == null ? new ArrayList<>() : new ArrayList<>(lines);
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    /** @return the raw lines; never {@code null} */
    public List<String> getLines() {
        return lines == null ? new ArrayList<>() : lines;
    }

    public void setLines(List<String> lines) {
        this.lines = lines;
    }
}
