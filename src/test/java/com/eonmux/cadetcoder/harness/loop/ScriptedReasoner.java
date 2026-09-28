package com.eonmux.cadetcoder.harness.loop;

import java.util.ArrayList;
import java.util.List;

/**
 * A reasoner that says what a test wrote for it, in order.
 *
 * <h2>Why the driver is tested without anything thinking</h2>
 *
 * <p>Every question worth asking about the driver -- when a deliberation ends, what a plateau does,
 * what happens to a reply that ran out of room -- is a question about a particular sequence of
 * replies. A real backend answers a different sequence every time, so the answers would be tested
 * against whatever it happened to say. A script is the sequence itself, which is what makes the
 * driver's behaviour something a test can state.</p>
 *
 * <h2>Why it keeps what it was shown</h2>
 *
 * <p>Half of what the driver does is decide what the agent gets to see: the first observation, a
 * nudge, a complaint, the block that replaces a compacted transcript. None of that is visible in the
 * run's result, so it is asserted on the transcripts the reasoner was actually handed.</p>
 */
public final class ScriptedReasoner implements Reasoner {

    /** What one scripted reply costs to send, which is what drives compaction in a test. */
    public static final long TOKENS_IN = 1_000L;

    /** What one scripted reply costs to receive. */
    public static final long TOKENS_OUT = 100L;

    /** What is said once the script has run out, which ends a run rather than repeating forever. */
    public static final String EXHAUSTED = SystemPrompt.STUCK + ": the script ran out";

    private final String       name;
    private final List<String> replies;
    private final long         tokensIn;
    private final long         tokensOut;

    private final List<Transcript> seen    = new ArrayList<>();
    private final List<String>     systems = new ArrayList<>();

    private int at;

    private ScriptedReasoner(String name, List<String> replies, long tokensIn, long tokensOut) {
        this.name      = name;
        this.replies   = List.copyOf(replies);
        this.tokensIn  = tokensIn;
        this.tokensOut = tokensOut;
    }

    /** A reasoner that will say these things, one to a turn. */
    public static ScriptedReasoner saying(String... replies) {
        return new ScriptedReasoner("scripted", List.of(replies), TOKENS_IN, TOKENS_OUT);
    }

    /** The same script under another name, for a test with more than one reasoner in it. */
    public ScriptedReasoner named(String called) {
        return new ScriptedReasoner(called, replies, tokensIn, tokensOut);
    }

    /** The same script at another price, for a test about what a run spends. */
    public ScriptedReasoner costing(long in, long out) {
        return new ScriptedReasoner(name, replies, in, out);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Reply think(String system, Transcript transcript) {
        systems.add(system);
        seen.add(transcript);
        return new Reply(at < replies.size() ? replies.get(at++) : EXHAUSTED, tokensIn, tokensOut);
    }

    /** Every transcript this reasoner was shown, oldest first. */
    public List<Transcript> seen() {
        return List.copyOf(seen);
    }

    /** Every system prompt it was given, oldest first. */
    public List<String> systems() {
        return List.copyOf(systems);
    }

    /** The transcript it was shown most recently. */
    public Transcript last() {
        return seen.get(seen.size() - 1);
    }

    /** How many times it was asked to think. */
    public int asked() {
        return seen.size();
    }
}
