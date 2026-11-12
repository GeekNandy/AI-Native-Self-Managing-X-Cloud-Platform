# SRE foundation: reliability goals, alerts and runbooks

## Implementation and limits

The coordinator exports Micrometer HTTP request metrics, repair capacity metrics and a low-cardinality readiness gauge/counter pair at the authenticated `/actuator/prometheus` endpoint. `platform_readiness_state` is 1 after a successful `/readyz` check and 0 after a failed check or before the first check. `platform_readiness_checks_total` counts observed probe results with only `result=ready|not_ready`. The existing state fencing and authentication still apply; the exporter exposes neither secrets nor arbitrary identifiers as metric labels.

The `/readyz` check verifies storage availability and PostgreSQL writer fencing, **not** model-provider availability, worker availability, successful repairs or a cloud provider's health. A healthy probe is not a claim that end-to-end workflows work. `/actuator/health` remains public liveness; the new Prometheus endpoint remains protected by the workbench's operator/agent bearer or hosted Basic authentication. Keep collection over HTTPS and do not make a public metrics mirror.

The committed alerting rules are **examples only**: no Prometheus server, alert destination, dashboard, pager or HA deployment is activated by this change. The sample job assumes an always-on coordinator; an intentionally sleeping/free demo will generate misleading target-down alerts. Do not apply paging rules to a sleeping demo.

## Provisional SLIs / SLOs (not achieved claims)

| User-facing signal | Measurement contract | Proposed initial objective |
| --- | --- | --- |
| State/API availability | External 30-second synthetic `/readyz` plus separate *authenticated* `/api/operations` success; record each independently | >=99.9% successful valid requests over rolling 30 days, after deploying always-on hosting |
| Operator API latency | p95 of successful authenticated `GET /api/operations`, from request receipt to HTTP response | <2 seconds over rolling 30 days, with traffic floor |
| Recovery time | First successful authenticated action after an incident is declared; tested with fault injection | RTO <=30 minutes |
| Recoverable state | Difference between last committed durable state and restored state in a documented DR drill | RPO <=15 minutes, **not established** by current hosting |
| Worker/repair reliability | Accepted-to-completed job outcome, queue wait and interruption/redelivery rates | Establish baseline before setting a target |

Readiness metrics do not themselves measure authenticated operator success, external reachability or backup freshness. Add black-box checks and incident timestamps before treating the proposed SLOs as met. Exclude no downtime from the denominator without an explicit agreed maintenance policy. Error budget (99.9% availability) is approximately 43.2 minutes per 30-day month. Observe the actual sample volume before paging on low-traffic HTTP error ratios.

## Enable a secured scrape

1. Use an always-on, securely reachable coordinator. Configure the required hosted operator credentials privately. If scraping outside the host, enforce HTTPS and narrow access/network exposure.
2. Set up a Prometheus instance with a private secret mount and adapt `monitoring/prometheus.example.yml` to the real hostname and credentials; **never commit or print passwords**. The sample uses username/password files rather than cleartext values.
3. Configure a separate external monitor to request `/readyz` every 30 seconds, with an authenticated `/api/operations` synthetic check. The first probe initializes the readiness gauge.
4. Load `monitoring/alerts.yml`; configure the recipient, deduplication, inhibition and escalation route in the alert manager separately. Test routes with a non-production receiver before enabling pages.
5. Build dashboards from `up`, `platform_readiness_state`, `http_server_requests_seconds_*`, `platform_repair_active`, `platform_repair_queued`, `platform_repair_slots`, `platform_worker_jobs_total` and authenticated `/api/operations` diagnostics. Do not claim worker health from a ready database alone.

Validate rules with the same Prometheus version as CI:

```sh
docker run --rm -v "$PWD/monitoring:/monitoring:ro" --entrypoint=/bin/promtool \
  prom/prometheus:v3.5.0 check rules /monitoring/alerts.yml
docker run --rm -v "$PWD/monitoring:/monitoring:ro" --entrypoint=/bin/promtool \
  prom/prometheus:v3.5.0 test rules /monitoring/alerts.test.yml
```

## Runbook: coordinator is unavailable

**Detect:** external synthetic fails, `up == 0`, readiness gauge drops, or authenticated operations fail. Distinguish a host/network failure (no scrape) from state/fence failure (503). Verify independently before paging on an intentionally sleeping demo.

**Triage:** inspect deployment revision, service process/health, PostgreSQL connection and disk, and whether another coordinator replaced its writer fence. Collect a bounded, redacted event timeline and current worker lease counts. Avoid logging access tokens and connection strings.

**Mitigate:** restore one healthy coordinator and its persistent database; do not spawn competing write leaders. A fenced old coordinator must remain inactive. If PostgreSQL is unavailable, stop privileged writes and recover storage before re-enabling work. Only checkpointed deterministic remote repairs resume automatically, within their original budgets and without approval. Inspect durable receipts and current observations before any infrastructure retry; see [coordinator recovery](coordinator-recovery.md).

**Verify:** check `/readyz`, authenticated `/api/operations`, a known prior receipt, repository head and audit continuity. Confirm a canary proposal works; apply only after normal approval. Record outage duration and unmet SLOs.

## Runbook: workers or repair queue degraded

**Detect:** `XCloudRepairBacklogStuck`, `XCloudWorkerLost`, increased HTTP 429 or worker diagnostics. **Triage:** verify worker identity/acceptance deadline, lease age, Docker host capacity and per-job deadlines. **Mitigate:** restore trusted workers within bounded limits. Do not increase queue limits before memory/capacity review or bypass receipt-bound approvals. **Verify:** complete one isolated failing-baseline-to-passing-test repair awaiting review, reject stale completion and confirm no leaked sandbox containers.

## Disaster recovery and release gate

Before **2026-11-21**, choose a persistent database with a documented backup retention policy; the current free database expiration is a release risk. Follow [the backup/restore drill](postgres-backup-recovery.md) (merged in PR #15). Its CI dump/restore is a disposable-test check, not hosted RPO/RTO evidence. Record a privately performed hosted restore, external synthetic availability, and operator-approved recovery exercise before claiming production readiness.

Prove new telemetry and alert rules by executing the CI suite and `promtool` fixtures. Run `python3 scripts/verify-restart.py` for a process-level single-node crash check. Failure experiments must use disposable state/containers, never the hosted coordinator or customer resources without explicit authorization.

The authenticated metrics endpoint also exposes `platform_coordinator_epoch` and `platform_coordinator_fenced`. An expired/replaced process stays fenced. Lease-mode standbys are not ready while waiting for ownership; route only to the ready instance and restart a fenced process before reuse.
