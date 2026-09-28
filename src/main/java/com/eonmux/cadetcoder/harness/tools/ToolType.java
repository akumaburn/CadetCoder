package com.eonmux.cadetcoder.harness.tools;

import java.util.Locale;

/**
 * What kind of thing an argument is.
 *
 * <h2>Why there are five and not the whole of JSON</h2>
 *
 * <p>A tool call arrives as text a language model wrote, so every argument has to be read back into
 * something before it can be used. Five kinds cover every argument any tool here takes, and each of
 * the five has one unambiguous reading -- which is what lets a wrong argument be refused by name
 * instead of quietly becoming a zero.</p>
 */
public enum ToolType {

    /** A word or a sentence. */
    TEXT,

    /** A whole number, such as a ledger index. */
    INTEGER,

    /** A number that may have a fraction, such as a number of seconds. */
    DECIMAL,

    /** True or false. */
    FLAG,

    /** A list of values, whose elements each tool reads its own way. */
    LIST;

    /** How the kind is written where an author reads it. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
