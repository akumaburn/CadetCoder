package com.eonmux.cadetcoder.harness.commit;

import com.eonmux.cadetcoder.harness.budget.Budget;
import com.eonmux.cadetcoder.harness.certify.Certificate;
import com.eonmux.cadetcoder.harness.env.Environment;
import com.eonmux.cadetcoder.harness.ledger.Ledger;
import com.eonmux.cadetcoder.harness.ledger.Transition;
import com.eonmux.cadetcoder.harness.model.GroundingException;
import com.eonmux.cadetcoder.harness.model.WorldModel;
import com.eonmux.cadetcoder.harness.plan.PlanAudit;
import com.eonmux.cadetcoder.harness.plan.Planner;
import com.eonmux.cadetcoder.harness.plan.SimulatedStep;
import com.eonmux.cadetcoder.harness.plan.Simulation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only channel from thinking to acting.
 *
 * <h2>Why a plan is committed against a certificate rather than against a model</h2>
 *
 * <p>Anyone can hold a model. What entitles an agent to act on one is evidence that it survived
 * everything that has already happened, and that evidence is about a particular ledger. Passing the
 * certificate in makes the claim explicit and checkable at the moment of acting: it must be about
 * this model, it must be about the ledger as it stands, and -- unless the policy says a contradicted
 * model may still be explored with -- it must have held. There is no installed model to quietly go
 * stale behind the agent's back.</p>
 *
 * <h2>Why the plan is simulated first</h2>
 *
 * <p>Simulating is the last chance to find out that the model breaks on step four before the first
 * three have happened, and it is the only way to know which rules each step turns on. Steps that
 * reach a rule nothing has ever exercised are experiments, and a plan that walks confidently through
 * four of them in a row has learned nothing from any of them: the plan is cut off at the first, so
 * its outcome is observed before anything depends on it.</p>
 *
 * <h2>Why the gate remembers nothing</h2>
 *
 * <p>Where the world is, is read from the ledger every time. A cached observation is a second
 * version of the truth that drifts the moment anything else acts, and two gates on the same ledger
 * would disagree about a world they share. The ledger's last recorded observation is the world as of
 * the last thing that happened to it; only an empty ledger has to ask the environment.</p>
 */
public final class CommitGate {

    private final Environment  env;
    private final Ledger       ledger;
    private final Budget       budget;
    private final CommitPolicy policy;

    public CommitGate(Environment env, Ledger ledger, Budget budget, CommitPolicy policy) {
        if (env == null || ledger == null || budget == null || policy == null) {
            throw new IllegalArgumentException("a commit gate needs a world, a ledger, a budget and "
                                               + "a policy");
        }
        this.env    = env;
        this.ledger = ledger;
        this.budget = budget;
        this.policy = policy;
    }

    /** What this policy allows, as the agent is told it. */
    public CommitPolicy policy() {
        return policy;
    }

    /** Where the world is: what the ledger last recorded, or what the environment says. */
    public Object observation() {
        List<Transition> last = ledger.tail(1);
        return last.isEmpty() ? env.observe() : last.get(0).obsAfter();
    }

    /**
     * Starts a new episode, recording the reset as a transition like any other.
     *
     * @param note why the run started over, or {@code null}
     * @return what can be seen at the start of the new episode
     */
    public Object reset(String note) {
        Object           before = observation();
        Object           after  = env.reset();
        List<Transition> last   = ledger.tail(1);
        int              next   = last.isEmpty() ? 0 : last.get(0).episode() + 1;
        Transition proposal = Transition.proposal(next, before, Environment.RESET_ACTION, after,
                                                  Map.of(), Map.of());
        ledger.append(note == null || note.isEmpty() ? proposal : proposal.annotated(note));
        budget.countReset();
        return after;
    }

    /**
     * Acts with no model at all, to find out what the world does.
     *
     * @param actions the actions to take
     * @return what happened
     */
    public CommitResult probe(List<Object> actions) {
        return probe(actions, null);
    }

    /**
     * Acts with no model at all, to find out what the world does.
     *
     * @param actions the actions to take
     * @param note    a line for whoever reads the ledger later, or {@code null}
     * @return what happened
     */
    public CommitResult probe(List<Object> actions, String note) {
        return run(null, null, actions, false, note);
    }

    /**
     * Acts on a model, on the evidence that it has survived everything so far.
     *
     * @param model       the theory the plan was made in
     * @param certificate what a replay of the ledger established about it
     * @param actions     the plan
     * @return what happened
     */
    public CommitResult commit(WorldModel model, Certificate certificate, List<Object> actions) {
        return commit(model, certificate, actions, false, null);
    }

    /**
     * Acts on a model, on the evidence that it has survived everything so far.
     *
     * @param model         the theory the plan was made in
     * @param certificate   what a replay of the ledger established about it
     * @param actions       the plan
     * @param allowUntested whether the plan is deliberately an experiment and may run in full
     * @param note          a line for whoever reads the ledger later, or {@code null}
     * @return what happened
     */
    public CommitResult commit(WorldModel model, Certificate certificate, List<Object> actions,
                               boolean allowUntested, String note) {
        if (model == null) {
            throw new IllegalArgumentException("committing needs a model; use probe to act without "
                                               + "one");
        }
        return run(model, certificate, actions, allowUntested, note);
    }

    private CommitResult run(WorldModel model, Certificate certificate, List<Object> actions,
                             boolean allowUntested, String note) {
        String refusal = Refusals.size(actions, policy);
        if (refusal != null) {
            return CommitResult.refused(refusal);
        }
        refusal = model == null ? Refusals.blind(actions, policy)
                                : Refusals.certificate(model, certificate, ledger, policy);
        if (refusal != null) {
            return CommitResult.refused(refusal);
        }
        return model == null ? blind(actions, note)
                             : planned(model, certificate, actions, allowUntested, note);
    }

    private CommitResult blind(List<Object> actions, String note) {
        String refusal = Refusals.reversibility(env, actions, actions.size(), policy, null, null,
                                                PlanAudit.nothing());
        if (refusal != null) {
            return CommitResult.refused(refusal);
        }
        return act(null, null, null, actions, actions.size(), null, PlanAudit.nothing(), null,
                   note);
    }

    private CommitResult planned(WorldModel model, Certificate certificate, List<Object> actions,
                                 boolean allowUntested, String note) {
        Object state;
        try {
            state = model.regroundedOn(observation(), certificate.finalState());
        } catch (GroundingException failure) {
            return CommitResult.refused("this model cannot ground the observation the world is at: "
                                        + failure.getMessage());
        }
        Simulation simulation = Planner.simulate(model, state, actions);
        if (!simulation.ok()) {
            return CommitResult.refused("the plan does not simulate: step " + simulation.errorAt()
                                        + ": " + simulation.error());
        }
        PlanAudit audit       = Planner.audit(simulation, certificate.coverage().hit());
        Integer   truncatedAt = null;
        int       count       = actions.size();
        if (!audit.clean() && !allowUntested) {
            truncatedAt = audit.firstUntested();
            count       = policy.untested() == UntestedRule.AFTER ? truncatedAt + 1 : truncatedAt;
        }
        String refusal = Refusals.reversibility(env, actions, count, policy, model, certificate,
                                                audit);
        if (refusal != null) {
            return CommitResult.refused(refusal);
        }
        if (count == 0) {
            return CommitResult.nothingRan(truncatedAt, audit, certificate);
        }
        return act(model, certificate, state, actions, count, truncatedAt, audit, simulation, note);
    }

    private CommitResult act(WorldModel model, Certificate certificate, Object state,
                             List<Object> actions, int count, Integer truncatedAt, PlanAudit audit,
                             Simulation simulation, String note) {
        Execution execution = new Execution(env, ledger, budget, model, state, observation(),
                                            episode(), note);
        execution.run(actions, count);

        CommitStatus status    = execution.status();
        String       reason    = execution.reason();
        Integer      truncated = null;
        if (status == CommitStatus.COMPLETED && truncatedAt != null) {
            status    = CommitStatus.TRUNCATED_UNTESTED;
            truncated = truncatedAt;
            reason    = "the plan stopped at the first step that exercised an untested rule; look at "
                        + "what happened, then certify and plan again";
        }
        Certificate after = carried(certificate, status, simulation, execution.indices().size(),
                                    execution.state());
        return new CommitResult(status, execution.indices(), execution.observation(),
                                execution.flags(), execution.surprise(), reason, truncated, audit,
                                after);
    }

    /**
     * The certificate as it stands after the commit.
     *
     * <p>Steps that were checked live and held were checked by the same code the replay uses, so
     * carrying the certificate over them costs one step each instead of one whole ledger. A surprise
     * is the case where that would be a lie, so the certificate is left exactly where it was --
     * behind the ledger, and no longer covering its head.</p>
     */
    private Certificate carried(Certificate certificate, CommitStatus status, Simulation simulation,
                                int executed, Object state) {
        if (certificate == null) {
            return null;
        }
        if (status == CommitStatus.SURPRISE) {
            return certificate;
        }
        Set<String>         hit   = new LinkedHashSet<>();
        List<SimulatedStep> steps = simulation.steps();
        for (int at = 0; at < executed && at < steps.size(); at++) {
            hit.addAll(steps.get(at).arms());
        }
        return certificate.extended(ledger.head(), ledger.size(), executed, hit, state);
    }

    private int episode() {
        List<Transition> last = ledger.tail(1);
        return last.isEmpty() ? 0 : last.get(0).episode();
    }
}
