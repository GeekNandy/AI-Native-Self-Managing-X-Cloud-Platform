# Durable coordinator replacement and isolated-test recovery

Opening a durable H2 or PostgreSQL `StateStore` claims a new owner and increments a persisted `leaderEpoch`. Every state transaction locks and checks that ownership before reading or changing records. A replacement cannot claim ownership midway through a checked transaction, and an old coordinator cannot start another successful state transaction afterward. The legacy PostgreSQL owner row is retained so an older coordinator is also fenced after an upgrade. In-memory test stores have no cross-process durability.

The owner is claimed on startup; it has no election or lease expiry. Keep one active coordinator. Starting competing replicas would replace each other, not create an HA cluster.

## What recovers

`RemoteJobBroker` stores immutable isolated-test inputs, their digest, owner/phase identity, original execution deadline, delivery count, and terminal result. Submission, lease assignment, heartbeat, completion, timeout, cancellation, and history eviction use short state-only transactions. Database transactions never wrap container execution or remote calls.

| Persisted job | Behavior under a replacement coordinator |
| --- | --- |
| `QUEUED` | Retain the inputs and deadline; adopt the new epoch. |
| `LEASED`, deadline still valid, fewer than two deliveries | Requeue with the new epoch and invalidate the previous lease. The next delivery receives a new fence. |
| `LEASED`, two deliveries already used | Record `WORKER_LOST`; do not dispatch a third attempt. |
| Active job whose deadline expired | Record `TIMEOUT`; restarting does not renew the budget. |
| Terminal result | Preserve the recorded result; inspecting or awaiting it does not execute another test. |

Only immutable, network-isolated test execution may be redelivered. A previous worker can continue briefly until its next heartbeat is rejected; its old epoch/fence cannot commit a result. This does not guarantee exactly-once container execution.

## Reattachment and inspection

The internal `submit(files, testClass, budget, owner, phase)` API returns a stable job ID for the same owner/phase. While that job remains in history, matching inputs reattach to the existing job and differing inputs are rejected. `await(id)` collects its eventual or recorded result without changing its deadline. The ordinary synchronous test API uses these same primitives.

Eligible jobs are dispatched by earliest original deadline, with job ID breaking ties. History eviction uses the same ordering among terminal receipts that have no local awaiting callers. This policy is explicit and remains stable across restart.

Job history is bounded by `PLATFORM_JOB_HISTORY`. Terminal receipts may be evicted when capacity is needed; idempotent submission and result retrieval apply only to retained jobs. After eviction, reusing an owner/phase can create a new execution. These keys are not a permanent ledger for infrastructure or financial effects.

Authenticated operator/agent clients can inspect `GET /api/code/workers/jobs/{id}`. The response includes status, input digest, ownership epoch, and a bounded result; it excludes source inputs and lease tokens. Missing or evicted jobs return 404. Worker credentials remain limited to the worker protocol.

## Recovery boundary

The repair orchestrator does not resume a model loop from these test receipts. Queued/running repairs remain `INTERRUPTED` on restart, and approvals or external infrastructure operations are not automatically replayed. A previously approved Git commit can still be reconciled through its existing approval journal. Continuing a model loop safely requires a durable checkpoint for the exact candidate, policy, model usage, attempt count, and remaining deadline.

The persisted epoch fences database state changes and accepted worker results. It cannot cancel an external call already sent, guarantee downstream cloud fencing, elect an etcd leader, renew a coordinator leadership lease, or establish production HA. The [leadership ADR](adr/0004-leadership-and-consensus.md) and [Raft lab](raft-consensus-lab.md) describe the remaining integration and failure experiments.

Workers must echo the lease's `leaderEpoch` in heartbeat and completion requests. Upgrade coordinator and worker JARs together following the [worker protocol procedure](remote-execution.md#worker-protocol-upgrade); older messages without the field are rejected.

## Validation

Run `./mvnw verify` with `TEST_DATABASE_URL` pointing to an isolated PostgreSQL instance for database replacement/concurrency coverage. `StateStoreLeadershipTest`, `PostgresStateTest`, `RemoteJobRecoveryTest`, and `WorkerProtocolTest` cover ownership, atomic transitions, recovery limits, retained receipts and the authenticated wire contract.

After packaging, run `python3 scripts/verify-coordinator-recovery.py`. It kills two real coordinator JVMs, restores the same H2 state, rejects old worker epochs, redelivers the same bounded job and verifies its receipt after another restart. The worker's result is an explicitly simulated protocol fixture; the script does not claim real container execution or model repair quality. CI retains `target/coordinator-recovery-evidence.json` alongside the existing restart and Docker worker evidence.
