package com.eonmux.cadetcoder.harness.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How every state a search reached was reached, so a plan can be read back off the end.
 *
 * <h2>Why a goal gets a link of its own</h2>
 *
 * <p>The goal is checked before the visited set, because a {@code key} that leaves out a
 * goal-relevant field would otherwise let a search throw away the answer as somewhere it had already
 * been. That means a goal state can arrive under a key already spoken for, so it is filed beside it
 * rather than over it -- overwriting would rewrite how an earlier state was reached and corrupt
 * every plan that runs through it.</p>
 */
final class Trail {

    /** What separates the goal's link from the link of an ordinary state under the same key. */
    private static final String GOAL_MARK = "#goal";

    private record Link(String parent, Object action) {
    }

    private final Map<String, Link> links = new LinkedHashMap<>();

    /** Files the state a search begins from, which was reached by doing nothing. */
    void start(String key) {
        links.put(key, new Link(null, null));
    }

    /** Whether the search has already reached a state with this identity. */
    boolean knows(String key) {
        return links.containsKey(key);
    }

    /** Files how a state was reached. */
    void link(String key, String parent, Object action) {
        links.put(key, new Link(parent, action));
    }

    /**
     * Files how a goal state was reached, without disturbing anything already filed.
     *
     * @return the key to read the plan back from
     */
    String reach(String key, String parent, Object action) {
        String at = key + GOAL_MARK;
        links.put(at, new Link(parent, action));
        return at;
    }

    /** How many distinct states the search reached. */
    int size() {
        return links.size();
    }

    /** The actions that lead from the start to this state, in order. */
    List<Object> plan(String key) {
        List<Object> actions = new ArrayList<>();
        String       at      = key;
        while (true) {
            Link link = links.get(at);
            if (link == null || link.parent() == null) {
                break;
            }
            actions.add(link.action());
            at = link.parent();
        }
        Collections.reverse(actions);
        return actions;
    }
}
