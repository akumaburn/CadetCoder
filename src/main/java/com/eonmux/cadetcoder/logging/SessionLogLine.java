package com.eonmux.cadetcoder.logging;

import java.util.List;

/**
 * How a session log entry reads in the file a person opens.
 *
 * <h2>A table rather than a hundred lines of the same thing</h2>
 *
 * <p>Every kind of entry is written the same way -- a name, a colon, the thing it is about, and
 * then whatever else was recorded alongside it, each as {@code | Name: value}. Written out as a
 * branch per kind, that was a hundred lines in which each branch differed from its neighbours by a
 * word, so a kind added later got a branch copied from whichever one was nearest and the differences
 * between them stopped meaning anything.</p>
 *
 * <p>Said once and applied to a table, adding a kind is a row. The table is a switch over the kinds
 * rather than a map of them, so a kind added without a row does not fall through to a default
 * rendering that nobody looked at -- it fails to compile.</p>
 */
final class SessionLogLine {

    /** How much of a long line is shown before the middle is replaced by a count of what is not. */
    private static final int SHOWN_EITHER_END = 100;

    private SessionLogLine() {
    }

    /** What follows the colon. */
    private enum Head { MESSAGE, SHORTENED_MESSAGE, SOURCE }

    /**
     * One thing recorded alongside an entry, and how it is shown.
     *
     * @param key  the name it was recorded under
     * @param name the name it is shown under
     * @param unit what it is measured in, appended to the value; empty if it is not a measurement
     */
    private record Detail(String key, String name, String unit) {
        private Detail(String key, String name) {
            this(key, name, "");
        }
    }

    /** How one kind of entry is written. */
    private record Shape(String label, Head head, List<Detail> details) {
        private Shape(String label, Head head, Detail... details) {
            this(label, head, List.of(details));
        }
    }

    /**
     * Renders an entry as the one line it occupies in the readable log.
     *
     * @param entry what to write down
     * @return the line, without its timestamp, which the writer puts in front of it
     */
    static String from(SessionLogEntry entry) {
        Shape         shape = shapeOf(entry.type);
        StringBuilder line  = new StringBuilder(shape.label()).append(": ")
                                                              .append(head(entry, shape.head()));
        for (Detail detail : shape.details()) {
            if (entry.metadata.containsKey(detail.key())) {
                line.append(" | ").append(detail.name()).append(": ")
                    .append(entry.metadata.get(detail.key())).append(detail.unit());
            }
        }
        appendFailure(line, entry);
        return line.toString();
    }

    private static String head(SessionLogEntry entry, Head head) {
        return switch (head) {
            case MESSAGE           -> entry.message;
            case SHORTENED_MESSAGE -> shortened(entry.message);
            case SOURCE            -> entry.source;
        };
    }

    /**
     * Adds the failure an entry carries, whatever kind of entry it is.
     *
     * <p>Only errors carry one today, and only errors used to show one. Tying it to the kind was
     * the fragile half of that: an exception attached to any other kind was written to the JSON
     * record and left out of the readable line, so the two files disagreed about the same
     * event.</p>
     */
    private static void appendFailure(StringBuilder line, SessionLogEntry entry) {
        if (entry.failure != null) {
            line.append(" | Exception: ").append(entry.failure.type).append(": ")
                .append(entry.failure.message);
        }
    }

    /**
     * A long line with its middle replaced by a count of what was left out.
     *
     * <p>Both ends rather than the first part alone: command output is read for how it started and
     * for how it ended, and the end is the half that says what went wrong.</p>
     */
    private static String shortened(String text) {
        if (text == null) {
            return "null";
        }
        if (text.length() <= SHOWN_EITHER_END * 2) {
            return text;
        }
        return text.substring(0, SHOWN_EITHER_END)
               + " ... [" + (text.length() - SHOWN_EITHER_END * 2) + " chars] ... "
               + text.substring(text.length() - SHOWN_EITHER_END);
    }

    private static Shape shapeOf(LogEntryType type) {
        return switch (type) {
            case USER_INPUT     -> new Shape("USER_INPUT", Head.MESSAGE);
            case COMMAND_START  -> new Shape("COMMAND_START", Head.MESSAGE,
                                             new Detail("args", "Args"));
            case COMMAND_OUTPUT -> new Shape("OUTPUT", Head.SHORTENED_MESSAGE);
            case COMMAND_END    -> new Shape("COMMAND_END", Head.MESSAGE,
                                             new Detail("exitCode", "Exit Code"),
                                             new Detail("duration", "Duration", "ms"));
            case AI_REQUEST     -> new Shape("AI_REQUEST", Head.SOURCE,
                                             new Detail("model", "Model"),
                                             new Detail("promptLength", "Prompt Length"));
            case AI_RESPONSE    -> new Shape("AI_RESPONSE", Head.SOURCE,
                                             new Detail("responseLength", "Response Length"),
                                             new Detail("duration", "Duration", "ms"));
            case LLM_REQUEST    -> new Shape("LLM_REQUEST", Head.MESSAGE,
                                             new Detail("model", "Model"),
                                             new Detail("payloadSize", "Payload Size", " bytes"));
            case LLM_RESPONSE   -> new Shape("LLM_RESPONSE", Head.MESSAGE,
                                             new Detail("model", "Model"),
                                             new Detail("duration", "Duration", "ms"),
                                             new Detail("responseSize", "Response Size", " bytes"));
            case FILE_OPERATION -> new Shape("FILE_OP", Head.MESSAGE,
                                             new Detail("path", "Path"),
                                             new Detail("success", "Success"));
            case ERROR          -> new Shape("ERROR", Head.MESSAGE);
            case SESSION_EVENT  -> new Shape("SESSION", Head.MESSAGE);
            case SECURITY_EVENT -> new Shape("SECURITY", Head.MESSAGE,
                                             new Detail("allowed", "Allowed"));
            case PERFORMANCE    -> new Shape("PERFORMANCE", Head.MESSAGE,
                                             new Detail("duration", "Duration", "ms"));
        };
    }
}
