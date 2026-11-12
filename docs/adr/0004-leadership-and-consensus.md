# ADR 0004 — Leadership, quorum and coordinator high availability

- **Status:** Proposed HA architecture; durable replacement epochs and isolated-test job recovery implemented. No Raft-backed Java leadership or multiple active coordinators deployed.
- **Date:** 2026-11-04

## Context

The platform runs one mutable coordinator. Its durable owner and increasing epoch fence state transactions from replaced writers. `RemoteJobBroker` persists atomic queue/lease/result transitions and can redeliver an unfinished isolated test within its existing deadline and delivery limit. Higher-level repair/model loops still become `INTERRUPTED` on restart. External infrastructure effects cannot be made safe merely by electing a leader. Running multiple coordinators today risks conflicting approvals, duplicate scheduling and side effects.

## Decision

**Do not add a Raft service to the current demo.** Coordinator leadership and job transitions must use a durable, transactionally fenced contract with an explicit monotonically increasing epoch before HA is enabled. Reads may be served by followers only if consistency is documented; the elected writer alone may issue privileged operations. Every worker lease and external-effect intent must be tied to a durable idempotency key and epoch; the old leader must be prevented from completing writes after a leadership change. An explicit fail-closed path is required when leadership cannot be established.

The [replacement recovery increment](../durable-coordinator-recovery.md) supplies persisted H2/PostgreSQL epochs, atomic isolated-test transitions, epoch-bearing worker messages, and retained result receipts. It does not supply an expiring coordinator leadership lease, renew that lease, resume model loops, or fence an external request that was already sent. The remaining contracts below still gate concurrent coordinator deployment.

Evaluate three production options after the durable contract exists:

| Option | Benefit | Limit |
| --- | --- | --- |
| PostgreSQL-backed lease/epoch | Reuses the existing authoritative store; few services | PostgreSQL availability/consensus remains an external dependency; advisory locks alone do not fence already-issued cloud writes |
| Kubernetes Lease API | Works with a Kubernetes deployment and existing control-plane consensus | Kubernetes dependency; lease timing is not a substitute for transactional fencing |
| etcd (Raft-backed) | Independent three-member election/quorum and watch interface | More infrastructure, quorum management and backup/restore duties; **does not** make PostgreSQL or cloud APIs highly available |

For an independent self-hosted coordinator cluster outside Kubernetes, **etcd is the preferred candidate**, not a custom Raft implementation. Do not run three nodes on the same failure domain and call them highly available.

## Required implementation contracts before introducing consensus

1. Represent every coordinator ownership transition by a strictly increasing, persisted `leaderEpoch` with a bounded lease TTL.
2. Require the active epoch inside state mutations, job leases, plan approval and external-effect journals; reject stale epochs transactionally. Never hold a database transaction open across cloud API calls.
3. Persist QUEUED → LEASED → COMPLETED and terminal transitions atomically; on failover reconcile committed receipts before redelivery. Treat an uncertain external write as UNKNOWN until observed, never replay it by assumption.
4. Propagate epoch/fencing data into worker claims/results. Worker completion from an old epoch or old lease must fail.
5. Define liveness/readiness separately: a follower may serve safe reads but must not report mutation readiness; quorum loss forces writes to 503 without violating existing operator approval checks.
6. Make PostgreSQL and its backups recoverable independently of etcd. Snapshot/restore and member replacement procedures must be documented and tested.

## Acceptance and failure experiments

Only enable concurrent coordinator instances after repeatable tests establish: one active writer during normal operation; no two accepted leaders during a partition; old-leader writes rejected after failover; approved operations remain idempotent; stale workers are fenced; loss of quorum refuses writes; and database loss/recovery preserves committed receipts. Run process crash, SIGKILL, asymmetric network partition, delayed cloud response, lease expiry, clock skew and restore tests. Report actual RTO/RPO and distinguish simulated from external-cloud results.

## Consequences

This preserves current single-writer safety while building meaningful SRE evidence. An etcd cluster or Raft library without the contracts above would provide election but not reliable self-healing or correct recovery. Roll out the future change behind an explicit configuration mode, with the existing single-coordinator default retained for development.
