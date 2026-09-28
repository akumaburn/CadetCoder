package com.eonmux.cadetcoder.harness.ledger;

import com.eonmux.cadetcoder.harness.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Append-only, hash-chained record of everything that really happened.
 *
 * <h2>Why the chain matters</h2>
 *
 * <p>A certificate says "this model agrees with the ledger up to hash H". That sentence is only
 * worth something if the ledger cannot quietly become a different ledger. Each line names the hash
 * of the line before it and carries a hash over its own contents, so editing a past transition,
 * dropping one, or reordering two all fail to load rather than silently changing what a green
 * certificate means. It also stops an agent's account of its own history from drifting away from
 * what it did.</p>
 *
 * <h2>Why it refuses rather than repairs</h2>
 *
 * <p>A torn last line is the one damage that looks recoverable, and skipping it is the tempting fix.
 * It is not available: the head hash after a skipped line is not the head hash the certificates on
 * disk were issued against, so a repaired ledger would silently promote a stale certificate to a
 * current one. Damage is reported and the run stops.</p>
 */
public final class Ledger {

    /** What the first transition names as its predecessor. */
    public static final String GENESIS = "genesis";

    private final Path             path;
    private final List<Transition> transitions = new ArrayList<>();

    /**
     * Opens a ledger, verifying anything already written there.
     *
     * @param path the file; its directory is created if it does not exist
     * @throws LedgerException if the file exists and does not verify
     */
    public Ledger(Path path) {
        this.path = path;
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new LedgerException("cannot create the ledger directory for " + path, e);
        }
        load();
    }

    private void load() {
        if (!Files.exists(path)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LedgerException("cannot read the ledger at " + path, e);
        }
        String previous = GENESIS;
        for (int i = 0; i < lines.size(); i++) {
            String line   = lines.get(i);
            int    number = i + 1;
            if (line.isBlank()) {
                throw new LedgerException("ledger line " + number + " is blank; the record is torn");
            }
            Transition transition = read(line, number);
            check(transition, previous, i, number);
            transitions.add(transition);
            previous = transition.hash();
        }
    }

    private Transition read(String line, int number) {
        try {
            Object value = Json.parse(line);
            if (!(value instanceof Map)) {
                throw new LedgerException("ledger line " + number + " is not a record");
            }
            @SuppressWarnings ("unchecked")
            Map<String, Object> record = (Map<String, Object>) value;
            return Transition.fromValue(record);
        } catch (IllegalArgumentException e) {
            throw new LedgerException("ledger line " + number + " is not a record: " + e.getMessage(), e);
        }
    }

    private void check(Transition transition, String previous, int position, int number) {
        if (!previous.equals(transition.prevHash())) {
            throw new LedgerException("ledger line " + number + " does not follow line " + (number - 1)
                                      + ": it names " + transition.prevHash() + ", not " + previous);
        }
        if (transition.index() != position) {
            throw new LedgerException("ledger line " + number + " is out of order: it is numbered "
                                      + transition.index() + ", not " + position);
        }
        if (!transition.intact()) {
            throw new LedgerException("ledger line " + number
                                      + " was altered: its hash does not match its contents");
        }
    }

    /**
     * Records a transition that really happened.
     *
     * @param proposal the unsealed transition
     * @return the same transition with its index, place in the chain, and hash
     * @throws LedgerException if it cannot be written
     */
    public Transition append(Transition proposal) {
        Transition sealed = proposal.sealed(transitions.size(), head());
        try {
            Files.writeString(path, Json.canonical(sealed.toValue()) + System.lineSeparator(),
                              StandardCharsets.UTF_8,
                              StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new LedgerException("cannot append to the ledger at " + path, e);
        }
        transitions.add(sealed);
        return sealed;
    }

    /** The hash every certificate is measured against; {@link #GENESIS} when nothing has happened. */
    public String head() {
        return transitions.isEmpty() ? GENESIS : transitions.get(transitions.size() - 1).hash();
    }

    /** How many transitions have been recorded. */
    public int size() {
        return transitions.size();
    }

    /** Every transition, oldest first. */
    public List<Transition> all() {
        return Collections.unmodifiableList(transitions);
    }

    /**
     * One transition by its index.
     *
     * @param index its position in the ledger
     * @return the transition
     * @throws IndexOutOfBoundsException if nothing was recorded at that index
     */
    public Transition get(int index) {
        return transitions.get(index);
    }

    /**
     * The most recent transitions, oldest first.
     *
     * @param count how many to return at most
     * @return up to {@code count} transitions
     */
    public List<Transition> tail(int count) {
        int from = Math.max(0, transitions.size() - Math.max(0, count));
        return List.copyOf(transitions.subList(from, transitions.size()));
    }

    /** Where the ledger is written. */
    public Path path() {
        return path;
    }

    /** The state of the evidence. */
    public LedgerStats stats() {
        int          resets           = 0;
        int          liveChecked      = 0;
        int          liveMispredicted = 0;
        int          goals            = 0;
        Set<Integer> episodes         = new HashSet<>();
        for (Transition transition : transitions) {
            episodes.add(transition.episode());
            if (transition.isReset()) {
                resets++;
            }
            if (transition.predictionHeld() != null) {
                liveChecked++;
                if (!transition.predictionHeld()) {
                    liveMispredicted++;
                }
            }
            if (transition.reachedGoal()) {
                goals++;
            }
        }
        return new LedgerStats(transitions.size(), transitions.size() - resets, resets,
                               episodes.size(), liveChecked, liveMispredicted, goals, head());
    }
}
