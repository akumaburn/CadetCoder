package com.eonmux.cadetcoder.harness.model.lang;

import java.util.ArrayList;
import java.util.List;

/**
 * The model language as the author of a model is told it.
 *
 * <h2>Why the language explains itself</h2>
 *
 * <p>Every model this harness runs is written by a language model that has never seen this language
 * before, from nothing but the prompt. A description kept anywhere else -- a document, a prompt
 * template, a comment -- is a description that can fall behind the parser without anything failing,
 * and the way it fails is a run that spends its whole budget being refused for syntax nobody told it
 * about. What can be derived is derived: the library is {@link Builtins#render()} and the beliefs
 * are {@link ExpectMethods#names()}, so a builtin that is added is a builtin that is described.</p>
 *
 * <h2>Why an example rather than a grammar</h2>
 *
 * <p>A grammar says what is legal; an example says what to write. {@link #EXAMPLE} is a complete,
 * working model of a world small enough to read in one go, and it is small enough to be compiled by
 * a test -- which is what keeps the guide honest about a language that is still moving.</p>
 */
public final class LanguageGuide {

    /** A complete model of a corridor, which is what a first model of anything tends to look like. */
    public static final String EXAMPLE = """
            // What parse() cannot recover from a single observation.
            hidden carried;

            fn parse(obs) {
                return {"pos": obs.pos, "carried": 0};
            }

            fn step(state, action) {
                let next = copy(state);
                next.pos = state.pos + action.move;
                return next;
            }

            fn predict(state) {
                return {"pos": state.pos};
            }

            fn is_goal(state) {
                return state.pos >= 3;
            }

            fn actions(state) {
                return [{"move": 1}, {"move": -1}];
            }
            """;

    /** How wide a folded line of the library may run. */
    private static final int LINE_WIDTH = 72;

    private LanguageGuide() {
    }

    /** The whole language, as it goes into a prompt. */
    public static String render() {
        List<String> lines = new ArrayList<>();
        lines.add("THE MODEL LANGUAGE");
        lines.add("  A model is a program. It is not Python, JavaScript or any other language you");
        lines.add("  know; it is the small language described here, and nothing outside this");
        lines.add("  description exists. There is no import, no class, no file, no clock and no");
        lines.add("  random number: a model is a pure function of what it is given.");
        lines.add("");
        lines.add("  Declarations");
        lines.add("    fn name(a, b) { ... }      a function; the contract says which are needed");
        lines.add("    hidden a, b;               state fields parse() cannot recover from an");
        lines.add("                               observation; their values are carried forward when");
        lines.add("                               the harness re-grounds on what really happened");
        lines.add("");
        lines.add("  Statements");
        lines.add("    let x = expression;        a new name; only a let or a parameter is assignable");
        lines.add("    x = expression;            also x.field = ... and x[i] = ...");
        lines.add("    return expression;");
        lines.add("    if (c) { ... } else { ... }");
        lines.add("    while (c) { ... }          break and continue work as you expect");
        lines.add("    for (x in list) { ... }    over a list; for an object, for (k in keys(o))");
        lines.add("");
        lines.add("  Expressions");
        lines.add("    numbers, \"strings\", true, false, null");
        lines.add("    [1, 2, 3]                  a list");
        lines.add("    {\"a\": 1, b: 2}             an object; a key may be written as a bare name");
        lines.add("    x.field  x[\"field\"]  x[0]  reading; reading what is not there is an error, so");
        lines.add("                               use has(x, \"f\") or get(x, \"f\", fallback) instead");
        lines.add("    + - * / %                  arithmetic; + joins when either side is text");
        lines.add("    == !=                      by value, all the way down");
        lines.add("    < <= > >=                  numbers, or text in dictionary order");
        lines.add("    && || !                    empty is false: null, zero, no text, no list, no");
        lines.add("                               object; && and || answer with one of their sides");
        lines.add("    c ? a : b                  a choice");
        lines.add("    fn(x) { return x + 1; }    a function with no name, for expect().where");
        lines.add("    // a line comment          /* and a block one */");
        lines.add("");
        lines.add("  The library");
        lines.add("    " + wrapped(Builtins.render(), "    "));
        lines.add("    A name carries how many arguments it takes: /2 exactly two, /1..2 either,");
        lines.add("    /1+ at least one.");
        lines.add("    error(\"why\") stops the model with a message you will be shown.");
        lines.add("");
        lines.add("  Saying what you are unsure of");
        lines.add("    predict() may answer with an exact observation, or with beliefs about one when");
        lines.add("    an exact answer is more than you know. Beliefs chain off expect() and each");
        lines.add("    names a dotted path into the observation, such as \"hud.score\" or \"grid.3.4\":");
        lines.add("");
        lines.add("      return expect().eq(\"pos\", state.pos).ge(\"hp\", 1, \"still alive\");");
        lines.add("");
        lines.add("    eq, ne, gt, ge, lt, le, contains and matches take a path and a value;");
        lines.add("    present and absent take a path alone; where takes a name and a function of");
        lines.add("    the whole observation, as in .where(\"even\", fn(o) { return o.n % 2 == 0; }).");
        lines.add("    Each of the others may take one more argument naming the belief, to be told");
        lines.add("    when it fails. The beliefs are:");
        lines.add("      " + wrapped(String.join(", ", ExpectMethods.names()), "      "));
        lines.add("    Only what you name is checked -- which is what makes a partial prediction");
        lines.add("    honest rather than a hedge.");
        return String.join("\n", lines);
    }

    /** The library, folded so that no line of the prompt runs off the edge. */
    private static String wrapped(String names, String indent) {
        StringBuilder out    = new StringBuilder();
        int           onLine = 0;
        for (String name : names.split(", ")) {
            if (onLine > 0 && onLine + name.length() + 2 > LINE_WIDTH) {
                out.append(",\n").append(indent);
                onLine = 0;
            } else if (onLine > 0) {
                out.append(", ");
                onLine += 2;
            }
            out.append(name);
            onLine += name.length();
        }
        return out.toString();
    }
}
