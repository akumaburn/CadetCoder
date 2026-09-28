package com.eonmux.cadetcoder.harness.model.lang;

import java.util.List;

/**
 * One function a model declares.
 *
 * @param name       what it is called, or {@code null} when it was written anonymously
 * @param parameters its parameter names, in order
 * @param body       its statements
 * @param armId      what coverage calls "this function ran at all"
 * @param line       the 1-based line of its {@code fn}
 * @param column     the 1-based column of its {@code fn}
 */
public record FunctionDef(String name, List<String> parameters, List<Stmt> body, String armId,
                          int line, int column) {

    /** How it reads in a report. */
    public String signature() {
        return (name == null ? "fn" : name) + "(" + String.join(", ", parameters) + ")";
    }
}
