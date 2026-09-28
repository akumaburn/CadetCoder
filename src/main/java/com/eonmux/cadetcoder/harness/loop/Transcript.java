package com.eonmux.cadetcoder.harness.loop;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything said so far in a run, and what is given up when it stops fitting.
 *
 * <h2>Why compaction squashes tool answers and nothing else</h2>
 *
 * <p>A run outlives any context, so something has to go. A tool answer is the only kind of turn that
 * can be given up without losing anything: it is a copy of what the ledger, the registry or the
 * belief store still hold, and the agent can ask for it again. The agent's own words are the theory
 * it is working from -- and, for a backend that replays reasoning, the thing the next request has to
 * send back unchanged -- and a harness turn was said once and is written down nowhere else.</p>
 *
 * <h2>Why the newest answers are kept</h2>
 *
 * <p>The oldest answers describe a world the run has already moved past; the newest are the evidence
 * the current deliberation is reasoning about. Squashing from the far end is what makes compaction
 * cost the run its history rather than its argument.</p>
 *
 * <h2>Why this is a value</h2>
 *
 * <p>Compaction rewrites turns that have already been sent. Done in place, a bug in it corrupts the
 * only record of the run and there is nothing to compare against; done as a new transcript, the one
 * it was made from is still there, which is what makes the rewrite testable at all.</p>
 *
 * @param turns what was said, oldest first
 */
public record Transcript(List<Turn> turns) {

    /** How long an answer has to be before squashing it saves more than it costs. */
    public static final int SQUASHED_ABOVE = 200;

    /** What is left where an answer was, saying where the same facts can still be found. */
    public static final String SQUASHED =
            "[compacted: an earlier tool answer. What lasts is in the ledger, the beliefs and the "
            + "certificates; ask for it again if you need it.]";

    public Transcript {
        turns = List.copyOf(turns);
    }

    /** A run that has not said anything yet. */
    public static Transcript empty() {
        return new Transcript(List.of());
    }

    /**
     * The same transcript with one more turn at the end.
     *
     * @param turn what was said
     * @return the longer transcript
     */
    public Transcript plus(Turn turn) {
        List<Turn> longer = new ArrayList<>(turns);
        longer.add(turn);
        return new Transcript(longer);
    }

    /**
     * The transcript with its older tool answers given up.
     *
     * @param keepRecent how many of the most recent answers to leave exactly as they are
     * @return the compacted transcript
     */
    public Transcript compacted(int keepRecent) {
        int squashUpTo = squashUpTo(keepRecent);
        List<Turn> kept  = new ArrayList<>(turns.size());
        int        seen  = 0;
        for (Turn turn : turns) {
            boolean answer = turn.kind() == TurnKind.ANSWER;
            kept.add(answer && seen++ < squashUpTo && worthSquashing(turn) ? Turn.answer(SQUASHED)
                                                                          : turn);
        }
        return new Transcript(kept);
    }

    /** How many answers have already been given up. */
    public int squashed() {
        int given = 0;
        for (Turn turn : turns) {
            if (turn.kind() == TurnKind.ANSWER && SQUASHED.equals(turn.text())) {
                given++;
            }
        }
        return given;
    }

    /** How many turns there are. */
    public int size() {
        return turns.size();
    }

    /**
     * How many of the answers, counting from the oldest, are old enough to be given up.
     *
     * @param keepRecent how many of the most recent to leave alone
     */
    private int squashUpTo(int keepRecent) {
        int answers = 0;
        for (Turn turn : turns) {
            if (turn.kind() == TurnKind.ANSWER) {
                answers++;
            }
        }
        return Math.max(0, answers - Math.max(0, keepRecent));
    }

    private static boolean worthSquashing(Turn turn) {
        return turn.text().length() > SQUASHED_ABOVE;
    }
}
