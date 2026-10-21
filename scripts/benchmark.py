#!/usr/bin/env python3
"""Measure the local structured-plan API. Excludes inference, provider calls and apply operations."""
import argparse
import os
import concurrent.futures
import datetime
import json
import math
import time
import urllib.parse
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--requests', type=int, default=128)
    parser.add_argument('--concurrency', type=int, default=8)
    parser.add_argument('--environments', type=int, default=8)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--token-file', type=Path, default=Path(os.environ.get('PLATFORM_DATA_DIR', '.workbench')) / 'operator.token')
    args = parser.parse_args()
    token = os.environ.get('PLATFORM_OPERATOR_TOKEN') or args.token_file.read_text().strip()
    origin = urllib.parse.urlparse(args.base_url)
    if origin.scheme != 'http' or origin.hostname not in ('127.0.0.1', 'localhost') or origin.path not in ('', '/'):
        parser.error('Use the local workbench origin.')
    if not (1 <= args.requests <= 400 and 1 <= args.concurrency <= 32 and 1 <= args.environments <= 32):
        parser.error('Use 1-400 requests, 1-32 concurrent clients and 1-32 environment identities.')

    def request(index):
        spec = dict(serviceName=f'bench-{index % args.environments:03d}', runtime='java21', tier='development',
                    region='eu', privateNetwork=True, restrictedEgress=True, dataClassification='internal',
                    encryptedStorage=True, zones=1, replicas=1, monitoring=True, rollback=True)
        transport = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        payload = urllib.request.Request(args.base_url.rstrip('/') + '/api/plans',
                                         data=json.dumps({'spec': spec}).encode(), headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token})
        start = time.perf_counter()
        try:
            with transport.open(payload, timeout=30) as response:
                result = json.load(response)
            if result['status'] != 'AWAITING_APPROVAL':
                return (time.perf_counter() - start) * 1000, 'Unexpected plan status'
            return (time.perf_counter() - start) * 1000, None
        except Exception as failure:
            return (time.perf_counter() - start) * 1000, str(failure)

    # Warm up a separate identity; these calls are excluded from measurement.
    for _ in range(5):
        _, error = request(0)
        if error:
            raise RuntimeError('Warmup failed: ' + error)
    start = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        results = list(pool.map(request, range(args.requests)))
    elapsed = time.perf_counter() - start
    times = sorted(duration for duration, _ in results)
    errors = [error for _, error in results if error]
    percentile = lambda p: times[max(0, math.ceil(len(times) * p) - 1)]
    report = dict(recordedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  workload='POST /api/plans, structured Java development intent; five warmup requests excluded',
                  requests=args.requests, concurrency=args.concurrency, environmentIdentities=args.environments,
                  elapsedSeconds=elapsed, completedRequestsPerSecond=args.requests / elapsed,
                  errors=len(errors), errorDetails=errors[:10], p50Ms=percentile(.50), p95Ms=percentile(.95), p99Ms=percentile(.99),
                  limitations='One local JVM and loopback HTTP; client wall-clock timings include Python scheduling. Embedded state commits included; no model, cloud execution or distributed scaling measured.')
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    if errors:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
