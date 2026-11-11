#!/usr/bin/env python3
"""Verify a PostgreSQL custom-format dump by restoring it to a disposable database.

Requires Docker on Linux, a disposable TEST_DATABASE_URL and database CREATE rights.
Never use this against a production server: it creates and removes a database.
"""

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
from urllib.parse import unquote, urlsplit, urlunsplit

IMAGE = 'postgres:17'
FINGERPRINT_SQL = (
    "SELECT count(*)::text || ':' || md5(coalesce("
    "string_agg(md5(bucket || chr(31) || item_id || chr(31) || payload), '' "
    "ORDER BY bucket, item_id), '')) FROM ("
    "SELECT bucket, item_id, payload FROM workbench_state UNION ALL "
    "SELECT 'leadership', singleton::text, leader_epoch::text || ':' || owner || ':' || lease_until::text "
    "FROM workbench_leadership) AS durable_state"
)


def restore_target(url, new_database):
    """Allow only disposable *_test databases; preserve URI auth and connection options."""
    parts = urlsplit(url)
    database = unquote(parts.path.lstrip('/'))
    if parts.scheme not in ('postgres', 'postgresql') or not parts.hostname:
        raise ValueError('TEST_DATABASE_URL must be a PostgreSQL URL with a host')
    if not database.endswith('_test') or '/' in database:
        raise ValueError('Refusing non-test database; name must end in _test')
    if not new_database.startswith('xcloud_restore_'):
        raise ValueError('Invalid temporary restore database name')
    return urlunsplit((parts.scheme, parts.netloc, '/' + new_database, parts.query, ''))


def postgres(source, restore, script, archive=None, restore_name=None):
    """Run the matching PostgreSQL client without putting URLs into command args."""
    command = ['docker', 'run', '--rm', '--network', 'host',
               '--user', f'{os.getuid()}:{os.getgid()}', '--env', 'TEST_DATABASE_URL',
               '--env', 'RESTORE_URL', '--env', 'RESTORE_DB']
    if archive is not None:
        command += ['--mount', f'type=bind,src={archive},dst=/archive']
    command += [IMAGE, 'sh', '-ec', script]
    env = {**os.environ, 'TEST_DATABASE_URL': source, 'RESTORE_URL': restore,
           'RESTORE_DB': restore_name or ''}
    try:
        result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=1200,
                                check=False)
    except (FileNotFoundError, subprocess.TimeoutExpired):
        raise RuntimeError('PostgreSQL verification container could not complete') from None
    if result.returncode:
        # pg tools can echo connection URLs in errors; never include their raw output.
        raise RuntimeError(f'PostgreSQL client step failed (exit {result.returncode})')
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('target/postgres-restore-evidence.json'))
    args = parser.parse_args()
    source = os.environ.get('TEST_DATABASE_URL', '')
    restore_name = 'xcloud_restore_' + secrets.token_hex(8)
    restore = restore_target(source, restore_name)
    created = False
    with tempfile.TemporaryDirectory(prefix='xcloud-pg-backup-') as temporary:
        archive = Path(temporary)
        try:
            # The source database is read-only for the entire verification.
            before = postgres(source, restore,
                              f'''psql -X -A -t -v ON_ERROR_STOP=1 -d "$TEST_DATABASE_URL" -c "{FINGERPRINT_SQL}"''')
            postgres(source, restore,
                     'pg_dump --format=custom --no-owner --no-acl --file=/archive/state.dump "$TEST_DATABASE_URL"',
                     archive=archive)
            postgres(source, restore,
                     'createdb --maintenance-db="$TEST_DATABASE_URL" "$RESTORE_DB"',
                     restore_name=restore_name)
            created = True
            postgres(source, restore,
                     'pg_restore --no-owner --no-acl --exit-on-error --dbname="$RESTORE_URL" /archive/state.dump',
                     archive=archive, restore_name=restore_name)
            after = postgres(source, restore,
                             f'''psql -X -A -t -v ON_ERROR_STOP=1 -d "$RESTORE_URL" -c "{FINGERPRINT_SQL}"''',
                             restore_name=restore_name)
            if not before or before != after:
                raise RuntimeError('Restored workbench_state does not match the test source')
            digest = hashlib.sha256((archive / 'state.dump').read_bytes()).hexdigest()
            report = {
                'recordedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
                'experiment': 'PostgreSQL 17 custom-format dump and isolated database restore',
                'archiveSha256': digest,
                'schemaAndPayloadMatch': True,
                'leadershipEpochPreserved': True,
                'sourceUntouched': True,
                'limitation': 'Disposable *_test database only; does not prove hosted persistence, backup scheduling or point-in-time recovery.'
            }
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, indent=2) + '\n')
            print(json.dumps({'verified': True, 'output': str(args.output)}))
        finally:
            if created:
                postgres(source, restore,
                         'dropdb --if-exists --force --maintenance-db="$TEST_DATABASE_URL" "$RESTORE_DB"',
                         restore_name=restore_name)


if __name__ == '__main__':
    main()
