package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.commands.ChatCommand;
import com.eonmux.cadetcoder.commands.MultiEditCommand;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An {@code edit} action that names the text to replace is a replacement, not a request.
 *
 * <p>{@code EditCommand} joins everything it is given into ONE prose string and asks the model for
 * SEARCH/REPLACE blocks. The {@code edit} arm of {@link ParsedAction#toLegacyAction()} emitted the
 * leftover parameters in alphabetical key order, so {@code new_string} arrived before
 * {@code old_string} and the request read {@code "Main.java newName oldName"} -- the replacement
 * stated backwards, with nothing to say which word was which. The model had already supplied an
 * exact, deterministic edit and it was handed back to a model to be guessed at again.</p>
 *
 * <p>{@code multiedit} parses exactly this structure deterministically, so that is where such an
 * action goes. The assertions run the emitted argument through {@code MultiEditCommand}'s own block
 * grammar, so the producer and the consumer are checked against each other.</p>
 */
public class DeterministicEditReachesTheEditorTest {

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

    /** Parses the emitted instruction with the command that will actually receive it. */
    private static List<?> operationsFrom(String instruction) throws Exception {
        MultiEditCommand command = new MultiEditCommand();
        Method contains = MultiEditCommand.class.getDeclaredMethod(
                "containsStructuredEditBlocks", String.class);
        contains.setAccessible(true);
        assertThat((Boolean) contains.invoke(command, instruction))
                .as("multiedit must recognise this as a deterministic edit rather than prose")
                .isTrue();

        Method parse = MultiEditCommand.class.getDeclaredMethod(
                "parseStructuredEditBlocks", String.class);
        parse.setAccessible(true);
        return (List<?>) parse.invoke(command, instruction);
    }

    private static String stringField(Object operation, String name) throws Exception {
        Field field = operation.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(operation);
    }

    private static boolean replaceAllOf(Object operation) throws Exception {
        Field field = operation.getClass().getDeclaredField("replaceAll");
        field.setAccessible(true);
        return (Boolean) field.get(operation);
    }

    @Test
    public void anEditNamingTheTextToReplaceIsAppliedRatherThanReDerived() throws Exception {
        ChatCommand.AIAction legacy = new ParsedAction.Builder("edit")
                .addParameter("file_path", "src/Main.java")
                .addParameter("old_string", "oldName")
                .addParameter("new_string", "newName")
                .setReasoning("rename the field")
                .build()
                .toLegacyAction();

        assertThat(commandOf(legacy))
                .as("an exact replacement belongs to the command that applies one")
                .isEqualTo("multiedit");
        assertThat(argsOf(legacy)).hasSize(2);
        assertThat(argsOf(legacy).get(0)).isEqualTo("src/Main.java");

        List<?> operations = operationsFrom(argsOf(legacy).get(1));
        assertThat(operations).hasSize(1);
        assertThat(stringField(operations.get(0), "oldString")).isEqualTo("oldName");
        assertThat(stringField(operations.get(0), "newString")).isEqualTo("newName");
    }

    /** The direction of a replacement is the whole of its meaning. */
    @Test
    public void theReplacementIsNotStatedBackwards() throws Exception {
        ChatCommand.AIAction legacy = new ParsedAction.Builder("edit")
                .addParameter("file_path", "src/Main.java")
                // Declared new-before-old, and alphabetically new sorts first either way: neither
                // order may decide which string is searched for.
                .addParameter("new_string", "bbb")
                .addParameter("old_string", "aaa")
                .setReasoning("swap them")
                .build()
                .toLegacyAction();

        List<?> operations = operationsFrom(argsOf(legacy).get(1));
        assertThat(stringField(operations.get(0), "oldString")).isEqualTo("aaa");
        assertThat(stringField(operations.get(0), "newString")).isEqualTo("bbb");
    }

    @Test
    public void aReplaceAllFlagSurvives() throws Exception {
        ChatCommand.AIAction legacy = new ParsedAction.Builder("edit")
                .addParameter("file_path", "src/Main.java")
                .addParameter("old_string", "a")
                .addParameter("new_string", "b")
                .addParameter("replace_all", true)
                .setReasoning("every occurrence")
                .build()
                .toLegacyAction();

        List<?> operations = operationsFrom(argsOf(legacy).get(1));
        assertThat(replaceAllOf(operations.get(0)))
                .as("the model asked for every occurrence; replacing one is a different edit")
                .isTrue();
    }

    @Test
    public void theCamelCaseSpellingWorksToo() throws Exception {
        ChatCommand.AIAction legacy = new ParsedAction.Builder("edit")
                .addParameter("filePath", "src/Main.java")
                .addParameter("oldText", "alpha")
                .addParameter("newText", "beta")
                .setReasoning("rename")
                .build()
                .toLegacyAction();

        assertThat(commandOf(legacy)).isEqualTo("multiedit");
        List<?> operations = operationsFrom(argsOf(legacy).get(1));
        assertThat(stringField(operations.get(0), "oldString")).isEqualTo("alpha");
        assertThat(stringField(operations.get(0), "newString")).isEqualTo("beta");
    }

    /** A multi-line replacement is the common case for real code. */
    @Test
    public void aMultiLineReplacementSurvives() throws Exception {
        ChatCommand.AIAction legacy = new ParsedAction.Builder("edit")
                .addParameter("file_path", "src/Main.java")
                .addParameter("old_string", "if (a) {\n  b();\n}")
                .addParameter("new_string", "if (a) {\n  c();\n}")
                .setReasoning("call c instead")
                .build()
                .toLegacyAction();

        List<?> operations = operationsFrom(argsOf(legacy).get(1));
        assertThat(stringField(operations.get(0), "oldString")).isEqualTo("if (a) {\n  b();\n}");
        assertThat(stringField(operations.get(0), "newString")).isEqualTo("if (a) {\n  c();\n}");
    }

    /** An edit that only describes what it wants is still the AI editor's job. */
    @Test
    public void aProseEditRequestStillGoesToTheAiEditor() throws Exception {
        ChatCommand.AIAction legacy = new ParsedAction.Builder("edit")
                .addParameter("file_path", "src/Main.java")
                .addParameter("request", "add null checks to the constructor")
                .setReasoning("harden it")
                .build()
                .toLegacyAction();

        assertThat(commandOf(legacy))
                .as("nothing here names an exact replacement, so the model must derive one")
                .isEqualTo("edit");
        assertThat(argsOf(legacy))
                .containsExactly("src/Main.java", "add null checks to the constructor");
    }

    /** An edit with no old text names no replacement, however the new text is spelled. */
    @Test
    public void newTextWithoutOldTextIsNotADeterministicEdit() throws Exception {
        ChatCommand.AIAction legacy = new ParsedAction.Builder("edit")
                .addParameter("file_path", "src/Main.java")
                .addParameter("new_string", "b")
                .setReasoning("no idea what to replace")
                .build()
                .toLegacyAction();

        assertThat(commandOf(legacy)).isEqualTo("edit");
    }

    /** The sibling verbs sharing the arm keep their own shape. */
    @Test
    public void refactorIsUnaffected() throws Exception {
        ChatCommand.AIAction legacy = new ParsedAction.Builder("refactor")
                .addParameter("file_path", "src/Main.java")
                .addParameter("instructions", "extract a method")
                .setReasoning("tidy it")
                .build()
                .toLegacyAction();

        assertThat(commandOf(legacy)).isEqualTo("refactor");
        assertThat(argsOf(legacy)).containsExactly("src/Main.java", "extract a method");
    }
}
