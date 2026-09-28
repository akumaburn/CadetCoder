package com.eonmux.cadetcoder.harness.fit;

import com.eonmux.cadetcoder.harness.Json;

import java.util.Map;

/**
 * One state, described as features and labelled.
 *
 * <h2>Why the features are a flat map</h2>
 *
 * <p>A fit compares one example's features against another's, so the descriptions have to be
 * commensurable: named values, not nested structure. Flattening is the modelling decision, and it
 * belongs to whoever writes the feature function -- the agent, which knows what about a state might
 * matter. What the fit needs is only that the same name means the same thing in every example.</p>
 *
 * @param features what was true of the state; frozen, because the search evaluates each one many
 *                 times and an atom that could change its own data would report anything
 * @param label    which side of the question this example is on
 */
public record Example(Map<String, Object> features, boolean label) {

    public Example {
        features = Json.frozenMap(features);
    }
}
