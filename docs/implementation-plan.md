# Repository engineering workflow

The next release connects repository context, bounded agent actions, isolated execution, reviewed publication and paired evaluation. Model providers remain interchangeable; no provider is preferred without measured results.

## Acceptance checklist

- [x] Common live-provider evaluation coverage and reproducible model/configuration provenance.
- [ ] Private model configuration and live contract/smoke evidence when credentials are available.
- [x] Pinned GitHub imports and an explicitly supported Java 21/Maven test profile.
- [x] Authenticated remote Docker workers, fenced leases, cancellation and bounded cleanup.
- [x] Context search/read, iterative patch verification and per-run inference/tool budgets.
- [x] Digest-bound review and idempotent draft pull-request publication (contract verified; live credential still required).
- [ ] Durable jobs, repository recovery and hosted storage configuration.
- [x] A versioned 20–30-task repair dataset, separate development/evaluation partitions and comparable budgets.
- [x] Versioned feedback candidates, explicit promotion gates and rollback.
- [x] A repository workspace with source, diff, actual tool events and evaluation comparisons.
- [x] Correlated operational measurements and adversarial/recovery integration checks.
- [x] Reproducible setup, design decisions, execution evidence and a concise demonstration.

## Delivery boundaries

Changes are developed on the existing `feature` branch. Secrets stay in private runtime configuration. Untrusted source and repository build instructions never run in the web process. Live inference, publication and hosted durability must be reported according to their actual credentials and infrastructure; contract tests are labelled separately from live results.

## Activation still required

The implementation includes the model, PostgreSQL and draft-publication paths. The deployed workspace currently has no live-model credential or GitHub write credential. Its free PostgreSQL database is provisioned but the private internal URL is not exposed by the deployment connector, so `DATABASE_URL` must be supplied privately. Live model-quality results, live PR creation and hosted restart durability are not claimed until these settings are configured.
