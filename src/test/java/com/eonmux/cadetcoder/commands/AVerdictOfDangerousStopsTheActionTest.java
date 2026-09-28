package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.parsing.ParsedAction;
import com.eonmux.cadetcoder.ai.parsing.ParsedResponse;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the conversion from a parsed action to a dispatchable one refuses to carry across.
 *
 * <p>The conversion used to read every verdict, log the bad ones, and hand all of them on to be
 * run. An action the parser had already labelled {@code SECURITY_RISK} -- the label it gives a
 * shell line that names {@code rm -rf}, {@code dd}, {@code mkfs} -- was executed with a warning in
 * the log and nothing in the way. Nothing came of it only because the parsing engine drops such an
 * action before the conversion ever sees it, which is a filter in a different class that a later
 * call site is free not to use. These tests hold the refusal where the dispatch is.</p>
 */
public class AVerdictOfDangerousStopsTheActionTest {

    /** The reasoning field is never what these tests are about. */
    private static final String WHY = "because the model asked for it";

    @Test
    public void anActionTheParserCalledASecurityRiskIsNotHandedOnToBeRun() {
        List<ChatCommand.AIAction> dispatched =
                convert(actionJudged("bash", ParsedAction.ValidationResult.SECURITY_RISK));

        assertThat(dispatched).isEmpty();
    }

    @Test
    public void anActionWhoseCommandTheParserDidNotRecogniseIsNotHandedOnToBeRun() {
        List<ChatCommand.AIAction> dispatched =
                convert(actionJudged("frobnicate", ParsedAction.ValidationResult.INVALID_COMMAND));

        assertThat(dispatched).isEmpty();
    }

    @Test
    public void anActionMissingAnArgumentIsStillRunSoThatTheCommandCanSayWhatItNeeded() {
        List<ChatCommand.AIAction> dispatched =
                convert(actionJudged("read", ParsedAction.ValidationResult.INVALID_PARAMETERS));

        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.get(0).command).isEqualTo("read");
    }

    @Test
    public void aDangerousActionDoesNotTakeTheSafeOnesBesideItDown() {
        ParsedResponse response = new ParsedResponse.Builder(
                ParsedResponse.ParseResult.SUCCESS,
                ParsedResponse.ParsingStrategy.ACTION_BLOCK, "")
                .addAction(actionJudged("bash", ParsedAction.ValidationResult.SECURITY_RISK))
                .addAction(actionJudged("read", ParsedAction.ValidationResult.VALID))
                .build();

        List<ChatCommand.AIAction> dispatched = ChatActions.runnable(response, new ChatCommand());

        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.get(0).command).isEqualTo("read");
    }

    private static List<ChatCommand.AIAction> convert(ParsedAction action) {
        return ChatActions.runnable(
                new ParsedResponse.Builder(ParsedResponse.ParseResult.SUCCESS,
                                           ParsedResponse.ParsingStrategy.ACTION_BLOCK, "")
                        .addAction(action)
                        .build(), new ChatCommand());
    }

    /** An action the parser has already reached a verdict on, whatever the command happens to be. */
    private static ParsedAction actionJudged(String command, ParsedAction.ValidationResult verdict) {
        return new ParsedAction(command, Map.of(), WHY, 1.0, verdict, null);
    }
}
