# Engineering roadmap

## Completed PoC scope

The working release includes a responsive Material 3 workbench with local Google open source assets, optional hosted username/password access, and Gemini/Nebius proposal adapters. It covers governed environment planning/recovery, durable single-node state, operator/agent scopes, bounded repository repair in actual containers, inspected Git patches, MCP tools, capability-checked local workload execution, and feedback-derived search-policy promotion gated by paired public tasks and isolation probes. See the [demo](demo-guide.md) and [evidence](evidence/README.md).

## Next production increments

| Area | Current evidence | Further work |
| --- | --- | --- |
| Generative quality | Strict Gemini/Nebius adapters and deterministic contract tests | Repeated live-key trials, model/version tracking, token/cost measurements and unseen private datasets |
| Repair correctness | Unchanged regression tests on bounded Java snapshots | Multi-module projects, dependency-aware context, stronger oracles and adversarial correctness evaluation |
| Feedback | Versioned mutation ordering, disjoint public smoke tasks and operator promotion | Protected evolving holdouts, statistical confidence, rollback and broader candidate changes |
| Provider execution | Actual isolated local workloads and capability denials | Cloud IAM, quotas, encrypted storage, verified residency, Kubernetes admission and cross-zone failures |
| Durability | Embedded transactional journal and crash/restart evidence | Migrations, backups/restore drills, distributed leases and fault injection across service boundaries |
| Access | Scoped bearer tokens, explicit hosted Basic access and origin enforcement | OIDC, tenant/resource authorization, credential rotation and OAuth for remote agents |
| Execution isolation | Docker resource/privilege/network controls | MicroVM workers, hardened images, image digest lifecycle and hostile multi-tenant security review |
| Operations | Bounded queues, retention and local measurements | Continuous reconciliation, workload lifecycle cleanup, long-duration saturation tests and distributed tracing |

Acceptance for production claims requires evidence for the actual backend, identity system and deployment topology. Local timings, fixture extraction and public repair tasks are labelled according to what they establish.
