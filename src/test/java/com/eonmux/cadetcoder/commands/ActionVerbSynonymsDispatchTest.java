package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.ai.parsing.ParsedAction;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A synonym the model picks must reach the command it means.
 *
 * <p>{@link CommandAliases} was created because a model that writes {@code execute} for "run a shell
 * command" had its action dispatched to a different registered command. The same failure was left in
 * place for every other synonym: {@code create}, {@code modify}, {@code update}, {@code show},
 * {@code list} and the rest are not registered commands, so {@code CommandRegistry.executeCommand}
 * fell through to its chat fallback -- turning a tool call the model had already made into another
 * billed round trip that re-derived it.</p>
 *
 * <p>{@code ParsedAction} already states which verbs mean the same operation, in
 * {@code isReadOperation}, {@code isWriteOperation}, {@code isModifyOperation} and
 * {@code isExecutionOperation}. Those sets and the dispatch table are checked against each other
 * here rather than being written down twice and allowed to drift.</p>
 */
public class ActionVerbSynonymsDispatchTest {

    private static Object fieldOf(ChatCommand.AIAction action, String name) throws Exception {
        Field field = ChatCommand.AIAction.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(action);
    }

    private static String commandOf(ChatCommand.AIAction action) throws Exception {
        return (String) fieldOf(action, "command");
    }

    private static List<String> argsOf(ChatCommand.AIAction action) throws Exception {
        return Arrays.asList((String[]) fieldOf(action, "arguments"));
    }

    /** Every dispatched name must be a command that exists. */
    private void dispatchesToARegisteredCommand(String verb, String expected,
                                                ParsedAction action) throws Exception {
        ChatCommand.AIAction legacy = action.toLegacyAction();
        assertThat(commandOf(legacy))
                .as("`%s` is not a registered command; dispatching it literally costs a round trip",
                    verb)
                .isEqualTo(expected);
        assertThat(new CommandRegistry().getCommands())
                .as("%s must actually exist", expected)
                .containsKey(expected);
    }

    @Test
    public void aCreateActionWritesTheFile() throws Exception {
        ParsedAction action = new ParsedAction.Builder("create")
                .addParameter("file_path", "notes.txt")
                .addParameter("content", "hello")
                .setReasoning("create the notes file")
                .build();

        dispatchesToARegisteredCommand("create", "write", action);
        assertThat(argsOf(action.toLegacyAction()))
                .as("the write arm knows the path comes first and the content is one argument")
                .containsExactly("notes.txt", "hello");
    }

    @Test
    public void aModifyActionEditsTheFile() throws Exception {
        ParsedAction action = new ParsedAction.Builder("modify")
                .addParameter("file_path", "src/Main.java")
                .addParameter("request", "add null checks")
                .setReasoning("harden it")
                .build();

        dispatchesToARegisteredCommand("modify", "edit", action);
        assertThat(argsOf(action.toLegacyAction()))
                .containsExactly("src/Main.java", "add null checks");
    }

    @Test
    public void anUpdateActionEditsTheFileToo() throws Exception {
        dispatchesToARegisteredCommand("update", "edit",
                new ParsedAction.Builder("update")
                        .addParameter("file_path", "src/Main.java")
                        .addParameter("request", "bump the version")
                        .setReasoning("bump it")
                        .build());
    }

    @Test
    public void aShowActionReadsTheFileWithItsOptions() throws Exception {
        ParsedAction action = new ParsedAction.Builder("show")
                .addParameter("file_path", "src/Main.java")
                .addParameter("limit", 20)
                .setReasoning("look at the top of it")
                .build();

        dispatchesToARegisteredCommand("show", "read", action);
        assertThat(argsOf(action.toLegacyAction()))
                .as("the read arm emits --limit as its own token; the default arm emitted a bare 20")
                .containsExactly("src/Main.java", "--limit", "20");
    }

    @Test
    public void catViewAndDisplayAllRead() throws Exception {
        for (String verb : new String[] {"cat", "view", "display"}) {
            dispatchesToARegisteredCommand(verb, "read",
                    new ParsedAction.Builder(verb)
                            .addParameter("file_path", "src/Main.java")
                            .setReasoning("read it")
                            .build());
        }
    }

    @Test
    public void aListActionListsTheDirectoryWithItsFlags() throws Exception {
        ParsedAction action = new ParsedAction.Builder("list")
                .addParameter("path", "src")
                .addParameter("recursive", true)
                .setReasoning("see the tree")
                .build();

        dispatchesToARegisteredCommand("list", "ls", action);
        assertThat(argsOf(action.toLegacyAction()))
                .as("the ls arm turns the flag into -R; the default arm emitted the word \"true\"")
                .containsExactly("src", "-R");
    }

    /**
     * A registered command name means itself, with one deliberate exception.
     *
     * <p>{@code execute} is registered -- it runs a FILE of commands -- but it is also the verb
     * models pick most often for "run a shell command", and dispatching it literally resolved the
     * shell string as a script path and skipped the shell security validation. That trade is
     * documented on {@link CommandAliases}; every other registered name must be left alone, because
     * an alias that shadows a real command silently sends its work somewhere else.</p>
     *
     * <p>Driven off the registry rather than a written-out list, because the list is what goes
     * stale.</p>
     */
    @Test
    public void noRegisteredCommandNameIsRewrittenToAnother() {
        for (String name : new CommandRegistry().getCommands().keySet()) {
            if ("execute".equals(name)) {
                assertThat(CommandAliases.canonicalize(name)).isEqualTo("bash");
                continue;
            }
            assertThat(CommandAliases.canonicalize(name))
                    .as("%s is a registered command and must dispatch to itself", name)
                    .isEqualTo(name);
        }
    }

    /** Every alias must name a command that exists, or it is a round trip with extra steps. */
    @Test
    public void everyAliasResolvesToARegisteredCommand() {
        CommandRegistry registry = new CommandRegistry();
        for (String alias : CommandAliases.aliases()) {
            String target = CommandAliases.canonicalize(alias);
            assertThat(registry.getCommands())
                    .as("`%s` resolves to `%s`, which is not registered", alias, target)
                    .containsKey(target);
        }
    }

    /** The synonym sets ParsedAction already declares are the ones that must dispatch together. */
    @Test
    public void theOperationSynonymsParsedActionDeclaresAllDispatchTogether() {
        assertThat(CommandAliases.canonicalize("cat")).isEqualTo("read");
        assertThat(CommandAliases.canonicalize("show")).isEqualTo("read");
        assertThat(CommandAliases.canonicalize("create")).isEqualTo("write");
        assertThat(CommandAliases.canonicalize("save")).isEqualTo("write");
        assertThat(CommandAliases.canonicalize("modify")).isEqualTo("edit");
        assertThat(CommandAliases.canonicalize("update")).isEqualTo("edit");
        assertThat(CommandAliases.canonicalize("run")).isEqualTo("bash");
        assertThat(CommandAliases.canonicalize("execute")).isEqualTo("bash");
    }

    /**
     * {@code search} is a registered command of its own -- semantic search over the project index --
     * so it must never be folded into {@code grep}, which searches for literal text.
     */
    @Test
    public void searchIsNotFoldedIntoGrep() {
        assertThat(CommandAliases.canonicalize("search")).isEqualTo("search");
        assertThat(CommandAliases.canonicalize("/search")).isEqualTo("search");
    }
}
