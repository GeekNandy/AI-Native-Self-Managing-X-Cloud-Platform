# Engineering roadmap

## Completed PoC scope

The working release includes a responsive Material 3 workbench with local Google open source assets, optional hosted username/password access, and Gemini/Nebius proposal adapters. It covers governed environment planning/recovery, H2/PostgreSQL state with Git-archive recovery, operator/agent scopes, bounded repository repair in actual containers, inspected Git patches, MCP tools, capability-checked local workload execution, authenticated remote pull workers, a 25-task repair corpus, paired prompt/tool-policy evaluation, gated promotion/rollback and digest-bound draft-publication contracts. See the [demo](demo-guide.md) and [evidence](evidence/README.md).

## Next production increments

| Area | Current evidence | Further work |
| --- | --- | --- |
| Generative quality | Strict Gemini/Nebius adapters and deterministic contract tests | Live-key quality trials, priced cost evidence and unseen private datasets |
| Repair correctness | Unchanged regression tests on bounded Java snapshots | Multi-module projects, dependency-aware context, stronger oracles and adversarial correctness evaluation |
| Feedback | Versioned examples/order, disjoint public splits, regression/quality gates, operator promotion and rollback | Protected evolving holdouts, statistical confidence and broader candidate changes |
| Provider execution | Actual isolated local workloads and capability denials | Cloud IAM, quotas, encrypted storage, verified residency, Kubernetes admission and cross-zone failures |
| Durability | Atomic metadata/Git archives, PostgreSQL writer fencing, worker leases and crash/restart evidence | Hosted PostgreSQL activation, backups/restore drills, coordinator HA and longer failure campaigns |
| Access | Scoped bearer tokens, hosted Basic access, origin enforcement and OIDC worker identities | Enterprise operator SSO, tenant/resource authorization, credential rotation and OAuth for remote agents |
| Execution isolation | Docker resource/privilege/network controls | MicroVM workers, hardened images, image digest lifecycle and hostile multi-tenant security review |
| Operations | Bounded queues, retention and local measurements | Continuous reconciliation, workload lifecycle cleanup, long-duration saturation tests and distributed tracing |

Acceptance for production claims requires evidence for the actual backend, identity system and deployment topology. Local timings, fixture extraction and public repair tasks are labelled according to what they establish.
