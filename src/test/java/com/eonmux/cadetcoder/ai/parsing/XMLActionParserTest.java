package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.commands.ChatCommand;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The XML parser is a STRUCTURED strategy, so what it returns is taken as authoritative.
 *
 * <p>Its element extractor ran {@code <(\w+)>(.*?)</\1>} over the whole {@code <action>…</action>}
 * match. With a backreference and a lazy body, the first thing that matches is the {@code <action>}
 * wrapper itself, and matching then resumes past it -- so exactly one element was ever found, named
 * "action", and {@code elements.get("command")} was always null. The structured branch could never
 * succeed. Every such response fell through to the prose-guessing path, which reads parameters from
 * the USER REQUEST rather than from the XML, and because this strategy is classified as structured
 * the result was dispatched rather than marked as a guess.</p>
 */
public class XMLActionParserTest {

    private static ParsedResponse parse(String response) {
        return new XMLActionParser().parse(
                response, new ParsingContext.Builder("fix the bug").build());
    }

    private static List<String> argsOf(ParsedAction action) throws Exception {
        ChatCommand.AIAction legacy = action.toLegacyAction();
        Field field = ChatCommand.AIAction.class.getDeclaredField("arguments");
        field.setAccessible(true);
        return Arrays.asList((String[]) field.get(legacy));
    }

    /** The class's own documented example, from {@code getExampleFormats()}. */
    @Test
    public void theStructuredFormItDocumentsProducesTheActionItDescribes() throws Exception {
        ParsedResponse response = parse(
                "<action><command>write</command><file_path>output.txt</file_path>"
                + "<content>Hello World</content><reasoning>Create output file</reasoning></action>");

        assertThat(response.getActions())
                .as("the parser's own example format must parse")
                .isNotEmpty();

        ParsedAction action = response.getActions().get(0);
        assertThat(action.getCommand()).isEqualTo("write");
        assertThat(argsOf(action))
                .as("the path and the content are both in the XML and must both survive")
                .contains("output.txt", "Hello World");
    }

    @Test
    public void theFilePathComesFromTheXmlAndNotFromTheUserRequest() throws Exception {
        ParsedResponse response = new XMLActionParser().parse(
                "<action><command>read</command><file_path>src/Chosen.java</file_path>"
                + "<reasoning>inspect it</reasoning></action>",
                // A request naming a DIFFERENT file: the action must not pick this one up.
                new ParsingContext.Builder("please look at src/FromTheRequest.java").build());

        assertThat(response.getActions()).isNotEmpty();
        assertThat(argsOf(response.getActions().get(0)))
                .contains("src/Chosen.java")
                .doesNotContain("src/FromTheRequest.java");
    }

    @Test
    public void aStructuredActionCarriesItsReasoning() {
        ParsedResponse response = parse(
                "<action><command>ls</command><path>src</path>"
                + "<reasoning>list the sources</reasoning></action>");

        assertThat(response.getActions()).isNotEmpty();
        assertThat(response.getActions().get(0).getReasoning()).contains("list the sources");
    }

    @Test
    public void aMultiLineElementBodySurvives() throws Exception {
        ParsedResponse response = parse(
                "<action>\n"
                + "  <command>write</command>\n"
                + "  <file_path>notes.md</file_path>\n"
                + "  <content>first line\nsecond line</content>\n"
                + "  <reasoning>record the notes</reasoning>\n"
                + "</action>");

        assertThat(response.getActions()).isNotEmpty();
        assertThat(String.join(" ", argsOf(response.getActions().get(0))))
                .contains("first line")
                .contains("second line");
    }

    /**
     * The simple form, where the action is a bare instruction rather than nested elements.
     *
     * <p>Its parameters were inferred entirely from the user's request: the split tokens of the
     * action were computed, checked for emptiness, and then never read. So an action naming one
     * file was dispatched against a different file the user had mentioned earlier.</p>
     */
    @Test
    public void aSimpleActionUsesItsOwnPathNotTheOneInTheRequest() throws Exception {
        ParsedResponse response = new XMLActionParser().parse(
                "<action>read src/Chosen.java</action>",
                new ParsingContext.Builder("there is a bug in src/FromTheRequest.java").build());

        assertThat(response.getActions()).isNotEmpty();
        assertThat(argsOf(response.getActions().get(0)))
                .as("the model asked for one file; it must not be given another")
                .contains("src/Chosen.java")
                .doesNotContain("src/FromTheRequest.java");
    }

    @Test
    public void aSimpleActionFallsBackToTheRequestWhenItNamesNoPath() throws Exception {
        ParsedResponse response = new XMLActionParser().parse(
                "<action>read the file</action>",
                new ParsingContext.Builder("please look at src/FromTheRequest.java").build());

        assertThat(response.getActions()).isNotEmpty();
        assertThat(argsOf(response.getActions().get(0)))
                .as("with nothing in the action, the request is still the best guess available")
                .contains("src/FromTheRequest.java");
    }

    @Test
    public void proseWithNoActionAtAllProducesNothingToDispatch() {
        ParsedResponse response = parse("I had a look and everything seems fine.");

        assertThat(response.getActions()).isEmpty();
    }
}
