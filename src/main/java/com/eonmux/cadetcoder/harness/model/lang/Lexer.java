package com.eonmux.cadetcoder.harness.model.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a model's source into tokens.
 *
 * <p>Deliberately small. The language has no imports, no string interpolation and no way to name
 * anything outside itself, so there is nothing here that could reach the host -- which is what makes
 * running a model the LLM wrote an ordinary operation rather than a sandboxing problem.</p>
 */
final class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("fn", TokenType.FN),
            Map.entry("let", TokenType.LET),
            Map.entry("return", TokenType.RETURN),
            Map.entry("if", TokenType.IF),
            Map.entry("else", TokenType.ELSE),
            Map.entry("while", TokenType.WHILE),
            Map.entry("for", TokenType.FOR),
            Map.entry("in", TokenType.IN),
            Map.entry("break", TokenType.BREAK),
            Map.entry("continue", TokenType.CONTINUE),
            Map.entry("hidden", TokenType.HIDDEN),
            Map.entry("true", TokenType.TRUE),
            Map.entry("false", TokenType.FALSE),
            Map.entry("null", TokenType.NULL));

    private final String      source;
    private final List<Token> tokens = new ArrayList<>();

    private int position;
    private int line   = 1;
    private int column = 1;

    Lexer(String source) {
        this.source = source;
    }

    /** Every word the language reserves, so what describes it can be checked against it. */
    static Set<String> keywords() {
        return KEYWORDS.keySet();
    }

    List<Token> scan() {
        while (!atEnd()) {
            skipBlanksAndComments();
            if (atEnd()) {
                break;
            }
            scanToken();
        }
        tokens.add(new Token(TokenType.END, "", null, line, column));
        return tokens;
    }

    private void skipBlanksAndComments() {
        while (!atEnd()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                advance();
            } else if (c == '/' && peekNext() == '/') {
                while (!atEnd() && peek() != '\n') {
                    advance();
                }
            } else if (c == '/' && peekNext() == '*') {
                skipBlockComment();
            } else {
                return;
            }
        }
    }

    private void skipBlockComment() {
        int startLine   = line;
        int startColumn = column;
        advance();
        advance();
        while (!atEnd() && !(peek() == '*' && peekNext() == '/')) {
            advance();
        }
        if (atEnd()) {
            throw new ModelSyntaxException("unterminated comment", startLine, startColumn);
        }
        advance();
        advance();
    }

    private void scanToken() {
        int  startLine   = line;
        int  startColumn = column;
        char c           = advance();
        switch (c) {
            case '+' -> add(TokenType.PLUS, "+", startLine, startColumn);
            case '-' -> add(TokenType.MINUS, "-", startLine, startColumn);
            case '*' -> add(TokenType.STAR, "*", startLine, startColumn);
            case '/' -> add(TokenType.SLASH, "/", startLine, startColumn);
            case '%' -> add(TokenType.PERCENT, "%", startLine, startColumn);
            case '?' -> add(TokenType.QUESTION, "?", startLine, startColumn);
            case ':' -> add(TokenType.COLON, ":", startLine, startColumn);
            case '.' -> add(TokenType.DOT, ".", startLine, startColumn);
            case ',' -> add(TokenType.COMMA, ",", startLine, startColumn);
            case ';' -> add(TokenType.SEMICOLON, ";", startLine, startColumn);
            case '(' -> add(TokenType.LEFT_PAREN, "(", startLine, startColumn);
            case ')' -> add(TokenType.RIGHT_PAREN, ")", startLine, startColumn);
            case '[' -> add(TokenType.LEFT_BRACKET, "[", startLine, startColumn);
            case ']' -> add(TokenType.RIGHT_BRACKET, "]", startLine, startColumn);
            case '{' -> add(TokenType.LEFT_BRACE, "{", startLine, startColumn);
            case '}' -> add(TokenType.RIGHT_BRACE, "}", startLine, startColumn);
            case '!' -> maybeDoubled('!', TokenType.BANG, TokenType.BANG_EQUAL, startLine, startColumn);
            case '=' -> maybeDoubled('=', TokenType.EQUAL, TokenType.EQUAL_EQUAL, startLine, startColumn);
            case '<' -> maybeDoubled('<', TokenType.LESS, TokenType.LESS_EQUAL, startLine, startColumn);
            case '>' -> maybeDoubled('>', TokenType.GREATER, TokenType.GREATER_EQUAL, startLine, startColumn);
            case '&' -> pair('&', TokenType.AND_AND, "&&", startLine, startColumn);
            case '|' -> pair('|', TokenType.OR_OR, "||", startLine, startColumn);
            case '"' -> string(startLine, startColumn);
            default  -> word(c, startLine, startColumn);
        }
    }

    private void add(TokenType type, String text, int startLine, int startColumn) {
        tokens.add(new Token(type, text, null, startLine, startColumn));
    }

    /**
     * Reads an operator that means one thing alone and another when followed by {@code =}.
     *
     * <p>{@link #match} consumes, so the second character has to be read exactly once and the
     * token chosen from what that read found.</p>
     */
    private void maybeDoubled(char first, TokenType alone, TokenType withEquals,
                              int startLine, int startColumn) {
        boolean equals = match('=');
        add(equals ? withEquals : alone, equals ? first + "=" : String.valueOf(first),
            startLine, startColumn);
    }

    private void pair(char expected, TokenType type, String text, int startLine, int startColumn) {
        if (!match(expected)) {
            throw new ModelSyntaxException("expected " + text, startLine, startColumn);
        }
        tokens.add(new Token(type, text, null, startLine, startColumn));
    }

    private void string(int startLine, int startColumn) {
        StringBuilder value = new StringBuilder();
        while (!atEnd() && peek() != '"') {
            char c = advance();
            if (c == '\n') {
                throw new ModelSyntaxException("a string may not span lines", startLine, startColumn);
            }
            value.append(c == '\\' ? escape(startLine, startColumn) : c);
        }
        if (atEnd()) {
            throw new ModelSyntaxException("unterminated string", startLine, startColumn);
        }
        advance();
        tokens.add(new Token(TokenType.STRING, value.toString(), value.toString(),
                             startLine, startColumn));
    }

    private char escape(int startLine, int startColumn) {
        if (atEnd()) {
            throw new ModelSyntaxException("unterminated escape", startLine, startColumn);
        }
        char c = advance();
        return switch (c) {
            case 'n'  -> '\n';
            case 't'  -> '\t';
            case 'r'  -> '\r';
            case '0'  -> (char) 0;
            case '"'  -> '"';
            case '\\' -> '\\';
            case 'u'  -> unicode(startLine, startColumn);
            default   -> throw new ModelSyntaxException("unknown escape \\" + c, startLine, startColumn);
        };
    }

    private char unicode(int startLine, int startColumn) {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 4 && !atEnd(); i++) {
            hex.append(advance());
        }
        try {
            return (char) Integer.parseInt(hex.toString(), 16);
        } catch (NumberFormatException e) {
            throw new ModelSyntaxException("bad unicode escape \\u" + hex, startLine, startColumn);
        }
    }

    private void word(char first, int startLine, int startColumn) {
        if (Character.isDigit(first)) {
            number(first, startLine, startColumn);
            return;
        }
        if (!Character.isLetter(first) && first != '_') {
            throw new ModelSyntaxException("unexpected character '" + first + "'",
                                           startLine, startColumn);
        }
        StringBuilder text = new StringBuilder().append(first);
        while (!atEnd() && (Character.isLetterOrDigit(peek()) || peek() == '_')) {
            text.append(advance());
        }
        String    word = text.toString();
        TokenType type = KEYWORDS.getOrDefault(word, TokenType.IDENTIFIER);
        Object    value = switch (type) {
            case TRUE  -> Boolean.TRUE;
            case FALSE -> Boolean.FALSE;
            default    -> null;
        };
        tokens.add(new Token(type, word, value, startLine, startColumn));
    }

    private void number(char first, int startLine, int startColumn) {
        StringBuilder text = new StringBuilder().append(first);
        while (!atEnd() && (Character.isDigit(peek()) || peek() == '.')) {
            text.append(advance());
        }
        if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
            text.append(advance());
            if (!atEnd() && (peek() == '+' || peek() == '-')) {
                text.append(advance());
            }
            while (!atEnd() && Character.isDigit(peek())) {
                text.append(advance());
            }
        }
        try {
            tokens.add(new Token(TokenType.NUMBER, text.toString(),
                                 Double.parseDouble(text.toString()), startLine, startColumn));
        } catch (NumberFormatException e) {
            throw new ModelSyntaxException("not a number: " + text, startLine, startColumn);
        }
    }

    private boolean match(char expected) {
        if (atEnd() || peek() != expected) {
            return false;
        }
        advance();
        return true;
    }

    private char peek() {
        return source.charAt(position);
    }

    private char peekNext() {
        return position + 1 < source.length() ? source.charAt(position + 1) : '\0';
    }

    private char advance() {
        char c = source.charAt(position++);
        if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        return c;
    }

    private boolean atEnd() {
        return position >= source.length();
    }
}
