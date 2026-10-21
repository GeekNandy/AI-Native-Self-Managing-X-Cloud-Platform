#!/usr/bin/env python3
"""Run a real HTTP demo against a running local workbench. No external Python packages needed."""
import argparse
import os
import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--evidence', type=Path, help='Write actual operation and evaluation results as JSON.')
    parser.add_argument('--artifacts-dir', type=Path, help='Export the generated workload and manifest previews.')
    parser.add_argument('--token-file', type=Path, default=Path(os.environ.get('PLATFORM_DATA_DIR', '.workbench')) / 'operator.token')
    args = parser.parse_args()
    token = os.environ.get('PLATFORM_OPERATOR_TOKEN') or args.token_file.read_text().strip()
    parsed = urllib.parse.urlparse(args.base_url)
    if parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost') or parsed.path not in ('', '/'):
        parser.error('Use the local workbench origin, for example http://127.0.0.1:8080.')
    base = args.base_url.rstrip('/')
    transport = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(base + '/api' + path, data=data,
                                         headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token})
        try:
            with transport.open(request, timeout=60) as response:
                return json.load(response)
        except urllib.error.HTTPError as failure:
            raise RuntimeError(f'{path}: {failure.code} {failure.read().decode()}') from failure

    config = call('/config')
    if config['provider'] != 'fixture':
        parser.error('The reproducible demo requires PLATFORM_AI_PROVIDER=fixture; evaluate Gemini separately.')
    examples = {item['id']: item for item in call('/examples')}
    run = call('/runs', {'prompt': examples['production']['prompt']})
    plan = run['plan']
    if run['status'] != 'AWAITING_APPROVAL':
        raise RuntimeError('The production example did not pass policy.')
    approval = {'digest': plan['digest'], 'idempotencyKey': str(uuid.uuid4())}
    applied = call(f'/plans/{plan["id"]}/apply', approval)
    retry = call(f'/plans/{plan["id"]}/apply', approval)
    if not retry['replayed'] or retry['receipt']['operationId'] != applied['receipt']['operationId']:
        raise RuntimeError('Retry deduplication failed.')
    environment_id = plan['environmentId']
    for fault in ('replica-loss', 'egress-open'):
        drift = call(f'/environments/{environment_id}/faults', {'kind': fault})
    if len(drift['drift']) != 2 or drift['desired'] != plan['intent']:
        raise RuntimeError('Fault injection did not preserve desired state and expose both differences.')
    recovery = call(f'/environments/{environment_id}/reconcile', {})
    recovered = call(f'/plans/{recovery["id"]}/apply', {'digest': recovery['digest'], 'idempotencyKey': str(uuid.uuid4())})
    if recovered['receipt']['environment']['convergence'] != 'CONVERGED':
        raise RuntimeError('Recovery did not converge.')
    denied = call('/runs', {'prompt': examples['public-network']['prompt']})
    clarify = call('/runs', {'prompt': examples['ambiguous']['prompt']})
    if denied['status'] != 'BLOCKED' or clarify['status'] != 'CLARIFICATION_REQUIRED' or clarify['plan'] is not None:
        raise RuntimeError('The denial or clarification boundary failed.')
    call('/feedback', {'runId': run['id'], 'note': 'Recovery preserves the reviewed production PII intent.',
                       'expectedSpec': plan['intent'], 'expectedDecision': 'ALLOW'})
    evaluation = call('/evaluations', {'mode': 'control-plane'})
    if evaluation['passed'] != evaluation['total']:
        raise RuntimeError(f'Evaluation failed: {evaluation["passed"]}/{evaluation["total"]}. Inspect the workbench report.')
    source_root = Path(__file__).resolve().parent.parent
    source = hashlib.sha256()
    for path in sorted([source_root / 'pom.xml', *source_root.joinpath('src/main').rglob('*')]):
        if path.is_file():
            source.update(str(path.relative_to(source_root)).encode())
            source.update(path.read_bytes())
    evidence = {'recordedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                'sourceDigest': source.hexdigest(), 'configuration': config,
                'provisioningReceipt': applied, 'retryWasDeduplicated': retry['replayed'],
                'driftBeforeRecovery': drift, 'recoveryReceipt': recovered,
                'negativeOutcomes': {'unsafeRequest': denied['status'], 'ambiguousRequest': clarify['status']},
                'evaluation': evaluation,
                'limitations': 'Offline fixture interpretation and durable simulation. No live model, cloud calls, workload deployment or load benchmark.'}
    if args.evidence:
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(json.dumps(evidence, indent=2) + '\n')
    if args.artifacts_dir:
        args.artifacts_dir.mkdir(parents=True, exist_ok=True)
        for artifact in plan['artifacts']:
            if Path(artifact['path']).name != artifact['path']:
                raise RuntimeError('Unexpected artifact path.')
            (args.artifacts_dir / artifact['path']).write_text(artifact['content'])
    print(f'Provisioned {environment_id}; retry deduplicated; two drift fields repaired.')
    print(f'Unsafe request: {denied["status"]}; ambiguous request: {clarify["status"]}.')
    print(f'Evaluation: {evaluation["passed"]}/{evaluation["total"]} passed, including {evaluation["feedbackCases"]} feedback cases.')
    print('Execution: durable simulator. No live model or cloud resources used.')


if __name__ == '__main__':
    main()
