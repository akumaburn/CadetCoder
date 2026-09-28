package com.eonmux.cadetcoder.harness.ledger;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.env.Environment;
import com.eonmux.cadetcoder.harness.env.StepOutcome;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing that really happened, as it is written down forever.
 *
 * <h2>Two lives</h2>
 *
 * <p>A transition starts as a {@link #proposal} -- what an environment just reported -- with no
 * index and no place in the chain. {@link Ledger#append} seals it: it takes the next index, the
 * current head as its {@link #prevHash()}, and a {@link #hash()} over everything else. After that
 * nothing about it may change, which is why every value it holds is frozen rather than copied.</p>
 *
 * <h2>Why the prediction's verdict is stored</h2>
 *
 * <p>{@link #predicted()} is what a model said would happen and {@link #predictionHeld()} is whether
 * it did. Recomputing the second from the first would only work while every prediction is an exact
 * observation; a prediction may instead be a set of named constraints, which nothing later can
 * re-evaluate. The gate writes down the verdict it reached at the moment it checked.</p>
 */
public final class Transition {

    /** How many hex characters of the SHA-256 identify a transition. */
    static final int HASH_LENGTH = 32;

    private final int                 index;
    private final int                 episode;
    private final Object              obsBefore;
    private final Object              action;
    private final Object              obsAfter;
    private final Map<String, Object> flags;
    private final Map<String, Object> info;
    private final String              timestamp;
    private final String              modelHash;
    private final Object              predicted;
    private final Boolean             predictionHeld;
    private final String              note;
    private final String              prevHash;
    private final String              hash;

    private Transition(int index, int episode, Object obsBefore, Object action, Object obsAfter,
                       Map<String, Object> flags, Map<String, Object> info, String timestamp,
                       String modelHash, Object predicted, Boolean predictionHeld, String note,
                       String prevHash, String hash) {
        this.index          = index;
        this.episode        = episode;
        this.obsBefore      = Json.frozen(obsBefore);
        this.action         = Json.frozen(action);
        this.obsAfter       = Json.frozen(obsAfter);
        this.flags          = Json.frozenMap(flags);
        this.info           = Json.frozenMap(info);
        this.timestamp      = timestamp;
        this.modelHash      = modelHash;
        this.predicted      = Json.frozen(predicted);
        this.predictionHeld = predictionHeld;
        this.note           = note;
        this.prevHash       = prevHash;
        this.hash           = hash;
    }

    /**
     * A transition that happened but has not been recorded yet.
     *
     * @param episode   which episode it belongs to
     * @param obsBefore what could be seen before, or {@code null} for the first step of an episode
     * @param action    what was done
     * @param obsAfter  what could be seen after
     * @param flags     the environment's verdicts, conventionally {@code goal} and {@code terminal}
     * @param info      diagnostics, never predicted and never checked
     * @return the unsealed transition
     */
    public static Transition proposal(int episode, Object obsBefore, Object action, Object obsAfter,
                                      Map<String, Object> flags, Map<String, Object> info) {
        return new Transition(-1, episode, obsBefore, action, obsAfter, flags, info,
                              Instant.now().toString(), null, null, null, null, null, null);
    }

    /**
     * The same transition, with what a model said would happen and whether it did.
     *
     * @param modelHash which model made the claim
     * @param predicted the claim: an exact observation, or the description of the constraints
     * @param held      whether reality agreed
     * @return a copy carrying the prediction
     */
    public Transition predicting(String modelHash, Object predicted, boolean held) {
        return new Transition(index, episode, obsBefore, action, obsAfter, flags, info, timestamp,
                              modelHash, predicted, held, note, prevHash, hash);
    }

    /**
     * The same transition, with a line of context for whoever reads the ledger later.
     *
     * @param text the note
     * @return a copy carrying it
     */
    public Transition annotated(String text) {
        return new Transition(index, episode, obsBefore, action, obsAfter, flags, info, timestamp,
                              modelHash, predicted, predictionHeld, text, prevHash, hash);
    }

    /** Fixes this transition's place in the chain and hashes it. Only {@link Ledger} may do this. */
    Transition sealed(int position, String previous) {
        Transition placed = new Transition(position, episode, obsBefore, action, obsAfter, flags,
                                           info, timestamp, modelHash, predicted, predictionHeld,
                                           note, previous, null);
        return new Transition(position, episode, obsBefore, action, obsAfter, flags, info, timestamp,
                              modelHash, predicted, predictionHeld, note, previous,
                              Json.digest(placed.payload(), HASH_LENGTH));
    }

    /** Everything the hash covers: the whole transition except the hash itself. */
    private Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("index", index);
        payload.put("episode", episode);
        payload.put("obs_before", obsBefore);
        payload.put("action", action);
        payload.put("obs_after", obsAfter);
        payload.put("flags", flags);
        payload.put("info", info);
        payload.put("ts", timestamp);
        payload.put("model_hash", modelHash);
        payload.put("predicted", predicted);
        payload.put("prediction_held", predictionHeld);
        payload.put("note", note);
        payload.put("prev_hash", prevHash);
        return payload;
    }

    /** The record as it appears on one line of the ledger file. */
    Map<String, Object> toValue() {
        Map<String, Object> value = payload();
        value.put("hash", hash);
        return value;
    }

    /** Reads back one line of the ledger file, without checking it. */
    @SuppressWarnings ("unchecked")
    static Transition fromValue(Map<String, Object> value) {
        return new Transition(
                asInt(value.get("index")),
                asInt(value.get("episode")),
                value.get("obs_before"),
                value.get("action"),
                value.get("obs_after"),
                (Map<String, Object>) value.get("flags"),
                (Map<String, Object>) value.get("info"),
                (String) value.get("ts"),
                (String) value.get("model_hash"),
                value.get("predicted"),
                (Boolean) value.get("prediction_held"),
                (String) value.get("note"),
                (String) value.get("prev_hash"),
                (String) value.get("hash"));
    }

    private static int asInt(Object value) {
        if (!(value instanceof Number)) {
            throw new LedgerException("a transition needs a numeric index and episode, not " + value);
        }
        return ((Number) value).intValue();
    }

    /** Whether the hash on the record still matches the record. */
    boolean intact() {
        return hash != null && hash.equals(Json.digest(payload(), HASH_LENGTH));
    }

    /** Whether this transition started an episode rather than acting within one. */
    public boolean isReset() {
        return Json.equal(action, Environment.RESET_ACTION);
    }

    /** Whether the environment said the goal was reached here. */
    public boolean reachedGoal() {
        return Json.truthy(flags.get(StepOutcome.GOAL_FLAG));
    }

    /** Whether the environment said the episode could not continue past here. */
    public boolean endedEpisode() {
        return Json.truthy(flags.get(StepOutcome.TERMINAL_FLAG));
    }

    public int index() {
        return index;
    }

    public int episode() {
        return episode;
    }

    public Object obsBefore() {
        return obsBefore;
    }

    public Object action() {
        return action;
    }

    public Object obsAfter() {
        return obsAfter;
    }

    public Map<String, Object> flags() {
        return flags;
    }

    public Map<String, Object> info() {
        return info;
    }

    public String timestamp() {
        return timestamp;
    }

    public String modelHash() {
        return modelHash;
    }

    public Object predicted() {
        return predicted;
    }

    /** Whether the prediction held, or {@code null} when nothing predicted this step. */
    public Boolean predictionHeld() {
        return predictionHeld;
    }

    public String note() {
        return note;
    }

    public String prevHash() {
        return prevHash;
    }

    public String hash() {
        return hash;
    }

    @Override
    public String toString() {
        return "#" + index + " ep" + episode + " " + Json.canonical(action)
               + " -> " + Json.canonical(obsAfter);
    }
}
