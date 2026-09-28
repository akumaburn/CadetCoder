package com.eonmux.cadetcoder.harness.belief;

import com.eonmux.cadetcoder.harness.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * What the agent believes, kept apart from what it used to believe.
 *
 * <h2>Why this exists instead of a notes file</h2>
 *
 * <p>An agent that keeps everything it has learned in one free-form document spends the second half
 * of a run re-reading the first half. The document fills with "(old)", "(superseded)" and "(STALE)"
 * markers that only prose can interpret, and nothing in it separates a claim the ledger supports
 * from one the agent talked itself into. Here a claim is a record: it has a status, it names the
 * ledger indices behind it, and when it dies it says what killed it -- so the rendered view can put
 * what is still true first and still keep the graveyard, because a refuted hypothesis is evidence
 * about the world too.</p>
 *
 * <h2>Why it is event sourced and not hash chained</h2>
 *
 * <p>The file is append-only and the beliefs are replayed from it, so a claim's history survives the
 * agent's context being compacted away and a crashed run resumes at the last complete line. It is
 * deliberately not tamper evident: these are notes. The ledger is the only thing a certificate rests
 * on, and it is the only thing that has to be impossible to quietly rewrite.</p>
 */
public final class BeliefStore {

    /** How many dead beliefs the rendered view shows before it stops being readable. */
    public static final int GRAVEYARD_SHOWN = 15;

    /** How many answered questions the rendered view shows. */
    public static final int ANSWERS_SHOWN = 8;

    /** How many ledger indices one claim shows behind it. */
    public static final int EVIDENCE_SHOWN = 6;

    private static final String ID_PREFIX = "B";

    private final Path                path;
    private final Map<String, Belief> beliefs = new LinkedHashMap<>();

    private int issued;

    /**
     * Opens a belief store, replaying anything already written there.
     *
     * @param path the log; its directory is created if it does not exist
     * @throws BeliefStoreException if the log cannot be read or does not replay
     */
    public BeliefStore(Path path) {
        this.path = path;
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new BeliefStoreException("cannot create the directory for the belief log at "
                                           + path, e);
        }
        load();
    }

    /**
     * Writes down a claim the agent means to act on.
     *
     * @param text         the claim
     * @param ledgerLength how long the ledger is now
     * @param evidence     the ledger indices behind it, empty when there are none yet
     * @param tags         what it is about
     * @return the claim as it was recorded
     */
    public Belief claim(String text, int ledgerLength, List<Integer> evidence, List<String> tags) {
        return claim(text, ledgerLength, evidence, tags, null);
    }

    /**
     * Writes down a claim that replaces one the agent has outgrown.
     *
     * <p>The replacement is checked before anything is written. A store that recorded the new claim
     * and then failed on the link would leave two live claims about the same thing, which is the
     * state this whole store exists to prevent.</p>
     *
     * @param supersedes the claim this one retires, or {@code null} when it retires nothing
     * @return the claim as it was recorded
     * @throws IllegalArgumentException if there is no such claim, or it is not one still being held
     */
    public Belief claim(String text, int ledgerLength, List<Integer> evidence, List<String> tags,
                        String supersedes) {
        String said = stated(text);
        if (supersedes != null) {
            replaceable(supersedes);
        }
        String id = nextId();
        emit(BeliefEvent.added(id, said, BeliefStatus.ACTIVE, ledgerLength, evidence, tags,
                               supersedes));
        if (supersedes != null) {
            emit(BeliefEvent.superseded(supersedes, id));
        }
        return beliefs.get(id);
    }

    /**
     * Writes down something the agent does not know yet.
     *
     * @param text         the question
     * @param ledgerLength how long the ledger is now
     * @param tags         what it is about
     * @return the question as it was recorded
     */
    public Belief question(String text, int ledgerLength, List<String> tags) {
        String said = stated(text);
        String id   = nextId();
        emit(BeliefEvent.added(id, said, BeliefStatus.OPEN, ledgerLength, List.of(), tags, null));
        return beliefs.get(id);
    }

    /**
     * Kills a claim the ledger has contradicted.
     *
     * @param id       the claim
     * @param evidence the ledger indices that contradict it
     * @param reason   why they contradict it
     * @return the claim, refuted
     * @throws IllegalArgumentException if there is no such claim, or it is not an active one
     */
    public Belief refute(String id, List<Integer> evidence, String reason) {
        BeliefStatus status = known(id).status();
        if (status != BeliefStatus.ACTIVE) {
            throw new IllegalArgumentException(id + " is " + status.label() + ", not an active "
                                               + "claim; only a claim still being acted on can be "
                                               + "refuted");
        }
        return emit(BeliefEvent.refuted(id, evidence, reason));
    }

    /**
     * Answers an open question.
     *
     * @param id       the question
     * @param answer   what the answer is
     * @param evidence the ledger indices that establish it
     * @return the question, answered
     * @throws IllegalArgumentException if there is no such belief, or it is not an open question
     */
    public Belief resolve(String id, String answer, List<Integer> evidence) {
        BeliefStatus status = known(id).status();
        if (status != BeliefStatus.OPEN) {
            throw new IllegalArgumentException(id + " is " + status.label() + ", not an open "
                                               + "question; only a question can be answered");
        }
        return emit(BeliefEvent.resolved(id, answer, evidence));
    }

    /**
     * Names more of the ledger behind an existing belief.
     *
     * @param id       the belief
     * @param evidence the ledger indices to add; ones already named are not repeated
     * @return the belief, with the evidence it now names
     * @throws IllegalArgumentException if there is no such belief
     */
    public Belief support(String id, List<Integer> evidence) {
        known(id);
        return emit(BeliefEvent.evidence(id, evidence));
    }

    /**
     * One belief by name.
     *
     * @param id what it is called
     * @return the belief, or {@code null} when nothing goes by that name
     */
    public Belief get(String id) {
        return beliefs.get(id);
    }

    /** How many beliefs the store holds, whatever has become of them. */
    public int size() {
        return beliefs.size();
    }

    /** Every belief, oldest first. */
    public List<Belief> all() {
        return List.copyOf(beliefs.values());
    }

    /**
     * The beliefs in any of these states, oldest first.
     *
     * @param statuses the states to include
     * @return the beliefs in them
     */
    public List<Belief> byStatus(BeliefStatus... statuses) {
        List<BeliefStatus> wanted = Arrays.asList(statuses);
        List<Belief>       found  = new ArrayList<>();
        for (Belief belief : beliefs.values()) {
            if (wanted.contains(belief.status())) {
                found.add(belief);
            }
        }
        return found;
    }

    /** Where the log is written. */
    public Path path() {
        return path;
    }

    /**
     * The beliefs as the agent is shown them: what is still true, then what is still unknown, then
     * what died and why.
     *
     * @return the rendered view
     */
    public String render() {
        List<Belief> active   = byStatus(BeliefStatus.ACTIVE);
        List<Belief> open     = byStatus(BeliefStatus.OPEN);
        List<Belief> answered = byStatus(BeliefStatus.RESOLVED);
        List<Belief> dead     = byStatus(BeliefStatus.REFUTED, BeliefStatus.SUPERSEDED);
        List<String> lines    = new ArrayList<>();
        lines.add("# Beliefs");
        lines.add("");
        lines.add("## Active (" + active.size() + ")");
        for (Belief belief : active) {
            lines.add("- " + belief.id() + ": " + belief.text() + evidenceOf(belief) + tagsOf(belief));
        }
        lines.add("");
        lines.add("## Open questions (" + open.size() + ")");
        for (Belief belief : open) {
            lines.add("- " + belief.id() + ": " + belief.text() + tagsOf(belief));
        }
        if (!answered.isEmpty()) {
            lines.add("");
            lines.add("## Answered questions (" + answered.size() + ")");
            for (Belief belief : mostRecent(answered, ANSWERS_SHOWN)) {
                lines.add("- " + belief.id() + ": " + belief.text() + " -> " + belief.answer());
            }
        }
        lines.add("");
        lines.add("## Graveyard (" + dead.size() + "; most recent "
                  + Math.min(GRAVEYARD_SHOWN, dead.size()) + ")");
        for (Belief belief : mostRecent(dead, GRAVEYARD_SHOWN)) {
            lines.add("- " + epitaph(belief));
        }
        return String.join(System.lineSeparator(), lines);
    }

    /** Every belief as a value the harness can keep. */
    public List<Object> toValue() {
        List<Object> value = new ArrayList<>();
        for (Belief belief : beliefs.values()) {
            value.add(belief.toValue());
        }
        return value;
    }

    private static String evidenceOf(Belief belief) {
        List<Integer> indices = belief.evidence();
        if (indices.isEmpty()) {
            return " [NO EVIDENCE]";
        }
        String shown = indices.subList(0, Math.min(EVIDENCE_SHOWN, indices.size())).stream()
                              .map(String::valueOf).collect(Collectors.joining(","));
        return " [ev: " + shown + (indices.size() > EVIDENCE_SHOWN ? "..." : "") + "]";
    }

    private static String tagsOf(Belief belief) {
        return belief.tags().isEmpty() ? "" : " #" + String.join(" #", belief.tags());
    }

    private static String epitaph(Belief belief) {
        if (belief.status() == BeliefStatus.REFUTED) {
            return belief.id() + " REFUTED by ledger " + belief.refutedBy() + ": " + belief.text()
                   + " -- " + belief.reason();
        }
        return belief.id() + " superseded by " + belief.supersededBy() + ": " + belief.text();
    }

    private static List<Belief> mostRecent(List<Belief> beliefs, int count) {
        return beliefs.subList(Math.max(0, beliefs.size() - count), beliefs.size());
    }

    private Belief emit(BeliefEvent event) {
        try {
            Files.writeString(path, Json.canonical(event.toValue()) + System.lineSeparator(),
                              StandardCharsets.UTF_8,
                              StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new BeliefStoreException("cannot append to the belief log at " + path, e);
        }
        apply(event);
        return beliefs.get(event.id());
    }

    private void apply(BeliefEvent event) {
        switch (event.kind()) {
            case ADD -> add(event);
            case EVIDENCE -> replace(event.id(), belief -> belief.supported(event.evidence()));
            case REFUTE -> replace(event.id(),
                                   belief -> belief.refuted(event.evidence(), event.reason()));
            case RESOLVE -> replace(event.id(),
                                    belief -> belief.answered(event.answer(), event.evidence()));
            case SUPERSEDE -> replace(event.id(), belief -> belief.replacedBy(event.replacement()));
        }
    }

    private void add(BeliefEvent event) {
        if (beliefs.containsKey(event.id())) {
            throw new IllegalArgumentException("the belief log names " + event.id() + " twice");
        }
        beliefs.put(event.id(), new Belief(event.id(), event.text(), event.status(),
                                           event.ledgerLength(), event.evidence(), List.of(), null,
                                           event.supersedes(), null, event.tags(), null));
    }

    private void replace(String id, UnaryOperator<Belief> change) {
        beliefs.put(id, change.apply(known(id)));
    }

    private Belief known(String id) {
        Belief belief = beliefs.get(id);
        if (belief == null) {
            throw new IllegalArgumentException("there is no belief called " + id);
        }
        return belief;
    }

    private void replaceable(String id) {
        BeliefStatus status = known(id).status();
        if (!status.live()) {
            throw new IllegalArgumentException(id + " is already " + status.label()
                                               + ", so nothing can replace it");
        }
    }

    private static String stated(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a belief has to say something");
        }
        return text.strip();
    }

    /**
     * The next unused name.
     *
     * <p>Names are looked for rather than counted, because a log written by an older run -- or by
     * hand -- may already name one this store has not issued, and reusing it would merge two claims
     * into one.</p>
     */
    private String nextId() {
        String id;
        do {
            issued++;
            id = ID_PREFIX + issued;
        } while (beliefs.containsKey(id));
        return id;
    }

    private void load() {
        if (!Files.exists(path)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BeliefStoreException("cannot read the belief log at " + path, e);
        }
        for (int i = 0; i < lines.size(); i++) {
            int number = i + 1;
            try {
                apply(read(lines.get(i)));
            } catch (IllegalArgumentException e) {
                throw new BeliefStoreException("belief log line " + number + " cannot be applied: "
                                               + e.getMessage(), e);
            }
        }
    }

    @SuppressWarnings ("unchecked")
    private BeliefEvent read(String line) {
        if (line.isBlank()) {
            throw new IllegalArgumentException("the line is blank; the log is torn");
        }
        Object value = Json.parse(line);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("the line is not a record");
        }
        return BeliefEvent.fromValue((Map<String, Object>) value);
    }
}
