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
 * A model that emits its edits as JSON objects must get those edits applied.
 *
 * <p>{@code multiedit} is the only command whose natural JSON argument is a list of OBJECTS:
 * {@code "edits": [{"old_string": "a", "new_string": "b"}]}. {@code JSONSchemaParser.convertValue}
 * turned every array into a {@code List<String>} by calling {@code asText()} on each element -- and
 * Jackson's {@code asText()} on a container node is the empty string. So the edits arrived as a list
 * of blanks, {@code asString} dropped them for being blank, and the dispatched command was
 * {@code multiedit <file>} with no edits at all: the iterative loop then asked the model "what edits
 * would you like to make to this file?", which it had just answered.</p>
 *
 * <p>The assertions run the emitted argument through {@code MultiEditCommand}'s own block grammar,
 * so the producer and the consumer are checked against each other rather than against a format
 * written down twice.</p>
 */
public class StructuredEditsSurviveJsonTest {

    private final JSONSchemaParser parser = new JSONSchemaParser();

    private List<String> argsFor(String response) throws Exception {
        ParsedResponse parsed = parser.parse(response, new ParsingContext.Builder("rename it").build());
        assertThat(parsed.getActions()).as("the response carries a multiedit action").isNotEmpty();

        ChatCommand.AIAction legacy = parsed.getActions().get(0).toLegacyAction();
        assertThat(fieldOf(legacy, "command")).isEqualTo("multiedit");
        return Arrays.asList((String[]) fieldOf(legacy, "arguments"));
    }

    private static Object fieldOf(ChatCommand.AIAction action, String name) throws Exception {
        Field field = ChatCommand.AIAction.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(action);
    }

    /** Parses the emitted instruction with the command that will actually receive it. */
    @SuppressWarnings("unchecked")
    private List<?> operationsFrom(String instruction) throws Exception {
        MultiEditCommand command = new MultiEditCommand();
        Method contains = MultiEditCommand.class.getDeclaredMethod(
                "containsStructuredEditBlocks", String.class);
        contains.setAccessible(true);
        assertThat((Boolean) contains.invoke(command, instruction))
                .as("multiedit must recognise this as a deterministic edit request rather than "
                    + "sending it back to the model as prose")
                .isTrue();

        Method parse = MultiEditCommand.class.getDeclaredMethod(
                "parseStructuredEditBlocks", String.class);
        parse.setAccessible(true);
        return (List<?>) parse.invoke(command, instruction);
    }

    private static String oldStringOf(Object operation) throws Exception {
        Field field = operation.getClass().getDeclaredField("oldString");
        field.setAccessible(true);
        return (String) field.get(operation);
    }

    private static String newStringOf(Object operation) throws Exception {
        Field field = operation.getClass().getDeclaredField("newString");
        field.setAccessible(true);
        return (String) field.get(operation);
    }

    private static boolean replaceAllOf(Object operation) throws Exception {
        Field field = operation.getClass().getDeclaredField("replaceAll");
        field.setAccessible(true);
        return (Boolean) field.get(operation);
    }

    @Test
    public void everyEditInTheArrayReachesTheCommand() throws Exception {
        List<String> args = argsFor(
                "{\"action\": \"multiedit\", \"parameters\": {"
                + "\"file_path\": \"src/Main.java\","
                + "\"edits\": ["
                + "  {\"old_string\": \"oldName\", \"new_string\": \"newName\"},"
                + "  {\"old_string\": \"int x\", \"new_string\": \"long x\"}"
                + "]}}");

        assertThat(args.get(0)).isEqualTo("src/Main.java");
        assertThat(args).as("the edits must be carried, not dropped for being unreadable").hasSize(2);

        List<?> operations = operationsFrom(args.get(1));
        assertThat(operations).hasSize(2);
        assertThat(oldStringOf(operations.get(0))).isEqualTo("oldName");
        assertThat(newStringOf(operations.get(0))).isEqualTo("newName");
        assertThat(oldStringOf(operations.get(1))).isEqualTo("int x");
        assertThat(newStringOf(operations.get(1))).isEqualTo("long x");
    }

    @Test
    public void aPerEditReplaceAllFlagIsHonoured() throws Exception {
        List<String> args = argsFor(
                "{\"action\": \"multiedit\", \"parameters\": {"
                + "\"file_path\": \"src/Main.java\","
                + "\"edits\": [{\"old_string\": \"a\", \"new_string\": \"b\", \"replace_all\": true}]}}");

        List<?> operations = operationsFrom(args.get(1));
        assertThat(operations).hasSize(1);
        assertThat(replaceAllOf(operations.get(0)))
                .as("the model asked for every occurrence; replacing one is a different edit")
                .isTrue();
    }

    @Test
    public void theCamelCaseSpellingWorksToo() throws Exception {
        List<String> args = argsFor(
                "{\"action\": \"multiedit\", \"parameters\": {"
                + "\"file_path\": \"src/Main.java\","
                + "\"edits\": [{\"oldText\": \"alpha\", \"newText\": \"beta\"}]}}");

        List<?> operations = operationsFrom(args.get(1));
        assertThat(operations).hasSize(1);
        assertThat(oldStringOf(operations.get(0))).isEqualTo("alpha");
        assertThat(newStringOf(operations.get(0))).isEqualTo("beta");
    }

    @Test
    public void aMultiLineEditSurvives() throws Exception {
        List<String> args = argsFor(
                "{\"action\": \"multiedit\", \"parameters\": {"
                + "\"file_path\": \"src/Main.java\","
                + "\"edits\": [{\"old_string\": \"if (a) {\\n  b();\\n}\","
                + " \"new_string\": \"if (a) {\\n  c();\\n}\"}]}}");

        List<?> operations = operationsFrom(args.get(1));
        assertThat(operations).hasSize(1);
        assertThat(oldStringOf(operations.get(0))).isEqualTo("if (a) {\n  b();\n}");
        assertThat(newStringOf(operations.get(0))).isEqualTo("if (a) {\n  c();\n}");
    }

    /** A model that writes the block format itself must not have it rewritten underneath it. */
    @Test
    public void anAlreadyStructuredEditStringIsPassedThroughUntouched() throws Exception {
        String block = "EDIT_START\\nOLD: a\\nNEW: b\\nREPLACE_ALL: false\\nEDIT_END";
        List<String> args = argsFor(
                "{\"action\": \"multiedit\", \"parameters\": {"
                + "\"file_path\": \"src/Main.java\", \"edits\": \"" + block + "\"}}");

        List<?> operations = operationsFrom(args.get(1));
        assertThat(operations).hasSize(1);
        assertThat(oldStringOf(operations.get(0))).isEqualTo("a");
    }

    /** Structure is preserved generally, not special-cased for one command. */
    @Test
    public void aNestedObjectParameterIsNotFlattenedToAnEmptyString() {
        ParsedResponse parsed = parser.parse(
                "{\"action\": \"read\", \"parameters\": {\"file_path\": \"a.java\","
                + " \"options\": {\"limit\": 10}}}",
                new ParsingContext.Builder("read it").build());

        assertThat(parsed.getActions()).isNotEmpty();
        Object options = parsed.getActions().get(0).getParameters().get("options");
        assertThat(options)
                .as("a JSON object is a map; rendering it as \"\" discards what the model said")
                .isInstanceOf(java.util.Map.class);
    }
}
