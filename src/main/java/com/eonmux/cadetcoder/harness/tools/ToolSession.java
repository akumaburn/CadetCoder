package com.eonmux.cadetcoder.harness.tools;

import com.eonmux.cadetcoder.harness.belief.BeliefStore;
import com.eonmux.cadetcoder.harness.budget.Budget;
import com.eonmux.cadetcoder.harness.certify.Certificate;
import com.eonmux.cadetcoder.harness.certify.Certification;
import com.eonmux.cadetcoder.harness.commit.CommitGate;
import com.eonmux.cadetcoder.harness.commit.CommitPolicy;
import com.eonmux.cadetcoder.harness.env.Environment;
import com.eonmux.cadetcoder.harness.ledger.Ledger;
import com.eonmux.cadetcoder.harness.model.ModelRegistry;
import com.eonmux.cadetcoder.harness.model.WorldModel;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything one run of the agent has, in one place the tools can reach.
 *
 * <h2>Why the certificates are held here and not on disk</h2>
 *
 * <p>A certificate is a claim about one model and one ledger head, and the head moves. Held for the
 * run, a certificate is either current or is replaced by a replay that makes it current, and the
 * question {@link Certificate#covers} asks always has a real answer. Filed alongside the model it
 * is also readable afterwards, which is what {@link ModelRegistry#epicycleWarning} needs -- but the
 * copy on disk is evidence for a human, never the thing a commit is allowed to run on.</p>
 *
 * <h2>Why the workspace is one directory</h2>
 *
 * <p>The ledger, the models, the beliefs and the notes are all the record of a single run and are
 * only meaningful together: a model digest that names nothing, or a belief whose evidence indexes a
 * ledger that has been replaced, is worse than no record at all. One directory is what makes a run
 * something that can be archived, inspected, or thrown away in one piece.</p>
 */
public final class ToolSession {

    /** What the ledger is called inside a run's workspace. */
    public static final String LEDGER_NAME = "ledger.jsonl";

    /** What the belief log is called inside a run's workspace. */
    public static final String BELIEFS_NAME = "beliefs.jsonl";

    /** What the agent's own notes are called inside a run's workspace. */
    public static final String NOTES_NAME = "notes.md";

    private final Environment   env;
    private final Path          workspace;
    private final Ledger        ledger;
    private final ModelRegistry registry;
    private final BeliefStore   beliefs;
    private final Budget        budget;
    private final CommitGate    gate;
    private final Notes         notes;
    private final Plans         plans = new Plans();

    private final Map<String, Certificate> certificates = new LinkedHashMap<>();

    /**
     * Opens a run against a workspace, building everything a run keeps there.
     *
     * @param env       the world to act on
     * @param workspace where this run's record lives
     * @param budget    what the run is allowed to spend
     * @param policy    what the commit gate allows
     * @return the session
     */
    public static ToolSession open(Environment env, Path workspace, Budget budget,
                                   CommitPolicy policy) {
        if (env == null || workspace == null || budget == null || policy == null) {
            throw new IllegalArgumentException("a run needs an environment, a workspace, a budget "
                                               + "and a commit policy");
        }
        Ledger ledger = new Ledger(workspace.resolve(LEDGER_NAME));
        return new ToolSession(env, workspace, ledger, new ModelRegistry(workspace),
                               new BeliefStore(workspace.resolve(BELIEFS_NAME)), budget,
                               new CommitGate(env, ledger, budget, policy));
    }

    /**
     * Builds a session on stores that already exist.
     *
     * @param env       the world to act on
     * @param workspace where this run's record lives
     * @param ledger    what really happened
     * @param registry  every version of the theory
     * @param beliefs   what the agent holds and what it is still asking
     * @param budget    what the run is allowed to spend
     * @param gate      the only way to the world
     */
    public ToolSession(Environment env, Path workspace, Ledger ledger, ModelRegistry registry,
                       BeliefStore beliefs, Budget budget, CommitGate gate) {
        if (env == null || workspace == null || ledger == null || registry == null
            || beliefs == null || budget == null || gate == null) {
            throw new IllegalArgumentException("a session cannot be missing any of its parts");
        }
        this.env       = env;
        this.workspace = workspace;
        this.ledger    = ledger;
        this.registry  = registry;
        this.beliefs   = beliefs;
        this.budget    = budget;
        this.gate      = gate;
        this.notes     = new Notes(workspace.resolve(NOTES_NAME));
    }

    /** The world this run acts on. */
    public Environment env() {
        return env;
    }

    /** Where this run's record lives. */
    public Path workspace() {
        return workspace;
    }

    /** What really happened. */
    public Ledger ledger() {
        return ledger;
    }

    /** Every version of the theory. */
    public ModelRegistry registry() {
        return registry;
    }

    /** What the agent holds and what it is still asking. */
    public BeliefStore beliefs() {
        return beliefs;
    }

    /** What the run is allowed to spend, and what it has. */
    public Budget budget() {
        return budget;
    }

    /** The only way to the world. */
    public CommitGate gate() {
        return gate;
    }

    /** The agent's own working notes. */
    public Notes notes() {
        return notes;
    }

    /** The plans this run has found. */
    public Plans plans() {
        return plans;
    }

    /**
     * The model a reference names.
     *
     * @param reference a digest, a unique prefix of one, or {@code latest}
     * @return the model
     * @throws com.eonmux.cadetcoder.harness.model.ModelStoreException if nothing answers to that name
     */
    public WorldModel model(String reference) {
        return registry.load(reference);
    }

    /**
     * A certificate for a model that is about the ledger there is now, replaying if it has to.
     *
     * @param model           the theory
     * @param strictGrounding whether drift is fatal for this question
     * @return a certificate that covers the current head
     */
    public Certificate certified(WorldModel model, boolean strictGrounding) {
        Certificate kept = certificates.get(model.digest());
        if (kept != null && kept.covers(ledger.head()) && kept.strictGrounding() == strictGrounding) {
            return kept;
        }
        return certify(model, strictGrounding);
    }

    /**
     * Replays the whole ledger through a model, whatever was known before.
     *
     * @param model           the theory
     * @param strictGrounding whether drift is fatal for this run
     * @return what the replay established
     */
    public Certificate certify(WorldModel model, boolean strictGrounding) {
        Certificate certificate = Certification.certify(model, ledger, strictGrounding);
        remember(certificate);
        return certificate;
    }

    /**
     * Keeps a certificate as the standing one for its model, and files a copy for inspection.
     *
     * @param certificate what a replay, or a commit that checked every step, established
     */
    public void remember(Certificate certificate) {
        if (certificate == null) {
            return;
        }
        certificates.put(certificate.modelDigest(), certificate);
        registry.saveCertificate(certificate.modelDigest(), certificate.toValue());
    }

    /**
     * The standing certificate for a model, if there is one and it is still current.
     *
     * @param digest which model
     * @return the certificate, or {@code null} when there is none or the ledger has moved since
     */
    public Certificate standing(String digest) {
        Certificate kept = certificates.get(digest);
        return kept != null && kept.covers(ledger.head()) ? kept : null;
    }
}
