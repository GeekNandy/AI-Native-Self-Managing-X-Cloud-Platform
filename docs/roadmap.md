# Engineering Roadmap

## Current baseline

The workbench compiles candidate environment intent into a reviewed engineering change, applies a governed sandbox transition, observes drift, and records regression evidence.

| Area | Implemented behavior | Next increment |
| --- | --- | --- |
| Developer workflow | Browser workbench, explicit interpreter contract and generated workload files | Repository tasks with edits, isolated build/test tools and a reviewable patch |
| Client and API | Java 21/Spring Boot API and responsive JavaScript client with browser tests | IDE integration and broader accessibility validation |
| Orchestration | Typed intent, bounded inference, independent policy and immutable plans | Durable asynchronous runs, cancellation and multi-tenant identity |
| Evaluation | Versioned golden scenarios, exact intent checks, isolated execution probes and exported results | Held-out repository tasks, repeated live-model trials and cost/quality comparisons |
| Feedback | Reviewed corrections become regression cases | Versioned prompt or tool changes, evaluated against a separate holdout before promotion |
| Reliability | Retry deduplication, state revision checks, explicit unknown observations and partial-failure tests | Real provider failures, distributed coordination and recovery after restart |

## Priorities

1. **Repository change workflow.** Introduce a worktree tool and a narrowly scoped agent that diagnoses a failing test, proposes a patch, runs an allowlisted build in an isolated container and submits the change for review. Each run needs an explicit tool, time and token budget. Measure task completion through tests and patch correctness.
2. **Live interpretation evidence.** Evaluate Gemini against fresh requests outside the fixture catalog. Record exact intent agreement, invalid tool requests, policy outcomes, task completion, end-to-end latency, token usage and cost. Identify fixture replay and live inference separately in reports.
3. **Measured improvement.** Keep feedback and holdout tasks separate. Compare a versioned prompt or retrieval change against the baseline on paired runs. Promotion requires passing safety checks and measured improvement. Retain failed candidates and protect the holdout from training or tuning.
4. **Execution adapter.** Start with an isolated Kubernetes namespace or a disposable cloud environment. Enforce identity, residency, encryption and provider capabilities through actual execution. Report unsupported requirements explicitly.
5. **Durable operation semantics.** Persist plans, desired state, receipts, feedback and audit records. Introduce per-environment optimistic concurrency, leased workers and recovery rules for uncertain provider writes. Measure bounded-concurrency workloads and recovery after process failure.

## Acceptance gates

- Repository changes include an inspectable patch, test results and an operation trace.
- Live interpreter results include the model, configuration, dataset version and failure analysis.
- Improvement candidates pass the same safety suite as the baseline and use a separate evaluation holdout.
- Provider adapters expose capability checks and demonstrate that required controls are enforced.
- Durable execution preserves retry semantics and reconstructs state after a crash or restart.

## Evidence boundaries

The current release uses curated offline examples and an in-memory simulator. Generated deployment files are review artifacts. Residency and encryption are logical policy requirements until a provider adapter enforces them. Feedback contributes regression cases; model or policy changes require a separate implementation and evaluation.

Recorded local measurements describe the tested JVM and loopback HTTP workload. Live-model quality, cloud execution, distributed capacity and durable recovery each require their own evidence.
