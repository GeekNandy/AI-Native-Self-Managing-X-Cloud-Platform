# Control Plane

## Purpose

The Control Plane is the deterministic authority responsible for managing the lifecycle of environments from an accepted Intent IR Version through planning, authorization, execution, observation, and reconciliation.

It converts validated platform intent into governed environment state and continuously works to keep runtime state aligned with authoritative Desired State.

The Control Plane is responsible for:

- Accepted intent processing
- Policy and authorization enforcement
- Dependency resolution
- Environment planning
- Execution-plan management
- Approval enforcement where required
- Environment lifecycle management
- Desired-state management
- Operation lifecycle management
- Reconciliation
- Provider-adapter coordination
- Auditability and lifecycle traceability
- Failure handling and recovery coordination

The Control Plane does not depend on a specific AI model, cloud provider, infrastructure engine, or execution technology.

The fundamental authority boundary is:

```text
AI proposes.
Control Plane authorizes.
Provider Adapter executes.
```

---

## Architectural Boundary

The Control Plane sits between validated platform intent and managed runtime environments.

```text
External Request
       |
       v
Intent Compiler
       |
       v
Accepted Intent IR Version
       |
       v
+--------------------------------+
| Control Plane                  |
|                                |
| Policy                         |
| Transition Authorization       |
| Dependency Resolution          |
| Environment Planning           |
| Execution Planning             |
| Operation Authorization        |
| Approval Enforcement           |
| Desired State                  |
| Environment Lifecycle          |
| Operation Management            |
| Reconciliation                 |
| Audit                          |
+---------------+----------------+
                |
                v
        Provider Adapter
                |
                v
       Provider API / Runtime
```

The Security Model is a cross-cutting contract enforced throughout the Control Plane rather than a downstream lifecycle stage.

```text
              Security Model
              /     |      \
             /      |       \
            v       v        v
Intent Processing  Planning  Execution
             \      |       /
              \     |      /
               v    v     v
                Control Plane
```

The accepted Intent IR Version is the input contract to deterministic Control Plane processing.

The Control Plane remains responsible for determining whether a requested transition is permitted and how it may be executed.

AI-generated output does not become authoritative merely because it has passed through the AI Plane.

---

## Scope

This document defines the Control Plane contract and lifecycle boundaries.

It covers:

- Control Plane responsibilities
- Logical component boundaries
- Lifecycle processing
- State ownership
- Policy and authorization
- Environment planning
- Execution planning
- Approval
- Desired State
- Observed State
- Operations
- Reconciliation
- Concurrency
- Idempotency
- Failure handling
- Provider adapter interaction
- Asynchronous processing
- Auditability
- AI and MCP boundaries

Implementation-specific choices such as framework selection, persistence technology, queue technology, provider SDKs, workflow engines, and deployment topology are outside this document.

---

## Relationship to Other Platform Contracts

The Control Plane consumes and coordinates the contracts established by the other platform documents.

```text
Architecture
     |
     v
Domain Model
     |
     v
Intent Compiler
     |
     v
Control Plane
```

The Security Model applies across every stage:

```text
                    Security Model
                         |
        +----------------+----------------+
        |                |                |
        v                v                v
   Intent/Planning   Authorization   Execution
        |                |                |
        +----------------+----------------+
                         |
                         v
                   Control Plane
```

The responsibilities are separated as follows.

### Architecture

Defines the overall platform planes, trust boundaries, and major execution flow.

### Domain Model

Defines the domain concepts, aggregates, lifecycle states, versioning, relationships, and invariants.

### Intent Compiler

Transforms a Request into an immutable, semantically valid Intent IR Version.

### Control Plane

Consumes the accepted Intent IR Version and governs the managed environment lifecycle.

### Security Model

Defines identity, authorization, agent authority, credential, approval, failure, and audit boundaries that the Control Plane enforces.

The Control Plane must preserve these contracts rather than redefining their ownership.

---

## Control Plane Responsibilities

The Control Plane owns the deterministic lifecycle of managed environments.

Its logical responsibilities are:

```text
Accepted Intent Processing
        |
        v
Policy Evaluation
        |
        v
Transition Authorization
        |
        v
Dependency Resolution
        |
        v
Environment Planning
        |
        v
Execution Planning
        |
        v
Operation Authorization
        |
        v
Approval Enforcement
        |
        v
Operation Coordination
        |
        v
Desired State Management
        |
        v
Observation
        |
        v
Reconciliation
```

The Control Plane is the authority for managed-environment lifecycle state.

It is not the authority for natural-language interpretation or AI model reasoning.

---

## Control Plane Principles

### Deterministic Authority

Security-sensitive lifecycle decisions are made by deterministic platform components.

AI reasoning may influence proposals but cannot establish privileged authority.

### Explicit State

Authoritative lifecycle state must be represented explicitly rather than inferred from transient execution behavior.

### Immutable Versioned Artifacts

Accepted lifecycle artifacts are versioned and immutable.

A material change creates a new version rather than mutating an accepted historical artifact.

### Separation of Planning and Execution

A plan describes a proposed transition.

Execution represents the actual lifecycle actions performed against an environment.

Planning is not itself privileged infrastructure execution.

### Layered Authorization

The Control Plane distinguishes between:

```text
Transition Authorization
```

and:

```text
Operation Authorization
```

Transition Authorization determines whether an accepted intent may proceed through Control Plane planning.

Operation Authorization binds authority to the concrete privileged Operation, its target, scope, parameters, and applicable security context.

### Desired State and Observed State Separation

Desired State represents the authoritative target.

Observed State represents runtime evidence.

Neither replaces the other.

### Reconciliation as a First-Class Lifecycle

Reconciliation is part of the normal Control Plane lifecycle.

It must not create an alternative privileged execution path.

### Provider Isolation

Provider-specific execution details remain behind provider adapters.

The Control Plane operates against provider-neutral platform contracts.

### Failure-Closed Security

Security-sensitive uncertainty prevents privileged execution.

Operational failures remain visible and recoverable through subsequent lifecycle processing.

---

## Control Plane Lifecycle

Planning, transition authorization, operation authorization, operation materialization, and execution are separate concerns.

The overall lifecycle is:

```text
Accepted Intent IR Version
          |
          v
Policy Evaluation
          |
          v
Transition Authorization
          |
          v
Dependency Resolution
          |
          v
Environment Plan
          |
          v
Execution Plan
          |
          v
Operation Materialization
          |
          v
Operation Governance
          |
          +----------------------------+
          |                            |
          v                            v
Operation Authorization      Approval, when required
          |                            |
          +--------------+-------------+
                         |
                         v
              Governance Conditions Satisfied
                         |
                         v
              Precondition Evaluation
          |
          v
Authorized Operation
          |
          v
Provider Adapter
          |
          v
Managed Environment
          |
          v
Observed State
          |
          v
Reconciliation
          |
          +----------------------+
                                 |
                                 v
                       New Reconciliation Plan
                                 |
                                 v
                       Operation Authorization
```

Transition Authorization establishes whether the requested lifecycle transition may proceed.

Operation Authorization establishes whether a specific planned privileged Operation may execute.

The final operation authorization must be bound to the concrete Operation and its material security-relevant properties.

Approval, when required, is a distinct lifecycle condition and is bound to the applicable Environment, Intent Version, Plan Version, Operation or Operation Set, scope, and approver.

Execution preconditions must be satisfied before the Operation enters execution.

Provisioning is not the end of Control Plane responsibility.

The Control Plane continues to observe and reconcile the environment for its managed lifetime.

---

## Primary Control Plane Components

The Control Plane is logically decomposed into the following components.

```text
+------------------------------------------------------------------+
|                           Control Plane                          |
|                                                                  |
|  +--------------------------+                                    |
|  | Accepted Intent          |                                    |
|  | Processing               |                                    |
|  +------------+-------------+                                    |
|               |                                                  |
|               v                                                  |
|  +------------------+      +----------------------+              |
|  | Policy &         |----->| Transition           |              |
|  | Governance       |      | Authorization        |              |
|  +--------+---------+      +----------+-----------+              |
|           |                           |                          |
|           v                           |                          |
|  +------------------+                 |                          |
|  | Dependency       |                 |                          |
|  | Resolution       |                 |                          |
|  +--------+---------+                 |                          |
|           |                           v                          |
|           v            +-----------------------------+            |
|  +------------------------------------------------+ |            |
|  | Environment Planning / Compilation             | |            |
|  +-------------------------+----------------------+ |            |
|                            |                         |            |
|                            v                         |            |
|                 +----------------------+             |            |
|                 | Execution Planner    |             |            |
|                 +----------+-----------+             |            |
|                            |                         |            |
|                            v                         |            |
|                 +----------------------+             |            |
|                 | Operation            |             |            |
|                 | Authorization &      |             |            |
|                 | Approval Enforcement |             |            |
|                 +----------+-----------+             |            |
|                            |                         |            |
|                            v                         |            |
|                 +----------------------+             |            |
|                 | Environment Control  |             |            |
|                 | Plane                |             |            |
|                 +----------+-----------+             |            |
|                            |                         |            |
|                            v                         |            |
|                 +----------------------+             |            |
|                 | Operations           |             |            |
|                 +----------+-----------+             |            |
|                            |                         |            |
|                            v                         |            |
|                 +----------------------+             |            |
|                 | Reconciliation       |             |            |
|                 +----------------------+             |            |
+------------------------------------------------------------------+
                            |
                            v
                    Provider Adapters
```

These are logical boundaries.

They may be implemented as services, modules, workers, controllers, or other components without changing the platform contract.

---

## Accepted Intent IR as the Entry Boundary

The Control Plane begins deterministic processing from an accepted Intent IR Version.

```text
Request
   |
   v
Intent Compiler
   |
   v
Accepted Intent IR Version
   |
   v
Control Plane
```

The Control Plane must not treat a Candidate Intent IR as authoritative.

The accepted Intent IR Version provides the Control Plane with:

```text
Intent Identity
Intent Version
Intent IR Version
Requested Outcomes
Constraints
Requirements
Traceability Metadata
```

The Control Plane must preserve traceability to the originating Intent Version.

---

## Intent Policy Evaluation

Policy evaluation determines whether the accepted intent may proceed toward environment planning and under which constraints.

```text
Accepted Intent IR Version
          |
          v
Policy Evaluation
          |
          +------ DENY
          |
          +------ ALLOW
          |
          +------ ALLOW_WITH_CONSTRAINTS
```

A Policy Decision should record enough information to establish:

```text
Policy Set
Policy Version
Evaluation Stage
Decision
Constraints
Evaluator Version
Correlation Information
```

A Policy Decision does not itself execute infrastructure actions.

---

## Transition Authorization

Transition Authorization determines whether the authenticated requester or agent is permitted to cause the requested lifecycle transition.

Conceptually:

```text
Accepted Intent IR Version
          |
          v
Policy Decision
          |
          v
Transition Authorization
          |
          +------ DENY
          |
          +------ ALLOW
```

Transition Authorization may consider:

```text
Requester Identity
Agent Identity
Intent
Environment
Requested Action
Requested Scope
Environment Classification
Security Context
Applicable Policy
```

Transition Authorization does not authorize provider execution.

Its purpose is to establish whether the lifecycle may proceed to deterministic planning.

A material change to the requested transition requires the applicable authorization decision to be evaluated again.

---

## Policy Constraints

When policy evaluation produces:

```text
ALLOW_WITH_CONSTRAINTS
```

the resulting constraints become part of the downstream planning context.

For example:

```text
Accepted Intent IR
        |
        v
Policy Decision
        |
        +---- Approved Region
        +---- Resource Limits
        +---- Mandatory Encryption
        +---- Network Restrictions
        +---- Approval Requirement
        |
        v
Environment Plan
```

Downstream components must preserve applicable policy constraints.

A planner, worker, provider adapter, or reconciliation component must not silently remove or weaken those constraints.

A material change to a constrained Operation requires applicable governance controls to be evaluated again.

---

## Authorization Context

A privileged execution context is bound to the Operation being authorized.

Conceptually:

```text
Authorization Context
---------------------
Requester Identity
Agent Identity
Environment
Resource Scope
Action
Operation Parameters
Policy Context
Plan Reference
Approval Context
Execution Context
```

Authorization is therefore not merely a generic permission associated with an Environment.

It is authority over a bounded Operation and scope.

---

## Operation Authorization

Operation Authorization establishes the authority to execute a concrete planned Operation.

```text
Execution Plan
      |
      v
Operation Materialization
      |
      v
Operation
      |
      v
Operation Authorization
      |
      +------ DENY
      |
      +------ ALLOW
```

Operation Authorization must evaluate the material properties of the Operation, including:

```text
Environment
Resource Scope
Action
Operation Parameters
Plan Reference
Policy Context
Approval Context
Execution Context
```

The authorization decision must remain bound to the authorized Operation.

A downstream component must not materially change an authorized Operation without triggering the applicable authorization, policy, and approval controls again.

Operation Authorization does not itself invoke provider APIs.

---

## Privileged Execution Ordering

The privileged execution boundary follows:

```text
Validation
    |
    v
Policy Decision
    |
    v
Transition Authorization
    |
    v
Environment Planning
    |
    v
Execution Plan
    |
    v
Operation Materialization
    |
    v
+-----------------------------+
| Operation Governance        |
|                             |
| Operation Authorization     |
|                             |
| Approval, when required     |
+-------------+---------------+
              |
              v
Governance Conditions Satisfied
    |
    v
Precondition Evaluation
    |
    v
Authorized Operation
    |
    v
Provider Adapter
    |
    v
Provider API
```

Transition Authorization governs whether the requested transition may proceed.

Operation Authorization governs the exact privileged Operation.

The distinction allows the Control Plane to remain aligned with both the planning lifecycle and exact-operation security binding.

---

## Dependency Resolution

A valid Intent IR Version may imply relationships between resources and capabilities.

Dependency resolution determines:

```text
Resource Dependencies
Capability Dependencies
Preconditions
Ordering Constraints
Required Relationships
Lifecycle Dependencies
```

Conceptually:

```text
Intent IR
    |
    v
Dependency Graph
    |
    +---- Resource A
    |        |
    |        +---- Resource B
    |
    +---- Capability C
    |
    +---- Network Dependency
```

Dependency resolution remains provider-neutral.

Provider-specific implementation decisions belong to provider adapters.

---

## Environment Compilation

Environment compilation transforms an accepted Intent IR Version and applicable constraints into a provider-neutral Environment Plan.

```text
Accepted Intent IR Version
          |
          v
Policy Decision
          |
          v
Transition Authorization
          |
          v
Dependency Resolution
          |
          v
Environment Compiler
          |
          v
Environment Plan
```

An Environment Plan should identify:

```text
Plan ID
Environment ID
Intent Version
Intent IR Version
Intent Policy Decision
Plan Version
Resources
Capabilities
Relationships
Dependencies
Constraints
Lifecycle Requirements
Created At
```

The Environment Plan describes the intended composition of the managed environment without binding execution to a specific provider API.

---

## Environment Plan Immutability

An accepted Environment Plan is immutable.

A material change creates a new Environment Plan version.

```text
Environment Plan v1
        |
        | Material Change
        v
Environment Plan v2
```

Historical plans remain traceable and cannot be silently modified after they have been used for downstream processing.

---

## Execution Planning

The Execution Planner converts an Environment Plan into an ordered Execution Plan.

```text
Environment Plan
      |
      v
Execution Planner
      |
      v
Execution Plan
```

An Execution Plan should identify:

```text
Plan ID
Environment ID
Environment Plan Version
Plan Version
Planned Operations
Dependencies
Preconditions
Ordering
Policy Constraints
Expected Result
Created At
```

The Execution Plan makes explicit:

- Resources to create
- Resources to update
- Resources to remove
- Required dependencies
- Preconditions
- Ordering constraints
- Applicable policy constraints
- Expected resulting state

An Execution Plan is a proposal for transition.

It is not execution itself.

---

## Execution Plan Immutability

An accepted Execution Plan is immutable.

A material change produces a new Execution Plan version.

```text
Execution Plan v1
        |
        | Material Change
        v
Execution Plan v2
```

Historical execution plans remain traceable to the Environment Plan from which they were derived.

---

## Approval Lifecycle

Approval is a separate lifecycle decision from Environment lifecycle state.

Approval is required when policy or environment risk requires explicit human authorization.

Approval and Operation Authorization are independent governance gates. Both are bound to the same concrete lifecycle context and must be satisfied before privileged execution.

```text
                    Execution Plan
                          |
                          v
                 Operation Materialization
                          |
                          v
                +---------------------+
                | Governance Gates    |
                |                     |
                | Operation           |
                | Authorization       |
                |                     |
                | Approval if         |
                | Required            |
                +----------+----------+
                           |
                           v
                  Governance Satisfied
```

Approval must be bound to the applicable:

```text
Environment
Intent Version
Plan Version
Operation or Operation Set
Requested Scope
Approver Identity
Approval Timestamp
Approval Status
```

Approval must not be inferred from:

```text
AI Recommendation
Plan Generation
Request Submission
Successful Validation
Policy Allow
```

Approval does not itself transition the Environment lifecycle.

A material change to the approved target, scope, Operation, or execution context requires the applicable approval condition to be evaluated again.

---

## Execution Preconditions

Execution Preconditions determine whether an authorized Operation may safely begin.

Examples include:

```text
Dependency Available
Required Resource Exists
Required State Version Matches
Provider Context Available
Security Context Valid
Approval Still Valid
Operation Not Already Completed
```

Preconditions are evaluated immediately before execution.

A precondition failure prevents the Operation from entering provider execution.

A material change caused by a failed precondition may require re-planning or re-authorization.

---

## Environment Control Plane

The Environment Control Plane is the lifecycle-execution component within the broader Control Plane.

It owns and coordinates:

```text
Environment Lifecycle State
Desired State Reference
Observed State Reference
Managed Resource Relationships
Lifecycle Transitions
Execution Coordination
Reconciliation Triggers
```

The Environment Control Plane does not independently create authority.

It accepts only lifecycle work that has satisfied the applicable authorization, approval, and precondition requirements.

---

## Environment Lifecycle

The Environment lifecycle is explicit.

```text
PROPOSED
    |
    v
PLANNED
    |
    v
PROVISIONING
    |
    v
ACTIVE
    |
    +------> UPDATING
    |           |
    |           v
    |         ACTIVE
    |
    +------> RECONCILING
    |           |
    |           v
    |         ACTIVE
    |
    v
DEPROVISIONING
    |
    v
DELETED
```

Failure conditions may occur during:

```text
PROVISIONING
UPDATING
RECONCILING
DEPROVISIONING
```

Failure does not implicitly restore the previous lifecycle state.

The Environment lifecycle represents authoritative Control Plane state.

It is not merely an inference from runtime health.

---

## Environment State Ownership

The Environment Aggregate owns authoritative Environment lifecycle state.

Conceptually:

```text
Environment Aggregate
|
+-- Environment ID
+-- Lifecycle State
+-- Managed Resources
+-- Desired State Reference
+-- Observed State Reference
+-- Provider Bindings
+-- Lifecycle Metadata
```

The Environment Aggregate is the authoritative lifecycle boundary for a managed environment.

Other components may observe Environment state but must not independently redefine its lifecycle.

---

## Desired State

Desired State represents the environment that the platform has committed to maintain.

Desired State is derived from:

```text
Accepted Intent Version
Applicable Intent Policy Decisions
Environment Plan
Satisfied Approval Conditions
```

Where execution-plan approval is required, Desired State becomes authoritative only after the required approval conditions have been satisfied.

Desired State is explicitly versioned.

Conceptually:

```text
Intent Version
      |
      v
Policy Decision
      |
      v
Environment Plan
      |
      v
Approved Transition
      |
      v
Desired State Version
```

---

## Desired State Versioning

Every material Desired State change creates a new Desired State Version.

```text
Desired State v5
      |
      | Material Change
      v
Desired State v6
```

A Desired State Version should retain references to:

```text
Source Intent Version
Environment Plan Version
Applicable Policy Decision
Lifecycle Requirements
Operational Requirements
```

Historical Desired State versions must remain traceable.

---

## Observed State

Observed State represents the runtime conditions reported or established through provider and operational observations.

Observed State may be derived from:

- Provider APIs
- Runtime inventory
- Infrastructure telemetry
- Application health
- Logs
- Metrics
- Traces
- Operational signals

Conceptually:

```text
Provider Runtime
      |
      v
Observation
      |
      v
Observed State Snapshot
```

Observed State is identified by an observation or snapshot identifier and observation timestamp.

Observed State is evidence about runtime conditions.

It does not establish authorization.

---

## Desired State and Observed State

Desired State and Observed State form the basis of the reconciliation loop.

```text
             +--------------------+
             |   Desired State    |
             +---------+----------+
                       |
                       v
                  Comparison
                       ^
                       |
             +---------+----------+
             |   Observed State   |
             +--------------------+
```

The comparison may produce:

```text
CONVERGED
DRIFT_DETECTED
UNKNOWN
```

`UNKNOWN` represents a condition where available observations are insufficient to establish convergence safely.

Incomplete observation must not be treated as proof of convergence.

Unknown state must not be interpreted as authorization to modify infrastructure.

---

## Reconciliation

Reconciliation is the domain process of determining whether the managed Environment conforms to authoritative Desired State.

```text
Desired State
      |
      v
Comparison <------ Observed State
      |
      v
Drift Evaluation
      |
      v
Reconciliation Decision
      |
      v
Reconciliation Plan
      |
      v
Policy / Transition Authorization
      |
      v
Operation Authorization
      |
      v
Approval, when required
      |
      v
Precondition Evaluation
      |
      v
Authorized Operations
      |
      v
Observed State
```

Reconciliation is part of the same governed lifecycle as initial provisioning.

It does not bypass:

```text
Validation
Policy
Authorization
Approval
Operation Lifecycle
Audit
```

Reconciliation planning may occur before exact Operation Authorization.

No privileged remediation executes until the concrete remediation Operation has satisfied the applicable authorization and governance controls.

---

## Drift

Drift is a difference between authoritative Desired State and available Observed State.

Examples include:

```text
Resource Missing
Resource Configuration Changed
Capacity Mismatch
Network Configuration Changed
Unexpected Resource
Health State Divergence
Provider-Side Mutation
External Administrative Change
```

Detected drift does not automatically imply remediation.

The Control Plane must determine whether remediation is:

```text
Required
Supported
Safe
Policy-Compliant
Authorized
```

---

## Reconciliation Plan

A Reconciliation Plan is a specialized execution proposal produced to address detected drift.

A Reconciliation Plan should identify:

```text
Plan ID
Environment ID
Desired State Version
Observed State Snapshot
Drift References
Planned Operation Specifications
Dependencies
Preconditions
Policy Constraints
Expected Result
Created At
```

Conceptually:

```text
Desired State v7
       |
       v
Observed State s42
       |
       v
Drift Detected
       |
       v
Reconciliation Plan
```

A Reconciliation Plan follows the same authorization and execution boundaries as any other Execution Plan.

It must not directly invoke privileged provider APIs.

Once authorized, planned operation specifications may be materialized as Operations.

---

## Reconciliation Safety

Reconciliation must remain conservative when information is incomplete.

For example:

```text
Observed State = UNKNOWN
```

must not be interpreted as:

```text
Resource Missing
```

Likewise:

```text
Provider API Unavailable
```

must not automatically become:

```text
Delete Resource
```

The platform should distinguish:

```text
Known Drift
Unknown State
Transient Observation Failure
Execution Failure
Policy Block
Authorization Block
```

This prevents uncertainty from becoming unintended destructive action.

---

## Operation Lifecycle

An Operation represents an individual lifecycle action performed against an Environment.

An Operation should identify:

```text
Operation ID
Environment ID
Operation Type
Requested By
Plan Type
Plan Reference
Status
Preconditions
Dependencies
Started At
Completed At
Result
Failure Information
```

Operations are durable lifecycle records.

Planned operation specifications are materialized as durable Operations before Operation Authorization and provider execution.

---

## Operation State

A conceptual Operation lifecycle is:

```text
PENDING
   |
   v
RUNNING
   |
   +------> SUCCEEDED
   |
   +------> FAILED
   |
   +------> CANCELLED
```

Authorization and approval are prerequisites for starting a privileged Operation.

They are not themselves Operation states.

The platform must preserve the distinction between:

```text
Planned
Authorized
Started
Completed
Failed
Canceled
```

A provider execution result that cannot yet be determined must be represented as an execution outcome, not silently converted into successful or failed completion.

---

## Operation Scope

Every Operation must have explicit scope.

Conceptually:

```text
Operation
|
+-- Environment
+-- Resource Scope
+-- Action
+-- Parameters
+-- Plan Reference
+-- Policy Context
+-- Authorization Context
+-- Approval Context
```

An Operation must not expand its own:

```text
Resource Scope
Action
Environment Scope
Credential Scope
Authorization Scope
```

A downstream component cannot infer broader authority from a valid Plan Reference.

---

## Material Operation Changes

A material change to an authorized Operation invalidates the previous authorization context for the affected Operation.

Material changes include:

```text
Target Environment
Target Resource
Action
Security-Relevant Parameters
Resource Scope
Execution Context
Credential Context
Applicable Policy Context
Approval Context
```

The applicable:

```text
Authorization
Policy
Approval
```

must be evaluated again before privileged execution continues.

---

## Provider Adapter Boundary

Provider adapters are the execution boundary between the provider-neutral Control Plane and provider-specific infrastructure.

```text
Control Plane
     |
     v
Authorized Operation
     |
     v
Provider Adapter
     |
     v
Provider API
     |
     v
Managed Resource
```

A provider adapter is responsible for:

- Translating authorized platform operations
- Applying provider-specific execution semantics
- Calling provider APIs
- Translating provider responses
- Reporting execution results
- Exposing provider-specific failures to the Control Plane

A provider adapter is not responsible for:

- Granting authority
- Overriding policy
- Broadening operation scope
- Creating approvals
- Reinterpreting user intent

---

## Provider Independence

The Control Plane operates against provider-neutral platform contracts.

```text
                   Control Plane
                        |
                        v
                Authorized Operation
                        |
          +-------------+-------------+
          |             |             |
          v             v             v
      AWS Adapter   GCP Adapter   Azure Adapter
          |             |             |
          v             v             v
      Provider APIs / Provider Resources
```

The same logical Environment Plan may be translated into different provider-specific execution strategies.

Provider-specific capabilities that cannot be represented uniformly should appear as explicit platform capabilities rather than silently leaking implementation details into the core Intent IR.

---

## Credential Boundary

Provider credentials remain outside the AI Plane.

The execution path is:

```text
Authorized Operation
        |
        v
Provider Execution Context
        |
        v
Credential Resolution
        |
        v
Provider Adapter
        |
        v
Provider API
```

Credential access must be derived from an already-authorized execution context.

Possession of:

```text
Environment ID
Resource ID
Plan Reference
Provider Identifier
Tool Access
```

must not by itself grant credential access.

Credential values must not be serialized into:

```text
Intent IR
Environment Plans
Execution Plans
Operations
General AI Context
Audit Records
```

---

## Execution Idempotency

Execution retries are expected in distributed systems.

The Control Plane must distinguish:

```text
Compiler Idempotency
Lifecycle Idempotency
Provider Operation Idempotency
```

Repeating the same Control Plane command must not unintentionally create a second logically equivalent lifecycle transition.

Where provider APIs support idempotency keys, the Operation identity or another stable execution identity should be used to preserve retry semantics.

A retry must not create a broader authorization scope.

---

## Exactly-Once Versus At-Least-Once Execution

The Control Plane must not assume exactly-once execution across distributed provider boundaries.

A realistic execution model is:

```text
Control Plane
     |
     v
At-Least-Once Delivery
     |
     v
Provider Adapter
     |
     v
Idempotent / Detectable Provider Operation
```

The platform must maintain enough durable state to determine whether an Operation:

```text
Has not started
May have started
Completed successfully
Failed
Has an unknown outcome
```

An unknown outcome must be resolved through observation or explicit provider-side operation status before issuing a potentially conflicting retry.

---

## Unknown Execution Outcome

A distributed execution may reach a condition where the Control Plane cannot determine the provider-side result immediately.

For example:

```text
Control Plane -> Provider Adapter
Provider operation may have started
Connection lost
No definitive result available
```

The platform must represent the outcome as unknown without converting it into successful or failed completion.

Conceptually:

```text
Operation
   |
   v
RUNNING
   |
   v
Execution Outcome = UNKNOWN
   |
   v
Observation / Provider Status Check
   |
   +---- Confirmed Success
   |
   +---- Confirmed Failure
   |
   +---- Still Unknown
```

`UNKNOWN` is an execution outcome and not an Operation lifecycle state.

The platform must avoid issuing potentially conflicting retries until sufficient evidence is available.

An unknown provider outcome is not a grant of authorization.

---

## Concurrency Control

Multiple requests may target the same Environment.

The Control Plane must prevent conflicting lifecycle transitions from being applied concurrently without coordination.

Conceptually:

```text
Environment
     |
     v
Lifecycle Version
     |
     +---- Operation A
     |
     +---- Operation B
```

Updates to authoritative Environment state should use explicit concurrency control.

A lifecycle transition should be accepted only when the expected state and version remain valid.

Stale Operations must not silently overwrite newer authoritative state.

---

## Optimistic Concurrency

The Control Plane may use version-based concurrency semantics.

Conceptually:

```text
Environment Version = 42
        |
        +---- Controller reads version 42
        |
        +---- Another transition creates version 43
        |
        +---- First controller attempts update from version 42
                    |
                    v
                 Reject
```

A stale update must be rejected or safely re-planned.

It must not overwrite newer authoritative lifecycle state.

---

## State Ownership

Each authoritative domain concept has a clear owner.

```text
Intent Version
    -> Intent Lifecycle

Intent IR Version
    -> Intent / Planning Lifecycle

Policy Decision
    -> Policy Lifecycle

Environment Plan
    -> Planning Lifecycle

Execution Plan
    -> Execution Planning Lifecycle

Environment Lifecycle
    -> Environment Aggregate

Desired State
    -> Environment Lifecycle

Observed State
    -> Observation Lifecycle

Operation
    -> Operation Aggregate

Audit Event
    -> Audit Lifecycle
```

No Control Plane component should become an accidental second source of truth for another aggregate.

The Control Plane consumes Intent and Intent IR artifacts through their defined lifecycle boundaries rather than redefining ownership of those artifacts.

---

## Command and Query Separation

Control Plane commands change authoritative state.

Conceptual commands include:

```text
Process Accepted Intent
Evaluate Policy
Authorize Transition
Create Environment Plan
Create Execution Plan
Request Approval
Authorize Operation
Start Authorized Operation
Complete Operation
Record Operation Failure
Update Desired State
Trigger Reconciliation
```

Queries observe state.

Conceptual queries include:

```text
Get Intent
Get Environment
Get Desired State
Get Observed State
Get Environment Plan
Get Execution Plan
Get Operation
Get Reconciliation Status
Get Audit History
```

A query must not create privileged side effects.

A command must have an explicit lifecycle outcome.

---

## Asynchronous Processing

The Control Plane supports asynchronous lifecycle processing for distributed and long-running operations.

Examples include:

```text
Environment Provisioning
Provider Operations
Observation Collection
Drift Detection
Reconciliation
Long-Running Operations
Scheduled Lifecycle Actions
```

A conceptual asynchronous flow is:

```text
Command
   |
   v
Persist Authoritative State
   |
   v
Publish Lifecycle Event
   |
   v
Worker
   |
   v
Provider Adapter
   |
   v
Result
   |
   v
Authoritative State Update
```

Persisted Control Plane state remains authoritative over transient worker state.

---

## Security Context Across Asynchronous Boundaries

Security context must remain integrity-protected when lifecycle execution crosses:

```text
Queues
Events
Message Brokers
Workflow Engines
Scheduled Jobs
Execution Workers
Reconciliation Workers
```

Relevant context may include:

```text
Requester Identity
Agent Identity
Correlation ID
Intent Version
Plan Version
Authorization Context
Policy Context
Approval Context
Operation Scope
```

A downstream worker must derive trusted security context from platform state.

It must not accept arbitrary authority embedded in an event or message payload.

Expired, missing, inconsistent, or invalid security context must not result in privileged execution.

Retries must not broaden authority or bypass an established security boundary.

---

## Lifecycle Events

The Control Plane may publish lifecycle events such as:

```text
IntentAccepted
PolicyEvaluated
EnvironmentPlanCreated
ExecutionPlanCreated
ExecutionPlanApproved
DesiredStateChanged
EnvironmentProvisioningStarted
EnvironmentActivated
EnvironmentUpdateStarted
EnvironmentDeprovisioningStarted
OperationStarted
OperationSucceeded
OperationFailed
ObservedStateUpdated
DriftDetected
ReconciliationProposed
ReconciliationPlanCreated
ReconciliationExecuted
```

Events communicate lifecycle transitions.

They are not substitutes for authoritative aggregate state.

Consumers may use events to react to changes while treating Control Plane state as authoritative.

---

## Event Idempotency

Consumers must tolerate duplicate event delivery.

An event should contain sufficient identity to establish:

```text
Event ID
Aggregate ID
Aggregate Version
Correlation ID
Event Type
Event Timestamp
```

Duplicate event delivery must not create duplicate privileged execution.

Operation identity remains the primary boundary for execution semantics.

---

## Auditability

The Control Plane must provide lifecycle traceability from request through environment state.

Conceptually:

```text
Request
   |
   v
Intent Version
   |
   v
Intent IR Version
   |
   v
Policy Decision
   |
   v
Environment Plan
   |
   v
Execution Plan
   |
   v
Authorization
   |
   v
Approval
   |
   v
Operation
   |
   v
Observed State
   |
   v
Reconciliation
```

Audit records should provide enough information to determine:

```text
Who initiated the lifecycle change
What Intent Version was used
Which Intent IR Version was accepted
Which policies were evaluated
What constraints applied
Which Plan was used
Whether approval was required
Who approved the change
Which Operation executed
What resource scope was targeted
What the outcome was
What was observed afterward
```

Audit records are immutable lifecycle history.

They are not the source of Desired State or Observed State.

---

## Observability

The Control Plane should expose enough operational information to understand lifecycle behavior.

Important dimensions include:

```text
Request ID
Intent ID
Intent Version
Intent IR Version
Environment ID
Environment Plan Version
Execution Plan Version
Operation ID
Correlation ID
Provider Context
Lifecycle State
Operation State
Policy Decision
Authorization Decision
Approval State
Execution Result
Observed State
Reconciliation Status
```

The observability model should support:

- Failure diagnosis
- Lifecycle tracing
- Reconciliation analysis
- Performance analysis
- Governance review
- Security investigation

Operational telemetry must not expose credentials or secret values.

---

## Failure Semantics

Failure is a first-class part of the lifecycle.

The Control Plane must distinguish between:

```text
Validation Failure
Policy Denial
Transition Authorization Denial
Approval Denial
Compilation Failure
Planning Failure
Operation Authorization Denial
Provider Failure
Timeout
Transient Failure
Permanent Failure
Observation Failure
Reconciliation Failure
Unknown Execution Outcome
```

These outcomes may have different recovery semantics and should not be collapsed into a single generic state.

---

## Validation Failure

An invalid Intent IR Version cannot enter Control Plane planning or privileged execution.

```text
Accepted Intent IR Version
         |
         v
Validation
         |
         +---- Invalid
                  |
                  v
               Rejected
```

No privileged execution occurs.

---

## Policy Denial

A valid Intent may still violate platform policy.

```text
Accepted Intent IR Version
         |
         v
Policy Evaluation
         |
         +---- DENY
                  |
                  v
            No Execution
```

Policy denial is a governance outcome, not an infrastructure execution failure.

---

## Transition Authorization Denial

An accepted and policy-valid Intent may still be unauthorized for the requesting identity, agent, Environment, action, or scope.

```text
Policy Decision
      |
      v
Transition Authorization
      |
      +---- DENY
               |
               v
        No Planning
```

Transition authorization denial must not be bypassed by modifying request or tool arguments.

---

## Approval Denial

When explicit approval is required:

```text
Execution Plan
      |
      v
Approval
      |
      +---- DENIED
               |
               v
          No Execution
```

Approval denial does not imply that the underlying Intent Version is invalid.

The plan remains a historical artifact unless replaced by a new governed plan or intent version.

---

## Operation Authorization Denial

An Execution Plan may be valid and policy-compliant while a concrete Operation is not authorized for its target, scope, or execution context.

```text
Execution Plan
      |
      v
Planned Operation
      |
      v
Operation Authorization
      |
      +---- DENY
               |
               v
          No Execution
```

Operation authorization denial must not be bypassed by changing provider adapter behavior.

---

## Planning Failure

If the Environment Plan or Execution Plan cannot be constructed safely:

```text
Planning
   |
   +---- Failure
           |
           v
        No Execution
```

The Control Plane retains sufficient failure information for diagnosis, retry, clarification, or replanning.

---

## Partial Execution Failure

A multi-step Execution Plan may partially succeed.

The Control Plane must preserve:

```text
Execution Plan
Operations Started
Operations Completed
Operations Failed
Current Environment Lifecycle State
Observed State
Desired State
Failure Information
```

A failed Operation does not imply that the Environment returned to its previous state.

Recovery may involve:

```text
Retry
Compensation
Rollback
Reconciliation
Manual Intervention
```

The available recovery mechanism depends on the semantics of the affected Operation.

The Control Plane must not assume rollback is universally possible.

---

## Retry Semantics

Retries are permitted only when Operation semantics support them.

A retry must preserve:

```text
Operation Identity
Authorization Scope
Policy Context
Approval Context
Execution Scope
```

A retry must not:

- Expand authority
- Change target scope implicitly
- Bypass approval
- Ignore updated policy
- Reuse expired credentials

A material change to the intended Operation creates a new governed lifecycle decision rather than silently mutating a retry.

---

## Reconciliation After Failure

After partial execution or external mutation, reconciliation may become the mechanism for recovery.

```text
Execution Failure
      |
      v
Observed State
      |
      v
Compare with Desired State
      |
      v
Drift / Divergence
      |
      v
Reconciliation Plan
      |
      v
Policy / Transition Authorization
      |
      v
Operation Authorization
      |
      v
Approval, when required
      |
      v
Precondition Evaluation
      |
      v
Authorized Operation
```

Reconciliation operates against the current authoritative Desired State rather than assuming that the previous execution step remains the desired target.

---

## Environment Deprovisioning

Deprovisioning is a governed lifecycle transition.

```text
ACTIVE
   |
   v
DEPROVISIONING
   |
   v
DELETED
```

Deprovisioning must pass through the applicable:

```text
Policy
Transition Authorization
Execution Planning
Operation Authorization
Approval, when required
Operation
Audit
```

A request to delete an Environment remains an explicit security-sensitive operation.

---

## Environment Updates

An Environment update is represented through a new governed lifecycle decision rather than direct mutation of runtime state.

Conceptually:

```text
Existing Desired State v5
          |
          v
New Intent Version
          |
          v
New Intent IR Version
          |
          v
Policy Decision
          |
          v
Transition Authorization
          |
          v
New Environment Plan Version
          |
          v
New Execution Plan Version
          |
          v
Operation Authorization
          |
          v
Approval, when required
          |
          v
Operations
          |
          v
New Desired State Version
```

The version identifiers are independent namespaces.

The platform must preserve complete lineage between old and new state.

---

## Control Plane and AI Agents

AI agents may interact with the Control Plane through controlled platform interfaces.

Conceptually:

```text
AI Agent
    |
    v
MCP / Tool Interface
    |
    v
Intent / Query / Proposal
    |
    v
Control Plane
```

An AI agent may:

```text
Construct Intent
Request Validation
Request a Plan
Inspect Environment State
Inspect Observed State
Analyze Failures
Propose Remediation
```

An AI agent does not receive unrestricted provider authority.

Privileged execution remains inside the deterministic Control Plane.

---

## MCP Interaction Boundary

MCP tools should expose platform-level operations rather than unrestricted provider capabilities.

Conceptual examples include:

```text
get_environment
get_desired_state
get_observed_state
validate_intent
evaluate_plan
generate_execution_plan
get_operation
inspect_reconciliation
propose_remediation
```

MCP tool access must remain subject to:

```text
Authentication
Authorization
Scope Validation
Input Validation
Policy Enforcement
Auditability
```

An MCP tool must not expose provider credentials or unrestricted infrastructure APIs to an AI agent.

---

## AI Proposals and Authoritative State

AI-generated proposals are not authoritative domain state.

For example:

```text
AI Proposal
     |
     v
Candidate Change
     |
     v
Validation
     |
     v
Policy
     |
     v
Transition Authorization
     |
     v
Planning
     |
     v
Operation Authorization
     |
     v
Approval
     |
     v
Execution
```

The Control Plane determines whether the proposal becomes an authorized lifecycle transition.

AI output must never directly mutate:

```text
Environment Lifecycle
Desired State
Operation State
Authorization State
Approval State
Provider Credentials
```

---

## Security Integration

The Control Plane enforces the security boundaries defined by the platform Security Model.

The privileged execution path is:

```text
Validation
    |
    v
Policy Decision
    |
    v
Transition Authorization
    |
    v
Environment Plan
    |
    v
Execution Plan
    |
    v
Operation Authorization
    |
    v
Approval, when required
    |
    v
Precondition Evaluation
    |
    v
Authorized Operation
    |
    v
Provider Adapter
```

The Control Plane must preserve:

```text
Bounded AI Authority
Provider Credential Isolation
Operation-Specific Authorization
Policy Constraint Preservation
Explicit Approval
Integrity-Protected Security Context
Failure-Closed Security
Privileged Auditability
```

---

## Policy Re-Evaluation

Policy is not necessarily a one-time lifecycle decision.

Policy may need to be re-evaluated when material conditions change.

Examples include:

```text
Environment Classification Changed
Resource Scope Changed
Policy Version Changed
Operation Parameters Changed
Execution Context Changed
Approval Conditions Changed
Security Context Changed
```

A historical Policy Decision remains traceable to the policy version under which it was evaluated.

A new or materially changed privileged transition must use the currently applicable policy context.

---

## Authorization Re-Evaluation

Authorization must be re-evaluated when material properties of an authorized Operation change.

Conceptually:

```text
Authorized Operation
        |
        +---- No Material Change
        |          |
        |          v
        |       Execute
        |
        +---- Material Change
                   |
                   v
              Re-Evaluate
                   |
          +--------+--------+
          |                 |
       Allowed            Denied
          |                 |
          v                 v
       Execute          No Execute
```

This prevents stale authorization from becoming implicit authority for a changed Operation.

---

## Approval Re-Evaluation

Approval must be re-evaluated when the approved scope or Operation is materially changed.

Examples include:

```text
Target Environment
Target Resource
Operation Set
Execution Parameters
Requested Scope
Applicable Security Context
```

Approval for one lifecycle transition must not be treated as permanent authorization for future transitions.

---

## Desired State Authority

Desired State is authoritative only when established through the governed lifecycle.

Conceptually:

```text
Candidate Change
      |
      v
Validation
      |
      v
Policy
      |
      v
Transition Authorization
      |
      v
Planning
      |
      v
Approval / Operation Authorization
      |
      v
Authoritative Desired State
```

An AI response, Execution Plan, or provider response does not itself establish Desired State.

---

## Observed State Authority

Observed State represents the platform's recorded observation of runtime conditions.

It should retain enough metadata to establish:

```text
Observation Identity
Observation Timestamp
Freshness
Completeness
Confidence
```

Observed State does not establish authorization.

The platform should distinguish:

```text
Observed
Fresh
Stale
Partial
Unavailable
Unknown
```

An unavailable, stale, partial, or unknown observation must remain distinguishable from a confirmed runtime condition.

---

## Lifecycle Consistency

The following consistency relationships should hold:

```text
Environment
    |
    +---- Desired State Version
    |
    +---- Observed State Snapshot
    |
    +---- Active / Historical Operations
```

Operations reference the Environment they affect.

Desired State references the Intent Version and Environment Plan that produced it.

Execution Plans reference the Environment Plan from which they were derived.

Reconciliation Plans reference both Desired State and Observed State.

This preserves lifecycle lineage.

---

## Version Lineage

The Control Plane should preserve a reconstructable lineage:

```text
Intent Version
      |
      v
Intent IR Version
      |
      v
Policy Decision
      |
      v
Transition Authorization
      |
      v
Environment Plan Version
      |
      v
Execution Plan Version
      |
      v
Operation Authorization
      |
      v
Approval
      |
      v
Operation
      |
      v
Desired State Version
      |
      v
Observed State Snapshot
```

A platform component should be able to determine which upstream version and authorization context produced a downstream lifecycle transition.

This supports:

```text
Auditability
Debugging
Reconciliation
Recovery
Governance
Incident Investigation
```

---

## Lifecycle Correlation

All related lifecycle artifacts should carry correlation metadata.

Conceptually:

```text
Correlation ID
     |
     +---- Intent
     +---- Intent IR
     +---- Policy Decision
     +---- Authorization Decision
     +---- Environment Plan
     +---- Execution Plan
     +---- Approval
     +---- Operation
     +---- Reconciliation
```

Correlation does not replace individual artifact identity or versioning.

---

## Control Plane Invariants

The following invariants define the core Control Plane contract.

### Invariant 1

The Control Plane is the deterministic authority for managed Environment lifecycle transitions.

### Invariant 2

An unaccepted or semantically invalid Intent IR Version cannot enter Control Plane planning or privileged execution.

### Invariant 3

Privileged execution requires an applicable Policy Decision.

### Invariant 4

The Control Plane must establish authorization before permitting the requested transition to proceed.

### Invariant 5

Privileged execution requires an applicable authorization decision bound to the concrete Operation.

### Invariant 6

Required approval conditions must be satisfied before the corresponding privileged Operation proceeds.

### Invariant 7

Execution Plans are distinct from execution.

### Invariant 8

Accepted Execution Plans are immutable.

### Invariant 9

Desired State and Observed State remain distinct.

### Invariant 10

Desired State becomes authoritative only through the governed lifecycle.

### Invariant 11

Observed State is evidence about runtime conditions and does not establish authorization.

### Invariant 12

Reconciliation cannot bypass policy, authorization, approval, or audit controls.

### Invariant 13

An Operation cannot broaden its own authority or execution scope.

### Invariant 14

Material security-relevant changes require applicable policy, authorization, and approval re-evaluation.

### Invariant 15

Provider adapters cannot independently establish or broaden platform authority.

### Invariant 16

Provider credentials are isolated from AI-facing interfaces.

### Invariant 17

Security-sensitive failures do not result in implicit authorization.

### Invariant 18

Authoritative Environment lifecycle state is owned by the Control Plane.

### Invariant 19

Authoritative state transitions are protected against stale overwrites.

### Invariant 20

Duplicate event delivery does not create duplicate privileged execution.

### Invariant 21

Operations remain durable enough to determine the current execution outcome and next valid lifecycle action.

### Invariant 22

A failed Operation does not imply that the Environment returned to its previous state.

### Invariant 23

Unknown execution outcomes must be explicitly represented and resolved before unsafe or conflicting retries.

---

## Control Plane State Machine

The overall governed lifecycle can be represented as:

```text
                    +-----------------------+
                    | ACCEPTED INTENT IR    |
                    | VERSION               |
                    +-----------+-----------+
                                |
                                v
                    +-----------------------+
                    | POLICY EVALUATION     |
                    +-----------+-----------+
                                |
                         +------+------+
                         |             |
                        DENY         ALLOW
                         |             |
                         v             v
                      REJECT    TRANSITION AUTHORIZATION
                                      |
                               +------+------+
                               |             |
                              DENY         ALLOW
                               |             |
                               v             v
                           NO PLANNING   DEPENDENCY RESOLUTION
                                             |
                                             v
                                    ENVIRONMENT PLAN
                                             |
                                             v
                                     EXECUTION PLAN
                                             |
                                             v
                                  OPERATION MATERIALIZATION
                                             |
                                             v
                                  +-----------------------+
                                  | OPERATION GOVERNANCE  |
                                  |                       |
                                  | OPERATION             |
                                  | AUTHORIZATION         |
                                  |                       |
                                  | APPROVAL IF REQUIRED |
                                  +-----------+-----------+
                                              |
                                              v
                                     GOVERNANCE SATISFIED
                                              |
                                              v
                                  PRECONDITION CHECK
                                             |
                                             v
                                      AUTHORIZED OP
                                             |
                                             v
                                      PROVIDER ADAPTER
                                             |
                                             v
                                       ENVIRONMENT
                                             |
                                             v
                                      OBSERVED STATE
                                             |
                                             v
                                      RECONCILIATION
                                             |
                                             +----------------+
                                                              |
                                                              v
                                                    NEW AUTHORIZED OP
```

This represents a continuous control loop rather than a one-time provisioning workflow.

---

## Example: Create an Application Environment

A conceptual request is:

```text
Create a production application environment in an approved region.
```

The lifecycle becomes:

```text
Request
   |
   v
Intent Compiler
   |
   v
Accepted Intent IR Version
   |
   v
Policy Evaluation
   |
   v
Transition Authorization
   |
   v
Dependency Resolution
   |
   v
Environment Plan
   |
   v
Execution Plan
   |
   v
Operation Authorization
   |
   v
Approval, when required
   |
   v
Precondition Evaluation
   |
   v
Authorized Operations
   |
   v
Provider Adapter
   |
   v
Managed Runtime
   |
   v
Observed State
   |
   v
Desired vs Observed Comparison
```

The AI may assist in constructing the initial Intent, but the Control Plane owns the authoritative lifecycle.

---

## Example: Runtime Drift

Assume a managed resource is externally modified.

```text
Desired State
    |
    | Expected Configuration
    v
Observed State
    |
    | External Change
    v
DRIFT_DETECTED
```

The Control Plane determines:

```text
Is the drift real?
        |
        v
Is remediation required?
        |
        v
Is remediation supported?
        |
        v
Does policy allow it?
        |
        v
Is the transition authorized?
        |
        v
Create Reconciliation Plan
        |
        v
Authorize Concrete Operation
        |
        v
Is approval required?
        |
        v
Precondition Evaluation
        |
        v
Authorized Operation
```

The external mutation does not create execution authority.

---

## Example: Partial Provisioning Failure

Suppose an Environment requires three Operations:

```text
Operation A -> Network
Operation B -> Compute
Operation C -> Application
```

Execution may produce:

```text
A -> SUCCEEDED
B -> SUCCEEDED
C -> FAILED
```

The Control Plane records this state.

```text
Desired State
     |
     v
Environment Partially Provisioned
     |
     v
Operation C FAILED
     |
     v
Observed State
     |
     v
Reconciliation
```

The platform does not assume that the Environment has returned to its pre-provisioning state.

The next action is determined from the current authoritative and observed state.

---

## Control Plane API Contract

The concrete API technology is implementation-specific.

The logical API should expose operations corresponding to Control Plane responsibilities.

Conceptual commands include:

```text
processAcceptedIntent
evaluatePolicy
authorizeTransition
createEnvironmentPlan
createExecutionPlan
requestApproval
authorizeOperation
startAuthorizedOperation
completeOperation
recordOperationFailure
updateDesiredState
triggerReconciliation
```

Conceptual queries include:

```text
getIntent
getEnvironment
getDesiredState
getObservedState
getEnvironmentPlan
getExecutionPlan
getOperation
getReconciliation
getAuditHistory
```

The API must preserve:

```text
Versioning
Correlation
Scope
Authorization Context
Idempotency
Lifecycle Identity
```

A client must not bypass lifecycle boundaries by invoking lower-level privileged provider operations directly.

---

## API Boundary Rules

### Explicit Resource Identity

Every lifecycle command identifies its target Environment, Operation, or other domain artifact explicitly.

### Explicit Versioning

Commands that modify versioned artifacts must establish the expected version.

### Explicit Scope

Privileged Operations carry explicit resource and Environment scope.

### Explicit Authorization

Privileged commands execute only within an established authorization context.

### Explicit Idempotency

Commands that may be retried must support stable idempotency semantics.

### Explicit Outcome

Operation APIs report lifecycle outcomes rather than relying on transport-level success alone.

---

## Persistence Boundary

The Control Plane requires durable storage for authoritative lifecycle state.

At minimum, durable state must preserve references or records for:

```text
Intent References
Intent IR References
Policy Decisions
Authorization Decisions
Environment State
Desired State Versions
Observed State Snapshots
Environment Plans
Execution Plans
Operations
Approval State
Audit Records
```

The Control Plane may reference Intent and Intent IR artifacts owned by their respective lifecycle components.

Transient worker memory must not become the source of truth for lifecycle state.

The exact persistence architecture is implementation-specific.

---

## Transactional Boundaries

A single distributed transaction across the entire provider execution path should not be assumed.

The Control Plane should instead use explicit lifecycle state transitions.

Conceptually:

```text
Persist Accepted Intent Reference
      |
      v
Persist Environment Plan
      |
      v
Persist Execution Plan
      |
      v
Persist Operation
      |
      v
Persist Operation Authorization
      |
      v
Execute Authorized Operation
      |
      v
Persist Operation Result
      |
      v
Update Observed State
```

The state machine is therefore the mechanism for durable progress tracking.

---

## Control Plane and Eventual Consistency

The platform operates across distributed systems.

As a result:

```text
Desired State
```

and:

```text
Observed State
```

may temporarily differ even when execution is healthy.

The platform must distinguish:

```text
Expected Convergence Delay
```

from:

```text
Actual Drift
```

Reconciliation should therefore account for Operation state, execution outcome, and observation freshness.

---

## Convergence

The desired end condition of the lifecycle is:

```text
Desired State ≈ Observed State
```

where the equivalence is defined by the platform's resource and operational semantics.

Conceptually:

```text
          Desired State
                |
                v
        +----------------+
        | Reconciliation |
        +-------+--------+
                |
                v
          Provider Runtime
                |
                v
          Observed State
                |
                +------> Converged
```

Convergence does not necessarily require byte-for-byte equality.

Provider-specific representation may differ while preserving the platform-level desired semantics.

---

## Handling Unsupported Capabilities

A provider may not support a capability required by the Intent IR or Environment Plan.

The Control Plane must not silently approximate a capability in a way that changes the semantics of the request.

The lifecycle should produce an explicit outcome such as:

```text
UNSUPPORTED_CAPABILITY
```

or:

```text
UNSATISFIABLE_PLAN
```

The request may then be:

```text
Rejected
Clarified
Replanned
```

depending on the platform contract.

Unsupported capabilities must not become hidden provider-specific behavior.

---

## Handling Policy Evolution

Policies may evolve after an Intent Version or Plan Version was created.

The Control Plane should preserve the policy version associated with each Policy Decision.

A later policy change does not silently mutate a historical decision.

For a new or materially changed privileged transition, the currently applicable policy context must be evaluated according to platform governance rules.

This preserves both:

```text
Historical Explainability
```

and:

```text
Current Governance
```

---

## Handling Environment Classification Changes

An Environment classification change may materially affect:

```text
Allowed Operations
Credential Scope
Approval Requirements
Network Requirements
Data Handling
Policy Requirements
```

The classification must therefore be treated as authoritative platform state.

A change in classification may require downstream policy and authorization re-evaluation.

It must not be inferred from resource naming conventions or natural-language descriptions at execution time.

---

## Control Plane Security Context

Every privileged lifecycle transition should carry enough trusted context to establish:

```text
Who initiated the request
Which agent acted, if applicable
Which Environment is targeted
Which resource scope is targeted
Which Intent Version applies
Which Plan Version applies
Which policies apply
Which authorization applies
Which approval applies
Which Operation is being executed
```

The context must be derived from trusted Control Plane state.

Request-supplied fields are inputs to validation, not authority.

---

## Control Plane Audit Invariants

The Control Plane should ensure that:

```text
Every privileged Operation
        |
        v
Produces auditable lifecycle records
```

At minimum, the platform should retain enough information to identify:

```text
Requester
Agent
Intent
Plan
Policy
Authorization
Approval
Operation
Target
Outcome
```

Failure to create a required security-critical audit event must not be interpreted as permission to continue privileged execution.

---

## Security Failure Behavior

Security failures must fail closed.

Examples include:

```text
Unknown Identity
Invalid Authorization
Missing Policy Decision
Missing Approval
Expired Security Context
Invalid Security Context
Credential Resolution Failure
Scope Mismatch
Audit Failure for Required Security Event
```

Conceptually:

```text
Security Control Failure
         |
         v
      No Access
```

Operational retry may occur only after the security condition has been restored and the applicable controls have been re-evaluated.

---

## Control Plane Non-Goals

### Natural-Language Interpretation

Natural-language interpretation belongs to the AI Plane and Intent Compiler.

### Model Selection

The Control Plane does not choose AI models.

### Provider-Specific Reasoning

Provider-specific execution semantics belong to provider adapters.

### Unrestricted Agent Execution

The Control Plane does not expose unrestricted infrastructure access to AI agents.

### Runtime Ownership

The Control Plane governs resources but does not become the workload runtime itself.

### Arbitrary Manual Mutation

Direct unmanaged provider-side mutation is external to the lifecycle contract and is treated as an externally observed change during reconciliation.

---

## Architectural Decision Summary

The Control Plane establishes a deterministic lifecycle around accepted platform intent.

The essential sequence is:

```text
Accepted Intent IR Version
        |
        v
Policy
        |
        v
Transition Authorization
        |
        v
Dependency Resolution
        |
        v
Environment Plan
        |
        v
Execution Plan
        |
        v
Operation Authorization
        |
        v
Approval, when required
        |
        v
Precondition Evaluation
        |
        v
Authorized Operation
        |
        v
Provider Adapter
        |
        v
Managed Environment
        |
        v
Observed State
        |
        v
Reconciliation
```

The architecture intentionally separates:

```text
Intent
Plan
Transition Authorization
Operation Authorization
Approval
Execution
Desired State
Observed State
Reconciliation
```

Each stage has an explicit responsibility and lifecycle identity.

---

## Summary

The Control Plane is the deterministic authority for the lifecycle of managed environments.

It consumes an immutable, semantically valid Intent IR Version and applies:

```text
Policy
Transition Authorization
Dependency Resolution
Environment Planning
Execution Planning
Operation Authorization
Approval
Execution
Observation
Reconciliation
```

The Control Plane maintains explicit authority over:

```text
Environment Lifecycle
Desired State
Operations
Execution Planning
Reconciliation
```

It preserves:

```text
Versioning
Traceability
Provider Independence
Security Boundaries
Failure Semantics
Auditability
```

The central lifecycle is:

```text
Intent
   |
   v
Validated Intent IR
   |
   v
Governed Transition
   |
   v
Environment Plan
   |
   v
Execution Plan
   |
   v
Authorized Operation
   |
   v
Runtime Environment
   |
   v
Observed State
   |
   v
Reconciliation
   |
   +----------------------+
                          |
                          v
                  New Authorized Operation
```

The fundamental architectural boundary remains:

```text
AI proposes.
Control Plane authorizes.
Provider Adapter executes.
```

This boundary allows the AI Plane to evolve independently while preserving a deterministic, auditable, provider-neutral control system for managed environments.
