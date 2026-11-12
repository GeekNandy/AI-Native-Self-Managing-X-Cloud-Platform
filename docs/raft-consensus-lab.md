# Three-node Raft consensus lab (disposable)

This laboratory **runs three real etcd servers using Raft** and tests election correctness during member loss. It does **not** change the live platform, start multiple Java coordinators, or claim that a 3-node quorum makes the application highly available.

## Run locally (Docker and Docker Compose required)

```bash
python3 -m unittest discover -s tests/raft -p 'test_*.py'
docker compose -f deploy/raft-lab/compose.yml config -q
python3 scripts/verify-raft-quorum.py --output target/raft-quorum-evidence.json
```

The command creates a project with a random name, starts etcd1/etcd2/etcd3, records a non-sensitive outcome receipt, and deletes the entire Compose cluster in a `finally` block. Client endpoints bind only to **127.0.0.1 ports 2379, 2381 and 2383**; ensure those ports are unused. Containers run with **HTTP and no authentication, strictly for the local test**. Do not deploy this file in a cloud account or expose etcd ports. The script's client rejects remote endpoints.

The test uses etcd v3 JSON/gRPC-gateway requests to create leases and perform an atomic `VERSION == 0` compare-and-swap for a unique election key. The successful commit's `mod_revision` acts as an **observable fencing epoch in the test**, not a transactionally enforced PostgreSQL or provider epoch. It then performs:

1. Two competing candidates attempt the same key; exactly one obtains a proof.
2. Stop etcd1: the two remaining etcd members retain quorum, and linearizable proof remains observable.
3. Revoke the winning lease: the other candidate wins with a strictly greater revision, and the old proof fails.
4. Stop etcd3: only etcd2 remains and a linearizable ownership check **must fail closed**.
5. Restart etcd3: quorum returns and the successor's committed leadership is visible.

The probe uses fixed, short-lived **unrenewed lab leases** (120 seconds) to avoid implementing a misleading production election service. The test is sensitive to extremely overloaded CI machines and may fail when it exceeds its bounded lease window. The runner automatically destroys the temporary cluster; it never attempts production failover.

CI executes the same real consensus experiment in a dedicated `raft-quorum` job and retains a redacted JSON report. This distinguishes **actual Raft cluster evidence** from the pre-existing single-node coordinator/worker recovery tests.

## Java integration boundary and remaining work

Java `StateStore` now persists increasing replacement epochs in H2/PostgreSQL and checks ownership inside state transactions. `RemoteJobBroker` durably retains isolated-test jobs and bounded recovery receipts, with worker messages carrying the ownership epoch. These are [single-coordinator recovery foundations](durable-coordinator-recovery.md); they do not connect this etcd lab to the Java application. Higher-level repairs/model loops remain interrupted on restart. The following work still gates coordinator HA:

- Add `CoordinationService` abstraction with controlled migration from a single-node owner to etcd-backed lease ownership.
- Bind elected leadership to the existing durable epochs and protect privileged external-effect dispatch with downstream idempotency/fencing. An etcd revision alone is insufficient; a database ownership check cannot revoke an external request already in flight.
- Add explicit durable repair/model checkpoints before resuming higher-level work, and reconcile uncertain external side effects before any approved retry. Isolated-test redelivery must not replay model calls or approvals.
- Perform bounded renewal and fail closed on quorum loss, expiration, bad epoch or PostgreSQL failure; perform a positive leadership check **before** starting external effects and enforce downstream idempotency/fencing.
- Test SIGKILL, asymmetric network partitions, delayed external effects, lease expiry, Postgres outages and real recovery of the Java application; require separately managed TLS, etcd backups and failure-domain separation before production.

**Do not deploy more than one active Java coordinator now**. A three-node etcd cluster only provides consensus for its own keyspace; it does not make PostgreSQL, the Java control plane or cloud API effects highly available. The SRE telemetry and on-call runbook changes are tracked separately in PR #16.
