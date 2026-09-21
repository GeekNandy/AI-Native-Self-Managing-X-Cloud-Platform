# Domain Model

## Purpose

This document defines the core domain model of the AI-Native Self-Managing X-Cloud Platform.

The domain model establishes the concepts, relationships, lifecycle states, and invariants required to translate software and infrastructure intent into governed execution and continuous environment management.

The model is intentionally independent of a specific AI model, cloud provider, infrastructure engine, or persistence technology.

## Domain Boundaries

The platform domain is organized around five primary concerns:

```text
Intent
  |
  v
Planning
  |
  v
Environment Lifecycle
  |
  v
Runtime Observation
  |
  v
Reconciliation
```

Cross-cutting concerns include policy, authorization, auditability, identity, and versioning.

The AI Plane interacts with these concepts through platform interfaces but does not define the authoritative domain state.

## Core Domain Concepts

The primary domain concepts are:

```text
Intent
Intent IR
Policy Decision
Environment Plan
Execution Plan
Environment
Managed Resource
Desired State
Observed State
Drift
Reconciliation
Reconciliation Plan
Operation
Audit Event
```

Each concept has a distinct responsibility.

## Intent

An Intent represents a requested change or desired environment expressed by a platform client.

An Intent may originate from:

- Developer or platform clients
- AI agents
- Automation systems

The Intent captures the request as submitted to the platform and provides the input for construction of the Intent IR.

An Intent has:

```text
Intent ID
Requester Identity
Source
Correlation ID
Created At
```

Each accepted request is represented by an immutable Intent Version.

An Intent Version has:

```text
Version
Timestamp
Payload
```

A change to an accepted request creates a new Intent Version. Each Intent Version is immutable and independently traceable through validation, policy evaluation, planning, and execution.

## Intent IR

The Intent Intermediate Representation is the normalized, provider-neutral representation of what the requester wants the platform to manage.

The Intent IR is the primary platform contract between request interpretation and deterministic control-plane processing.

Conceptually:

```text
Natural-language request
        |
        v
Intent extraction
        |
        v
Intent IR
        |
        +---- Identity
        +---- Application
        +---- Runtime
        +---- Environment
        +---- Network
        +---- Data
        +---- Availability
        +---- Security
        +---- Observability
        +---- Lifecycle
        +---- Constraints
```

The Intent IR does not contain provider-specific execution details.

### Intent IR Invariants

1. The Intent IR is independent of a specific AI model.
2. The Intent IR is independent of a specific cloud provider.
3. An Intent IR must be semantically valid before planning.
4. Each Intent IR version is traceable to the Intent Version from which it was constructed.
5. An Intent IR version is immutable once accepted into the planning lifecycle.

## Policy Decision

A Policy Decision represents the result of evaluating a domain artifact or proposed action against applicable platform policies.

Possible outcomes include:

```text
ALLOW
ALLOW_WITH_CONSTRAINTS
DENY
```

A Policy Decision records:

```text
Decision ID
Subject
Evaluation Stage
Policy Set / Policy Version
Decision
Constraints
Reason
Timestamp
Evaluator Version
```

The Evaluation Stage identifies the lifecycle point at which the policy decision was produced.

Examples include:

```text
INTENT
EXECUTION_PLAN
RECONCILIATION
```

Policy decisions are part of the auditable lifecycle.

A policy decision does not itself execute a change.

## Environment Plan

An Environment Plan represents the provider-neutral environment resulting from a valid Intent IR after semantic validation, policy evaluation, dependency resolution, and environment compilation.

The Environment Plan defines the intended composition of the managed environment without binding execution to a specific cloud provider.

An Environment Plan has:

```text
Plan ID
Environment ID
Intent Version
Plan Version
Resources
Capabilities
Relationships
Dependencies
Constraints
Lifecycle Requirements
Created At
```

Conceptually:

```text
Intent IR
   |
   v
Validation
   |
   v
Intent Policy Decision
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

An Environment Plan is immutable once accepted for execution planning.

## Execution Plan

An Execution Plan represents an ordered set of planned operation specifications required to transition an environment toward the state described by the Environment Plan.

The Execution Plan makes explicit:

```text
Planned Operation
Dependency
Precondition
Ordering
Policy Constraint
Expected Result
```

Conceptually:

```text
Environment Plan
       |
       v
Execution Planner
       |
       v
Execution Plan
```
An Execution Plan has:

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

The Execution Plan is distinct from execution itself. An Execution Plan is immutable once accepted for execution.

A change to an accepted plan results in a new Execution Plan version rather than mutating the existing plan.

Planned operation specifications are materialized as Operations when execution begins.

This separation allows the platform to:

- Inspect planned changes
- Validate preconditions
- Apply approval requirements
- Audit the proposed transition
- Execute operations deterministically

Execution of an Execution Plan that satisfies any required approval conditions produces Environment lifecycle transitions and associated Operations.

The Execution Plan therefore describes a proposed transition, while the Environment and Operations represent the authoritative lifecycle state and execution history resulting from that transition.

### Execution Plan Approval

Approval is a lifecycle decision associated with an Execution Plan when required by policy or environment risk.

Approval does not change the Environment lifecycle state.

An Execution Plan may proceed to privileged execution only after all required approval conditions have been satisfied.

Conceptually:

```text
Execution Plan
      |
      v
Approval Required?
   /          \
 yes           no
  |             |
  v             |
Approval        |
  |             |
  +------+------+
         |
         v
Authorized Execution
```

## Environment

An Environment represents a managed logical deployment boundary controlled by the platform.

An Environment provides the lifecycle identity against which desired state, observed state, operations, and reconciliation are associated.

An Environment may contain:

```text
Application Workloads
Compute
Networking
Storage
Databases
Messaging
Managed Services
Platform Capabilities
```

The logical Environment is provider-neutral even when its underlying resources are provider-specific.

An Environment has:

```text
Environment ID
Name
Owner
Lifecycle State
Desired State Version
Observed State Snapshot
Provider Bindings
Created At
Updated At
```

## Managed Resource

A Managed Resource represents a provider-neutral resource that the platform manages as part of an Environment.

A Managed Resource has:

```text
Resource ID
Environment ID
Resource Type
Lifecycle State
Configuration
Dependencies
Provider Binding
Created At
Updated At
```

The resource identity remains stable across provider-specific representations.

A Managed Resource may correspond to infrastructure, an application workload, or a managed service.

The Managed Resource remains provider-neutral at the domain level.

Provider-specific identifiers and execution details are represented through the Provider Binding as an external reference to the underlying provider resource. Provider-specific semantics remain outside the core domain model.

## Environment Lifecycle

An Environment progresses through explicit lifecycle states.

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
    +------------------+
    |                  |
    v                  v
UPDATING           RECONCILING
    |                  |
    +--------+---------+
             |
             v
           ACTIVE
             |
             v
         DEPROVISIONING
             |
             v
          DELETED
```

Approval is modeled separately as part of the Execution Plan lifecycle and is not itself an Environment lifecycle state.

Failure conditions may occur during provisioning, updating, reconciliation, or deprovisioning.

The lifecycle state represents the authoritative Control Plane state, not merely an inference from runtime health.

## Desired State

Desired State represents the environment that the platform has committed to maintain.

Desired State is derived from:

```text
Accepted Intent Version
Applicable Intent Policy Decisions
Environment Plan
Satisfied Approval Conditions
```

Desired State has an explicit version.

Where execution-plan approval is required, Desired State becomes authoritative only after the required approval conditions have been satisfied.

Conceptually:

```text
Desired State
    |
    +---- Environment Configuration
    +---- Required Capabilities
    +---- Security Constraints
    +---- Operational Requirements
    +---- Lifecycle Requirements
```
A Desired State has:

```text
Environment ID
Version
Source Intent Version
Environment Plan Version
Configuration
Required Capabilities
Security Constraints
Operational Requirements
Lifecycle Requirements
Created At
```

Desired State is authoritative only after the conditions required for the corresponding change have been satisfied.

## Observed State

Observed State represents the environment currently reported by runtime and provider observations.

Observed State may be derived from:

- Provider APIs
- Runtime inventory
- Infrastructure telemetry
- Application health
- Operational signals

Observed State is identified by an observation or snapshot identifier and observation timestamp.

Unlike Desired State, Observed State represents runtime observations rather than an authoritative target.

Observed State does not become authoritative merely because it was observed.

Its purpose is to describe actual runtime conditions against which Desired State can be evaluated.

## Desired State and Observed State

The platform deliberately keeps these representations separate.

```text
        Desired State
              |
              v
        +------------+
        | Difference |
        +------------+
              ^
              |
        Observed State
```

The relationship between them determines whether an environment is converged.

Possible conditions include:

```text
CONVERGED
DRIFT_DETECTED
UNKNOWN
```

`UNKNOWN` represents a condition where available observations are insufficient to establish convergence safely.

The platform should avoid treating incomplete observation as proof of convergence.

## Operation

An Operation represents an individual lifecycle action performed against an environment.

Examples include:

```text
CREATE_RESOURCE
UPDATE_RESOURCE
DELETE_RESOURCE
ATTACH_CAPABILITY
REMOVE_CAPABILITY
RECONCILE_RESOURCE
```

An Operation has:

```text
Operation ID
Environment ID
Operation Type
Requested By
Plan Type
Plan Reference
Status
Preconditions
Started At
Completed At
Result
Failure Information
```

An Operation may depend on other operations.

Possible states include:

```text
PENDING
RUNNING
SUCCEEDED
FAILED
CANCELLED
```

Operation state is retained for operational traceability and recovery.

## Reconciliation

Reconciliation represents the domain process of evaluating differences between Desired State and Observed State and determining whether corrective action is required.

Conceptually:

```text
Desired State
      |
      +------+
             |
             v
       Drift Detection
             |
             v
       Reconciliation
             |
             v
      Reconciliation Plan
             |
             v
      Authorized Operations
             |
             v
       Observed State
```

Reconciliation is not a separate privileged execution path.

It operates through the same policy, authorization, planning, and execution boundaries used for initial provisioning.

## Reconciliation Plan

A Reconciliation Plan represents the planned changes required to address detected drift between Desired State and Observed State.

A Reconciliation Plan is a specialized execution proposal derived from the reconciliation process.

A Reconciliation Plan has:

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

A Reconciliation Plan does not bypass the normal authorization and execution boundaries.

Once authorized, its planned operation specifications are materialized as Operations.

## Drift

Drift represents a difference between authoritative Desired State and the available Observed State.

Drift should identify:

```text
Drift ID
Environment ID
Resource
Expected Value
Observed Value
Difference
Severity
Detection Timestamp
Source
```

Not every difference necessarily results in automatic remediation.

The reconciliation process evaluates the difference against applicable policy and operation semantics before proposing corrective action.

## Audit Event

An Audit Event represents an immutable record of a significant lifecycle transition or privileged platform action.

Examples include:

```text
INTENT_ACCEPTED
INTENT_VALIDATED
POLICY_EVALUATED
PLAN_CREATED
PLAN_APPROVED
OPERATION_STARTED
OPERATION_COMPLETED
OPERATION_FAILED
DRIFT_DETECTED
RECONCILIATION_PROPOSED
RECONCILIATION_EXECUTED
```

Audit Event Types describe persisted audit records, while Domain Events represent domain facts emitted by state transitions. The two vocabularies may map to one another but serve different concerns.

An Audit Event contains sufficient correlation information to reconstruct the lifecycle of a request.

Typical fields include:

```text
Event ID
Event Type
Timestamp
Actor
Environment ID
Intent ID
Plan Type
Plan Reference
Operation ID
Correlation ID
Outcome
Metadata
```

Audit Events are append-only lifecycle records.

They are not the source of Desired State or Observed State.

## Relationships

The primary domain relationships are:

```text
Intent
   |
   v
Intent Version
   |
   v
Intent IR
   |
   v
Intent Policy Decision
   |
   v
Environment Plan
   |
   v
Execution Plan
   |
   v
Environment
   |
   +-------------------+
   |                   |
   v                   v
Managed Resources   Desired State
                        |
                        v
                   +------------+
                   |  Compare   |
                   +------------+
                        ^
                        |
                  Observed State
                        |
                        v
                       Drift
                        |
                        v
                 Reconciliation
                        |
                        v
               Reconciliation Plan
                        |
                        v
                    Operation
                        |
                        v
                   Environment
```

Audit Events provide traceability across the lifecycle rather than acting as a separate domain state store.

## Aggregate Boundaries

The initial aggregate boundaries are:

### Intent Aggregate

Owns:

```text
Intent Identity
Intent Versions
Intent Lifecycle Metadata
```

Each accepted Intent Version is immutable. The aggregate preserves version identity and lifecycle consistency across successive request versions.

### Environment Aggregate

Owns:

```text
Environment Identity
Lifecycle State
Managed Resources
Desired State Reference
Observed State Reference
Provider Bindings
```

Managed Resources are entities within the Environment aggregate and participate in the Environment lifecycle.

The Environment aggregate represents the authoritative lifecycle boundary for a managed environment.

### Operation Aggregate

Owns:

```text
Operation
Operation State
Preconditions
Dependencies
Execution Result
Failure Information
```

The Operation aggregate represents an individual state transition within the environment lifecycle.

Policy decisions and audit events are associated with these aggregates but remain independently managed domain records.

Policy decisions reference the applicable policy and evaluator versions. Audit events preserve immutable lifecycle history.

## State Ownership

Authoritative ownership is defined as follows:

```text
Intent
    → Intent Aggregate

Intent IR
    → Intent / Planning lifecycle

Policy Decision
    → Policy evaluation lifecycle

Environment Plan
    → Planning lifecycle

Execution Plan
    → Execution planning lifecycle

Environment
    → Environment Aggregate

Desired State
    → Environment lifecycle

Observed State
    → Observation lifecycle

Drift
    → Reconciliation lifecycle

Operation
    → Operation Aggregate

Audit Event
    → Audit lifecycle
```

No AI component is the authoritative owner of these states.

## Versioning

The following artifacts require explicit versioning:

```text
Intent Version
Intent IR Version
Policy Definition
Environment Plan
Execution Plan
Desired State
```

Observed State is identified by an observation or snapshot identifier and timestamp.

Versioning provides traceability between:

```text
Request
  |
  v
Intent
  |
  v
Intent Version
  |
  v
Intent IR Version
  |
  v
Intent Policy Decision
  |
  v
Environment Plan Version
  |
  v
Execution Plan Version
  |
  v
Desired State Version
  |
  v
Observed State Snapshot
```

A platform component should be able to determine which version of an upstream artifact produced a given downstream state transition.

## Domain Invariants

The following invariants are fundamental to the domain model.

### Invariant 1

An accepted Intent Version is immutable.

### Invariant 2

An accepted Intent IR is provider-neutral and model-independent.

### Invariant 3

Privileged execution requires a valid policy decision.

### Invariant 4

An Execution Plan is distinct from execution.

### Invariant 5

Desired State and Observed State remain separate.

### Invariant 6

Reconciliation cannot bypass policy and authorization boundaries.

### Invariant 7

Operation state provides a durable record of lifecycle execution.

### Invariant 8

Audit Events are immutable and append-only.

### Invariant 9

Authoritative environment lifecycle state is owned by the Control Plane.

### Invariant 10

AI-generated output cannot directly establish authoritative platform state.

## Domain Events

The initial domain event vocabulary includes:

```text
IntentAccepted
IntentValidated
PolicyEvaluated
EnvironmentPlanCreated
ExecutionPlanCreated
ExecutionPlanApproved
EnvironmentProvisioningStarted
EnvironmentProvisioned
OperationStarted
OperationSucceeded
OperationFailed
ObservedStateUpdated
DriftDetected
ReconciliationProposed
ReconciliationPlanCreated
ReconciliationExecuted
EnvironmentDeprovisioningStarted
EnvironmentDeleted
```

Events represent facts that have occurred.

Commands and events are deliberately separated:

```text
Command
   |
   v
Domain Processing
   |
   v
State Transition
   |
   v
Domain Event
```

## Failure Semantics

Failure is represented explicitly within the domain lifecycle.

Examples include:

```text
Validation Failure
Policy Denial
Planning Failure
Execution Failure
Observation Failure
Reconciliation Failure
```

A failed operation does not imply that the environment has returned to its previous state.

The resulting Environment, Desired State, Observed State, and Operation records must remain sufficient to determine the next valid lifecycle action.

Partial execution therefore remains observable and recoverable through subsequent lifecycle processing and reconciliation.

## Domain Model and AI Boundary

The AI Plane may:

```text
Interpret Intent
Construct Intent IR
Inspect State
Analyze Failures
Propose Actions
```

The deterministic domain owns:

```text
Validation
Policy Decisions
Environment Plans
Execution Plans
Environment State
Desired State
Operation State
Reconciliation
```

This boundary preserves the distinction between probabilistic reasoning and authoritative system state.

## Domain Model and Provider Boundary

The domain model remains provider-neutral.

Provider-specific execution concepts are introduced only at the execution boundary:

```text
Domain Model
     |
     v
Provider-neutral Plan
     |
     v
Cloud Provider Adapter
     |
     v
Provider APIs
```

A provider-specific capability that cannot be represented generically should be modeled explicitly as a platform capability rather than leaking provider-specific implementation details into the core domain contract.

## Summary

The domain model establishes a controlled lifecycle from requested intent to managed environment state:

```text
Intent
  ↓
Intent IR
  ↓
Intent Policy Decision
  ↓
Environment Plan
  ↓
Execution Plan
  ↓
Environment
  ↓
Desired State ↔ Observed State
  ↓
Drift Detection
  ↓
Reconciliation
  ↓
Operation
```

The model keeps authoritative state within the deterministic Control Plane, separates desired state from runtime observation, and provides explicit boundaries for policy, execution, reconciliation, and auditability.

This domain model forms the contract between the architecture and subsequent implementation phases.
