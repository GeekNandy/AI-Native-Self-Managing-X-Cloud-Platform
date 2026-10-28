#!/usr/bin/env python3
"""Exercise a real four-worker Docker pool, abrupt worker loss, redelivery and cleanup."""
import datetime
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


def main():
    subprocess.run(['docker', 'info'], check=True, stdout=subprocess.DEVNULL)
    jar = Path('target/AI-Native-Self-Managing-X-Cloud-Platform-0.0.1-SNAPSHOT.jar').resolve()
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    origin = f'http://127.0.0.1:{port}'
    transport = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    operator, worker = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    children = []

    def containers():
        result = subprocess.run(['docker', 'ps', '-aq', '--filter', 'label=xcloud.sandbox-owner'],
                                check=True, capture_output=True, text=True)
        return set(result.stdout.split())

    before = containers()
    with tempfile.TemporaryDirectory(prefix='xcloud-pool-') as directory:
        root = Path(directory)
        env = {**os.environ, 'DATABASE_URL': '', 'DEMO_AUTH_REQUIRED': 'false',
               'PLATFORM_OPERATOR_TOKEN': operator, 'PLATFORM_WORKER_TOKEN': worker,
               'PLATFORM_AGENT_TOKEN': secrets.token_urlsafe(32), 'PLATFORM_AI_PROVIDER': 'fixture',
               'PLATFORM_RUNTIME': 'sandbox', 'PLATFORM_EXECUTION_MODE': 'remote',
               'PLATFORM_REPAIR_WORKERS': '4', 'PLATFORM_REMOTE_WORKERS': '4',
               'PLATFORM_DATA_DIR': str(root / 'coordinator'),
               'SERVER_ADDRESS': '127.0.0.1', 'PORT': str(port)}

        def launch(name, overrides):
            with (root / f'{name}.log').open('ab') as log:
                process = subprocess.Popen(['java', '-Xmx256m', '-jar', str(jar)],
                                           env={**env, **overrides}, stdout=log, stderr=log)
            children.append(process)
            return process

        def call(path, body=None, credential=operator):
            data = None if body is None else json.dumps(body).encode()
            request = urllib.request.Request(origin + path, data=data, headers={
                'Content-Type': 'application/json', 'Authorization': 'Bearer ' + credential})
            with transport.open(request, timeout=15) as response:
                return json.load(response)

        def until(predicate, seconds, label):
            stop = time.monotonic() + seconds
            while time.monotonic() < stop:
                try:
                    value = predicate()
                    if value:
                        return value
                except (urllib.error.URLError, TimeoutError):
                    pass
                time.sleep(.1)
            raise AssertionError('Deadline exceeded: ' + label)

        def launch_worker(slot):
            return launch(f'worker-{slot}', {'PLATFORM_NODE_ROLE': 'worker',
                'PLATFORM_COORDINATOR_URL': origin, 'PLATFORM_WORKER_SECONDS': '240',
                'PLATFORM_SANDBOX_IMAGE': 'xcloud-java21-runner:1',
                'PLATFORM_WORKER_DATA_DIR': str(root / f'worker-{slot}')})

        started = time.monotonic()
        try:
            launch('coordinator', {'PLATFORM_NODE_ROLE': 'coordinator'})
            until(lambda: call('/readyz')['status'] == 'READY', 75, 'coordinator readiness')
            pool = [launch_worker(i) for i in range(4)]
            until(lambda: len(call('/api/code/config')['sandbox']['workers']) == 4,
                  60, 'four authenticated workers')
            files = {
                'src/main/java/Discount.java':
                    'public class Discount { public static int apply(int amount, int discount) { return amount + discount; } }',
                'src/test/java/DiscountTest.java':
                    'public class DiscountTest { public static void main(String[] args) throws Exception { Thread.sleep(1000); if (Discount.apply(100,20) != 80) throw new AssertionError("discount"); } }'}
            runs = []
            for slot in range(4):
                repository = call('/api/code/repositories', {
                    'name': f'worker-pool-{slot}', 'files': files, 'testClass': 'DiscountTest'})
                runs.append(call('/api/code/runs', {'repositoryId': repository['id'],
                    'instruction': 'Fix the discount arithmetic without changing tests.',
                    'provider': 'mutation-search', 'maxAttempts': 8, 'timeoutSeconds': 120}))
            until(lambda: call('/api/code/config')['sandbox']['leased'] == 4,
                  45, 'four simultaneous real Docker leases')
            # SIGKILL leaves no opportunity to send a synthetic error completion or run a hook.
            pool[0].kill()
            pool[0].wait(timeout=15)
            final = []
            for run in runs:
                final.append(until(lambda run_id=run['id']: (
                    receipt if (receipt := call('/api/code/runs/' + run_id))['status']
                    not in ('QUEUED', 'RUNNING', 'APPLYING') else None), 135, 'repair completion'))
            jobs = call('/api/code/workers/jobs')
            checks = {
                'fourConcurrentLeases': True,
                'allBaselinesFail': all(r['baseline']['status'] == 'FAIL' for r in final),
                'allVerificationsPass': all((r.get('verification') or {}).get('status') == 'PASS' for r in final),
                'allAwaitReview': all(r['status'] == 'AWAITING_REVIEW' and not r.get('approvedCommit') for r in final),
                'lostLeaseRedelivered': any(j['deliveries'] == 2 for j in jobs),
            }
            try:
                call('/api/code/runs/' + final[0]['id'] + '/approve',
                     {'digest': final[0]['digest']}, worker)
                checks['workerCannotApprove'] = False
            except urllib.error.HTTPError as denied:
                checks['workerCannotApprove'] = denied.code in (401, 403)
            # Reusing the killed worker's state reaps only its abandoned containers at startup.
            replacement = launch_worker(0)
            until(lambda: containers() <= before, 15, 'owner-scoped restart cleanup')
            if replacement.poll() is not None:
                raise AssertionError('Replacement worker stopped before cleanup verification')
            if not all(checks.values()):
                raise AssertionError(json.dumps({'checks': checks,
                    'statuses': [r['status'] for r in final]}))
        finally:
            for child in reversed(children):
                if child.poll() is None:
                    child.terminate()
            for child in reversed(children):
                try:
                    child.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)
        checks['noOwnedContainersLeaked'] = containers() <= before
        if not all(checks.values()):
            raise AssertionError(checks)
        report = {'recordedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  'revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                  'experiment': 'Four Docker workers; SIGKILL during repair; fenced redelivery and restart cleanup',
                  'workers': 4, 'completedRepairs': len(final), 'checks': checks,
                  'elapsedSeconds': round(time.monotonic() - started, 3),
                  'limitation': 'Synthetic arithmetic repair with a one-second test delay. Concurrency and recovery evidence, not live model quality or production throughput.'}
        output = Path('target/worker-pool-docker-evidence.json')
        output.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
