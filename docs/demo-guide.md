# Demonstrating the governed agent loop

## Start locally

Requirements: JDK 21 and Python 3. The Maven wrapper downloads Maven and the Java dependencies. The browser UI has no build step, CDN scripts or remote fonts.

```bash
./mvnw verify
java -jar target/AI-Native-Self-Managing-X-Cloud-Platform-0.0.1-SNAPSHOT.jar
```

Open <http://127.0.0.1:8080>. The default mode replays eight curated requests. Unknown requests ask for clarification instead of pretending to be interpreted by an AI model.

## Five-minute walkthrough

| Time | Action | What to explain |
| --- | --- | --- |
| 0:00-0:45 | Choose **Production service**, then **Compile intent**. | The interpreter proposes a provider-neutral IR. Validation, policy and compilation are separate deterministic stages. |
| 0:45-1:30 | Inspect **Policy**, **Plan** and **Artifacts**. | Eight versioned policy checks, ordered resource dependencies, a runnable Java source file, a Dockerfile and Kubernetes previews. Storage encryption and residency remain logical requirements, not provisioned guarantees. |
| 1:30-2:00 | Choose **Review & approve**, inspect the digest, check the acknowledgement and execute. | Approval is tied to the exact plan and environment revision. The interpreter has no execute or approval capability. This local acknowledgement is not production authentication. |
| 2:00-3:00 | Under **Environments**, choose **Lose replicas** and **Open egress**. Plan and approve recovery. | Desired state stays immutable. Observation exposes two differences. Remediation passes through the same policy and execution path. Desired state is not rewritten to hide drift. |
| 3:00-3:45 | Compile **Unsafe production networking**, then **Missing requirements**. | Unsafe complete intent is denied; incomplete intent creates no executable plan. Do not silently fix the user's requirements. |
| 3:45-4:45 | Compile the production example again. Open **Close the feedback loop**, record an expected result and run the suite. | Reviewed feedback becomes a regression case. The report shows its dataset digest, outcomes and case timings. This is an engineering feedback loop, not autonomous model improvement. |
| 4:45-5:00 | Open **Activity log**. | Policy, approval, operations and feedback share correlation IDs. Discuss retention and durability limits explicitly. |

For the observation failure path, choose **Lose observation**. The state becomes `UNKNOWN` and recovery is unavailable until **Restore observation** is selected. An absence of telemetry is never treated as proof of convergence.

## Reproduce and export evidence

With the app running in a separate terminal:

```bash
python3 scripts/demo.py --evidence target/demo-evidence.json --artifacts-dir target/generated-workload
```

The script calls the real HTTP API, provisions a sandbox, verifies retry deduplication, injects and repairs two faults, checks denial and clarification, captures feedback and runs the isolated evaluation suite. Repeated runs retain earlier feedback until the app restarts, so case totals can increase.

The exported JSON includes actual receipts, pre-recovery drift, configuration, a digest of the implementation source and the evaluation report. Local case timings exclude inference and cloud calls. They are not throughput, end-user latency or model-quality claims.

To compile and run the generated Java workload independently:

```bash
cd target/generated-workload
javac --release 21 Main.java
java Main
```

The workload listens on port 8080. Stop the workbench first or run it on a different port using `PORT=8081`. The generated Dockerfile can build the same sample workload. Kubernetes YAML is a review preview and requires a real cluster, image distribution, CNI, namespaces and provider integration. The workbench does not apply it.

## Live Gemini interpretation

Choose a currently available Gemini model that your API key can access. The adapter implements the official [structured generateContent interface](https://ai.google.dev/gemini-api/docs/generate-content/structured-output).

```bash
export PLATFORM_AI_PROVIDER=gemini
export GEMINI_MODEL=your-available-model-id
export GEMINI_API_KEY=your-api-key
java -jar target/AI-Native-Self-Managing-X-Cloud-Platform-0.0.1-SNAPSHOT.jar
```

Keep credentials in the process environment, not source files. Live mode sends the entered intent to Gemini. It never sends cloud credentials, approves changes or executes model-supplied commands. Inference has a 30-second request deadline, an 8-second connect timeout, a 64 KiB response cap and at most four concurrent calls. There are no automatic retries or silent fixture fallbacks.

The adapter rejects incomplete output, unknown intent fields, null primitive values and numeric/string coercions. Complete candidate intents still pass independent semantic validation and policy checks. Missing requirements produce clarification. The live adapter's response contract is tested with local responses; real model access and live quality need a configured key and a separate evaluation run.

**Evaluate Gemini interpreter** checks extracted intent against golden specifications and policy outcomes. It is a small, public smoke suite. Fixture interpreter evaluation checks exact example replay and is labelled accordingly. Neither establishes blind generalization or autonomous improvement.

## Verification

```bash
./mvnw verify
npm ci
npx playwright install chromium
npm run check
npm run test:e2e
```

Browser tests start the packaged application on port 8090, exercise actual UI/API behavior and check mobile overflow and uncaught client errors. Java tests cover concurrency, idempotency, stale and expired plans, changed policy, partial runtime failure, output schema rejection and compilation of generated code. GitHub Actions runs both layers.

For an explicitly local HTTP measurement, run this against a fresh workbench process:

```bash
python3 scripts/benchmark.py --requests 128 --concurrency 8 --output target/local-plan-benchmark.json
```

It reports actual client-side p50/p95/p99 timings, completed requests per second and errors for structured plan compilation. It excludes model inference, apply operations and cloud APIs. It creates proposals that count against demo retention limits, so restart before repeating large runs. Do not extrapolate these numbers to production or distributed scale.

## Demo boundaries

The server binds to loopback and checks local Host/Origin. It has no user authentication or tenant isolation and should remain a local demo. All runtime state, feedback and audit data are in memory. There is no real provider execution, persistent storage, MCP server, IDE integration, general code-repair agent, model training or production-scale benchmark. The architecture documents describe a broader target; this walkthrough describes the implemented slice.
