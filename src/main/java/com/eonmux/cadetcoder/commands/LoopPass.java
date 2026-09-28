package com.eonmux.cadetcoder.commands;

/**
 * Where an iteration sits in the loop that is running it.
 *
 * <h2>Why an iteration number alone is not enough</h2>
 *
 * <p>A loop runs a whole model run per pass, and every run counts its own iterations from one. A
 * transcript of a long loop therefore reads {@code Iteration 1} through {@code Iteration 31} and
 * then {@code Iteration 1} again, and that repetition is the only sign a pass ended. Scrolling
 * back through hours of it, there is nothing in a line to say which of a hundred passes it came
 * from, and no way to see how much work the loop has done short of counting the lines.</p>
 *
 * <h2>Why this is held per thread and not carried between them</h2>
 *
 * <p>A pass is one run on one thread: {@link LoopCommand} calls the run directly, so the place it
 * records is the place that run reads. It is deliberately absent from
 * {@code ThreadHandover}, unlike the four facts gathered there. Work handed to a thread of its own
 * inside a pass -- a worker, or a command a model asked for -- is a run of its own, and its
 * iterations are not the pass's iterations. Called from such a thread this says nothing, which is
 * what a run with no pass around it should say.</p>
 */
public final class LoopPass {

    /**
     * One pass, and how far it has got.
     *
     * <p>Mutable in the one field that has to be: the loop cannot know how many iterations a pass
     * ran until it has run them, and the run has no other way back to the loop than this.</p>
     */
    private static final class Place {

        private final int pass;
        private final int of;
        private final int before;
        private int reached;

        private Place(int pass, int of, int before) {
            this.pass   = pass;
            this.of     = of;
            this.before = before;
        }
    }

    private static final ThreadLocal<Place> CURRENT = new ThreadLocal<>();

    private LoopPass() {
    }

    /**
     * Runs one pass of a loop with its place in that loop in scope.
     *
     * @param pass   which pass this is, counting from 1
     * @param of     how many passes the loop runs
     * @param before how many iterations every earlier pass ran, in total
     * @param body   the pass
     * @return how many iterations this pass ran
     */
    static int inPass(int pass, int of, int before, Runnable body) {
        Place place    = new Place(pass, of, before);
        Place previous = CURRENT.get();
        CURRENT.set(place);
        try {
            body.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
        return place.reached;
    }

    /**
     * Whether the calling thread is running a pass of a loop.
     *
     * <p>A loop is started so that it runs without anyone, so a request made in one does not stop
     * to ask the terminal whether to try again; it waits out an outage by itself
     * ({@code OutageWait}). Work a pass hands to a thread of its own is not the pass, as above.</p>
     *
     * @return whether a pass is in scope on this thread
     */
    public static boolean isInAPass() {
        return CURRENT.get() != null;
    }

    /**
     * Opens one iteration, and says what to call it.
     *
     * <p>Recording and naming are one call because they are one event. Kept apart, a second
     * dispatch site is written with the name and without the record, and the loop's count then
     * quietly stops advancing -- which is a count nobody can trust and nobody can see is wrong.</p>
     *
     * @param iteration which iteration of this run it is, counting from 1
     * @return the label to open it with
     */
    public static String opening(int iteration) {
        Place place = CURRENT.get();
        if (place == null) {
            return "Iteration " + iteration;
        }
        place.reached = Math.max(place.reached, iteration);
        return "Pass " + place.pass + " of " + place.of + ", iteration " + iteration
               + " (" + (place.before + iteration) + " overall)";
    }
}
