package com.eonmux.cadetcoder.harness.model.lang;

import com.eonmux.cadetcoder.harness.Json;

import java.util.ArrayList;
import java.util.List;

/** Reading and reshaping text, which is most of what a shell observation is made of. */
final class TextBuiltins {

    private TextBuiltins() {
    }

    static List<Builtin> all() {
        return List.of(
                new Builtin("lower", 1, 1, (a, l, c) -> Values.text(a.get(0), "lower", l, c)
                        .toLowerCase(java.util.Locale.ROOT)),
                new Builtin("upper", 1, 1, (a, l, c) -> Values.text(a.get(0), "upper", l, c)
                        .toUpperCase(java.util.Locale.ROOT)),
                new Builtin("trim", 1, 1, (a, l, c) -> Values.text(a.get(0), "trim", l, c).trim()),
                new Builtin("replace", 3, 3, TextBuiltins::replace),
                new Builtin("split", 2, 2, TextBuiltins::split),
                new Builtin("join", 2, 2, TextBuiltins::join),
                new Builtin("startswith", 2, 2, (a, l, c) -> Values.text(a.get(0), "startswith", l, c)
                        .startsWith(Values.text(a.get(1), "startswith", l, c))),
                new Builtin("endswith", 2, 2, (a, l, c) -> Values.text(a.get(0), "endswith", l, c)
                        .endsWith(Values.text(a.get(1), "endswith", l, c))));
    }

    private static Object replace(List<Object> arguments, int line, int column) {
        return Values.text(arguments.get(0), "replace", line, column)
                .replace(Values.text(arguments.get(1), "replace", line, column),
                         Values.text(arguments.get(2), "replace", line, column));
    }

    /** Splits on a literal separator; an empty separator gives the characters. */
    private static Object split(List<Object> arguments, int line, int column) {
        String       text      = Values.text(arguments.get(0), "split", line, column);
        String       separator = Values.text(arguments.get(1), "split", line, column);
        List<Object> parts     = new ArrayList<>();
        if (separator.isEmpty()) {
            for (int i = 0; i < text.length(); i++) {
                parts.add(String.valueOf(text.charAt(i)));
            }
            return parts;
        }
        int from = 0;
        int at   = text.indexOf(separator);
        while (at >= 0) {
            parts.add(text.substring(from, at));
            from = at + separator.length();
            at   = text.indexOf(separator, from);
        }
        parts.add(text.substring(from));
        return parts;
    }

    private static Object join(List<Object> arguments, int line, int column) {
        String        separator = Values.text(arguments.get(1), "join", line, column);
        List<Object>  elements  = Values.list(arguments.get(0), "join", line, column);
        StringBuilder out       = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) {
                out.append(separator);
            }
            out.append(Json.readable(elements.get(i)));
        }
        return out.toString();
    }
}
