# Scaling and live activation

## Implemented topology

One coordinator owns repair state, approval decisions and a bounded execution queue. Docker-capable workers pull immutable test jobs; they receive a worker credential, never an operator credential. Tests run in separate containers without network, host mounts, secrets or a Docker socket. Model providers propose source replacements and cannot execute or approve them.

PostgreSQL stores metadata and integrity-checked Git archives. Starting a replacement coordinator fences the old writer. This is single-coordinator recovery, not concurrent coordinator HA. Keep `numInstances: 1`; scale execution workers independently.

| Setting | Default | Accepted range / relationship |
| --- | --- | --- |
| `PLATFORM_REPAIR_WORKERS` | 2 | 1–16 repair orchestration slots |
| `PLATFORM_REPAIR_QUEUE` | 16 | 1–128 waiting repairs |
| `PLATFORM_REMOTE_WORKERS` | 4 | 1–32 registered execution workers |
| `PLATFORM_REMOTE_JOBS` | 16 | At least the larger worker count; at most 128 active test jobs |
| `PLATFORM_JOB_HISTORY` | 200 | At least remote jobs + repair slots; at most 1,000 jobs |

Invalid combinations stop startup. Saturation returns HTTP 429 rather than growing queues without bounds. A terminal job cannot be evicted while its submitting repair is still collecting the result. Existing repository/run retention limits remain in force. Increasing a limit does not demonstrate additional throughput: size it against memory, provider quotas and measured workloads.

The committed Render configuration selects four orchestration slots and four execution workers. Each worker executes one test at a time. A plain Java test has 1 CPU, 384 MiB and 96 PIDs; the Maven profile has 1 CPU, 768 MiB and 128 PIDs. Include worker JVM/daemon overhead when sizing its host.

## Leases, draining and failure behavior

Each worker process adds a stable instance identifier to its authenticated principal. Parallel jobs in the same Actions run consequently get different lease owners, even when GitHub renews their OIDC tokens. The identifier adds no permissions. Legacy single-worker clients remain compatible.

Workers announce their acceptance deadline. The broker assigns a job only when its immutable execution deadline fits inside that window; a worker near shutdown drains instead of accepting work it cannot finish. Heartbeats run every five seconds. A 20-second lease expiry permits one redelivery with a new fence. Late completion, heartbeat or cancellation from the old fence is rejected.

SIGTERM cancels current execution and removes containers carrying that worker's persisted owner label. SIGKILL cannot execute cleanup; starting the same worker data directory reaps its abandoned containers before accepting more work. Cancellation revokes a lease, while coordinator restart marks unfinished jobs interrupted. Repair and approval operations are not automatically replayed.

## Worker availability

The **Hosted repair worker** workflow accepts 1, 2 or 4 workers and a 60–18,000 second window. Manual runs default to one hour. The existing path-triggered feature run remains one worker for 20 minutes. Dispatch on the branch named by the coordinator's exact `WORKER_OIDC_WORKFLOW` allowlist. The release Blueprint uses `master`; until applied, the existing hosted service still uses its current feature configuration.

Actions windows are bounded demonstration capacity. For continuous operation on an existing dedicated Linux Docker host, use [the systemd template](../deploy/xcloud-worker@.service). It restarts each worker after its five-hour drain window or process failure, preserves a separate owner directory per instance and limits restart storms. It is not installed automatically and provisions no host.

Prepare the host with Java 21, Git, Docker, a dedicated `xcloud-worker` user and Docker access. Build the pinned `xcloud-java21-runner:1` image, install the verified application JAR as `/opt/xcloud/workbench.jar`, and create root-owned `/etc/xcloud/worker.env` with mode 0600 using [the example](../deploy/worker.env.example). Supply a dedicated worker token privately on both coordinator and host. Install the template, then enable the desired instances:

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now xcloud-worker@1 xcloud-worker@2 xcloud-worker@3 xcloud-worker@4
```

Docker access makes the worker host trusted infrastructure. The unit's process hardening does not turn Docker daemon access into an untrusted tenant boundary. Do not put an operator token, GitHub publication token or model key on this host. Future managed sandbox/microVM hosting remains a separate infrastructure choice; a standard Render web service cannot run this Docker worker inside itself.

## Deploy the existing Render service

The root [render.yaml](../render.yaml) is prepared for the existing `x-cloud-workbench` service in its existing workspace and Frankfurt region. It retains the free plan, one coordinator, and the existing database. `DATABASE_URL` references `x-cloud-workbench-state` directly, so its private connection string need not be copied into a repository or chat. The database must already exist in the same workspace; the Blueprint does not create one.

After merging the release into protected `master`, adopt/sync the Blueprint in Render. Review that it updates the existing service rather than introducing another resource. Its changes are `master` releases after passing checks, `/readyz` HTTP health checks, PostgreSQL wiring, `DEMO_EPHEMERAL=false`, four repair slots, and the matching master-branch worker OIDC allowlist. Existing omitted model credentials and other environment variables are preserved; username/password values remain private. A public-repository-URL deployment might still need manual deployment until its GitHub integration is connected.

This file has been validated against Render's current public JSON Schema. Schema validation is not a platform adoption or deployment result. No Blueprint sync, plan upgrade or new recurring resource is implied by committing it.

The existing free PostgreSQL database expires on **2026-11-21** and has no managed backups. Durable operation beyond that date requires a storage decision, an approved plan if necessary, and a restore drill. A free sleeping web service and temporary Actions workers are not an always-on production deployment.

## Observe actual readiness

`GET /readyz` is public and returns only `READY` or `NOT_READY`, with no caching. It verifies state availability and the PostgreSQL writer fence. An unavailable/fenced coordinator returns HTTP 503. `/actuator/health` remains public liveness. Missing model credentials or an idle worker pool do not make state readiness fail; those are separate capabilities.

Authenticated `GET /api/operations` and the Repository agent's readiness cards expose capacity, live-model configuration, storage backend and publication configuration. They contain no secret values. A configured key is not proof that a provider accepted a request, and a PostgreSQL configuration is not a completed hosted restart drill. Repair receipts and live reports supply that evidence separately.

Metrics include `platform.repair.active`, `platform.repair.queued`, `platform.repair.slots`, worker state counters, inference usage and existing run/tool events. Metrics remain authenticated.

## Activate and collect live evidence

1. Configure one available Gemini or Nebius model privately. Choose based on accessible credentials and measured outcomes; neither provider is required. Keep live-model cost unpriced unless an actual applicable price is supplied.
2. Activate PostgreSQL wiring and verify a hosted restart preserves a known receipt, audit entries, repository head and Git recovery after checkout loss. Local PostgreSQL fencing/recovery CI does not substitute for this hosted check.
3. Connect workers with enough remaining execution time. A one-hour window suffices for many smoke runs; allow for the worst-case 80 minutes of per-trial budget plus isolation/startup overhead for 20 paired tasks.
4. Run a real model smoke and inspect FAIL → proposal → unchanged tests PASS. Review before committing. Configure a fine-grained GitHub credential restricted to the intended repository, Contents read/write and Pull requests read/write, then use **Review draft PR publication** on a pinned GitHub repair. Inspect destination, source SHA and digest; verify a retry returns the same draft PR. Do not auto-merge it.
5. Run paired evaluation with actual policy versions. The CLI below records all outcomes, model usage and provenance, including failures; it never promotes a policy or approves a patch.

Provide `PLATFORM_OPERATOR_TOKEN` or `DEMO_USERNAME`/`DEMO_PASSWORD` through private environment configuration, not command arguments or shell history. The preflight makes no inference calls:

```sh
python3 scripts/verify-live.py --output target/live-preflight.json
python3 scripts/verify-live.py --provider gemini --smoke --output target/live-smoke.json
python3 scripts/verify-live.py --provider gemini --benchmark \
  --baseline-policy actual-baseline-version --candidate-policy actual-candidate-version \
  --output target/live-paired-evaluation.json
```

Use `--provider nebius` when that is the selected, configured provider. Smoke uses at most three model calls. The paired run uses one repetition, 20 public evaluation tasks, the same provider, at most three calls per variant and a 120-second budget per trial: at most 120 model calls, each bounded to 4,096 output tokens. Actual usage may be absent and cost unknown; the report retains those limitations. The public synthetic corpus is not an unseen industrial benchmark. Compare quality, regressions, latency and actual usage before selecting a provider or promoting a policy.

## Reproduce scaling evidence

```sh
./mvnw verify
RUN_DOCKER_TESTS=true ./mvnw verify
python3 scripts/verify-worker-pool.py
python3 scripts/verify-restart.py
npm run test:e2e
python3 scripts/verify-container.py
```

`RemoteWorkerPoolTest` drives 80 coordinator jobs through four simulated protocol consumers and exercises a 12-job history window; its report explicitly says no real containers. `verify-worker-pool.py` boots four real worker JVMs, observes four simultaneous Docker test leases, kills one process, verifies redelivery and all four repairs awaiting review, checks that worker credentials cannot approve, and proves owner-scoped restart/shutdown cleanup. CI retains both reports separately. The real Docker scenario uses a synthetic arithmetic repair with a one-second test delay; it measures concurrency/recovery behavior, not production throughput or model quality.
