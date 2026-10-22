# Remote execution

The web coordinator queues bounded test jobs. A pull-only Docker worker claims a job with a unique lease token, renews it every five seconds, and returns an input-bound result. A 20-second heartbeat expiry permits one redelivery; stale tokens cannot complete, cancel or renew a replacement lease. Cancellation revokes the lease. Coordinator restart marks unfinished jobs interrupted and does not replay a repair or approval.

Run the **Hosted repair worker** Actions workflow on `feature` for a 20-minute review window. Before that workflow exists on the default branch, rerun the latest worker job from Actions; manual workflow dispatch requires the file on the default branch. The workflow uses a GitHub-issued OIDC token bound to the coordinator audience, repository and owner numeric IDs, exact workflow/ref, and push/manual event. Worker credentials only authorize `/api/worker/*`; operator credentials cannot claim jobs. Standalone workers retain their owner identity in `.workbench/worker` (override with `PLATFORM_WORKER_DATA_DIR`), so a restarted worker can reap only its own orphan containers. A Docker-capable machine may instead use a distinct private `PLATFORM_WORKER_TOKEN` (at least 32 characters).

```sh
docker build -f sandbox/java21/Dockerfile -t xcloud-java21-runner:1 .
PLATFORM_NODE_ROLE=worker \
PLATFORM_COORDINATOR_URL=https://your-workbench.example \
PLATFORM_SANDBOX_IMAGE=xcloud-java21-runner:1 \
java -jar target/AI-Native-Self-Managing-X-Cloud-Platform-0.0.1-SNAPSHOT.jar
```

Set credentials privately, not in shell history. Test containers have no network, credentials, Docker socket or host mounts. Plain Java uses 384 MiB/96 PIDs; the Maven profile uses 768 MiB/128 PIDs. Both have one CPU, a read-only root and immutable source/test inputs. A worker is trusted infrastructure; this protocol does not attest its operating system.

# Repository profiles

GitHub ingestion accepts a bounded public repository (or a privately configured GitHub token), resolves a ref to a commit, verifies blob hashes and retains provenance. The managed Git commit is a separate snapshot identifier. Symlinks, submodules, oversized/truncated trees and unsupported build configurations are rejected. If an anonymous GitHub API quota is exhausted, a fixed server-configured public sample repository may use a hook-free, shallow Git transport. This fallback does not accept arbitrary clone URLs or credentials. Other repositories require available API quota or a private read credential.

`java21-main-v1` runs a Java test main class. `java21-junit6-v1` supports Java 21 plus Jupiter 6.0.3 and the fixed offline Maven runner. The original POM is immutable evidence; repository plugins, parents, profiles and dependency downloads are not executed. `examples/ledger-service` is a public sample project with an intentional debit defect.

# State

Without `DATABASE_URL`, the coordinator uses local H2. With a private PostgreSQL URL, it stores state and integrity-checked Git bundles in PostgreSQL; local checkouts can be reconstructed after filesystem loss. A replacement coordinator fences the previous writer. Retain one active coordinator; this release does not implement horizontal coordinator replication.

The provisioned free Render database expires on 2026-11-21 and has no managed backups. Set its internal connection URL as private `DATABASE_URL` before relying on restart durability. Free web-service filesystems remain ephemeral.
