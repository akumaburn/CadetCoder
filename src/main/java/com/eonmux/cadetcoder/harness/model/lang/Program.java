package com.eonmux.cadetcoder.harness.model.lang;

import com.eonmux.cadetcoder.harness.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A world model, read and checked but not yet run.
 *
 * <h2>What a program is for</h2>
 *
 * <p>This is the harness's theory of its environment in the only form that can be argued with: a
 * program whose functions can be replayed against the ledger, whose rules can be counted, and whose
 * text hashes to a name. Everything downstream refers to a model by {@link #digest()}, so a
 * certificate cannot drift onto a source that has since been edited.</p>
 *
 * <h2>What it guarantees before anything runs</h2>
 *
 * <p>Parsing succeeds only if every name the model uses exists -- a parameter, a local, one of its
 * own functions, or the standard library. There is nothing else to reach, so a model that tried
 * would be refused here rather than partway through a replay.</p>
 */
public final class Program {

    /** How many characters of the source hash name a model. */
    public static final int DIGEST_LENGTH = 16;

    private final String                   source;
    private final Set<String>              hidden;
    private final Map<String, FunctionDef> functions;
    private final List<Arm>                arms;
    private final String                   digest;
    private final int                      nodes;

    Program(String source, Set<String> hidden, List<FunctionDef> functions, List<Arm> arms) {
        this.source    = source;
        this.hidden    = Set.copyOf(hidden);
        this.functions = named(functions);
        this.arms      = List.copyOf(arms);
        this.digest    = Json.digestOfText(source, DIGEST_LENGTH);
        this.nodes     = Nodes.count(functions);
    }

    private static Map<String, FunctionDef> named(List<FunctionDef> declared) {
        Map<String, FunctionDef> byName = new LinkedHashMap<>();
        for (FunctionDef function : declared) {
            if (byName.put(function.name(), function) != null) {
                throw new ModelSyntaxException("this model declares " + function.name() + " twice",
                                               function.line(), function.column());
            }
        }
        return Map.copyOf(byName);
    }

    /** Reads a model, refusing anything that cannot run. */
    public static Program parse(String source) {
        if (source == null || source.isBlank()) {
            throw new ModelSyntaxException("a model has to say something");
        }
        return new Parser(source, new Lexer(source).scan()).parse();
    }

    /** The source exactly as it was written. */
    public String source() {
        return source;
    }

    /** What the model is called: the digest of its source. */
    public String digest() {
        return digest;
    }

    /** The fields a model says {@code parse} cannot recover from a single observation. */
    public Set<String> hidden() {
        return hidden;
    }

    /** Every function the model declares, by name. */
    public Set<String> functionNames() {
        return functions.keySet();
    }

    /** Whether the model declares a function of this name. */
    public boolean defines(String name) {
        return functions.containsKey(name);
    }

    /**
     * How many arguments the named function takes.
     *
     * @param name a function the model declares
     * @return its parameter count
     * @throws IllegalArgumentException if the model declares no such function
     */
    public int arity(String name) {
        FunctionDef function = functions.get(name);
        if (function == null) {
            throw new IllegalArgumentException("this model does not define " + name);
        }
        return function.parameters().size();
    }

    /** How many lines of source the model is. */
    public int lineCount() {
        return source.split("\\n", -1).length;
    }

    /** How many syntax nodes the model is, as a stand-in for how much theory it carries. */
    public int nodeCount() {
        return nodes;
    }

    /** The named function, or {@code null} when the model does not declare one. */
    FunctionDef function(String name) {
        return functions.get(name);
    }

    /** Every rule the model states, in the order it states them. */
    public List<Arm> arms() {
        return arms;
    }

    /** Nothing has been exercised yet. */
    public Coverage noCoverage() {
        return Coverage.nothing(arms);
    }
}
