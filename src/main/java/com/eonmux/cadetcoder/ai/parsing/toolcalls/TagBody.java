package com.eonmux.cadetcoder.ai.parsing.toolcalls;

import java.util.regex.Pattern;

/**
 * The value a model wrote between an opening and a closing tag.
 *
 * <h2>Why the body is not simply trimmed</h2>
 *
 * <p>A tagged parameter is written one of two ways. DeepSeek's own examples put the value straight
 * between the tags, with nothing around it. Every notation modelled on Claude's puts it on its own
 * lines, so the body begins with a line break and ends with one. Trimming both cases handles the
 * second but corrupts the first whenever the value is file content: {@code write} loses the blank
 * line a file ends with, and a patch loses the leading space that marks a context line, so the patch
 * no longer applies. One line break is removed at each end, which is the one the notation added, and
 * nothing else is touched.</p>
 */
final class TagBody {

    /** A single line break at the start of a body, with any spaces that precede it. */
    private static final Pattern OPENING_BREAK = Pattern.compile("\\A[ \\t]*\\r?\\n");

    /** A single line break at the end of a body, with any spaces that follow it. */
    private static final Pattern CLOSING_BREAK = Pattern.compile("\\r?\\n[ \\t]*\\z");

    private TagBody() {
    }

    /**
     * @param body the raw text between the tags
     * @return the value it carries
     */
    static String valueIn(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        String value = OPENING_BREAK.matcher(body).replaceFirst("");
        return CLOSING_BREAK.matcher(value).replaceFirst("");
    }
}
