package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;
import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.*;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.*;
import java.util.*;

@EnabledIfEnvironmentVariable(named = "RUN_DOCKER_TESTS", matches = "true")
class DockerWorkflowTest {
    @TempDir Path directory;
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @org.junit.jupiter.api.AfterEach
    void closeMetrics() {
        metrics.close();
    }

    final tools.jackson.databind.json.JsonMapper json = new WorkbenchConfiguration().jsonMapper();

    @Test
    void realContainerRepairApprovalFeedbackComparisonAndPromotion() throws Exception {
        try (var store = new StateStore(json, directory.toString())) {
            var clock = Clock.systemUTC();
            var commands = new CommandRunner();
            var repositories = new RepositoryWorkspace(store, commands, clock);
            var sandbox = new DockerSandbox(commands, "eclipse-temurin:21-jdk");
            var tasks = new RepairTasks();
            var compiler = new IntentCompiler(new PolicyEngine(), json);
            var audit = new AuditLog(clock, store);
            var service =
                    new RepairService(
                            repositories,
                            sandbox,
                            new GeminiPatchPlanner("", "", json),
                            tasks,
                            store,
                            audit,
                            clock,
                            compiler);
            try {
                var task = tasks.examples().getFirst();
                var repository =
                        repositories.create(task.name(), task.files(), task.testClass(), task.id());
                var queued =
                        service.submit(
                                repository.id(), task.instruction(), "mutation-search", 8, 120);
                var run = awaitRun(service, queued.id());
                assertEquals("AWAITING_REVIEW", run.status(), json.writeValueAsString(run));
                assertEquals("FAIL", run.baseline().status());
                assertTrue(run.verification().passed());
                assertEquals(repository.head(), repositories.head(repository.id()));
                assertTrue(run.patch().contains("amount - 5"));
                assertFalse(run.patch().contains("src/test/"));
                assertEquals(
                        "DIGEST_MISMATCH",
                        assertThrows(
                                        PlatformException.class,
                                        () -> service.approve(run.id(), "0".repeat(64)))
                                .code());
                var approved = service.approve(run.id(), run.digest());
                assertEquals("APPROVED", approved.status());
                assertNotEquals(run.baseCommit(), approved.approvedCommit());
                assertEquals(approved, service.approve(run.id(), run.digest()));
                var comparison = service.compare();
                long limit = System.nanoTime() + Duration.ofMinutes(4).toNanos();
                do {
                    Thread.sleep(250);
                    comparison = service.comparisons().getFirst();
                } while (List.of("QUEUED", "RUNNING").contains(comparison.status())
                        && System.nanoTime() < limit);
                assertEquals("COMPLETED", comparison.status(), json.writeValueAsString(comparison));
                assertTrue(comparison.promotable(), json.writeValueAsString(comparison));
                assertEquals(
                        comparison.candidate().version(),
                        service.promote(comparison.id()).version());
                var evidence = Path.of("target/docker-repair-evidence.json");
                Files.createDirectories(evidence.getParent());
                Files.writeString(
                        evidence,
                        json.writeValueAsString(
                                Map.of(
                                        "repair",
                                        approved,
                                        "comparison",
                                        comparison,
                                        "activePolicy",
                                        service.activePolicy())));
            } finally {
                service.close();
            }
        }
    }

    @Test
    void actualWorkloadReplicasAreObservedLostAndRestoredAndUnsupportedClaimsAreDenied()
            throws Exception {
        try (var store = new StateStore(json, directory.toString())) {
            var compiler = new IntentCompiler(new PolicyEngine(), json);
            var adapter =
                    new DockerRuntimeAdapter(
                            new CommandRunner(), compiler, store, json, "eclipse-temurin:21-jdk");
            var control =
                    new ControlPlane(
                            compiler,
                            adapter,
                            new AuditLog(Clock.systemUTC(), store),
                            Clock.systemUTC(),
                            metrics,
                            store);
            var spec =
                    new IntentSpec(
                            "container-demo",
                            "java21",
                            "development",
                            "local",
                            true,
                            true,
                            "internal",
                            false,
                            1,
                            2,
                            true,
                            false);
            String environment = spec.environmentId();
            try {
                var unsupported = control.propose(EvaluationService.safe());
                assertEquals("BLOCKED", unsupported.status());
                var plan = control.propose(spec);
                assertEquals("AWAITING_APPROVAL", plan.status());
                assertEquals(
                        "CONVERGED",
                        control.apply(plan.id(), plan.digest(), "docker-provision-key")
                                .receipt()
                                .environment()
                                .convergence());
                assertEquals(2, control.refresh(environment).observed().replicas());
                var drift = control.injectFault(environment, "replica-loss");
                assertEquals(1, drift.observed().replicas());
                assertEquals("DRIFT_DETECTED", drift.convergence());
                var recovery = control.proposeReconciliation(environment);
                var result = control.apply(recovery.id(), recovery.digest(), "docker-recovery-key");
                assertEquals("CONVERGED", result.receipt().environment().convergence());
                assertEquals(1, result.receipt().environment().desiredVersion());
                Files.writeString(
                        Path.of("target/docker-runtime-evidence.json"),
                        json.writeValueAsString(
                                Map.of("provision", plan, "drift", drift, "recovery", result)));
            } finally {
                adapter.dispose(environment);
            }
        }
    }

    @Test
    void cancellationStopsAnActualHangingTestAndCleansItsContainer() throws Exception {
        try (var store = new StateStore(json, directory.toString())) {
            var commands = new CommandRunner();
            var clock = Clock.systemUTC();
            var repositories = new RepositoryWorkspace(store, commands, clock);
            var files =
                    Map.of(
                            "src/main/java/Rules.java",
                            "public class Rules {}",
                            "src/test/java/RulesTest.java",
                            "public class RulesTest { public static void main(String[] a) { for"
                                + " (;;) {} } }");
            var r =
                    repositories.create(
                            "Cancellation probe", files, "RulesTest", "imported-snapshot");
            var service =
                    new RepairService(
                            repositories,
                            new DockerSandbox(commands, "eclipse-temurin:21-jdk"),
                            new GeminiPatchPlanner("", "", json),
                            new RepairTasks(),
                            store,
                            new AuditLog(clock, store),
                            clock,
                            new IntentCompiler(new PolicyEngine(), json));
            try {
                var run =
                        service.submit(
                                r.id(),
                                "Observe cancellation without changing sources.",
                                "mutation-search",
                                2,
                                30);
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (service.require(run.id()).steps().isEmpty() && System.nanoTime() < deadline)
                    Thread.sleep(30);
                Thread.sleep(500);
                service.cancel(run.id());
                var cancelled = awaitRun(service, run.id());
                assertEquals("CANCELLED", cancelled.status());
                assertEquals(r.head(), repositories.head(r.id()));
                var containers =
                        commands.run(
                                null,
                                Duration.ofSeconds(3),
                                List.of(
                                        "docker",
                                        "ps",
                                        "--all",
                                        "--quiet",
                                        "--filter",
                                        "label=xcloud.sandbox=true"));
                assertEquals("", containers.output().strip());
            } finally {
                service.close();
            }
        }
    }

    private RepairRun awaitRun(RepairService service, String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(150).toNanos();
        RepairRun run;
        do {
            Thread.sleep(150);
            run = service.require(id);
        } while (List.of("QUEUED", "RUNNING").contains(run.status())
                && System.nanoTime() < deadline);
        return run;
    }
}
