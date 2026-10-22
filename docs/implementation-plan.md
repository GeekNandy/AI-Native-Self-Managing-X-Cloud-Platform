# Repository engineering workflow

The next release connects repository context, bounded agent actions, isolated execution, reviewed publication and paired evaluation. Model providers remain interchangeable; no provider is preferred without measured results.

## Acceptance checklist

- [ ] Common live-provider evaluation coverage and reproducible model/configuration provenance.
- [ ] Private model configuration and live contract/smoke evidence when credentials are available.
- [ ] Pinned GitHub imports and an explicitly supported Java 21/Maven test profile.
- [ ] Authenticated remote Docker workers, fenced leases, cancellation and bounded cleanup.
- [ ] Context search/read, iterative patch verification and per-run inference/tool budgets.
- [ ] Digest-bound review and idempotent draft pull-request publication.
- [ ] Durable jobs, repository recovery and hosted storage configuration.
- [ ] A versioned 20–30-task repair dataset, separate development/evaluation partitions and comparable budgets.
- [ ] Versioned feedback candidates, explicit promotion gates and rollback.
- [ ] A repository workspace with source, diff, actual tool events and evaluation comparisons.
- [ ] Correlated operational measurements and adversarial/recovery integration checks.
- [ ] Reproducible setup, design decisions, execution evidence and a concise demonstration.

## Delivery boundaries

Changes are developed on the existing `feature` branch. Secrets stay in private runtime configuration. Untrusted source and repository build instructions never run in the web process. Live inference, publication and hosted durability must be reported according to their actual credentials and infrastructure; contract tests are labelled separately from live results.
