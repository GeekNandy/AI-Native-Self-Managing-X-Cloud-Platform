#!/usr/bin/env python3
"""Verify durable worker-job recovery across real JVM crashes using a simulated worker.

This exercises authenticated HTTP, H2 persistence, deterministic repair resumption
and epoch fencing. It does not
execute repository tests, run Docker, call a model or prove distributed failover.
"""
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
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=Path(
        'target/AI-Native-Self-Managing-X-Cloud-Platform-0.0.1-SNAPSHOT.jar'))
    parser.add_argument('--output', type=Path,
                        default=Path('target/coordinator-recovery-evidence.json'))
    args = parser.parse_args()
    # A failed invocation must never leave a prior success receipt at this path.
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.unlink(missing_ok=True)
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    origin = f'http://127.0.0.1:{port}'
    transport = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    operator, agent, worker = (secrets.token_urlsafe(32) for _ in range(3))
    checks, observations = {}, {}
    children = []
    started = time.monotonic()
    fixture = {'status': 'PASS', 'exitCode': 0, 'timedOut': False,
               'truncated': False, 'durationMs': 0,
               'output': 'SIMULATED worker protocol fixture; no repository tests executed.'}

    def check(name, condition):
        checks[name] = bool(condition)
        if not condition:
            raise AssertionError('Coordinator recovery check failed: ' + name)

    def call(path, body=None, credential=operator, expected=200):
        request = urllib.request.Request(origin + path,
            data=None if body is None else json.dumps(body).encode(), headers={
                'Content-Type': 'application/json', 'Authorization': 'Bearer ' + credential,
                **({'X-Worker-Instance': 'recovery-protocol-fixture'}
                   if credential == worker else {})})
        try:
            with transport.open(request, timeout=10) as response:
                status, payload = response.status, json.load(response)
        except urllib.error.HTTPError as error:
            status = error.code
            payload = json.loads(error.read())
        if status != expected:
            # Responses may contain submitted source: report only method/path/status.
            raise AssertionError(f'{request.get_method()} {path}: expected {expected}, got {status}')
        return payload

    def claim():
        return call('/api/worker/claim', {'image': 'eclipse-temurin:21-jdk'}, worker)

    def until(predicate, seconds, label):
        stop = time.monotonic() + seconds
        while time.monotonic() < stop:
            value = predicate()
            if value:
                return value
            time.sleep(.1)
        raise AssertionError('Deadline exceeded: ' + label)

    def complete(lease, expected=200):
        return call(f'/api/worker/jobs/{lease["id"]}/complete', {
            'fence': lease['fence'], 'inputDigest': lease['inputDigest'],
            'leaderEpoch': lease['leaderEpoch'], 'result': fixture}, worker, expected)

    with tempfile.TemporaryDirectory(prefix='xcloud-coordinator-recovery-') as directory:
        root = Path(directory)
        release = root / 'release.jar'
        shutil.copyfile(args.jar, release)
        env = {**os.environ,
            'DATABASE_URL': '', 'PLATFORM_DATA_DIR': str(root / 'state'),
            'PLATFORM_OPERATOR_TOKEN': operator, 'PLATFORM_AGENT_TOKEN': agent,
            'PLATFORM_WORKER_TOKEN': worker, 'PLATFORM_AI_PROVIDER': 'fixture',
            'PLATFORM_RUNTIME': 'sandbox', 'PLATFORM_EXECUTION_MODE': 'remote',
            'PLATFORM_COORDINATOR_MODE': 'single',
            'PLATFORM_NODE_ROLE': 'coordinator', 'SERVER_ADDRESS': '127.0.0.1',
            'PORT': str(port), 'DEMO_AUTH_REQUIRED': 'false', 'DEMO_EPHEMERAL': 'false',
            'DEMO_USERNAME': '', 'DEMO_PASSWORD': '', 'PUBLIC_ORIGIN': '',
            'RENDER_EXTERNAL_URL': '', 'GEMINI_API_KEY': '', 'NEBIUS_API_KEY': '',
            'GITHUB_WORKBENCH_TOKEN': '', 'WORKER_OIDC_AUDIENCE': '',
            'WORKER_OIDC_REPOSITORY_ID': '', 'WORKER_OIDC_OWNER_ID': '',
            'WORKER_OIDC_WORKFLOW': ''}

        def start():
            with (root / 'server.log').open('ab') as log:
                child = subprocess.Popen(['java', '-Xmx256m', '-jar', str(release)],
                                         env=env, stdout=log, stderr=log)
            children.append(child)
            stop = time.monotonic() + 45
            while time.monotonic() < stop:
                if child.poll() is not None:
                    raise RuntimeError('Packaged coordinator exited before readiness.')
                try:
                    if call('/readyz')['status'] == 'READY':
                        return child
                except (urllib.error.URLError, TimeoutError, AssertionError):
                    pass
                time.sleep(.15)
            raise RuntimeError('Packaged coordinator did not become ready within 45 seconds.')

        def crash(child):
            child.kill()  # SIGKILL: no graceful cancellation or in-process restoration.
            child.wait(timeout=10)
            check('coordinatorKilledAbruptly', child.returncode == -9)

        try:
            first = start()
            check('emptyWorkerQueue', claim()['available'] is False)
            repository = call('/api/code/repositories/catalog', {'taskId': 'feedback-discount'})
            initial_head = repository['head']
            run = call('/api/code/runs', {'repositoryId': repository['id'],
                'instruction': 'Fix the discount calculation without changing tests.',
                'provider': 'mutation-search', 'maxAttempts': 1, 'timeoutSeconds': 120})
            old = until(lambda: claim().get('lease'), 15, 'baseline job lease')
            job_path = '/api/code/workers/jobs/' + old['id']
            initial_job = call(job_path)
            check('baselineLeasePersisted', initial_job['status'] == 'LEASED'
                  and initial_job['phase'] == 'baseline' and initial_job['owner'] == run['id']
                  and initial_job['deliveries'] == 1
                  and initial_job['leaderEpoch'] == old['leaderEpoch'])
            crash(first)

            second = start()
            resumed = call('/api/code/runs/' + run['id'])
            recovered = call(job_path)
            check('repairAutomaticallyResumes', resumed['status'] in ('QUEUED', 'RUNNING')
                  and resumed['createdAt'] == run['createdAt']
                  and resumed['timeoutSeconds'] == run['timeoutSeconds']
                  and not resumed.get('approvedCommit'))
            check('jobRecoveredQueued', recovered['status'] == 'QUEUED'
                  and recovered['deliveries'] == 1)
            check('immutableInputsAndDeadlinePreserved',
                  recovered['inputDigest'] == old['inputDigest']
                  and recovered['deadline'] == old['deadline'])
            check('ownershipEpochAdvanced', recovered['leaderEpoch'] > old['leaderEpoch'])
            call(f'/api/worker/jobs/{old["id"]}/heartbeat',
                 {'fence': old['fence'], 'leaderEpoch': old['leaderEpoch']}, worker, 409)
            check('oldEpochHeartbeatRejected', True)
            complete(old, 409)
            check('oldEpochCompletionRejected', True)

            current = claim().get('lease')
            check('sameJobRedeliveredWithNewFence', current is not None
                  and current['id'] == old['id'] and current['fence'] != old['fence']
                  and current['leaderEpoch'] > old['leaderEpoch']
                  and current['inputDigest'] == old['inputDigest']
                  and current['deadline'] == old['deadline']
                  and call(job_path)['deliveries'] == 2)
            check('newEpochHeartbeatAccepted', call(
                f'/api/worker/jobs/{current["id"]}/heartbeat',
                {'fence': current['fence'], 'leaderEpoch': current['leaderEpoch']}, worker)['accepted'])
            check('simulatedCompletionAccepted', complete(current)['accepted'])
            receipt = call(job_path)
            check('simulatedReceiptPersisted', receipt['status'] == 'COMPLETED'
                  and receipt['result'] == fixture)
            check('duplicateCompletionAccepted', complete(current)['accepted'])
            check('duplicateCompletionUnchanged', call(job_path) == receipt)
            resumed_result = until(lambda: (
                r if (r := call('/api/code/runs/' + run['id']))['status']
                not in ('QUEUED', 'RUNNING') else None), 15, 'resumed baseline receipt consumption')
            check('resumedRepairConsumesRetainedReceipt',
                  resumed_result['status'] == 'BASELINE_PASSED'
                  and resumed_result['baseline'] == fixture
                  and resumed_result['attempts'] == 0
                  and not resumed_result.get('approvedCommit'))
            crash(second)

            start()
            restored = call(job_path)
            active_epoch = call('/api/code/config')['sandbox']['leaderEpoch']
            check('receiptSurvivesSecondCrash', restored == receipt)
            check('thirdOwnershipEpochAdvanced', active_epoch > current['leaderEpoch'])
            complete(current, 409)
            check('previousEpochCannotRewriteReceipt', call(job_path) == restored)
            check('completedJobNotRedelivered', claim()['available'] is False)
            final_runs = call('/api/code/runs')
            check('noDuplicateRepairOrAutomaticApproval', len(final_runs) == 1
                  and final_runs[0]['id'] == run['id']
                  and final_runs[0] == resumed_result
                  and not final_runs[0].get('approvedCommit')
                  and len(call('/api/code/workers/jobs')) == 1
                  and next(r for r in call('/api/code/repositories')
                           if r['id'] == repository['id'])['head'] == initial_head)
            observations.update(jobId=old['id'], inputDigest=old['inputDigest'],
                originalDeadline=old['deadline'], ownershipEpochs=[
                    old['leaderEpoch'], current['leaderEpoch'], active_epoch],
                deliveries=restored['deliveries'], jobStatus=restored['status'],
                repairStatus=final_runs[0]['status'])
        finally:
            for child in reversed(children):
                if child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        child.wait(timeout=5)

    report = {'recordedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'experiment': 'Two coordinator SIGKILLs; fresh JVMs; retained H2 state; automatic deterministic repair resumption; authenticated simulated worker',
        'artifactSha256': hashlib.sha256(args.jar.read_bytes()).hexdigest(),
        'checks': checks, 'observations': observations,
        'elapsedSeconds': round(time.monotonic() - started, 3),
        'realContainerExecution': False, 'liveModelInference': False,
        'limitation': 'PASS is an explicitly simulated protocol fixture. No repository tests execute. '
            'This proves single-coordinator process recovery and epoch fencing, not repair correctness, '
            'PostgreSQL failover, distributed election or external-effect recovery.'}
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'checks': checks, 'output': str(args.output),
                      'realContainerExecution': False}, indent=2))


if __name__ == '__main__':
    main()
