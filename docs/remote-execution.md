# Remote execution

The web coordinator queues bounded test jobs. A pull-only Docker worker claims a job with a unique lease token, renews it every five seconds, and returns an input-bound result. A 20-second heartbeat expiry permits one redelivery; stale tokens cannot complete, cancel or renew a replacement lease. Cancellation revokes the lease. Coordinator restart marks unfinished jobs interrupted and does not replay a repair or approval.

Run the **Hosted repair worker** Actions workflow with 1, 2 or 4 workers for a bounded 60–18,000 second window (manual default: one hour). Choose the branch matching the coordinator's exact OIDC workflow allowlist: currently `feature`; the prepared release Blueprint switches it to `master`. Each process has a distinct authenticated instance identity, so parallel workers can hold separate leases within one Actions run. Workers announce a deadline and drain jobs that would outlive it. Manual workflow dispatch requires the file on the default branch. The workflow uses GitHub-issued OIDC credentials bound to the coordinator audience, repository and owner numeric IDs, exact workflow/ref, and push/manual event. Worker credentials only authorize `/api/worker/*`; operator credentials cannot claim jobs. Standalone workers retain their owner identity in `.workbench/worker` (override with `PLATFORM_WORKER_DATA_DIR`), so a restarted worker can reap only its own orphan containers. SIGTERM also cancels current execution and performs owner-scoped cleanup. A Docker-capable machine may instead use a distinct private `PLATFORM_WORKER_TOKEN` (at least 32 characters).

```sh
docker build -f sandbox/java21/Dockerfile -t xcloud-java21-runner:1 .
PLATFORM_NODE_ROLE=worker \
PLATFORM_COORDINATOR_URL=https://your-workbench.example \
PLATFORM_SANDBOX_IMAGE=xcloud-java21-runner:1 \
java -jar target/AI-Native-Self-Managing-X-Cloud-Platform-0.0.1-SNAPSHOT.jar
```

Set credentials privately, not in shell history. Test containers have no network, credentials, Docker socket or host mounts. Plain Java uses 384 MiB/96 PIDs; the Maven profile uses 768 MiB/128 PIDs. Both have one CPU, a read-only root and immutable source/test inputs. A worker is trusted infrastructure; this protocol does not attest its operating system.

For continuous availability on an existing dedicated Docker host, use the restart-supervised worker template and capacity guidance in [scaling and activation](scaling-and-activation.md). Actions windows are not continuous hosting. Authenticated `/api/operations`, UI readiness cards and worker job summaries expose bounded capacity; public `/readyz` tests coordinator state/fencing without requiring a worker or a model.

# Repository profiles

GitHub ingestion accepts a bounded public repository (or a privately configured GitHub token), resolves a ref to a commit, verifies blob hashes and retains provenance. The managed Git commit is a separate snapshot identifier. Symlinks, submodules, oversized/truncated trees and unsupported build configurations are rejected. If an anonymous GitHub API quota is exhausted, a fixed server-configured public sample repository may use a hook-free, shallow Git transport. This fallback does not accept arbitrary clone URLs or credentials. Other repositories require available API quota or a private read credential.

`java21-main-v1` runs a Java test main class. `java21-junit6-v1` supports Java 21 plus Jupiter 6.0.3 and the fixed offline Maven runner. The original POM is immutable evidence; repository plugins, parents, profiles and dependency downloads are not executed. `examples/ledger-service` is a public sample project with an intentional debit defect.

# State

Without `DATABASE_URL`, the coordinator uses local H2. With a private PostgreSQL URL, it stores state and integrity-checked Git bundles in PostgreSQL; local checkouts can be reconstructed after filesystem loss. A replacement coordinator fences the previous writer. Retain one active coordinator; this release does not implement horizontal coordinator replication.

The provisioned free Render database expires on 2026-11-21 and has no managed backups. Set its internal connection URL as private `DATABASE_URL` before relying on restart durability. Free web-service filesystems remain ephemeral.
