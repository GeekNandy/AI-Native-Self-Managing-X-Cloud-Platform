package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/** Serializes mutations per environment. Model output can create proposals, never invoke apply. */
@Service
public class ControlPlane {
    private static final Duration PLAN_TTL = Duration.ofMinutes(15);
    private final IntentCompiler compiler;
    private final RuntimeAdapter adapter;
    private final StateStore store;
    private final AuditLog audit;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final ConcurrentHashMap<String, Plan> plans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Environment> environments = new ConcurrentHashMap<>();
    // Same bounded receipt objects as the environment ledger, retained independently of expired plan details.
    private final ConcurrentHashMap<String, Receipt> completedPlans = new ConcurrentHashMap<>();

    private static final class Environment {
        final String id;
        long revision;
        long desiredVersion;
        IntentSpec desired;
        IntentSpec observed;
        String pendingPlan;
        final HashMap<String, Receipt> receipts = new HashMap<>();
        Environment(String id) { this.id = id; }
    }

    public record EnvironmentSnapshot(String id, long revision, long desiredVersion, IntentSpec desired,
            IntentSpec observed, List<Receipt> receipts, String pendingPlan) {}
    public ControlPlane(IntentCompiler compiler, RuntimeAdapter adapter, AuditLog audit, Clock clock, MeterRegistry metrics) {
        this(compiler, adapter, audit, clock, metrics, StateStore.volatileStore());
    }
    @Autowired public ControlPlane(IntentCompiler compiler, RuntimeAdapter adapter, AuditLog audit, Clock clock, MeterRegistry metrics, StateStore store) {
        this.compiler = compiler; this.adapter = adapter; this.audit = audit; this.clock = clock; this.metrics = metrics; this.store = store;
        for (var saved : store.all("environments", EnvironmentSnapshot.class)) {
            var e = new Environment(saved.id()); e.revision = saved.revision(); e.desiredVersion = saved.desiredVersion();
            e.desired = saved.desired(); e.observed = saved.observed(); e.pendingPlan = saved.pendingPlan();
            saved.receipts().forEach(r -> { e.receipts.put(r.idempotencyKey(), r); completedPlans.put(r.planId(), r); });
            environments.put(e.id, e);
            if (e.pendingPlan != null) {
                String interrupted = e.pendingPlan; e.observed = null; e.revision++; e.pendingPlan = null; persist(e);
                audit.append("OPERATION_RECOVERY_REQUIRED", "recovery", e.id, interrupted, "Execution was interrupted. Observation is unknown; refresh and review a new plan.");
            }
        }
        for (var saved : store.all("plans", Plan.class)) {
            if (saved.expiresAt().isAfter(clock.instant())) plans.put(saved.id(), saved);
            else store.remove("plans", saved.id());
        }
    }
    private void persist(Environment e) {
        store.put("environments", e.id, new EnvironmentSnapshot(e.id, e.revision, e.desiredVersion, e.desired, e.observed,
                List.copyOf(e.receipts.values()), e.pendingPlan));
    }
    public String executionMode() { return adapter.mode(); }
    private PolicyReport analyze(IntentSpec spec) {
        var base = compiler.analyze(spec); var checks = new ArrayList<>(base.checks()); checks.addAll(adapter.capabilityChecks(spec));
        return new PolicyReport(base.version(), base.allowed() && checks.stream().allMatch(PolicyCheck::passed) ? "ALLOW" : "DENY", checks);
    }

    public Plan propose(IntentSpec spec) {
        store.requireAvailable();
        PolicyReport policy = analyze(spec);
        var environment = reserveEnvironment(spec.environmentId());
        synchronized (environment) {
            return savePlan(environment, spec, environment.desired == null ? "PROVISION" : "UPDATE", policy,
                    compiler.actions(spec));
        }
    }

    public Plan proposeReconciliation(String environmentId) {
        store.requireAvailable();
        var environment = requireEnvironment(environmentId);
        synchronized (environment) {
            var state = view(environment);
            if (environment.desired == null) throw new PlatformException(409, "NOT_PROVISIONED", "No approved desired state exists.");
            if ("UNKNOWN".equals(state.convergence()))
                throw new PlatformException(409, "OBSERVATION_REQUIRED", "Restore observation before proposing remediation.");
            if (state.drift().isEmpty()) throw new PlatformException(409, "NO_DRIFT", "The environment is already converged.");
            var actions = new ArrayList<Action>();
            for (var drift : state.drift()) actions.add(new Action(actions.size() + 1, drift.field(), "RESTORE",
                    "Restore " + drift.field() + " from " + drift.observed() + " to " + drift.expected(), List.of()));
            return savePlan(environment, environment.desired, "RECONCILE", analyze(environment.desired), actions);
        }
    }

    private Plan savePlan(Environment environment, IntentSpec target, String kind, PolicyReport policy, List<Action> actions) {
        String id = UUID.randomUUID().toString();
        var created = clock.instant();
        var expires = created.plus(PLAN_TTL);
        var artifacts = compiler.artifacts(target);
        String digest = IntentCompiler.digest(compiler.serialize(List.of(id, environment.id, kind,
                environment.revision, target, policy, actions, artifacts, created, expires)));
        var plan = new Plan(id, environment.id, kind, policy.allowed() ? "AWAITING_APPROVAL" : "BLOCKED",
                environment.revision, target, policy, actions, artifacts, digest, created, expires);
        synchronized (plans) {
            plans.entrySet().removeIf(entry -> {
                if (!entry.getValue().expiresAt().isBefore(clock.instant())) return false;
                store.remove("plans", entry.getKey()); return true;
            });
            if (plans.size() >= 500) throw new PlatformException(429, "PLAN_CAPACITY", "The local workbench has reached its plan limit. Wait for plans to expire.");
            persist(environment);
            store.put("plans", id, plan);
            plans.put(id, plan);
        }
        metrics.counter("platform.plans", "decision", policy.decision()).increment();
        audit.append("POLICY_EVALUATED", "control-plane", environment.id, id, policy.version() + ": " + policy.decision());
        audit.append(policy.allowed() ? "PLAN_CREATED" : "PLAN_DENIED", "control-plane", environment.id, id,
                kind + " bound to revision " + environment.revision);
        return plan;
    }

    public Plan plan(String id) {
        var plan = plans.get(id);
        if (plan == null) throw new PlatformException(404, "PLAN_NOT_FOUND", "Plan not found or no longer retained.");
        return plan;
    }

    public ApplyResult apply(String id, String approvedDigest, String idempotencyKey) {
        store.requireAvailable();
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{8,100}"))
            throw new PlatformException(400, "INVALID_IDEMPOTENCY_KEY", "Use an 8-100 character idempotency key containing letters, digits, hyphens or underscores.");
        var completed = completedPlans.get(id);
        if (completed != null) {
            if (!completed.digest().equals(approvedDigest))
                throw new PlatformException(409, "DIGEST_MISMATCH", "Approval must reference the exact reviewed plan digest.");
            if (!completed.idempotencyKey().equals(idempotencyKey))
                throw new PlatformException(409, "ALREADY_APPLIED", "This plan already executed. Reuse its original idempotency key to retrieve the receipt.");
            return new ApplyResult(completed, true);
        }
        var plan = plan(id);
        var environment = requireEnvironment(plan.environmentId());
        synchronized (environment) {
            if (!plan.digest().equals(approvedDigest)) reject(plan, "DIGEST_MISMATCH", "Approval must reference the exact reviewed plan digest.");
            var previous = environment.receipts.get(idempotencyKey);
            if (previous != null) {
                if (!previous.planId().equals(id)) reject(plan, "IDEMPOTENCY_CONFLICT", "This key is already bound to another plan in this environment.");
                return new ApplyResult(previous, true);
            }
            if (!"AWAITING_APPROVAL".equals(plan.status())) reject(plan, "POLICY_DENIED", "A denied plan cannot be executed.");
            if (!clock.instant().isBefore(plan.expiresAt())) reject(plan, "PLAN_EXPIRED", "This plan expired. Generate and review a fresh plan.");
            if (environment.revision != plan.baseRevision()) reject(plan, "STALE_PLAN", "Environment state changed after planning. Generate and review a fresh plan.");
            if (!analyze(plan.intent()).allowed()) reject(plan, "POLICY_CHANGED", "The current policy no longer permits this plan.");
            if (environment.receipts.size() >= 200) throw new PlatformException(429, "OPERATION_CAPACITY", "The environment reached its local operation limit.");
            String operationId = UUID.randomUUID().toString();
            audit.append("PLAN_APPROVED", "local-operator", environment.id, id, "Reviewed digest " + plan.digest());
            audit.append("OPERATION_STARTED", "control-plane", environment.id, id, operationId);
            environment.pendingPlan = id;
            persist(environment); // Write-ahead marker precedes every external side effect.
            IntentSpec observed;
            try {
                observed = adapter.apply(environment.id, plan.intent());
                if (!plan.intent().equals(observed)) throw new IllegalStateException("Adapter did not converge");
            } catch (RuntimeException failure) {
                // Preserve the last committed desired state and invalidate proposals after uncertain execution.
                try { environment.observed = adapter.observe(environment.id); } catch (RuntimeException unavailable) { environment.observed = null; }
                environment.revision++;
                environment.pendingPlan = null; persist(environment);
                audit.append("OPERATION_FAILED", "control-plane", environment.id, id, "Adapter failed; refresh and replan.");
                metrics.counter("platform.operations", "outcome", "failed").increment();
                throw new PlatformException(502, "ADAPTER_FAILED", "Sandbox execution failed. State was re-observed; review a fresh plan before retrying.");
            }
            if (!"RECONCILE".equals(plan.kind())) {
                environment.desired = plan.intent();
                environment.desiredVersion++;
            }
            environment.observed = observed;
            environment.revision++;
            var receipt = new Receipt(operationId, id, plan.digest(), idempotencyKey, view(environment), clock.instant());
            environment.receipts.put(idempotencyKey, receipt);
            environment.pendingPlan = null;
            persist(environment);
            completedPlans.put(id, receipt);
            audit.append("OPERATION_SUCCEEDED", "control-plane", environment.id, id, operationId + "; " + receipt.environment().convergence());
            metrics.counter("platform.operations", "outcome", "succeeded").increment();
            return new ApplyResult(receipt, false);
        }
    }

    private void reject(Plan plan, String code, String message) {
        audit.append("EXECUTION_REJECTED", "control-plane", plan.environmentId(), plan.id(), code);
        throw new PlatformException(409, code, message);
    }

    public EnvironmentView injectFault(String id, String kind) {
        store.requireAvailable();
        var environment = requireEnvironment(id);
        synchronized (environment) {
            adapter.inject(id, kind);
            environment.observed = adapter.observe(id);
            environment.revision++;
            persist(environment);
            var state = view(environment);
            audit.append("SANDBOX_FAULT_INJECTED", "local-operator", id, "fault", kind);
            audit.append("UNKNOWN".equals(state.convergence()) ? "OBSERVATION_LOST" : "DRIFT_DETECTED",
                    "observer", id, "fault", state.convergence());
            metrics.counter("platform.drift.observations", "convergence", state.convergence()).increment();
            return state;
        }
    }

    public EnvironmentView refresh(String id) {
        store.requireAvailable();
        var e = requireEnvironment(id);
        synchronized (e) {
            var observed = adapter.observe(id);
            if (!Objects.equals(e.observed, observed)) { e.observed = observed; e.revision++; persist(e); }
            return view(e);
        }
    }

    public EnvironmentView environment(String id) {
        var environment = requireEnvironment(id);
        synchronized (environment) { return view(environment); }
    }

    public List<EnvironmentView> environments() {
        return environments.values().stream().map(e -> { synchronized (e) { return view(e); } })
                .filter(e -> e.desired() != null).sorted(Comparator.comparing(EnvironmentView::id)).toList();
    }

    private Environment reserveEnvironment(String id) {
        synchronized (environments) {
            if (!environments.containsKey(id) && environments.size() >= 100)
                throw new PlatformException(429, "ENVIRONMENT_CAPACITY", "The local workbench supports at most 100 retained environments.");
            return environments.computeIfAbsent(id, Environment::new);
        }
    }

    private Environment requireEnvironment(String id) {
        var environment = environments.get(id);
        if (environment == null) throw new PlatformException(404, "ENVIRONMENT_NOT_FOUND", "Environment not found.");
        return environment;
    }

    private EnvironmentView view(Environment e) {
        var differences = new ArrayList<Drift>();
        if (e.desired != null && e.observed != null) {
            diff(differences, "serviceName", e.desired.serviceName(), e.observed.serviceName(), "HIGH");
            diff(differences, "runtime", e.desired.runtime(), e.observed.runtime(), "HIGH");
            diff(differences, "tier", e.desired.tier(), e.observed.tier(), "HIGH");
            diff(differences, "region", e.desired.region(), e.observed.region(), "CRITICAL");
            diff(differences, "privateNetwork", e.desired.privateNetwork(), e.observed.privateNetwork(), "CRITICAL");
            diff(differences, "restrictedEgress", e.desired.restrictedEgress(), e.observed.restrictedEgress(), "CRITICAL");
            diff(differences, "dataClassification", e.desired.dataClassification(), e.observed.dataClassification(), "CRITICAL");
            diff(differences, "encryptedStorage", e.desired.encryptedStorage(), e.observed.encryptedStorage(), "CRITICAL");
            diff(differences, "zones", e.desired.zones(), e.observed.zones(), "HIGH");
            diff(differences, "replicas", e.desired.replicas(), e.observed.replicas(), "HIGH");
            diff(differences, "monitoring", e.desired.monitoring(), e.observed.monitoring(), "HIGH");
            diff(differences, "rollback", e.desired.rollback(), e.observed.rollback(), "HIGH");
        }
        String convergence = e.desired == null || e.observed == null ? "UNKNOWN" : differences.isEmpty() ? "CONVERGED" : "DRIFT_DETECTED";
        return new EnvironmentView(e.id, e.revision, e.desiredVersion, convergence, e.desired, e.observed, differences);
    }

    private static void diff(List<Drift> result, String field, Object expected, Object actual, String severity) {
        if (!Objects.equals(expected, actual)) result.add(new Drift(field, expected, actual, severity));
    }
}
