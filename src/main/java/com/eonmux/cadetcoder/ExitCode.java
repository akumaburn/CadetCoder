package com.eonmux.cadetcoder;

/**
 * What this tool tells the shell that started it.
 *
 * <h2>Why an interruption has a code of its own</h2>
 *
 * <p>A run somebody took back did not succeed and did not fail: the work it was asked to do was
 * neither finished nor found impossible, it was abandoned on request. A script that reads only the
 * exit code has no other way to tell those three apart, and the three of them call for three
 * different next moves -- carry on, report the failure, or stop quietly because a person said so.</p>
 *
 * <p>{@link #INTERRUPTED} is 130 because that is what a shell reports for a process killed by
 * SIGINT (128 + 2), which is the same event arriving by a different route. Anything reading exit
 * codes already knows that number.</p>
 *
 * <h2>What this replaces</h2>
 *
 * <p>The same interruption used to be reported three different ways depending on which routine
 * noticed it: {@code bash} and {@code workers} answered 130, a harness run somebody called off
 * answered 1 as though the task had failed, and {@code agent}, {@code chat} and {@code edit}
 * answered 0 as though it had succeeded -- so a script that stopped an agent was told the agent had
 * finished the job.</p>
 */
public final class ExitCode {

    /** The command did what it was asked. */
    public static final int OK = 0;

    /** The command could not do what it was asked. */
    public static final int FAILED = 1;

    /** The command was taken back before it could finish. */
    public static final int INTERRUPTED = 130;

    /**
     * The command needed the model and could not reach it.
     *
     * <h2>Why this is not simply a failure</h2>
     *
     * <p>Nothing was attempted. The request never arrived, so the work was neither done nor found
     * impossible, and the next thing to do about it is different from the next thing to do about a
     * task that failed: wait, or fix the credentials, rather than read the output and try
     * something else. {@code loop} is the caller that has to tell them apart. A pass that failed is
     * a pass; a pass that could not open a request is not, and running the remaining
     * ninety-eight against a provider that has cut this account off for the next five hours takes
     * a moment, changes nothing, and ends by reporting a hundred passes done.</p>
     *
     * <p>69 is {@code EX_UNAVAILABLE} from sysexits, which is what a shell already reads as "the
     * service this needed was not there".</p>
     */
    public static final int UNREACHABLE = 69;

    private ExitCode() {
    }
}
