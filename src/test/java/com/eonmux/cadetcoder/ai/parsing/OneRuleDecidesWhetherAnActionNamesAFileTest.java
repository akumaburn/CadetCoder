package com.eonmux.cadetcoder.ai.parsing;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether an action names a file cannot depend on the notation the model happened to write it in.
 *
 * <h2>The defect</h2>
 *
 * <p>Four parsing strategies each kept a private copy of "which commands need a file, and which
 * parameter names count as one", and the four copies disagreed. A pathless {@code multiread} was
 * refused by the action-block parser and accepted by the XML and JSON parsers; a pathless
 * {@code multiedit} was refused by three and accepted by the semantic parser; and an action whose
 * path arrived under {@code filename} was reported as missing its parameters by the XML parser even
 * though {@link ParsedAction#getFilePath()} -- the very method the dispatcher calls before running
 * it -- read the path without difficulty. All four strategies hand their actions to the same
 * dispatcher, so the same reply was judged by a different rule depending on which strategy got to
 * it first.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the rule is {@link FilePathRule} and nothing else: the commands it lists, the keys it
 * accepts (by asking the dispatcher rather than by keeping a list beside it), and the agreement of
 * the notations on the actions where the copies used to differ. Each strategy keeps its own verdict
 * -- the inferring parsers say REQUIRES_INFERENCE where the strict ones say INVALID_PARAMETERS --
 * because what to do about a missing path is a strategy's own business. Whether one is missing is
 * not.</p>
 */
public class OneRuleDecidesWhetherAnActionNamesAFileTest {

    /** Commands that cannot be run without something to run them on. */
    private static final List<String> NEEDS_A_FILE =
            List.of("read", "write", "edit", "multiedit", "multiread", "analyze", "explain");

    /** Commands that are perfectly runnable with no file named at all. */
    private static final List<String> NEEDS_NO_FILE =
            List.of("grep", "bash", "ls", "search", "glob", "git", "help");

    private static ParsingContext context() {
        return new ParsingContext.Builder("do the thing").build();
    }

    private static Map<String, Object> parameters(String key, Object value) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(key, value);
        return parameters;
    }

    /** The verdict a strategy reached on the first action it found, or {@code null} if it found none. */
    private static ParsedAction.ValidationResult verdict(ParsedResponse response) {
        return response.getActions().isEmpty() ? null
                                               : response.getActions().get(0).getValidation();
    }

    private static ParsedResponse asXml(String command, String elements) {
        return new XMLActionParser().parse(
                "<action><command>" + command + "</command>" + elements
                + "<reasoning>because</reasoning></action>", context());
    }

    private static ParsedResponse asJson(String body) {
        return new JSONSchemaParser().parse(body, context());
    }

    private static ParsedResponse asBlock(String command, String args) {
        return new ActionBlockParser().parse(
                "ACTION_START\nCOMMAND: " + command + "\nARGS: " + args + "\nREASON: because\nACTION_END",
                context());
    }

    @Test
    public void everyCommandThatCannotRunWithoutAFileIsAskedForOne() {
        for (String command : NEEDS_A_FILE) {
            assertThat(FilePathRule.requiresPath(command))
                    .as("%s cannot be run without a file", command)
                    .isTrue();
        }
    }

    @Test
    public void aCommandThatRunsWithoutAFileIsNotAskedForOne() {
        for (String command : NEEDS_NO_FILE) {
            assertThat(FilePathRule.requiresPath(command))
                    .as("%s runs perfectly well with no file named", command)
                    .isFalse();
        }
    }

    @Test
    public void theCommandIsRecognisedHoweverTheModelCasedOrPaddedIt() {
        assertThat(FilePathRule.requiresPath("  READ ")).isTrue();
        assertThat(FilePathRule.requiresPath("MultiEdit")).isTrue();
        assertThat(FilePathRule.requiresPath(null))
                .as("an action with no command at all is not an action missing a path")
                .isFalse();
    }

    @Test
    public void aPathIsFoundUnderEveryKeyTheDispatcherWouldRead() {
        for (String key : List.of("file_path", "filePath", "FILE-PATH", "filepath",
                                  "path", "filename", "file")) {
            assertThat(FilePathRule.isSatisfiedBy(parameters(key, "src/Main.java")))
                    .as("the dispatcher reads a path written as %s, so the rule must see one", key)
                    .isTrue();
        }
        for (String key : List.of("files", "paths")) {
            assertThat(FilePathRule.isSatisfiedBy(parameters(key, List.of("src/Main.java"))))
                    .as("the dispatcher reads the first element of %s", key)
                    .isTrue();
        }
    }

    @Test
    public void aPathTheDispatcherCouldNotUseIsNotAPath() {
        assertThat(FilePathRule.isSatisfiedBy(new HashMap<>())).isFalse();
        assertThat(FilePathRule.isSatisfiedBy(parameters("file_path", "   "))).isFalse();
        assertThat(FilePathRule.isSatisfiedBy(parameters("files", List.of()))).isFalse();
        assertThat(FilePathRule.isSatisfiedBy(parameters("file_path", parameters("old", "new"))))
                .as("a structure is not something a path-shaped command arm can use")
                .isFalse();
        assertThat(FilePathRule.isSatisfiedBy(parameters("pattern", "TODO")))
                .as("a parameter that is not a path does not stand in for one")
                .isFalse();
    }

    @Test
    public void theRuleAgreesWithTheDispatcherItAsks() {
        Map<String, Object> named = parameters("filename", "src/Main.java");

        assertThat(new ParsedAction("read", named, "because").getFilePath())
                .as("the dispatcher reads this path")
                .isEqualTo("src/Main.java");
        assertThat(FilePathRule.isSatisfiedBy(named))
                .as("so no parser may report the same action as having none")
                .isTrue();
    }

    @Test
    public void aPathlessMultireadIsRefusedWhateverNotationItArrivedIn() {
        assertThat(verdict(asXml("multiread", "")))
                .isEqualTo(ParsedAction.ValidationResult.REQUIRES_INFERENCE);
        assertThat(verdict(asJson("{\"action\":\"multiread\",\"reasoning\":\"because\"}")))
                .isEqualTo(ParsedAction.ValidationResult.INVALID_PARAMETERS);
        assertThat(verdict(asBlock("multiread", "")))
                .isEqualTo(ParsedAction.ValidationResult.INVALID_PARAMETERS);
    }

    @Test
    public void aPathlessMultieditIsRefusedWhateverNotationItArrivedIn() {
        assertThat(verdict(asXml("multiedit", "")))
                .isEqualTo(ParsedAction.ValidationResult.REQUIRES_INFERENCE);
        assertThat(verdict(asJson("{\"action\":\"multiedit\",\"reasoning\":\"because\"}")))
                .isEqualTo(ParsedAction.ValidationResult.INVALID_PARAMETERS);
        assertThat(verdict(asBlock("multiedit", "")))
                .isEqualTo(ParsedAction.ValidationResult.INVALID_PARAMETERS);
    }

    @Test
    public void aPathlessReadIsRefusedWhateverNotationItArrivedIn() {
        assertThat(verdict(asXml("read", "")))
                .isEqualTo(ParsedAction.ValidationResult.REQUIRES_INFERENCE);
        assertThat(verdict(asJson("{\"action\":\"read\",\"reasoning\":\"because\"}")))
                .isEqualTo(ParsedAction.ValidationResult.INVALID_PARAMETERS);
        assertThat(verdict(asBlock("read", "")))
                .isEqualTo(ParsedAction.ValidationResult.INVALID_PARAMETERS);
    }

    @Test
    public void anActionThatNamesItsFileIsValidWhateverNotationItArrivedIn() {
        assertThat(verdict(asXml("read", "<file_path>src/Main.java</file_path>")))
                .isEqualTo(ParsedAction.ValidationResult.VALID);
        assertThat(verdict(asJson("{\"action\":\"read\",\"file_path\":\"src/Main.java\"}")))
                .isEqualTo(ParsedAction.ValidationResult.VALID);
        assertThat(verdict(asBlock("read", "src/Main.java")))
                .isEqualTo(ParsedAction.ValidationResult.VALID);
    }

    /**
     * The XML parser leaves {@code <filename>} under the name the model wrote, and its private key
     * list did not contain it -- so the action was marked as needing inference while the path sat in
     * its parameters, readable by the method the dispatcher calls.
     */
    @Test
    public void aPathUnderAKeyTheModelChoseIsNotReportedAsMissing() {
        ParsedResponse response = asXml("read", "<filename>src/Main.java</filename>");

        assertThat(response.getActions()).isNotEmpty();
        ParsedAction action = response.getActions().get(0);

        assertThat(action.getFilePath()).isEqualTo("src/Main.java");
        assertThat(action.getValidation())
                .as("the path is right there, so the action is not missing one")
                .isEqualTo(ParsedAction.ValidationResult.VALID);
    }
}
