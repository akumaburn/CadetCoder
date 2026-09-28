package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.commands.CommandAliases;
import org.junit.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The check that a verb names a command asks the same question the dispatcher does.
 *
 * <p><b>The defect</b>: {@link CommandAliases} exists so that {@code show}, {@code create},
 * {@code list}, {@code execute} and a leading slash reach the command they mean -- and every parser
 * screened the verb against the raw registry keys before any of that happened. So a model writing
 * {@code COMMAND: show} had its action marked INVALID_COMMAND and dropped, and the turn became a
 * "your response could not be parsed" correction for a response that was perfectly well formed and
 * would have dispatched correctly. The catalog's own line -- "a leading slash is tolerated but
 * unnecessary" -- was false at the same time: {@code COMMAND: /read} was refused too.</p>
 */
public class AsynonymSurvivesTheCheckThatItIsAcommandTest {

    private static ParsingContext contextKnowing(String... commands) {
        return new ParsingContext.Builder("do the work")
                .setAvailableCommands(Set.of(commands))
                .build();
    }

    @Test
    public void asynonymCountsAsTheCommandItMeans() {
        ParsingContext context = contextKnowing("read", "write", "edit", "ls", "bash");

        assertThat(context.isCommandAvailable("show")).isTrue();
        assertThat(context.isCommandAvailable("create")).isTrue();
        assertThat(context.isCommandAvailable("list")).isTrue();
        assertThat(context.isCommandAvailable("execute")).isTrue();
    }

    @Test
    public void aleadingSlashIsToleratedAsTheCatalogSaysItIs() {
        ParsingContext context = contextKnowing("read", "ls");

        assertThat(context.isCommandAvailable("/read")).isTrue();
        assertThat(context.isCommandAvailable("/ls")).isTrue();
        assertThat(context.isCommandAvailable("  /READ  ")).isTrue();
    }

    @Test
    public void averbThatNamesNothingIsStillRefused() {
        ParsingContext context = contextKnowing("read", "ls");

        assertThat(context.isCommandAvailable("frobnicate")).isFalse();
        assertThat(context.isCommandAvailable("write")).isFalse();
        assertThat(context.isCommandAvailable(null)).isFalse();
    }

    /** End to end: a parsed action written with a synonym is valid and dispatches to the command. */
    @Test
    public void anActionWrittenWithAsynonymIsAcceptedAndDispatched() {
        ParsingContext context = new ParsingContext.Builder("do the work")
                .setAvailableCommands(new CommandRegistry().getCommands().keySet())
                .build();

        ParsedResponse response = new ActionBlockParser().parse(
                "ACTION_START\n"
                + "COMMAND: show\n"
                + "ARGS: src/main/java/Example.java\n"
                + "REASON: read the file\n"
                + "ACTION_END", context);

        assertThat(response.getActions()).hasSize(1);
        ParsedAction action = response.getActions().get(0);
        assertThat(action.getValidation()).isEqualTo(ParsedAction.ValidationResult.VALID);
        assertThat(action.dispatchedCommand()).isEqualTo("read");
    }

    /** Every alias the dispatcher knows is a command the screen accepts. */
    @Test
    public void everyAliasPassesTheScreen() {
        ParsingContext context = new ParsingContext.Builder("do the work")
                .setAvailableCommands(new CommandRegistry().getCommands().keySet())
                .build();

        for (String alias : CommandAliases.aliases()) {
            assertThat(context.isCommandAvailable(alias))
                    .as("alias '%s' dispatches to '%s' but the screen refuses it",
                        alias, CommandAliases.canonicalize(alias))
                    .isTrue();
        }
    }
}
