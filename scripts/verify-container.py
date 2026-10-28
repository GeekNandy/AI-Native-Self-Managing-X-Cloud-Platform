#!/usr/bin/env python3
"""Build the actual hosted image and verify its access boundary without model credentials."""
import base64
import json
import os
import secrets
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

image = 'x-cloud-workbench:verify'
name = 'x-cloud-workbench-verify-' + secrets.token_hex(4)
base = 'http://127.0.0.1:8092'
username = 'container-reviewer'
password = secrets.token_urlsafe(32)

def request(path, authenticated=False, origin=None):
    headers = {}
    if authenticated:
        headers['Authorization'] = 'Basic ' + base64.b64encode((username + ':' + password).encode()).decode()
    if origin:
        headers['Origin'] = origin
    try:
        with urllib.request.urlopen(urllib.request.Request(base + path, headers=headers), timeout=3) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as response:
        return response.code, response.headers, response.read()

subprocess.run(['docker', 'build', '-t', image, '.'], check=True, timeout=420)
container_env = dict(os.environ, DEMO_USERNAME=username, DEMO_PASSWORD=password, PUBLIC_ORIGIN=base, PLATFORM_AI_PROVIDER='fixture')
try:
    subprocess.run(['docker', 'run', '-d', '--name', name, '-p', '127.0.0.1:8092:10000', '-e', 'DEMO_USERNAME', '-e', 'DEMO_PASSWORD', '-e', 'PUBLIC_ORIGIN', '-e', 'PLATFORM_AI_PROVIDER', image], env=container_env, check=True, stdout=subprocess.DEVNULL, timeout=30)
    deadline = time.monotonic() + 60
    while True:
        try:
            if request('/actuator/health')[0] == 200:
                break
        except (OSError, TimeoutError):
            pass
        if time.monotonic() >= deadline:
            raise RuntimeError('Hosted container did not become healthy.')
        time.sleep(0.4)
    checks = {}
    for path in ['/', '/app.js', '/api/config', '/api/code/config', '/api/operations', '/actuator/metrics', '/mcp']:
        status, headers, _ = request(path)
        checks['protected:' + path] = status == 401 and headers.get('WWW-Authenticate', '').startswith('Basic ')
    status, _, body = request('/api/config', True)
    config = json.loads(body)
    checks['authenticated-config'] = status == 200 and config.get('authentication') == 'username-password'
    checks['ephemeral-profile-declared'] = config.get('dataRetention') == 'ephemeral'
    status, headers, body = request('/readyz')
    checks['public-readiness-no-details'] = status == 200 and json.loads(body) == {'status': 'READY'} and headers.get('Cache-Control') == 'no-store'
    status, _, body = request('/api/operations', True)
    operations = json.loads(body)
    checks['authenticated-operations'] = status == 200 and operations['capacity']['coordinators'] == 1
    checks['unconfigured-capabilities-disclosed'] = operations['models'] == [] and not operations['publication']['enabled'] and not operations['state']['survivesDeploysConfigured']
    checks['authenticated-ui'] = request('/', True)[0] == 200
    checks['authenticated-session'] = json.loads(request('/api/session', True)[2]).get('role') == 'operator'
    checks['foreign-origin-denied'] = request('/api/environments', True, 'https://attacker.test')[0] == 403
    user = subprocess.check_output(['docker', 'inspect', '--format', '{{.Config.User}}', name], text=True).strip()
    checks['non-root'] = user == '10001:10001'
    report = {'image': image, 'checks': checks, 'passed': all(checks.values()), 'liveModelCalls': 0, 'containerRuntime': 'actual Docker'}
    Path('target').mkdir(exist_ok=True)
    Path('target/hosted-container-evidence.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))
    if not report['passed']:
        raise RuntimeError('Hosted container access checks failed.')
finally:
    subprocess.run(['docker', 'rm', '-f', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
