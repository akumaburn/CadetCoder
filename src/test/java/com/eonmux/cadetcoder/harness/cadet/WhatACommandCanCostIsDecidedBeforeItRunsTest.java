package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.harness.env.Reversibility;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The commit gate reads one fact about an action and nothing else, so that fact has to be right
 * before the action runs.
 *
 * <h2>The defect these lock out</h2>
 *
 * <p>A classification derived from what a command turned out to do is derived too late: by then it
 * has done it. So the answer comes from the command's name, and from the shell line when the command
 * is a shell. That leaves two ways to be wrong. Calling something free when it is not lets an
 * uncertified guess push a branch; calling everything expensive makes the gate demand a certificate
 * to read a file, and a run that has to certify a theory before looking at anything never gets far
 * enough to have one. Only the commands that provably read and nothing else are free.</p>
 */
class WhatACommandCanCostIsDecidedBeforeItRunsTest {

    /** No arguments; the classification of these does not depend on them. */
    private static final String[] NONE = new String[0];

    @Test
    void lookingAtTheWorkspaceCostsNothingThatCannotBeUndone() {
        for (String reading : new String[] {"read", "multiread", "ls", "stat", "diff", "grep",
                                            "glob", "search", "todoread", "help",
                                            "notebookread"}) {
            assertThat(CommandEffects.of(reading, NONE))
                    .describedAs(reading)
                    .isEqualTo(Reversibility.REVERSIBLE);
        }
    }

    @Test
    void changingAFileCostsSomethingRealButLeavesNothingBroken() {
        for (String writing : new String[] {"write", "edit", "multiedit", "patch", "notebookedit"}) {
            assertThat(CommandEffects.of(writing, new String[] {"notes.txt"}))
                    .describedAs(writing)
                    .isEqualTo(Reversibility.COSTLY);
        }
    }

    @Test
    void whatLeavesTheWorkspaceOrEndsTheRunCannotBeTakenBack() {
        for (String spending : new String[] {"commit", "push", "login", "undo", "quit", "execute"}) {
            assertThat(CommandEffects.of(spending, NONE))
                    .describedAs(spending)
                    .isEqualTo(Reversibility.IRREVERSIBLE);
        }
    }

    @Test
    void aShellLineThatDeletesCannotBeTakenBack() {
        assertThat(CommandEffects.of("bash", new String[] {"rm", "-rf", "src"}))
                .isEqualTo(Reversibility.IRREVERSIBLE);
    }

    @Test
    void aShellLineThatOnlyLooksAroundIsCostlyRatherThanFreeBecauseAShellCanDoAnything() {
        assertThat(CommandEffects.of("bash", new String[] {"ls", "-la"}))
                .isEqualTo(Reversibility.COSTLY);
    }

    @Test
    void aCommandNobodyClassifiedIsTreatedAsTheSaferAnswer() {
        assertThat(CommandEffects.of("somethingAddedNextYear", NONE))
                .isEqualTo(Reversibility.COSTLY);
    }

    @Test
    void theNameIsRecognisedWhateverCaseItIsWrittenIn() {
        assertThat(CommandEffects.of("READ", NONE)).isEqualTo(Reversibility.REVERSIBLE);
        assertThat(CommandEffects.of("  Push ", NONE)).isEqualTo(Reversibility.IRREVERSIBLE);
    }

    @Test
    void anActionWithNoCommandInItIsNotClaimedToBeFree() {
        assertThat(CommandEffects.of(null, NONE)).isEqualTo(Reversibility.COSTLY);
        assertThat(CommandEffects.of("   ", NONE)).isEqualTo(Reversibility.COSTLY);
    }

    @Test
    void everyCommandTheAgentCanReachHasAnAnswerHere() {
        for (String name : new CommandRegistry().getCommands().keySet()) {
            assertThat(CommandEffects.of(name, NONE)).describedAs(name).isNotNull();
        }
    }
}
