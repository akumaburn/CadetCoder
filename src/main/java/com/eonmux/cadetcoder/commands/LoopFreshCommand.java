package com.eonmux.cadetcoder.commands;

import picocli.CommandLine.Command;

/**
 * The same loop, with each pass told nothing about what the last one said.
 *
 * <h2>Why the two are separate commands</h2>
 *
 * <p>What carries between passes decides what the loop is for, and the two uses are opposite. When
 * the passes build on one another, knowing what the last one tried saves this one from trying it
 * again. When they do not -- looking for what was missed, writing a second opinion, attacking a
 * problem the last attempt got stuck on -- being told what was just said is exactly what produces
 * the same answer again, and the point of the pass is that it does not.</p>
 *
 * <p>The project is still shared, because the project is where the work is. What is withheld is the
 * previous pass's account of itself.</p>
 */
@Command (name = "loopfresh",
        description = "Work at one goal over and over, each pass told nothing of the last")
public class LoopFreshCommand extends LoopCommand {

    @Override
    boolean carriesTheLastResult() {
        return false;
    }

    @Override
    String name() {
        return "loopfresh";
    }
}
