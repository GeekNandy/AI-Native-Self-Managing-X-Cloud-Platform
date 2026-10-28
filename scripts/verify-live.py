#!/usr/bin/env python3
"""Inspect live configuration or collect bounded model repair/evaluation evidence."""
import argparse
import base64
import datetime
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='https://x-cloud-workbench.onrender.com')
    parser.add_argument('--output', type=Path, default=Path('target/live-validation.json'))
    parser.add_argument('--provider', choices=['gemini', 'nebius'])
    parser.add_argument('--smoke', action='store_true', help='Run one live repair, up to three model calls; leave it awaiting review')
    parser.add_argument('--benchmark', action='store_true', help='Run 20 paired public tasks, one repetition, up to 120 model calls total')
    parser.add_argument('--baseline-policy')
    parser.add_argument('--candidate-policy')
    args = parser.parse_args()
    origin = urllib.parse.urlsplit(args.base_url)
    if (origin.username or origin.password or origin.query or origin.fragment
            or origin.path not in ('', '/') or not origin.hostname
            or (origin.scheme != 'https' and not (
                origin.scheme == 'http' and origin.hostname in ('localhost', '127.0.0.1')))):
        parser.error('Use an HTTPS origin or loopback HTTP origin without credentials or a path.')
    if (args.smoke or args.benchmark) and not args.provider:
        parser.error('Select a configured live --provider for inference work.')
    if args.benchmark and not (args.baseline_policy and args.candidate_policy):
        parser.error('Specify both policy versions for a paired benchmark.')
    token = os.environ.get('PLATFORM_OPERATOR_TOKEN')
    if token:
        authorization = 'Bearer ' + token
    else:
        username, password = os.environ.get('DEMO_USERNAME'), os.environ.get('DEMO_PASSWORD')
        if not (username and password):
            parser.error('Provide an operator token or workspace username/password through private environment variables.')
        authorization = 'Basic ' + base64.b64encode(f'{username}:{password}'.encode()).decode()
    handlers = [NoRedirect()]
    if origin.hostname in ('localhost', '127.0.0.1'):
        handlers.append(urllib.request.ProxyHandler({}))
    transport = urllib.request.build_opener(*handlers)

    def call(path, body=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(args.base_url.rstrip('/') + path, data=data,
            headers={'Content-Type': 'application/json', 'Authorization': authorization})
        with transport.open(request, timeout=30) as response:
            return json.load(response)

    report = {'recordedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'origin': args.base_url.rstrip('/'), 'operations': call('/api/operations'),
              'readiness': call('/readyz'),
              'limitation': 'Configuration is distinct from activation evidence. Public synthetic tasks do not establish industrial repair quality. Unknown model usage/cost remains unknown.'}

    def save():
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n')

    save()
    if not (args.smoke or args.benchmark):
        print(json.dumps({'output': str(args.output), 'pendingActivation': report['operations']['pendingActivation']}))
        return
    config = call('/api/code/config')
    if args.provider not in config['providers']:
        parser.error('Selected live provider is not configured; no inference was started.')
    if not config['sandbox']['available']:
        parser.error('Connect an isolated worker before running live validation.')
    if args.smoke:
        task = next(t for t in call('/api/code/tasks') if t['id'] == 'feedback-discount')
        repository = call('/api/code/repositories/catalog', {'taskId': task['id']})
        run = call('/api/code/runs', {'repositoryId': repository['id'], 'instruction': task['instruction'],
                   'provider': args.provider, 'maxAttempts': 3, 'timeoutSeconds': 120})
        deadline = time.monotonic() + 150
        while run['status'] in ('QUEUED', 'RUNNING', 'APPLYING') and time.monotonic() < deadline:
            time.sleep(1)
            run = call('/api/code/runs/' + run['id'])
        if run['status'] in ('QUEUED', 'RUNNING', 'APPLYING'):
            call('/api/code/runs/' + run['id'] + '/cancel', {})
            run = call('/api/code/runs/' + run['id'])
        report['smoke'] = {'run': run, 'modelCalls': call('/api/code/runs/' + run['id'] + '/model-calls')}
        save()
        if not (run['status'] == 'AWAITING_REVIEW' and (run.get('baseline') or {}).get('status') == 'FAIL'
                and (run.get('verification') or {}).get('status') == 'PASS' and report['smoke']['modelCalls']):
            raise RuntimeError('Live smoke did not produce FAIL → model patch → PASS; actual evidence saved.')
    if args.benchmark:
        benchmark = call('/api/code/benchmarks', {'baselineProvider': args.provider,
            'candidateProvider': args.provider, 'baselinePolicy': args.baseline_policy,
            'candidatePolicy': args.candidate_policy, 'repetitions': 1, 'maxAttempts': 3, 'timeoutSeconds': 120})
        report['benchmark'] = benchmark
        save()
        deadline = time.monotonic() + 6000
        while benchmark['status'] in ('QUEUED', 'RUNNING') and time.monotonic() < deadline:
            time.sleep(2)
            benchmark = next(b for b in call('/api/code/benchmarks') if b['id'] == benchmark['id'])
            report['benchmark'] = benchmark
            save()
        if benchmark['status'] in ('QUEUED', 'RUNNING'):
            report['benchmark'] = call('/api/code/benchmarks/' + benchmark['id'] + '/cancel', {})
            save()
        if report['benchmark']['status'] != 'COMPLETED':
            raise RuntimeError('Paired benchmark did not complete; actual partial evidence saved.')
    print(json.dumps({'output': str(args.output), 'smoke': report.get('smoke', {}).get('run', {}).get('status'),
                      'benchmark': report.get('benchmark', {}).get('status')}))


if __name__ == '__main__':
    try:
        main()
    except (urllib.error.URLError, TimeoutError):
        raise SystemExit('Validation could not reach the authenticated workspace. Check access, readiness and the saved partial report.')
