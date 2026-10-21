# ADR 0002: Durable governed workflows and bounded local execution

Status: Accepted

The first simulator slice established the approval and desired/observed invariants. Extend it with embedded H2 state, scoped local access, real managed Git snapshots, isolated Java test tools, a capability-checked Docker adapter and proposal-only MCP access. Preserve the same independent policy and operator-approval boundaries.

Choose single-process aggregate transactions and write-ahead markers rather than claiming distributed exactly-once execution. Interrupted external writes invalidate approval and require observation plus a fresh plan. Completed receipts and approved Git commits can be recovered without replaying their effects.

Keep offline intent fixtures distinct from actual bounded mutation search and optional generative patch proposals. Accepted source repairs may derive only a versioned search ordering. Paired public smoke tasks, isolation probes and explicit operator promotion gate that improvement. This demonstrates the engineering loop without claiming model training or blind generalization.

Docker is an explicit local capability: development-only, one zone, local residency, ephemeral non-PII storage, no egress and no automatic rollback. Unsupported provider requirements are denied. Generated Kubernetes manifests remain review artifacts. Cloud providers, multi-tenant identity, distributed workers and hostile-code microVM isolation remain further implementations.

See [repository contracts](../repository-workflow.md), [state/recovery](../runtime-and-state.md) and [MCP](../mcp.md) for exact boundaries and acceptance evidence.
