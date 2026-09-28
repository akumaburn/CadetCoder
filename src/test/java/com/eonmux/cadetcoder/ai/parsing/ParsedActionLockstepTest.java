package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.commands.ChatCommand;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the catalog/parser lockstep: {@link ParsedAction#toLegacyAction()} must emit the exact CLI
 * argument shape each command's parser accepts (and the catalog advertises), so a model following
 * the advertised interface is dispatched correctly. Each case here corresponds to a verified review
 * finding (glob --path, grep flags, suggest type-first, write content/force, ls flags, todowrite
 * subcommand, webfetch url-first, read range).
 */
public class ParsedActionLockstepTest {

    private String[] argsOf(ChatCommand.AIAction action) throws Exception {
        Field f = ChatCommand.AIAction.class.getDeclaredField("arguments");
        f.setAccessible(true);
        return (String[]) f.get(action);
    }

    private String commandOf(ChatCommand.AIAction action) throws Exception {
        Field f = ChatCommand.AIAction.class.getDeclaredField("command");
        f.setAccessible(true);
        return (String) f.get(action);
    }

    @Test
    public void glob_emitsPathAsFlagNotBarePositional() throws Exception {
        ParsedAction action = new ParsedAction.Builder("glob")
                .addParameter("pattern", "**/*.java")
                .addParameter("path", "/project")
                .build();
        String[] args = argsOf(action.toLegacyAction());
        assertThat(args).containsExactly("**/*.java", "--path=/project");
    }

    @Test
    public void grep_emitsEveryAdvertisedFlag() throws Exception {
        ParsedAction action = new ParsedAction.Builder("grep")
                .addParameter("pattern", "class Foo")
                .addParameter("path", "/src")
                .addParameter("include", "*.java")
                .addParameter("exclude", "*Test.java")
                .addParameter("ignore_case", true)
                .addParameter("line_number", true)
                .build();
        String[] args = argsOf(action.toLegacyAction());
        assertThat(args[0]).isEqualTo("class Foo");
        assertThat(Arrays.asList(args))
                .contains("--include=*.java", "--exclude=*Test.java", "--path=/src",
                        "--ignore-case", "--line-number");
    }

    @Test
    public void grep_emitsExtendedFlags() throws Exception {
        // The extended grep flags (whole-word/whole-line/case-sensitive/max-depth) must round-trip from
        // ParsedAction parameters to the exact CLI shape GrepCommand accepts, in lockstep with the parser
        // that can now capture them.
        ParsedAction action = new ParsedAction.Builder("grep")
                .addParameter("pattern", "foo")
                .addParameter("word", true)
                .addParameter("line", true)
                .addParameter("case-sensitive", true)
                .addParameter("max-depth", "3")
                .build();
        String[] args = argsOf(action.toLegacyAction());
        assertThat(args[0]).isEqualTo("foo");
        assertThat(Arrays.asList(args))
                .contains("--word", "--line", "--case-sensitive", "--max-depth=3");
    }

    @Test
    public void suggest_emitsTypeFirstThenPath() throws Exception {
        ParsedAction action = new ParsedAction.Builder("suggest")
                .addParameter("type", "tests")
                .addParameter("file_path", "src/Main.java")
                .build();
        String[] args = argsOf(action.toLegacyAction());
        assertThat(args).containsExactly("tests", "src/Main.java");
    }

    @Test
    public void write_emitsPathContentAndForceFlag() throws Exception {
        ParsedAction action = new ParsedAction.Builder("write")
                .addParameter("file_path", "out.txt")
                .addParameter("content", "line one\n  indented")
                .addParameter("force", true)
                .build();
        String[] args = argsOf(action.toLegacyAction());
        assertThat(args).containsExactly("out.txt", "line one\n  indented", "-f");
    }

    @Test
    public void ls_emitsFlags() throws Exception {
        ParsedAction action = new ParsedAction.Builder("ls")
                .addParameter("path", "src")
                .addParameter("recursive", true)
                .addParameter("max_depth", 2)
                .build();
        String[] args = argsOf(action.toLegacyAction());
        assertThat(args[0]).isEqualTo("src");
        assertThat(Arrays.asList(args)).contains("-R", "--max-depth", "2");
    }

    @Test
    public void todowrite_emitsSubcommandContentAndFlags() throws Exception {
        ParsedAction action = new ParsedAction.Builder("todowrite")
                .addParameter("action", "add")
                .addParameter("content", "write tests")
                .addParameter("priority", "high")
                .build();
        String[] args = argsOf(action.toLegacyAction());
        assertThat(args).containsExactly("add", "write tests", "-p", "high");
    }

    @Test
    public void webfetch_emitsUrlFirstThenPrompt() throws Exception {
        ParsedAction action = new ParsedAction.Builder("webfetch")
                .addParameter("url", "https://example.com")
                .addParameter("prompt", "summarize the page")
                .build();
        String[] args = argsOf(action.toLegacyAction());
        assertThat(args).containsExactly("https://example.com", "summarize the page");
    }

    @Test
    public void read_emitsRangeFlagsAsSeparateTokens() throws Exception {
        ParsedAction action = new ParsedAction.Builder("read")
                .addParameter("file_path", "big.log")
                .addParameter("limit", 100)
                .addParameter("offset", 50)
                .build();
        String[] args = argsOf(action.toLegacyAction());
        assertThat(args).containsExactly("big.log", "--limit", "100", "--offset", "50");
    }

    @Test
    public void executeSynonym_stillCanonicalizesToBash() throws Exception {
        ParsedAction action = new ParsedAction.Builder("run")
                .addParameter("command", "ls -la")
                .build();
        assertThat(commandOf(action.toLegacyAction())).isEqualTo("bash");
    }
}
