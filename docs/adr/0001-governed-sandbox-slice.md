# ADR 0001: Prove the governed loop with an explicit simulator

Status: Accepted for the first working slice

## Context

The repository contains a broad control-plane architecture and an initial Spring Boot application. Demonstrating every cloud, identity and agent subsystem at once would make it difficult to establish working behavior or distinguish implemented guarantees from proposed ones.

## Decision

Build a single Java 21 control-plane service and same-origin browser workbench. Use immutable records for intent, plans and receipts. Keep the interpreter behind an interface with no approval or execution methods. Provide exact fixture replay for offline demonstrations and a configurable Gemini extraction adapter.

An in-memory adapter is the only execution target. It has observable desired/observed state and controlled fault injection, but no cloud SDK, shell, workload container execution or production credentials.

Every apply checks the reviewed digest, idempotency binding, policy outcome, expiry, environment revision and current policy under a per-environment lock. Successful retries return the original receipt. Reconciliation uses the same apply path and does not rewrite desired state. An uncertain adapter failure re-observes the runtime, preserves the last committed desired state and invalidates old proposals.

The digest includes the plan ID, target, policy report, action list, generated artifact contents, base revision and expiry. It binds local review to immutable server state; it is not a signature or an identity credential.

Feedback becomes a human-specified regression case. It does not change policies, prompts or model weights. Evaluations disclose whether they check deterministic control-plane behavior, exact fixture replay or live extraction.

## Consequences

The system is runnable without external credentials and its failure invariants are executable. Independent environments can mutate concurrently within one process; mutations of the same environment are serialized. The browser can review real generated source and manifests while execution stays explicitly simulated.

All data is process-local. Plans expire after fifteen minutes. The process retains at most 100 environment identities, 500 unexpired plans, 500 agent runs, 100 feedback cases, 200 successful operation receipts per environment, 20 evaluation reports and the latest 2,000 audit events. These are demo retention limits, not a durable storage design. Expired plan cleanup can remove plan details while successful receipts remain retrievable through an apply retry with the original digest and idempotency key.

The local acknowledgement is not production approval identity. Loopback binding, Host/Origin checks, strict JSON and browser headers reduce accidental exposure but do not replace authentication or authorization. Enforcing real provider guarantees, durable state and distributed execution requires additional implementations.
