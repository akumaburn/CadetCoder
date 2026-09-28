package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.commands.ChatCommand;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tests for {@link ParsedAction#toLegacyAction()} argument emission (audit finding 3).
 *
 * <p>The legacy conversion must emit option-style parameters as real flags the target command can
 * parse (e.g. ReadCommand's {@code --limit <n>}/{@code --offset <n>} as separate tokens) and must
 * iterate remaining parameters in a stable, deterministic order rather than relying on HashMap
 * iteration order.</p>
 */
public class ParsedActionTest {

    /** Reads the package-private {@code arguments} array from a legacy AIAction via reflection. */
    private String[] argsOf(ChatCommand.AIAction action) throws Exception {
        java.lang.reflect.Field f = ChatCommand.AIAction.class.getDeclaredField("arguments");
        f.setAccessible(true);
        return (String[]) f.get(action);
    }

    @Test
    public void readLimitIsEmittedAsSeparateFlagTokens() throws Exception {
        ParsedAction action = new ParsedAction.Builder("read")
                .addParameter("file_path", "src/Main.java")
                .addParameter("limit", 50)
                .build();

        String[] args = argsOf(action.toLegacyAction());

        // file_path stays positional 0; limit becomes "--limit" "50" (separate tokens, never a bare "50").
        assertThat(args).containsExactly("src/Main.java", "--limit", "50");
    }

    @Test
    public void readLimitAndOffsetBothEmittedAsFlags() throws Exception {
        ParsedAction action = new ParsedAction.Builder("read")
                .addParameter("file_path", "src/Main.java")
                .addParameter("limit", 100)
                .addParameter("offset", 5)
                .build();

        String[] args = argsOf(action.toLegacyAction());

        // file_path stays positional 0; limit/offset are emitted as flag+value pairs (the value
        // appears only immediately after its flag, never as a bare dropped positional).
        java.util.List<String> a = java.util.Arrays.asList(args);
        assertThat(a.get(0)).isEqualTo("src/Main.java");
        assertThat(a.get(a.indexOf("--limit") + 1)).isEqualTo("100");
        assertThat(a.get(a.indexOf("--offset") + 1)).isEqualTo("5");
        // Exactly: file_path + two flag/value pairs — no extra stray token.
        assertThat(args).hasSize(5);
    }

    @Test
    public void readLimitGivenAsStringStillEmittedAsFlag() throws Exception {
        ParsedAction action = new ParsedAction.Builder("read")
                .addParameter("file_path", "a.txt")
                .addParameter("limit", "25")
                .build();

        String[] args = argsOf(action.toLegacyAction());

        assertThat(args).containsExactly("a.txt", "--limit", "25");
    }

    @Test
    public void defaultBranchEmitsRemainingParametersInStableSortedOrder() throws Exception {
        // Use an unknown command (default branch) with deliberately out-of-order insertion.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("zebra", "z");
        params.put("file_path", "f.txt");
        params.put("alpha", "a");
        params.put("mike", "m");

        ParsedAction action = new ParsedAction.Builder("somecustomcmd")
                .setParameters(params)
                .build();

        String[] args = argsOf(action.toLegacyAction());

        // file_path is positional 0; the rest follow in stable (sorted-key) order: alpha, mike, zebra.
        assertThat(args).containsExactly("f.txt", "a", "m", "z");
    }

    @Test
    public void readWithoutLimitEmitsOnlyFilePath() throws Exception {
        ParsedAction action = new ParsedAction.Builder("read")
                .addParameter("file_path", "only.txt")
                .build();

        String[] args = argsOf(action.toLegacyAction());

        assertThat(args).containsExactly("only.txt");
    }

    @Test
    public void readWithFilesListUsesFirstPath_andEmitsNoStrayListToken() throws Exception {
        // The shape that broke the agent loop: a "files" array instead of a "file_path". getFilePath
        // must read the first element, and the conversion must emit exactly that one positional — never
        // a "[README.md, COMMAND_REFERENCE.md]" blob and never the list re-appended as extra args.
        ParsedAction action = new ParsedAction.Builder("read")
                .addParameter("files", java.util.Arrays.asList("README.md", "COMMAND_REFERENCE.md"))
                .build();

        assertThat(action.getFilePath()).isEqualTo("README.md");
        assertThat(action.requiresFileAccess()).isTrue();

        String[] args = argsOf(action.toLegacyAction());
        assertThat(args).containsExactly("README.md");
    }

    @Test
    public void readWithFileScalarAliasIsHonoured() throws Exception {
        ParsedAction action = new ParsedAction.Builder("read")
                .addParameter("file", "src/App.java")
                .build();

        assertThat(action.getFilePath()).isEqualTo("src/App.java");

        String[] args = argsOf(action.toLegacyAction());
        assertThat(args).containsExactly("src/App.java");
    }
}
