# Recorded execution evidence

These files contain outputs from actual local HTTP/process experiments and hosted Docker integration tests. [Verification summary](verification.json): **37 Java tests, eight browser scenarios and the process crash-restart check passed**. [Verified run](https://github.com/GeekNandy/AI-Native-Self-Managing-X-Cloud-Platform/actions/runs/39632194511).

| Experiment | Recorded result | Boundary |
| --- | --- | --- |
| [Control loop](control-plane.json) | 23/23 checks; one reviewed feedback case; retry replay; two drift fields recovered | Fixture interpretation and durable simulator |
| [Crash and restart](restart.json) | Six recovery checks passed after SIGKILL and a fresh JVM | Same single-node H2 directory; no distributed failover claim |
| [Repository repair](docker-repair-evidence.json) | Failing baseline, passing unchanged tests, reviewed Git commit | Actual Docker execution of bounded Java snapshots |
| Paired search improvement | Three tasks pass for both versions; five candidate attempts become three; all four safety probes pass | Disjoint public smoke tasks; measures mutation-search efficiency |
| [Local runtime](docker-runtime-evidence.json) | Actual generated workloads, replica loss and approved recovery | Local development Docker capabilities only |
| [Structured-plan measurement](local-plan-benchmark.json) | 128 requests, eight concurrent clients, zero errors | Includes embedded H2 commits; excludes inference, provider APIs and distributed scale |

The Docker reports include the exact tested commit, workflow-run URL and artifact digest in `provenance`. GitHub Actions also runs a complete browser repair/approval/promotion scenario and retains execution reports and screenshots. These records are evidence for their identified source, not a substitute for checks on a later release.

The control-loop, restart and API measurement files share implementation digest `6d754068e6a160b7b9b8ed756eb8a7180e468f83511ed79b427aa2de0e1d7c5e`. The local measurement recorded p50 33.05 ms and p95 60.29 ms. It is a short loopback experiment, not a steady-state capacity result. Results depend on filesystem behavior, machine load, JVM warmup and client scheduling.

## Reproduce

Start a fresh default workspace, then run:

```bash
python3 scripts/demo.py --evidence target/demo-evidence.json --artifacts-dir target/generated-workload
python3 scripts/benchmark.py --requests 128 --concurrency 8 --output target/local-plan-benchmark.json
python3 scripts/verify-restart.py
```

For real isolated execution, start Docker, pre-pull `eclipse-temurin:21-jdk`, then run `RUN_DOCKER_TESTS=true ./mvnw verify`. Reports appear under `target/docker-*-evidence.json`. The browser suite exercises the same runner when available.

No recorded file establishes live Gemini quality, model-weight training, cloud residency/encryption enforcement, hostile multi-tenant isolation or distributed capacity. Each requires its own backend and evaluation evidence.
