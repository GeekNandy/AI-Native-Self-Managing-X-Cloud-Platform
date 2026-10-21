# Recorded local evidence

These are outputs of the running application, not estimates or targets. Regenerate them against a fresh workbench process using:

```bash
python3 scripts/demo.py --evidence docs/evidence/control-plane.json --artifacts-dir target/generated-workload
python3 scripts/benchmark.py --requests 128 --concurrency 8 --output docs/evidence/local-plan-benchmark.json
```

- [Control-plane evidence](control-plane.json): operation receipts, retry replay, two observed drift fields, recovery, denied/ambiguous outcomes and an evaluation report. The report includes a digest of the source used, the dataset digest and per-case outcomes/timings.
- [Local API measurement](local-plan-benchmark.json): 128 structured-plan requests, eight concurrent clients and eight environment identities, with client-side p50/p95/p99, completed requests per second and error count. Five warmup calls are excluded. This is a short local measurement, not a steady-state capacity result.

All recorded execution uses fixture replay and a single in-memory JVM. No live Gemini quality, cloud provider execution, durable recovery, distributed performance or autonomous learning is established by these files. Results depend on machine load, JVM warmup and client scheduling. A changing source digest or dataset requires new evidence.
