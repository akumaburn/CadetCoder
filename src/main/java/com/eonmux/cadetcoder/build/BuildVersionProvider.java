package com.eonmux.cadetcoder.build;

import picocli.CommandLine.IVersionProvider;

/**
 * What {@code --version} answers with: the version and the exact build it came from.
 *
 * <p>The provider rather than a literal in the annotation, because "1.0" names the program and
 * nothing about the bytes answering the question. Two jars of the same version differ in the
 * commit they were built from, and that difference is the whole subject of {@code update check},
 * so the line it prints and the line {@code --version} prints name the same facts.</p>
 */
public class BuildVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        return new String[] {BuildInfo.current().describe()};
    }
}
