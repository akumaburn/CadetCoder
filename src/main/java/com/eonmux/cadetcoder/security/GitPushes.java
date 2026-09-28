package com.eonmux.cadetcoder.security;

import java.util.List;
import java.util.Set;

/**
 * Recognises a {@code git push} that can discard commits on the remote.
 *
 * <p>A force push, a deleted or pruned branch and a mirror all remove commits from the remote that
 * may exist nowhere else. {@code push --force} asks the person for each one, and a push written as
 * a shell command has to be caught as well, or {@code bash "git push --force"} would go round it.</p>
 */
final class GitPushes {

    /** Options of {@code git} itself that take their value as the next token. */
    private static final Set<String> GIT_OPTIONS_WITH_A_VALUE =
            Set.of("-C", "-c", "--git-dir", "--work-tree", "--namespace", "--super-prefix",
                   "--config-env", "--exec-path");

    /** Options of {@code git push} that can remove commits from the remote. */
    private static final Set<String> DISCARDING_OPTIONS =
            Set.of("--force", "--force-with-lease", "--force-if-includes", "--mirror", "--delete",
                   "--prune");

    private GitPushes() {
    }

    /**
     * What a {@code git} command can discard on the remote, if it is a push that can.
     *
     * @param segment the command
     * @param at      where in its tokens {@code git} is named
     * @return a reason to put to the person, or {@code null} when it is no such push
     */
    static String whatItCanDiscard(ShellCommandLine.Segment segment, int at) {
        List<String> tokens = segment.tokens();
        int          index  = at + 1;
        while (index < tokens.size() && tokens.get(index).startsWith("-")) {
            String option = tokens.get(index);
            index += GIT_OPTIONS_WITH_A_VALUE.contains(option) ? 2 : 1;
        }
        if (index >= tokens.size()) {
            return null;
        }
        if (segment.isHidden(index)) {
            return "the git command it runs is built by expansion, and it may be a force push";
        }
        if (!tokens.get(index).equals("push")) {
            return null;
        }
        for (int i = index + 1; i < tokens.size(); i++) {
            if (segment.isHidden(i)) {
                return "an argument of this git push is built by expansion, and it may force the push";
            }
            if (discards(tokens.get(i))) {
                return "this git push ('" + tokens.get(i) + "') can discard commits on the remote "
                       + "that exist nowhere else";
            }
        }
        return null;
    }

    private static boolean discards(String token) {
        if (token.startsWith("--")) {
            String name = token.contains("=") ? token.substring(0, token.indexOf('=')) : token;
            return DISCARDING_OPTIONS.contains(name);
        }
        if (token.startsWith("-") && token.length() > 1) {
            // Short options combine: -uf and -fu both force.
            return token.indexOf('f', 1) >= 0 || token.indexOf('d', 1) >= 0;
        }
        // A refspec that forces (+main) or deletes (:old).
        return token.startsWith("+") || token.startsWith(":");
    }
}
