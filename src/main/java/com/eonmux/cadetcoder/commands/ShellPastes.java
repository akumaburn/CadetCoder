package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.PromptImage;
import com.eonmux.cadetcoder.logging.CadetLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a paste too big to read at the prompt stands in for, and how it comes back.
 *
 * <h2>Why a paste is not always typed in</h2>
 *
 * <p>A pasted block arrived as text, with its newlines turned into spaces so it would still be one
 * command. Forty lines of a stack trace became one line of four thousand characters: the field
 * scrolls to keep the caret visible, so what was on screen was the tail of the paste and nothing
 * else -- no prompt, no question, and no way to see what had been pasted without walking back
 * through it. Anything typed after it was lost in the same line.</p>
 *
 * <p>So a large paste is kept here and a short marker goes into the line instead. The marker is
 * ordinary text: it can be typed around, moved past a word at a time, and deleted like any other
 * word. What is sent is the line with each marker put back to the text it stands for.</p>
 *
 * <h2>Why a dropped file is the same thing</h2>
 *
 * <p>Dropping a file on a terminal pastes its path, usually quoted and with a trailing space. That
 * is a paste whose useful form is shorter than what arrives, which is what this does -- the marker
 * names the file and stands for the path, so a dropped file reads as its name and is sent as
 * something a command can open.</p>
 *
 * <h2>What a dropped image also does</h2>
 *
 * <p>A dropped file whose bytes are a picture is read as well as named. Its marker reads
 * {@code [#1: image shot.png]}, it still stands for the path, and the picture itself is attached to
 * the turn so the model can look at it. The marker says "image" precisely because those are two
 * different things: a model that cannot take one is told about the file and not the picture, and
 * the line should say which was offered. See {@code ai/PromptAttachments} for where the picture
 * waits, and {@code ai/ImageChannel} for what happens when the model cannot read it.</p>
 */
final class ShellPastes {

    /**
     * The longest paste still inserted as typed text.
     *
     * <p>Chosen so that a pasted path, URL or command line -- the pastes someone wants to see and
     * edit in place -- goes in as text, and a block of output does not.</p>
     */
    static final int MAX_INLINE_CHARS = 200;

    /** How many stand-ins are remembered; the oldest goes first. */
    private static final int MAX_KEPT = 32;

    /**
     * How many bytes of picture are held at once, across every marker.
     *
     * <h2>Why the pictures are counted in bytes and the markers in markers</h2>
     *
     * <p>What a text marker stands for is a line or two; thirty-two of them cost nothing. A picture
     * is up to {@link PromptImage#MAX_BYTES}, and it is held as base64, which is a third larger
     * again -- so the same count of thirty-two allowed roughly two hundred megabytes of screenshots
     * to sit in the session for as long as it ran, whether or not any marker was still in the line.
     * Pictures are therefore capped by what they weigh, counted as the size of each image, and the
     * oldest is dropped first. Its marker
     * stays and still stands for the file's path, so the line it is in is unchanged and the model
     * can still be asked to read the file.</p>
     */
    private static final long MAX_PICTURE_BYTES = 32L * 1024 * 1024;

    private static final CadetLogger LOG = CadetLogger.getLogger(ShellPastes.class);

    /** Marker text to what it stands for, oldest first. */
    private final LinkedHashMap<String, String> kept = new LinkedHashMap<>();

    /** Marker text to the picture it also carries, for the dropped files that are images. */
    private final Map<String, PromptImage> pictures = new LinkedHashMap<>();

    /** How many markers this session has issued, which is what numbers them. */
    private int issued;

    /**
     * Whether a paste goes in as a marker rather than as text.
     *
     * @param pasted what arrived
     * @return whether it is more than one line, or longer than {@link #MAX_INLINE_CHARS}
     */
    static boolean standsInFor(String pasted) {
        if (pasted == null || pasted.isEmpty()) {
            return false;
        }
        return pasted.indexOf('\n') >= 0 || pasted.indexOf('\r') >= 0
               || pasted.length() > MAX_INLINE_CHARS;
    }

    /**
     * The file a paste names, when it names one.
     *
     * <p>A terminal quotes a dropped path when it contains a space and adds a trailing space
     * either way, so both are taken off before the path is resolved.</p>
     *
     * @param pasted what arrived
     * @return the file, or {@code null} when the paste is not a path to one that exists
     */
    static Path droppedFile(String pasted) {
        if (pasted == null) {
            return null;
        }
        String text = pasted.strip();
        if (text.isEmpty() || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return null;
        }
        text = unquote(text, '\'');
        text = unquote(text, '"');
        if (text.isEmpty()) {
            return null;
        }
        try {
            Path path = Path.of(text);
            return Files.isRegularFile(path) ? path.toAbsolutePath() : null;
        } catch (InvalidPathException notAPath) {
            return null;
        }
    }

    /**
     * What a paste puts into the line.
     *
     * <p>A dropped file and a block too big to read at the prompt go in as a marker standing for
     * the path or the text. Everything else goes in as it arrived: what is left is one short line,
     * a path, a URL or a command line, and those are worth having in the line where they can be
     * edited. Nothing is flattened on the way in any more -- a paste with a line break in it is
     * one of the two cases above, and what it stands for keeps its line breaks until it is
     * sent.</p>
     *
     * @param pasted what the terminal delivered
     * @return the text to insert at the caret
     */
    String insertionFor(String pasted) {
        if (pasted == null || pasted.isEmpty()) {
            return "";
        }
        Path dropped = droppedFile(pasted);
        if (dropped != null) {
            return keepFile(dropped);
        }
        return standsInFor(pasted) ? keep(pasted) : pasted;
    }

    /**
     * Keeps a pasted block and returns the marker that stands for it.
     *
     * @param pasted what arrived
     * @return the marker to put in the line
     */
    String keep(String pasted) {
        String text   = pasted == null ? "" : pasted;
        String marker = "[#" + (++issued) + ": " + describe(text) + "]";
        remember(marker, text);
        return marker;
    }

    /**
     * Keeps a dropped file and returns the marker that stands for its path.
     *
     * @param file the file, already resolved
     * @return the marker to put in the line
     */
    String keepFile(Path file) {
        Path        name    = file.getFileName();
        PromptImage picture = pictureIn(file);
        String      label   = (picture == null ? "" : "image ") + (name == null ? "file" : name);
        String      marker  = "[#" + (++issued) + ": " + label + "]";
        remember(marker, quoteIfNeeded(file.toString()));
        if (picture != null) {
            pictures.put(marker, picture);
            dropPicturesPastTheBudget();
        }
        return marker;
    }

    /**
     * The picture a dropped file holds, when it holds one this tool can send.
     *
     * <p>A file that is not an image, is too large for the providers, or cannot be read is not an
     * error here: its marker still stands for the path, which is something a command can open and
     * the model can ask for. Only the picture is missing, and saying so once in the log is enough --
     * a warning at the prompt for every dropped {@code .zip} would be noise.</p>
     *
     * @param file the dropped file
     * @return the picture, or {@code null} when there is none to send
     */
    private static PromptImage pictureIn(Path file) {
        try {
            return PromptImage.of(file);
        } catch (IllegalArgumentException notSendable) {
            LOG.debug("Not attaching " + file.getFileName() + " as an image: "
                            + notSendable.getMessage());
            return null;
        } catch (IOException unreadable) {
            LOG.debug("Could not read " + file + " to attach it: " + unreadable.getMessage());
            return null;
        }
    }

    /**
     * The pictures a line still asks for.
     *
     * <p>Read from the line rather than from what was dropped, because a marker can be deleted
     * before the line is sent. Dropping a screenshot, changing your mind, and deleting the marker
     * has to stop the picture being sent too -- it is the only way back out.</p>
     *
     * @param line the line as typed
     * @return the pictures whose markers are still in it, in the order they were dropped
     */
    List<PromptImage> picturesIn(String line) {
        List<PromptImage> found = new ArrayList<>();
        if (line == null || line.isEmpty() || pictures.isEmpty()) {
            return found;
        }
        for (Map.Entry<String, PromptImage> entry : pictures.entrySet()) {
            if (line.contains(entry.getKey())) {
                found.add(entry.getValue());
            }
        }
        return found;
    }

    /**
     * Puts back what each marker in a line stands for.
     *
     * <p>Only markers this issued are substituted, by the exact text it issued. A marker whose
     * paste has since been forgotten, or one somebody typed out by hand, is left as it is: a line
     * is sent as it reads unless this knows otherwise.</p>
     *
     * @param line the line as typed
     * @return the line to run
     */
    String expand(String line) {
        if (line == null || line.isEmpty() || kept.isEmpty()) {
            return line;
        }
        String out = line;
        for (Map.Entry<String, String> entry : kept.entrySet()) {
            if (out.contains(entry.getKey())) {
                out = out.replace(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    /** Whether {@code line} holds a marker this issued. */
    boolean holdsMarker(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        for (String marker : kept.keySet()) {
            if (line.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * How a block of pasted text is described in its marker.
     *
     * @param text what was pasted
     * @return e.g. {@code "42 lines"} or {@code "1,203 characters"}
     */
    static String describe(String text) {
        String body  = text == null ? "" : text;
        int    lines = body.isEmpty() ? 0 : 1;
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == '\n') {
                lines++;
            }
        }
        if (body.endsWith("\n")) {
            lines--; // the terminator of the last line, not a line of its own
        }
        if (lines > 1) {
            return lines + " lines";
        }
        return String.format("%,d characters", body.length());
    }

    /** Keeps a marker, dropping the oldest once there are too many to keep. */
    private void remember(String marker, String text) {
        kept.put(marker, text);
        while (kept.size() > MAX_KEPT) {
            String oldest = kept.keySet().iterator().next();
            kept.remove(oldest);
            // The picture goes with its marker: a marker nobody can expand any more must not leave
            // a megabyte of base64 behind it for the rest of the session.
            pictures.remove(oldest);
        }
    }

    /**
     * Drops the oldest pictures until what is held is within {@link #MAX_PICTURE_BYTES}.
     *
     * <p>The newest picture is never dropped, even alone over the budget: it is the one just
     * dropped on the prompt, and the marker for it is about to go into the line.</p>
     */
    private void dropPicturesPastTheBudget() {
        long held = 0;
        for (PromptImage picture : pictures.values()) {
            held += picture.byteCount();
        }
        Iterator<Map.Entry<String, PromptImage>> oldestFirst = pictures.entrySet().iterator();
        while (held > MAX_PICTURE_BYTES && pictures.size() > 1) {
            Map.Entry<String, PromptImage> oldest = oldestFirst.next();
            held -= oldest.getValue().byteCount();
            LOG.debug("Letting go of the picture for " + oldest.getKey()
                            + "; its marker still stands for the file.");
            oldestFirst.remove();
        }
    }

    /** Takes one pair of matching quotes off a dropped path. */
    private static String unquote(String text, char quote) {
        return text.length() > 1 && text.charAt(0) == quote
               && text.charAt(text.length() - 1) == quote
               ? text.substring(1, text.length() - 1)
               : text;
    }

    /** Quotes a path that carries a space, so it survives as one argument. */
    private static String quoteIfNeeded(String path) {
        return path.indexOf(' ') < 0 ? path : "'" + path.replace("'", "'\\''") + "'";
    }
}
