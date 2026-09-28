package com.eonmux.cadetcoder.ai.parsing.toolcalls;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The attributes written on an opening tag.
 *
 * <p>Shared by the notations that carry a tool's name and a parameter's name as attributes. Both
 * quoting styles are read, because a model that has been asked for JSON elsewhere in the same reply
 * reaches for the single quote often enough to matter, and the notations themselves place no value
 * on which quote was used.</p>
 */
final class TagAttributes {

    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_.:-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    private TagAttributes() {
    }

    /**
     * @param attributes the text between the tag name and the closing angle bracket
     * @param key        the attribute to read
     * @return its value, or {@code null} when the tag does not carry it
     */
    static String valueOf(String attributes, String key) {
        if (attributes == null) {
            return null;
        }
        Matcher attribute = ATTRIBUTE.matcher(attributes);
        while (attribute.find()) {
            if (attribute.group(1).equalsIgnoreCase(key)) {
                return attribute.group(2) != null ? attribute.group(2) : attribute.group(3);
            }
        }
        return null;
    }
}
