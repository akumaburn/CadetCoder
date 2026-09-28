package com.eonmux.cadetcoder.harness.model;

import com.eonmux.cadetcoder.harness.model.lang.Program;

/**
 * How much theory a model carries, in numbers that can be compared across versions.
 *
 * <p>A model that keeps growing while the set of rules reality has tested stays where it was is
 * being patched rather than corrected. Nothing here decides anything: it makes that pattern visible
 * so a report can name it, which is all a cheap description-length proxy is good for.</p>
 *
 * @param nodes     how many syntax nodes the model is
 * @param lines     how many lines of source
 * @param arms      how many rules it states
 * @param functions how many functions it declares
 */
public record ModelComplexity(int nodes, int lines, int arms, int functions) {

    /** Measures a model. */
    public static ModelComplexity of(Program program) {
        return new ModelComplexity(program.nodeCount(), program.lineCount(),
                                   program.arms().size(), program.functionNames().size());
    }

    /** How it reads in a report. */
    public String render() {
        return nodes + " nodes, " + lines + " lines, " + arms + " arms, "
               + functions + " functions";
    }

    @Override
    public String toString() {
        return render();
    }
}
