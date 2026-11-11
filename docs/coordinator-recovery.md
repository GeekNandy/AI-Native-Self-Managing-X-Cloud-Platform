# Fenced coordinator recovery

The Java coordinator now has a durable leadership contract. Each ownership change increments a database epoch. All state reads and writes, conditional worker transitions, and environment operation journals check the owner, epoch and unexpired lease inside the same transaction. PostgreSQL supplies the lease clock. A failed or expired instance permanently refuses requests until restarted; it cannot renew an expired epoch or resume with stale in-memory state.

## Modes

| Setting | Behavior |
| --- | --- |
| `PLATFORM_COORDINATOR_MODE=single` (default) | One configured coordinator. Starting a replacement increments the epoch and fences the previous process, retaining the previous deployment behavior. |
| `PLATFORM_COORDINATOR_MODE=lease` | PostgreSQL only. A candidate waits for the current lease to expire or be released, acquires the next epoch, then loads application state. An unexpired leader cannot be displaced. |
| `PLATFORM_COORDINATOR_LEASE_SECONDS=30` | Lease lifetime, 5–300 seconds. Renewal runs every third of the lifetime. Use a value longer than observed database/startup pauses. |
| `PLATFORM_COORDINATOR_ACQUIRE_TIMEOUT_SECONDS=60` | Startup wait, 0–600 seconds. A standby that times out exits; a supervisor can restart it. |

Lease mode is deliberately restricted in code to `PLATFORM_RUNTIME=sandbox`, `PLATFORM_EXECUTION_MODE=remote`, and an empty `GITHUB_PUBLISH_REPOSITORY`. The simulator's state changes are database-fenced. External cloud/Docker runtime writes and GitHub publication do not enforce downstream epochs and cannot be enabled in this topology. The existing single mode retains those capabilities.

A standby blocks before loading mutable application services and does not serve ready traffic. This is cold standby takeover, not a readable follower or an active-active deployment. Use `/readyz` for traffic routing; `/actuator/health` remains liveness. A resumed old leader is alive but returns 503 for readiness and application requests. It must be restarted to reload state before ever acquiring leadership again.

Use one PostgreSQL database, separate writable directories per coordinator, the same configured operator/agent/worker credentials, and the same application release and settings. Never share a coordinator directory between running processes. A stale process can still clean up its own disposable files; it must not touch a successor's directory. `deploy/xcloud-coordinator@.service` and `deploy/coordinator.env.example` supply a restart-supervised host template. They do not provision a database, route traffic or deploy services.

## Repair recovery

Only a remote `mutation-search` repair with a persisted checkpoint is eligible for automatic recovery. Its checkpoint pins the search policy; its base Git revision, instruction, attempt limit and creation-time deadline remain fixed. A recovered run reconstructs its deterministic steps and reuses durable test results keyed by `(run ID, phase)`. Reusing a step with different inputs is rejected. Already completed steps are retained while their parent repair is active, even if normal history pruning would otherwise evict them.

An in-flight worker keeps its original deadline and delivery count. On takeover, its previous epoch/token cannot heartbeat or complete. The successor waits until the old 20-second worker lease expires before allowing the one remaining delivery. Only isolated tests can be redelivered. A crash during the second delivery ends as `WORKER_LOST` after expiry. Completed results and terminal jobs are not rescheduled. Queue transitions use a conditional write so a stale scheduler cannot overwrite a completion.

Expired, cancelled, legacy, uncheckpointed, generative and local-execution runs are not automatically resumed. Live model requests may already have incurred charges, so they remain interrupted. Comparisons/evaluations also remain interrupted. Recovered passing repairs stop at `AWAITING_REVIEW`; approval and publication are never replayed automatically. Operator cancellation and graceful shutdown terminate the current execution; recovery targets abrupt loss of the coordinator.

## Environment operations and upgrades

Before an adapter call, the pending environment marker and an effect intent commit together. The intent binds the reviewed plan digest, idempotency key, operation ID and leader epoch. A completed receipt and `COMPLETED` effect status commit together. If the process dies after an uncertain effect, recovery marks it `UNKNOWN`, invalidates the previous plan revision and requires observation plus fresh approval. It does not retry the adapter by assumption.

The schema addition is `workbench_leadership`; existing state payloads remain readable. Worker jobs without `leaderEpoch` migrate to epoch 0 and are interrupted unless backed by a new checkpoint. Lease tokens remain opaque strings in the unchanged worker HTTP protocol. PostgreSQL's legacy owner row is still updated and checked so old binaries are fenced during an upgrade. **Do not run mixed versions or mixed coordination modes**: an old binary or single-mode replacement can intentionally replace a lease-mode writer. Stop old instances before enabling lease mode.

Epochs increase within one authoritative database history. A backup restore rewinds that history: stop all coordinators/workers, isolate the original database, restore, rotate worker credentials, and only then restart. Do not restore a backup beneath live coordinators or reconnect old workers to a restored cluster. The backup verifier now fingerprints the leadership row as well as all application payloads. PostgreSQL failover, point-in-time recovery and production backup schedules remain separate infrastructure responsibilities.

## Evidence

```bash
./mvnw verify
# Requires Docker, the Java runner image, and a disposable *_test PostgreSQL database:
TEST_DATABASE_URL=postgres://.../workbench_test python3 scripts/verify-coordinator-failover.py
```

Java tests cover competing ownership, replacement/expired epochs, conditional transition conflicts, legacy migration, result reuse, immutable step inputs, bounded redelivery, and a resumed repair stopping for review. PostgreSQL-specific tests run when `TEST_DATABASE_URL` is present.

The process experiment creates and removes its own database. It starts a leader and a waiting candidate, pauses the leader with SIGSTOP, observes takeover, resumes the old leader and checks 503/stale-worker rejection. A synthetic initial worker claim gives the test an old opaque token; its replacement executes actual Docker tests and produces a patch awaiting review. The experiment then SIGKILLs the successor and verifies committed receipts and the reviewed patch in a third JVM. It records measured takeover time and checks in `target/coordinator-failover-evidence.json`.

This is PostgreSQL-coordinated recovery, not Java integration with the separate etcd/Raft lab. It does not establish external provider fencing, database HA, traffic routing, asymmetric network-partition behavior, or production RTO/RPO. The next consensus step is to evaluate whether an independent etcd dependency is needed and, if so, integrate its lease renewal/quorum-loss gate with this database fencing contract.
