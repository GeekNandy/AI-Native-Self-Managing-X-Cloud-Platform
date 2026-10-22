# Recorded execution evidence

These files contain outputs from actual local HTTP/process experiments and hosted Docker integration tests. [Verification summary](verification.json): **45 Java tests, 15 browser scenarios, the process crash-restart check and the password-protected deployment-image check passed**. Automated WCAG checks found zero violations across eleven page/dialog states. [Verified run](https://github.com/GeekNandy/AI-Native-Self-Managing-X-Cloud-Platform/actions/runs/39675808610).

| Experiment | Recorded result | Boundary |
| --- | --- | --- |
| [Control loop](control-plane.json) | 23/23 checks; one reviewed feedback case; retry replay; two drift fields recovered | Fixture interpretation and durable simulator |
| [Render deployment](render-deployment.json) | 23 hosted checks and 23/23 control-plane regressions pass: password access, approval/retry and reviewed drift recovery | Free Frankfurt service; fixture interpretation, ephemeral simulator; no live model key |
| [Hosted image](hosted-container-evidence.json) | Eleven checks pass: protected UI/API/assets/metrics/MCP, valid credentials, origin rejection and non-root execution | Actual deployment image; no Render deploy or live model calls |
| [Accessibility](ui-accessibility.json) | Zero axe violations across eleven states; responsive layout at five widths in both themes | Automated WCAG rules plus retained screenshots; manual assistive-technology testing remains separate |
| [Crash and restart](restart.json) | Six recovery checks passed after SIGKILL and a fresh JVM | Same single-node H2 directory; no distributed failover claim |
| [Repository repair](docker-repair-evidence.json) | Failing baseline, passing unchanged tests, reviewed Git commit | Actual Docker execution of bounded Java snapshots |
| Paired search improvement | Three tasks pass for both versions; five candidate attempts become three; all four safety probes pass | Disjoint public smoke tasks; measures mutation-search efficiency |
| [Local runtime](docker-runtime-evidence.json) | Actual generated workloads, replica loss and approved recovery | Local development Docker capabilities only |
| [Structured-plan measurement](local-plan-benchmark.json) | 128 requests, eight concurrent clients, zero errors | Includes embedded H2 commits; excludes inference, provider APIs and distributed scale |

The Docker reports include the exact tested commit, workflow-run URL and artifact digest in `provenance`. GitHub Actions also runs a complete browser repair/approval/promotion scenario and retains execution reports and screenshots. These records are evidence for their identified source, not a substitute for checks on a later release.

The retained control-loop and API measurement files share implementation digest `0120e0921cb7d9e60e15ef16d258644bbb25c5fa1485a15b09b77d8918a1d014`. The local measurement recorded p50 36.76 ms and p95 69.64 ms. It is a short loopback experiment, not a steady-state capacity result. Results depend on filesystem behavior, machine load, JVM warmup and client scheduling.

## Reproduce

Start a fresh default workspace, then run:

```bash
python3 scripts/demo.py --evidence target/demo-evidence.json --artifacts-dir target/generated-workload
python3 scripts/benchmark.py --requests 128 --concurrency 8 --output target/local-plan-benchmark.json
python3 scripts/verify-restart.py
```

For real isolated execution, start Docker, pre-pull `eclipse-temurin:21-jdk`, then run `RUN_DOCKER_TESTS=true ./mvnw verify`. Reports appear under `target/docker-*-evidence.json`. The browser suite exercises the same runner when available. `python3 scripts/verify-container.py` builds the hosted image and checks username/password access with temporary credentials. It leaves a secret-free report in `target/hosted-container-evidence.json`.

No recorded file establishes live Gemini/Nebius quality, model-weight training, cloud residency/encryption enforcement, hostile multi-tenant isolation or distributed capacity. Each requires its own backend and evaluation evidence.

## Repository-engineering release

`hosted-remote-repair.json` records a Render-coordinated, GitHub OIDC-authenticated worker run of the offline Maven/Jupiter profile. The pinned ledger snapshot failed its baseline, passed after a source-only reference-search repair, and produced an idempotently approved managed Git commit. GitHub API import hit Render's anonymous quota during this run, so the exact pinned files were supplied through the authenticated snapshot endpoint. This receipt is execution evidence, not live-model quality evidence.

The new CI corpus artifact (`target/docker-corpus-evidence.json`) validates all 25 failing baselines and test-only reference fixes in isolated containers. It must not be reported as a coding model's repair score. Publication tests use a local contract server to exercise stale-base rejection and recovery after a lost PR response. Live publication requires private write access.

`repository-release.json` ties the verified application source to the successful CI run, hosted deployment and activation limits. `repository-release-accessibility.json`, `repository-release-container.json` and `docker-corpus-validation.json` are source-labelled exports from that CI artifact. The hosted repair receipt records a separate live execution through the authenticated worker protocol.
