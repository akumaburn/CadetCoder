package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.commands.InteractiveShell.Admission;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shell runs one command at a time, including while the previous one is stopping.
 *
 * <p>The guard read {@code processing && !interruptRequested}, so pressing F2 did not only ask the
 * running command to stop -- it opened the door. The next line typed was admitted while the first
 * command was still on its thread, and {@code startProcessing} then set {@code interruptRequested}
 * back to false and cleared the {@code cadet.interrupt.requested} property, which is the only
 * signal the first command had to observe. So the interrupt was withdrawn before it could be acted
 * on, and two commands ran at once over the same singleton {@code Command} objects -- {@code grep}
 * clears and refills its match lists on every {@code execute}, so the second search rewrote the
 * first one's state mid-scan. Whichever finished first ran {@code stopProcessing} and told the user
 * the shell was idle.</p>
 *
 * <p>Commands that never poll for interruption ({@code websearch}, {@code glob},
 * {@code notebookedit} are plain {@code Command}s) made the window unbounded: F2 could not stop
 * them at all, so the door stayed open for as long as they ran.</p>
 *
 * <p>A line typed while a command runs is queued instead: it runs after the command, or joins the
 * conversation the command is having, and never beside it.</p>
 *
 * <p>Exercised through the pure static decision, as the Ctrl+C classifier next to it is: the shell
 * constructor captures {@code System.out} for the whole JVM.</p>
 */
public class ShellAdmitsOneCommandAtATimeTest {

    @Test
    public void anIdleShellRunsWhatIsTyped() {
        assertThat(InteractiveShell.classifyNewCommand(false, false, 0)).isEqualTo(Admission.RUN);
    }

    /**
     * A stale interrupt flag must not admit anything either. {@code stopProcessing} does not clear
     * {@code interruptRequested}; only the next {@code startProcessing} does.
     */
    @Test
    public void anIdleShellRunsWhatIsTypedAfterTheLastCommandWasInterrupted() {
        assertThat(InteractiveShell.classifyNewCommand(false, true, 0)).isEqualTo(Admission.RUN);
    }

    /** A line typed while a command runs waits its turn; it never starts beside the command. */
    @Test
    public void aLineTypedWhileACommandRunsIsQueued() {
        assertThat(InteractiveShell.classifyNewCommand(true, false, 0)).isEqualTo(Admission.QUEUE);
    }

    /** The case that was admitted: asked to stop, not yet stopped. */
    @Test
    public void aCommandThatHasBeenAskedToStopStillHoldsTheShell() {
        assertThat(InteractiveShell.classifyNewCommand(true, true, 0))
                .isEqualTo(Admission.REFUSE_STILL_STOPPING);
    }

    /** A line typed as a command ends goes behind the lines sent while it ran. */
    @Test
    public void anIdleShellWithLinesStillQueuedQueuesTheNextOne() {
        assertThat(InteractiveShell.classifyNewCommand(false, false, 2)).isEqualTo(Admission.QUEUE);
    }
}
