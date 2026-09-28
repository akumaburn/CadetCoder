package com.eonmux.cadetcoder.session;

import com.eonmux.cadetcoder.OutputFormatter;

/**
 * Which session a run starts in when the command line named one it did not start itself.
 *
 * <h2>Why the two flags are decided in one place</h2>
 *
 * <p>{@code --resume <id>} and {@code --continue} ask the same question and were answered by two
 * pieces of code with two different ideas of what a failure is. {@code --resume} stopped when the
 * session would not load; {@code --continue} threw the answer away and announced that it was
 * continuing a session it had just failed to open, so the work went into whatever session happened
 * to be current. Deciding once means neither flag can be given the careful answer while the other
 * is given none.</p>
 *
 * <h2>Why an exit code rather than an exception</h2>
 *
 * <p>The caller is a front door whose whole job is to return one, and a session that is not there
 * is a thing the user asked for that cannot be done -- not a fault in the program.</p>
 */
public final class SessionResumption {

    /** Nothing was asked for, or what was asked for is now open. */
    public static final int CARRY_ON = 0;

    /** The session named on the command line could not be opened, and it was named for a reason. */
    public static final int UNAVAILABLE = 1;

    private SessionResumption() {
    }

    /**
     * Opens whichever session the command line asked for.
     *
     * @param continueMostRecent whether {@code --continue} was given
     * @param sessionId          the id {@code --resume} was given, or {@code null}
     * @param sessions           where sessions are kept
     * @return {@link #CARRY_ON} to go on with the run, {@link #UNAVAILABLE} to stop
     */
    public static int open(boolean continueMostRecent, String sessionId, SessionManager sessions) {
        if (continueMostRecent) {
            SessionState recent = sessions.getMostRecentSession();
            if (recent == null) {
                OutputFormatter.printWarning("No recent session found");
                return CARRY_ON;
            }
            return opened(sessions, recent.getSessionId());
        }
        if (sessionId != null) {
            return opened(sessions, sessionId);
        }
        return CARRY_ON;
    }

    /**
     * @return whether the run may go on, given what loading the session did
     */
    private static int opened(SessionManager sessions, String sessionId) {
        return sessions.loadSessionById(sessionId) ? CARRY_ON : UNAVAILABLE;
    }
}
