package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the shell-execution synonyms an LLM favours canonicalize to the real shell runner
 * ({@code bash}), while every other verb (including the distinct interactive {@code shell} command) is
 * preserved. This is the mapping that stops {@code execute <shell command>} from being misrouted to the
 * script-file {@link ExecuteCommand} and reported as "File not found".
 */
public class CommandAliasesTest {

    @Test
    public void shellExecutionSynonymsBecomeBash() {
        assertThat(CommandAliases.canonicalize("execute")).isEqualTo("bash");
        assertThat(CommandAliases.canonicalize("exec")).isEqualTo("bash");
        assertThat(CommandAliases.canonicalize("run")).isEqualTo("bash");
    }

    @Test
    public void synonymMatchingIsCaseAndWhitespaceInsensitive() {
        assertThat(CommandAliases.canonicalize("EXECUTE")).isEqualTo("bash");
        assertThat(CommandAliases.canonicalize("  Run  ")).isEqualTo("bash");
    }

    @Test
    public void bashItselfIsUnchanged() {
        assertThat(CommandAliases.canonicalize("bash")).isEqualTo("bash");
    }

    @Test
    public void nonShellVerbsArePreservedLowerCased() {
        assertThat(CommandAliases.canonicalize("read")).isEqualTo("read");
        assertThat(CommandAliases.canonicalize("GREP")).isEqualTo("grep");
        // The interactive REPL command must NOT be hijacked into bash.
        assertThat(CommandAliases.canonicalize("shell")).isEqualTo("shell");
    }

    @Test
    public void nullIsPassedThrough() {
        assertThat(CommandAliases.canonicalize(null)).isNull();
    }
}
