# AI-Native Self-Managing X-Cloud Platform

An AI-native control plane for translating high-level software and infrastructure intent into governed, deployable, and self-managing cloud environments.

## Working Proof of Concept

The repository now includes a runnable **governed agent workbench**: describe a Java service, review its structured intent and policy checks, inspect generated code and manifests, approve a sandbox plan, introduce drift, and review its recovery. Reviewed feedback becomes regression evidence.

![The working agent workbench with a compiled production-service proposal](docs/images/workbench.png)

### Run in two commands

Requirements: **JDK 21**. No external services, API keys or frontend build are needed for the offline demo.

```bash
./mvnw verify
java -jar target/AI-Native-Self-Managing-X-Cloud-Platform-0.0.1-SNAPSHOT.jar
```

Open **http://127.0.0.1:8080**. Choose **Production service**, compile the intent and inspect the proposal. After approval, open **Environments**, inject a fault and review the recovery plan.

| Implemented | Demonstrable behavior |
| --- | --- |
| Typed intent and independent policy | Semantic validation and eight versioned policy checks, including residency, encryption, networking, availability and quota |
| Deterministic compilation | Ordered logical resource operations; Java source, Dockerfile and Kubernetes review previews |
| Explicit change review | SHA-256 digest bound to the complete plan; fifteen-minute expiry; state revision and current-policy checks at apply |
| Sandbox lifecycle | Committed desired state, separate observations, retry deduplication and per-environment concurrency control |
| Drift and recovery | Replica loss, opened egress and lost observation; remediation uses the same approval and execution boundary |
| Measured feedback | Versioned golden scenarios, isolated execution-safety checks and operator-specified regression cases |
| Full-stack workbench | Responsive same-origin browser client, HTTP API, activity history and exportable artifacts/evaluation reports |
| Optional live extraction | Configurable Gemini structured-output adapter with bounded concurrency, deadlines and strict response validation |

**Scope:** default interpretation is exact replay of curated examples; execution is an in-memory simulator. No cloud resources are provisioned. Encryption and residency are policy requirements, not deployed guarantees. Gemini needs explicit configuration and a live evaluation. Feedback does not train a model or alter policy. The target architecture below extends beyond the implemented slice.

### Reproduce the evidence

With the app running:

```bash
python3 scripts/demo.py --evidence target/demo-evidence.json --artifacts-dir target/generated-workload
python3 scripts/benchmark.py --requests 128 --concurrency 8 --output target/local-plan-benchmark.json
```

The demo verifies the complete HTTP control loop, retry deduplication, policy denial, clarification, recovery and feedback evaluation. The benchmark measures the local structured-plan endpoint; it excludes model inference and cloud execution. [Recorded results](docs/evidence/README.md) disclose their source digest and limits.

### Start here

- [Five-minute demo and live Gemini setup](docs/demo-guide.md)
- [CodeAI relevance, honest claims and next increments](docs/poc-positioning.md)
- [Decision: one governed sandbox slice](docs/adr/0001-governed-sandbox-slice.md)
- [Architecture](docs/architecture.md), [domain model](docs/domain-model.md), [intent compiler](docs/intent-compiler.md) and [security model](docs/security-model.md)

For browser checks: `npm ci`, `npx playwright install chromium`, then `npm run test:e2e`. The Java build and browser workflows also run in GitHub Actions.

## Problem

Modern engineering platforms expose developers to a growing set of infrastructure, security, networking, identity, compliance, and operational decisions.

This creates friction between:

- What a developer wants to build
- What the platform allows
- How that intent is translated into infrastructure
- How the resulting environment is operated and maintained

This project explores a control-plane architecture that reduces that gap while keeping security, governance, and execution deterministic.

## Vision

A developer, platform engineer, or AI agent should be able to express an environment in terms of intent:

> "Create a production Java service in Germany with private networking, restricted egress, encrypted PII data, multi-zone availability, monitoring, and rollback support."

The platform translates that intent into a structured representation, validates it against policy, produces an execution plan, and manages the resulting environment lifecycle.

## Architecture

```mermaid
flowchart TB
    Client["Developer / Platform Client"]
    AgentClient["AI Agent"]

    AI["AI Plane"]
    Agent["Agent Orchestrator"]
    Knowledge["Platform Knowledge / RAG"]
    MCP["MCP / Tool Interface"]

    Intent["Intent Gateway"]
    IR["Intent IR"]
    Policy["Policy & Governance Engine"]
    Compiler["Environment Compiler"]
    Planner["Execution Planner"]
    Control["Environment Control Plane"]
    State["Desired State / Audit"]

    Runtime["Cloud / Hybrid Cloud Runtime"]
    Observe["Observability"]
    Observed["Observed State"]
    Drift["Drift Detection"]
    Remediate["Reconciliation / Remediation"]

    Client --> Intent

    AgentClient --> AI
    AI --> Agent
    Agent --> Knowledge
    Agent --> MCP
    MCP --> Intent

    Intent --> IR
    IR --> Policy
    Policy --> Compiler
    Compiler --> Planner
    Planner --> Control
    Control --> Runtime
    Control --> State

    Runtime --> Observe
    Observe --> Observed
    State --> Drift
    Observed --> Drift
    Drift --> Remediate
    Remediate --> Control
```

## Core Design Principles

### 1. AI reasons about intent; deterministic systems execute infrastructure

LLMs and agents are useful for interpreting requests, retrieving knowledge, analyzing failures, and proposing actions.

They are not treated as the final authority for privileged infrastructure execution.

### 2. Policy is evaluated before privileged execution

Security, compliance, residency, networking, budget, and operational constraints are represented as explicit policies.

A requested environment must pass policy evaluation before execution can proceed.

### 3. Structured intent is the platform contract

Natural language is an input format, not the execution contract.

The platform converts natural-language requests into a structured intermediate representation that can be validated, planned, audited, and compiled independently of the underlying AI model.

### 4. Control plane and workload/data plane remain separate

The control plane owns intent, policy, planning, lifecycle, state, and reconciliation.

The workload/data plane contains the actual applications, infrastructure, and managed services.

### 5. Agent authority is bounded

AI agents interact with controlled platform interfaces and operate within explicitly scoped permissions.

An agent cannot use the platform to grant itself broader authority.

### 6. Desired state and observed state are explicit

The platform maintains a representation of what an environment should look like and what it actually looks like.

The difference between the two becomes the basis for drift detection and reconciliation.

## Intent to Environment

The platform follows an explicit lifecycle:

```text
Natural-language intent
        |
        v
Intent extraction
        |
        v
Structured Intent IR
        |
        v
Semantic validation
        |
        v
Policy evaluation
        |
        v
Dependency resolution
        |
        v
Environment plan
        |
        v
Execution
        |
        v
Runtime observation
        |
        v
Drift detection
        |
        v
Reconciliation
```
The separation between these stages is intentional.

The AI layer may help construct or interpret intent, while deterministic control-plane components own validation, authorization, planning, and execution.

## Example

A request such as:

```text
Create a production Java 21 service in Germany.
Use private networking, restricted egress, encrypted PII storage,
multi-zone availability, monitoring, and rollback support.
```
is transformed into a structured environment specification:

```yaml
service:
  name: application-service
  runtime: java21

environment:
  tier: production
  region: germany

network:
  private: true
  egress: restricted

data:
  classification: pii
  encryption: required

availability:
  zones: 3

operations:
  monitoring: required
  rollback: enabled
```
The structured specification is then evaluated against platform policies and converted into an executable environment plan.

## AI and Agent Model

AI agents are treated as platform clients rather than privileged infrastructure executors.

Agents may:

- Interpret natural-language requests
- Retrieve platform knowledge
- Construct structured intent
- Inspect environment state
- Request environment plans
- Analyze operational failures
- Propose remediation actions
- Assist with application development workflows

Privileged operations remain behind authenticated, authorized, policy-controlled platform interfaces.

This allows the AI layer and the infrastructure control plane to evolve independently.

## Self-Management

The platform is designed around a reconciliation loop:

```text
Desired State
      |
      v
Observed State
      |
      v
Drift / Difference
      |
      v
Policy Evaluation
      |
      v
Reconciliation Plan
      |
      v
Execution
      |
      v
Observed State
```
The same control-plane boundaries used during initial provisioning are reused during lifecycle management and remediation.

## Multi-Cloud Model

The intent model remains provider-neutral:

```text
Intent IR
    ↓
Environment Plan
    ↓
┌──────────┬──────────┬──────────┐
│   AWS    │   GCP    │  Azure   │
│ Adapter  │ Adapter  │ Adapter  │
└──────────┴──────────┴──────────┘
```
Provider-specific configuration and execution are isolated behind cloud adapters rather than exposed through the user-facing intent model.

## Security Model

Security is treated as an architectural property rather than an implementation detail.

Key principles include:

- Explicit identity and authorization
- Policy evaluation before privileged execution
- Short-lived, scoped credentials
- Bounded agent permissions
- No agent self-escalation
- Full auditability of privileged actions
- Fail-closed behavior for security-sensitive operations
- Human approval for actions that require it

The AI layer does not receive unrestricted cloud credentials. Privileged operations are performed through controlled interfaces governed by the control plane.

## Engineering Focus

This project explores:

- Intent-driven infrastructure management
- AI agent orchestration
- MCP-based platform interaction
- Policy and governance
- Control-plane architecture
- Cloud environment compilation
- Infrastructure execution planning
- Multi-cloud abstraction
- Observability
- Drift detection
- Reconciliation and remediation
- Developer productivity
- Self-managing software workflows

## Project Status

| Area | Current state |
| --- | --- |
| Architecture and domain contracts | Documented in the original design work |
| Intent, validation, policy and compilation | Working Java 21 slice |
| Planning and lifecycle | Working local simulator; real providers remain future work |
| AI integration | Exact offline examples and a configurable Gemini interpreter; no MCP server yet |
| Observation and remediation | Working simulator faults, unknown-state handling and governed recovery |
| Developer workflows | Browser review and generated workload files; repository repair/IDE tools remain future work |
| Evaluation and feedback | Executable control-plane checks, interpreter smoke suite and regression capture |
| Production foundations | Authentication, durable state, distributed orchestration, real provider guarantees and independent live-model quality evidence remain future work |

## Repository Structure

```text
.
├── src/
├── docs/
│   ├── architecture.md
│   ├── domain-model.md
│   ├── intent-compiler.md
│   ├── control-plane.md
│   └── security-model.md
└── README.md
```

The `docs` directory contains the architecture, domain model, intent compilation, control-plane lifecycle, and security contracts. Implementation is introduced incrementally against these contracts.

## Design Goal

Build a strongly governed AI-native engineering platform that translates software and infrastructure intent into deployable, observable, and self-managing cloud environments.
