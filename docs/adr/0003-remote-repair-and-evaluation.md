# 0003: Pull workers and evidence-bound repair policies

Status: accepted

## Decision

Keep the authenticated web coordinator separate from untrusted source execution. A Docker-capable worker pulls short jobs over HTTPS using a narrowly scoped credential. GitHub Actions workers use short-lived OIDC tokens restricted to an audience, immutable repository/owner IDs, workflow/ref and allowed event. Operators retain review and publication authority.

The coordinator is a single logical writer. PostgreSQL replacement fencing prevents an older coordinator from committing state during a deployment overlap. Git metadata and bundles are committed atomically so an ephemeral checkout can be reconstructed. In-flight jobs and repairs are interrupted after restart; approvals are never replayed automatically.

Use a declared Java 21 profile and an offline runner image instead of executing arbitrary Maven plugins, dependency resolution or repository scripts. This narrows compatibility in exchange for a reproducible execution boundary. The Maven POM and tests remain immutable evidence.

Use versioned prompt/context policies and a public paired corpus to measure changes. Promotion requires complete comparable trials, no individual regressions and explicit operator action. Unknown model usage and unavailable credentials remain visible. Reference-search and fixture scores are kept separate from live inference.

## Consequences

Pull workers can run outside Render without inbound ports or operator credentials. Leases permit bounded read-only test redelivery and fence late results, but workers remain trusted infrastructure. GitHub Actions provides a finite review window; this release does not supply a continuously provisioned worker fleet. Persistent PostgreSQL and private model/GitHub credentials must be configured separately. Horizontal coordinator replication, arbitrary build systems and blind industrial evaluation remain future work.
