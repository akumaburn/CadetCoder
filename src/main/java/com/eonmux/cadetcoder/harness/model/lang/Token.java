package com.eonmux.cadetcoder.harness.model.lang;

/**
 * One piece of a model's source, and where it was written.
 *
 * @param type   what kind of piece it is
 * @param text   how it was spelled
 * @param value  the value of a literal, otherwise {@code null}
 * @param line   the 1-based line it starts on
 * @param column the 1-based column it starts at
 */
public record Token(TokenType type, String text, Object value, int line, int column) {

    @Override
    public String toString() {
        return type + "(" + text + ")";
    }
}
