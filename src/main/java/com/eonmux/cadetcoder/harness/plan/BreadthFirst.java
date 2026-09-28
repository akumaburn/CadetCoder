package com.eonmux.cadetcoder.harness.plan;

import com.eonmux.cadetcoder.harness.model.ModelException;
import com.eonmux.cadetcoder.harness.model.WorldModel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

/**
 * The shortest plan there is, found by looking at everything one step further out.
 *
 * <p>Breadth first is the default because the shortest plan is the one with the fewest chances to be
 * wrong. A model that can offer a heuristic gets a better search; a model that cannot gets this one
 * rather than a depth-first walk that would return a hundred-step answer to a three-step problem.</p>
 */
final class BreadthFirst {

    private record Reached(Object state, String key, int depth) {
    }

    private BreadthFirst() {
    }

    static SearchResult search(WorldModel model, Object start, List<Object> offered,
                               Predicate<Object> goal, SearchLimits limits) {
        Clock  clock    = new Clock(limits);
        Trail  trail    = new Trail();
        int    expanded = 0;
        int    deepest  = 0;
        try {
            if (goal.test(start)) {
                return SearchResult.found(List.of(), clock.effort(0, 1, 0));
            }
            String          startKey = model.key(start);
            Deque<Reached>  queue    = new ArrayDeque<>();
            trail.start(startKey);
            queue.addLast(new Reached(start, startKey, 0));
            while (!queue.isEmpty()) {
                SearchStatus stopped = clock.stopped(expanded);
                if (stopped != null) {
                    return SearchResult.stopped(stopped, clock.effort(expanded, trail.size(), deepest));
                }
                Reached reached = queue.removeFirst();
                if (reached.depth() >= limits.maxDepth()) {
                    continue;
                }
                expanded++;
                for (Object action : model.actions(reached.state(), offered)) {
                    Object next    = model.step(reached.state(), action);
                    String nextKey = model.key(next);
                    if (goal.test(next)) {
                        String at = trail.reach(nextKey, reached.key(), action);
                        return SearchResult.found(trail.plan(at),
                                                  clock.effort(expanded, trail.size(),
                                                               reached.depth() + 1));
                    }
                    if (trail.knows(nextKey)) {
                        continue;
                    }
                    trail.link(nextKey, reached.key(), action);
                    deepest = Math.max(deepest, reached.depth() + 1);
                    queue.addLast(new Reached(next, nextKey, reached.depth() + 1));
                }
            }
            return SearchResult.stopped(SearchStatus.EXHAUSTED,
                                        clock.effort(expanded, trail.size(), deepest));
        } catch (ModelException failure) {
            return SearchResult.broken(failure.getMessage(),
                                       clock.effort(expanded, trail.size(), deepest));
        }
    }
}
