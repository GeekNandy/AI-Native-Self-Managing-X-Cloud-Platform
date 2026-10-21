# Demonstrating the governed workflows

## Start

Use JDK 21 and Git. Run `./mvnw verify`, then start the packaged JAR shown in the README. Open [http://127.0.0.1:8080](http://127.0.0.1:8080) and enter the token from `.workbench/operator.token`. Treat that file as a local credential. The UI keeps the token in the current tab's session storage; it never puts it in a URL.

The default server uses fixture interpretation and a durable environment simulator. Existing state is restored on restart. Use a different `PLATFORM_DATA_DIR` for an independent demonstration instead of deleting earlier evidence.

## Environment walkthrough

| Step | Action | Observable result |
| --- | --- | --- |
| 1 | Choose **Production service** and compile. | Complete typed intent and independent policy checks. |
| 2 | Inspect **Policy**, **Plan** and **Artifacts**. | Resource dependencies, Java source, Dockerfile and Kubernetes previews. |
| 3 | Review the digest and approve. | A committed desired version and durable operation receipt. Approval requires the operator token. |
| 4 | Under **Environments**, choose **Lose replicas** and **Open egress**. | Desired intent stays unchanged; two observed differences appear. |
| 5 | Plan and approve recovery. | Observed state converges; desired version remains unchanged. |
| 6 | Try **Unsafe production networking** and **Missing requirements**. | Policy denial and clarification, respectively; neither can execute. |
| 7 | Record reviewed feedback and run evaluation. | The report includes a dataset digest and the additional regression case. |
| 8 | Inspect **Activity log**, stop the app and restart it. | Receipts, runs, feedback, observations and audit history survive. |

**Lose observation** produces `UNKNOWN`. Recovery remains unavailable until observation is restored. **Refresh environments** explicitly re-observes the configured runtime and invalidates stale proposals when observations change.

The default simulator does not enforce cloud residency or storage encryption. Kubernetes output is a review artifact; the workbench does not apply it to a cluster.

## Repository walkthrough

Start Docker and run `docker pull eclipse-temurin:21-jdk`. The server must be able to reach its trusted local Docker daemon. Open **Repository agent**:

1. Create **Discount calculation**. This creates an actual managed Git repository with source and immutable regression tests.
2. Use **Bounded mutation search**, eight attempts and a 90-second budget. Start the repair.
3. Inspect the failing baseline, candidate validation, passing verification and source diff. The managed repository head has not changed.
4. Review the digest and commit. Repeating the same approved request returns the committed result.
5. Run **Compare feedback candidate**. It derives candidate ordering from accepted example repairs and compares it with the active version on disjoint public smoke tasks.
6. Inspect per-task outcomes, attempt counts, durations and isolation probes. Promotion requires all tasks/safety checks to pass and fewer aggregate attempts. Promote explicitly, then export the comparison.

This demonstrates measured improvement in bounded search efficiency. It does not train model weights or establish general repair quality. Importing a snapshot accepts only bounded Java source/test files; Maven/Gradle scripts and external dependencies are unsupported. See [the repository contract](repository-workflow.md).

## Actual local runtime

Start a separate workspace with `PLATFORM_RUNTIME=docker PLATFORM_DATA_DIR=.workbench-docker` and the same packaged JAR. Select **Local development runtime**; it is the initial example in Docker mode. Approve it, observe two generated Java HTTP workloads, lose a replica and approve recovery.

Only one local zone, `region=local`, development tier, up to three replicas, private networking with no egress, ephemeral non-PII data and no automatic rollback are supported. The adapter denies unsupported requirements. Workloads have no published host ports; trusted health checks run through Docker exec. Stop or remove only containers labelled for this workspace when cleaning up. Runtime containers remain until explicitly removed or their 24-hour supervisor exits.

## Live Gemini

```bash
export GEMINI_MODEL=your-available-model-id
export GEMINI_API_KEY=your-api-key
export PLATFORM_AI_PROVIDER=gemini
java -jar target/AI-Native-Self-Managing-X-Cloud-Platform-0.0.1-SNAPSHOT.jar
```

The interpreter calls the official [structured generateContent interface](https://ai.google.dev/gemini-api/docs/generate-content/structured-output). It has an 8-second connect timeout, 30-second request deadline, four inference slots, a 64 KiB response cap and no automatic retries or fixture fallback. Model output still passes semantic validation, policy and operator approval.

A configured key/model also enables **Gemini structured patch** in the repository workflow, independently of the environment interpreter mode. Source snapshots, the task instruction and bounded test output are sent to Gemini. Credentials and the host environment are not included. Repair allows at most three model calls, 4,096 output tokens per call and a 120-second run budget. Full replacements for existing source files must match before hashes and pass unchanged tests. No generated shell commands are accepted.

No live key is included. Evaluate live behavior separately and retain model/version, dataset and failure evidence. Offline replay/search scores are not live-model scores.

## Verification and evidence

```bash
./mvnw verify
python3 scripts/verify-restart.py
RUN_DOCKER_TESTS=true ./mvnw verify
npm ci
npx playwright install chromium
npm run check
npm run test:e2e
```

Docker tests run actual repairs, paired promotion gates and generated workload recovery. The process test uses SIGKILL and a fresh JVM. Browser tests cover approval, denial, clarification, feedback, authentication, repository creation and mobile overflow.

For API evidence and local measurements, run `scripts/demo.py` and `scripts/benchmark.py` against a fresh default workspace. They read the operator credential from `PLATFORM_OPERATOR_TOKEN` or `.workbench/operator.token`; `--token-file` supports another data directory. These workloads include durable local writes but exclude model inference, provider APIs and distributed scaling. Retention survives restart, so use a new workspace for repeated large measurements.
