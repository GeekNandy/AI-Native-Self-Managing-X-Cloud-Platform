# Repository engineering workflow

The next release connects repository context, bounded agent actions, isolated execution, reviewed publication and paired evaluation. Model providers remain interchangeable; no provider is preferred without measured results.

## Acceptance checklist

- [x] Common live-provider evaluation coverage and reproducible model/configuration provenance.
- [ ] Private model configuration and live contract/smoke evidence when credentials are available.
- [x] Pinned GitHub imports and an explicitly supported Java 21/Maven test profile.
- [x] Authenticated remote Docker workers, fenced leases, cancellation and bounded cleanup.
- [x] Context search/read, iterative patch verification and per-run inference/tool budgets.
- [x] Digest-bound review and idempotent draft pull-request publication (contract verified; live credential still required).
- [x] Durable jobs, repository recovery and declarative hosted PostgreSQL wiring (activation/restart proof remains pending).
- [x] A versioned 20–30-task repair dataset, separate development/evaluation partitions and comparable budgets.
- [x] Versioned feedback candidates, explicit promotion gates and rollback.
- [x] A repository workspace with source, diff, actual tool events and evaluation comparisons.
- [x] Correlated operational measurements and adversarial/recovery integration checks.
- [x] Configurable bounded worker pools, distinct identities, draining, crash redelivery and four-worker Docker evidence.
- [x] State/fence readiness, authenticated operations diagnostics, UI readiness cards and continuous-worker host template.
- [x] Reproducible setup, design decisions, execution evidence and a concise demonstration.

## Delivery boundaries

Changes are developed on the existing `feature` branch. Secrets stay in private runtime configuration. Untrusted source and repository build instructions never run in the web process. Live inference, publication and hosted durability must be reported according to their actual credentials and infrastructure; contract tests are labelled separately from live results.

## Activation still required

The implementation includes model, PostgreSQL and draft-publication paths. Live model-quality results, live PR creation and hosted restart durability are not claimed until privately configured and verified. The prepared `render.yaml` references the existing database directly, avoiding manual connection-string copying; it still requires adoption after merge. Continuous worker hosting requires an existing trusted Docker host or a separately selected infrastructure service. See [the activation sequence](scaling-and-activation.md).
