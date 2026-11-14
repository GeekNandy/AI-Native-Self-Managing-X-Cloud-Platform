#!/usr/bin/env python3
"""Disposable PostgreSQL coordinator failover with one synthetic lease and real Docker recovery."""
import datetime
import importlib.util
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


def main():
    spec = importlib.util.spec_from_file_location('backup', Path(__file__).with_name('verify-postgres-backup.py'))
    backup = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(backup)
    source = os.environ.get('TEST_DATABASE_URL', '')
    name = 'xcloud_restore_failover_' + secrets.token_hex(8)
    database = backup.restore_target(source, name)
    jar = Path('target/AI-Native-Self-Managing-X-Cloud-Platform-0.0.1-SNAPSHOT.jar').resolve()
    transport = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    operator, worker = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    children, paused, created = [], None, False
    checks = {}

    def port():
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0))
            return s.getsockname()[1]

    origins = [f'http://127.0.0.1:{port()}' for _ in range(3)]

    def call(origin, path, body=None, credential=operator, instance=None):
        headers = {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + credential}
        if instance:
            headers['X-Worker-Instance'] = instance
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(origin + path, data=data, headers=headers)
        with transport.open(request, timeout=5) as response:
            return json.load(response)

    def status(origin, path, body=None, credential=operator, instance=None):
        try:
            call(origin, path, body, credential, instance)
            return 200
        except urllib.error.HTTPError as error:
            return error.code
        except (urllib.error.URLError, TimeoutError):
            return 0

    def until(predicate, seconds, label):
        stop = time.monotonic() + seconds
        while time.monotonic() < stop:
            try:
                value = predicate()
                if value:
                    return value
            except (urllib.error.URLError, TimeoutError):
                pass
            time.sleep(.15)
        raise AssertionError('Deadline exceeded: ' + label)

    def containers():
        result = subprocess.run(['docker', 'ps', '-aq', '--filter', 'label=xcloud.sandbox-owner'],
                                check=True, capture_output=True, text=True)
        return set(result.stdout.split())

    before = containers()
    with tempfile.TemporaryDirectory(prefix='xcloud-failover-') as temporary:
        root = Path(temporary)
        env = {**os.environ, 'DATABASE_URL': database, 'PLATFORM_COORDINATOR_MODE': 'lease',
               'PLATFORM_COORDINATOR_LEASE_SECONDS': '6', 'PLATFORM_COORDINATOR_ACQUIRE_TIMEOUT_SECONDS': '60',
               'PLATFORM_OPERATOR_TOKEN': operator, 'PLATFORM_AGENT_TOKEN': secrets.token_urlsafe(32),
               'PLATFORM_WORKER_TOKEN': worker, 'PLATFORM_RUNTIME': 'sandbox',
               'PLATFORM_EXECUTION_MODE': 'remote', 'PLATFORM_AI_PROVIDER': 'fixture',
               'DEMO_AUTH_REQUIRED': 'false', 'DEMO_USERNAME': '', 'DEMO_PASSWORD': '',
               'GITHUB_PUBLISH_REPOSITORY': '', 'PUBLIC_ORIGIN': '', 'SERVER_ADDRESS': '127.0.0.1'}

        def launch(label, overrides):
            with (root / f'{label}.log').open('ab') as log:
                process = subprocess.Popen(['java', '-Xmx256m', '-jar', str(jar)],
                        env={**env, 'PLATFORM_DATA_DIR': str(root / label), **overrides}, stdout=log, stderr=log)
            children.append(process)
            return process

        def coordinator(index):
            return launch(f'coordinator-{index}', {'PLATFORM_NODE_ROLE': 'coordinator',
                          'PORT': origins[index].rsplit(':', 1)[1]})

        try:
            backup.postgres(source, database, 'createdb --maintenance-db="$TEST_DATABASE_URL" "$RESTORE_DB"',
                            restore_name=name)
            created = True
            first = coordinator(0)
            until(lambda: status(origins[0], '/readyz') == 200, 60, 'first leader')
            epoch = call(origins[0], '/api/operations')['state']['leaderEpoch']
            second = coordinator(1)
            # A standby is not an API read replica and must not report mutation readiness.
            time.sleep(2)
            checks['standbyNotReady'] = status(origins[1], '/readyz') != 200
            checks['liveLeaseNotStolen'] = call(origins[0], '/api/operations')['state']['leaderEpoch'] == epoch
            example = next(e for e in call(origins[0], '/api/examples') if e['id'] == 'production')
            plan = call(origins[0], '/api/runs', {'prompt': example['prompt']})['plan']
            approval = {'digest': plan['digest'], 'idempotencyKey': 'failover-receipt-key'}
            receipt = call(origins[0], f'/api/plans/{plan["id"]}/apply', approval)['receipt']
            # Claim a baseline without executing it; keep its opaque token for a stale-result probe.
            call(origins[0], '/api/worker/claim', {'image': 'eclipse-temurin:21-jdk'}, worker, 'stale-probe')
            files = {'src/main/java/Discount.java':
                     'public class Discount { public static int apply(int n,int d){ return n+d; } }',
                     'src/test/java/DiscountTest.java':
                     'public class DiscountTest { public static void main(String[] a){ if(Discount.apply(100,20)!=80) throw new AssertionError("discount"); } }'}
            repo = call(origins[0], '/api/code/repositories', {'name': 'failover repair', 'files': files, 'testClass': 'DiscountTest'})
            run = call(origins[0], '/api/code/runs', {'repositoryId': repo['id'], 'instruction': 'Fix arithmetic; preserve tests',
                       'provider': 'mutation-search', 'maxAttempts': 8, 'timeoutSeconds': 120})
            stale = until(lambda: call(origins[0], '/api/worker/claim', {'image': 'eclipse-temurin:21-jdk'},
                                      worker, 'stale-probe').get('lease'), 10, 'baseline lease')
            paused = first
            first.send_signal(signal.SIGSTOP)
            started = time.monotonic()
            until(lambda: status(origins[1], '/readyz') == 200, 45, 'successor after lease expiry')
            rto = time.monotonic() - started
            new_epoch = call(origins[1], '/api/operations')['state']['leaderEpoch']
            checks['epochIncreased'] = new_epoch > epoch
            first.send_signal(signal.SIGCONT)
            paused = None
            until(lambda: status(origins[0], '/readyz') == 503, 15, 'old leader fenced')
            checks['oldLeaderAliveButNotReady'] = status(origins[0], '/actuator/health') == 200
            checks['oldLeaderRejectsMutation'] = status(origins[0], f'/api/plans/{plan["id"]}/apply', approval) == 503
            stale_result = {'fence': stale['fence'], 'inputDigest': stale['inputDigest'],
                            'leaderEpoch': stale['leaderEpoch'],
                            'result': {'status': 'PASS', 'exitCode': 0, 'timedOut': False,
                                       'truncated': False, 'durationMs': 1, 'output': 'stale synthetic response'}}
            checks['oldWorkerRejected'] = status(origins[1], f'/api/worker/jobs/{stale["id"]}/complete',
                                                 stale_result, worker, 'stale-probe') == 409
            replay = call(origins[1], f'/api/plans/{plan["id"]}/apply', approval)
            checks['approvedReceiptPreserved'] = replay['replayed'] and replay['receipt'] == receipt
            launch('worker', {'PLATFORM_NODE_ROLE': 'worker', 'PLATFORM_COORDINATOR_URL': origins[1],
                             'PLATFORM_WORKER_SECONDS': '180', 'PLATFORM_SANDBOX_IMAGE': 'eclipse-temurin:21-jdk',
                             'PLATFORM_WORKER_DATA_DIR': str(root / 'worker-state')})
            final = until(lambda: (r if (r := call(origins[1], '/api/code/runs/' + run['id']))['status']
                                   not in ('QUEUED', 'RUNNING') else None), 100, 'actual Docker repair after failover')
            checks['realDockerRecovery'] = (final['status'] == 'AWAITING_REVIEW'
                                           and final['baseline']['status'] == 'FAIL'
                                           and final['verification']['status'] == 'PASS')
            checks['noAutomaticCommit'] = not final.get('approvedCommit')
            jobs = call(origins[1], '/api/code/workers/jobs')
            checks['boundedRedelivery'] = any(j['id'] == stale['id'] and j['deliveries'] == 2 for j in jobs)
            checks['deadlinePreserved'] = next(j for j in jobs if j['id'] == stale['id'])['deadline'] == stale['deadline']
            # A hard crash after completion must preserve the tested patch and approval receipt too.
            second.kill()
            second.wait(timeout=10)
            coordinator(2)
            until(lambda: status(origins[2], '/readyz') == 200, 45, 'SIGKILL successor')
            replay = call(origins[2], f'/api/plans/{plan["id"]}/apply', approval)
            restored = call(origins[2], '/api/code/runs/' + run['id'])
            checks['sigkillPreservesReceipts'] = replay['receipt'] == receipt and replay['replayed']
            checks['sigkillPreservesReview'] = restored == final
            checks['thirdEpochIncreased'] = call(origins[2], '/api/operations')['state']['leaderEpoch'] > new_epoch
            if not all(checks.values()):
                raise AssertionError(json.dumps(checks))
        except Exception:
            # Keep bounded, redacted startup diagnostics; connection URLs are never published.
            for path in root.glob('*.log'):
                text = path.read_text(errors='replace')[-12000:]
                for secret in (database, source, operator, worker, env['PLATFORM_AGENT_TOKEN']):
                    text = text.replace(secret, '[redacted]')
                Path('target', 'coordinator-failover-' + path.name).write_text(text)
            raise
        finally:
            if paused and paused.poll() is None:
                paused.send_signal(signal.SIGCONT)
            for child in reversed(children):
                if child.poll() is None:
                    child.terminate()
            for child in reversed(children):
                try:
                    child.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)
            if created:
                backup.postgres(source, database,
                                'dropdb --if-exists --force --maintenance-db="$TEST_DATABASE_URL" "$RESTORE_DB"',
                                restore_name=name)
        checks['noWorkerContainersLeaked'] = containers() <= before
        if not all(checks.values()):
            raise AssertionError(json.dumps(checks))
        report = {'recordedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  'revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                  'experiment': 'PostgreSQL leases; paused leader resumes stale; isolated Docker repair; SIGKILL successor',
                  'observedTakeoverSeconds': round(rto, 3), 'checks': checks,
                  'limitation': 'Disposable database, direct coordinator URLs and one synthetic initial claim. Replacement tests use actual Docker. No etcd integration, traffic routing, database failover, external cloud effects or production RTO claim.'}
        output = Path('target/coordinator-failover-evidence.json')
        output.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
