# Runtime capabilities and durable state

## Two explicit runtime profiles

| Property | Default `sandbox` | Optional `docker` |
| --- | --- | --- |
| Execution | Durable simulator | Actual generated Java workloads in local containers |
| Regions and topology | Logical policy assertions | `local`, one zone, at most three replicas |
| Tier | Development/production simulation | Development only |
| Networking | Simulated booleans | Network namespace with no egress or published ports |
| Data | Logical classification/encryption assertions | Ephemeral public/internal data; no PII or encryption requirement |
| Observation | Durable simulated observations | Docker inspection and actual HTTP health probes |
| Faults | Replica loss, egress opening, observation loss | Actual replica removal and observation loss |
| Recovery | Restore approved simulated state | Recreate approved healthy local replicas |
| Unsupported guarantees | Explicitly simulated | Denied by adapter capability checks before approval |

Use `PLATFORM_RUNTIME=docker` and a separate `PLATFORM_DATA_DIR` for local execution. Pre-pull `eclipse-temurin:21-jdk`. The generated service runs as UID 10001 with read-only root, tmpfs, dropped capabilities and CPU/memory/PID limits. The trusted supervisor and compiler are part of the container setup; HTTP health checks run through `docker exec`. No cloud SDK, Kubernetes apply or cloud account is involved.

A deployment remains a PoC process, not an orchestrator with background scheduling. Workload supervisors live for 24 hours; runtime changes are observed on apply, fault actions and explicit refresh. Health-check monitoring is on demand. Automatic rollback, host disaster recovery and cross-zone failover are not implemented.

## Durable aggregate boundaries

H2 stores JSON contracts in an embedded file under the data directory. `WRITE_DELAY=0` commits every transaction synchronously; H2 file locking prevents another JVM from opening the same embedded database. The data directory is owner-only. Tokens are generated into owner-readable files and may instead be configured through `PLATFORM_OPERATOR_TOKEN` / `PLATFORM_AGENT_TOKEN` (different URL-safe values of 24-200 characters).

A single environment record atomically contains revision, desired version, desired/observed intent, pending operation marker and successful receipts. Plans, agent runs, feedback, evaluation history, repository metadata, repair runs, comparisons and active search policy have independent bounded records. Audit appends are durable but separate transactions; the execution journal is authoritative for recovery if an audit append is interrupted. This is single-node durability, not a distributed event log.

Apply proceeds under the environment lock:

1. Recheck digest, original key binding, expiry, revision, policy and adapter capabilities.
2. Commit a pending-operation marker before external effects.
3. Execute and independently observe the adapter.
4. Commit desired/observed state and the receipt together, then append audit evidence.

A process death before step 4 leaves an uncertain external write. On restart, the server preserves committed desired state, reports observation as `UNKNOWN`, increments the revision, clears the interrupted marker and records recovery-required evidence. It never replays the old operation automatically. Refresh observations and approve a new plan. A completed receipt survives plan expiry and returns on the same digest/key retry without another adapter call.

State-write failure makes subsequent mutations unavailable for that process. It cannot return an uncommitted in-memory receipt on retry. Restart after correcting storage failure, then inspect journal/observations before proceeding.

## Retention and operation

Retain at most 100 environment identities, 500 unexpired plans, 500 interpreter runs, 100 reviewed intent cases, 200 receipts per environment, 20 intent reports, 25 managed repositories, 200 repair runs, 20 paired comparisons and 2,000 audit events. Restart does not reset these limits. Use a new data directory for an independent experiment; export evidence before retiring a workspace.

Back up the entire data directory with the server stopped, including managed Git repositories and token files. Keep it private. This release has no automatic schema migration, key rotation endpoint, tenant isolation, distributed lease service or horizontal scaling. Do not run multiple application nodes against the embedded file. The larger architecture documents describe future contracts.

`python3 scripts/verify-restart.py` performs an actual SIGKILL and new-JVM receipt/observation check. Java tests also simulate interruption between an external write and receipt commit. Docker integration tests validate real replica loss and governed recovery.
