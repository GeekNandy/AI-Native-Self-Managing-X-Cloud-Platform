# Repair evaluation and review

`repair-v1` contains 25 public, synthetic Java regressions across null handling, bounds, overflow, Unicode, locale, collection semantics, pagination, calendars, decimal rounding, encoding, unsigned arithmetic, parsing, iteration, paths, CSV state, backoff, duplicate resolution, intervals and statistics. Five development cases and twenty evaluation cases have disjoint identifiers. Test-only reference patches live outside the application classpath. Corpus validation is not a model-quality score.

## Paired runs

Choose two configured providers and two immutable policy versions. Both variants receive identical attempt/deadline budgets and the same task inputs. The harness alternates which variant runs first and supports one to three repetitions. Each trial links to its repository run, failing baseline, actual tool trace, patch digest and verification outcome. Reports identify the corpus digest, harness/context versions, source revision, requested model and reported model, token usage and wall time. Missing usage is unknown; monetary cost remains unpriced. No score is prepopulated.

A promotion requires all of the following:

- Complete paired coverage and a passing real container-isolation probe.
- Source-only changes and immutable evaluation tests/build configuration.
- No task/repetition that passes with the baseline may fail with the candidate.
- At least 80% of candidate trials pass.
- The same provider and requested/reported model for both variants, with recorded usage.
- Higher pass count or fewer aggregate attempts at equal pass count, within a 20% reported-token increase.
- A distinct candidate policy and a still-current baseline version.

These are small-corpus engineering gates, not statistical proof of general improvement. Cross-provider comparisons are informative and cannot promote a policy. Use repetitions and larger external corpora before drawing broader conclusions. The coordinator executes one benchmark at a time; a 20-task comparison can outlive a single worker window at its worst-case budget. Completed trials survive interruption, but an interrupted comparison cannot promote.

## Feedback and rollback

A candidate retains up to three approved development examples and derives mutation ordering from accepted repairs. Evaluation repositories cannot be approved, and copies with matching evaluation-test digests are excluded from feedback. This is explicit provenance-based contamination checking, not a guarantee against manually rewritten or previously known public tasks. Model prompts continue to treat examples as untrusted data and cannot gain execution or approval rights. Promotion is an operator action; rollback accepts only a previously active version and checks the expected current version.

## Draft publication

Configure `GITHUB_WORKBENCH_TOKEN`, `GITHUB_PUBLISH_REPOSITORY` and `GITHUB_PUBLISH_BRANCH` privately. The token needs only the selected repository's contents and pull-request permissions. A preview binds the approved patch, original GitHub revision, destination and deterministic repair branch into a second SHA-256 approval digest. Publication rejects a moved base. It only supports a repair directly against the imported revision; import a fresh revision for later repairs.

The publication journal reconciles existing branches/PRs after uncertain network responses. Existing branch contents must match the approved tree, parent and digest. Drafts are never auto-merged. Current automated publication evidence uses a local GitHub API contract server; no live PR is claimed until private write access is configured.
