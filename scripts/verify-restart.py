#!/usr/bin/env python3
"""Crash and restart the packaged server, then verify durable retry semantics over HTTP."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=Path('target/AI-Native-Self-Managing-X-Cloud-Platform-0.0.1-SNAPSHOT.jar'))
    parser.add_argument('--output', type=Path, default=Path('target/restart-evidence.json'))
    args = parser.parse_args()
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    transport = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    token = secrets.token_urlsafe(32)
    base = f'http://127.0.0.1:{port}'
    process = None
    with tempfile.TemporaryDirectory(prefix='xcloud-restart-') as directory:
        env = {**os.environ, 'PLATFORM_DATA_DIR': directory, 'PLATFORM_OPERATOR_TOKEN': token,
               'PLATFORM_AGENT_TOKEN': secrets.token_urlsafe(32), 'PLATFORM_AI_PROVIDER': 'fixture', 'PLATFORM_RUNTIME': 'sandbox'}
        log_path = Path(directory) / 'server.log'
        release_jar = Path(directory) / 'release.jar'
        shutil.copyfile(args.jar, release_jar)

        def call(path, body=None):
            data = None if body is None else json.dumps(body).encode()
            request = urllib.request.Request(base + path, data=data, headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token})
            with transport.open(request, timeout=20) as response:
                return json.load(response)

        def start():
            log = log_path.open('ab')
            child = subprocess.Popen(['java', '-jar', str(release_jar), f'--server.port={port}'], env=env, stdout=log, stderr=log)
            log.close()
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                if child.poll() is not None:
                    raise RuntimeError('Server stopped before becoming healthy: ' + log_path.read_text()[-3000:])
                try:
                    call('/actuator/health')
                    return child
                except Exception:
                    time.sleep(.15)
            child.kill()
            child.wait()
            raise RuntimeError('Server did not become healthy before the startup deadline.')

        try:
            process = start()
            example = next(e for e in call('/api/examples') if e['id'] == 'production')
            run = call('/api/runs', {'prompt': example['prompt']})
            plan = run['plan']
            approval = {'digest': plan['digest'], 'idempotencyKey': 'crash-restart-retry'}
            receipt = call(f'/api/plans/{plan["id"]}/apply', approval)['receipt']
            call(f'/api/environments/{plan["environmentId"]}/faults', {'kind': 'replica-loss'})
            before = call('/api/audit')
            # No graceful shutdown or in-process state restoration.
            process.kill()
            process.wait(timeout=10)
            process = start()
            replay = call(f'/api/plans/{plan["id"]}/apply', approval)
            state = call(f'/api/environments/{plan["environmentId"]}')
            restored_run = call(f'/api/runs/{run["id"]}')
            after = call('/api/audit')
            checks = dict(receiptSurvived=replay['receipt'] == receipt, replayDeduplicated=replay['replayed'],
                          desiredVersionPreserved=state['desiredVersion'] == 1, observedDriftPreserved=state['convergence'] == 'DRIFT_DETECTED',
                          runRestored=restored_run['id'] == run['id'], auditHistoryPreserved=before == after)
            if not all(checks.values()):
                raise AssertionError(checks)
            paths = [Path('pom.xml'), *sorted(p for p in Path('src/main').rglob('*') if p.is_file())]
            source = hashlib.sha256()
            for path in paths:
                source.update(path.as_posix().encode())
                source.update(path.read_bytes())
            report = dict(recordedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                          experiment='SIGKILL after committed provision and observed drift; fresh JVM; same H2 directory',
                          sourceDigest=source.hexdigest(), checks=checks, receipt=replay['receipt'], restoredEnvironment=state,
                          limitation='Single-node embedded state. In-flight external-write recovery is covered by a separate journal-interruption test; distributed leases and cross-node failover are outside this release.')
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, indent=2) + '\n')
            print(json.dumps({'checks': checks, 'output': str(args.output)}, indent=2))
        finally:
            if process and process.poll() is None:
                process.terminate()
                process.wait(timeout=15)


if __name__ == '__main__':
    main()
