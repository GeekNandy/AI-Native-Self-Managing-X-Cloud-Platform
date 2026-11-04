"""Safety checks for the disposable PostgreSQL restore verifier (no Docker needed)."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[2] / 'scripts' / 'verify-postgres-backup.py'
spec = importlib.util.spec_from_file_location('verify_postgres_backup', SCRIPT)
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


class RestoreVerifierTest(unittest.TestCase):
    def test_restores_only_test_database_and_preserves_auth_options(self):
        url = 'postgresql://user:p%40ss@127.0.0.1:5432/workbench_test?sslmode=require'
        actual = backup.restore_target(url, 'xcloud_restore_0123abcd')
        self.assertEqual(actual, 'postgresql://user:p%40ss@127.0.0.1:5432/xcloud_restore_0123abcd?sslmode=require')

    def test_rejects_non_test_or_invalid_database(self):
        for url in ('', 'mysql://host/workbench_test', 'postgresql://host/production',
                    'postgresql://host/workbench_test/other', 'postgresql:///workbench_test'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                backup.restore_target(url, 'xcloud_restore_0123abcd')

    def test_rejects_unexpected_restore_name(self):
        with self.assertRaises(ValueError):
            backup.restore_target('postgresql://localhost/workbench_test', 'production')

    @patch.object(backup.subprocess, 'run')
    def test_credentials_are_environment_only(self, run):
        run.return_value.returncode = 0
        run.return_value.stdout = 'ok\n'
        secret = 'postgresql://alice:secret@localhost/workbench_test'
        self.assertEqual(backup.postgres(secret, 'postgresql://alice:secret@localhost/xcloud_restore_1',
                                         'echo checked', restore_name='xcloud_restore_1'), 'ok')
        args = run.call_args.args[0]
        self.assertNotIn('secret', ' '.join(args))
        self.assertEqual(run.call_args.kwargs['env']['TEST_DATABASE_URL'], secret)

    @patch.object(backup.subprocess, 'run')
    def test_database_errors_do_not_echo_secrets(self, run):
        run.return_value.returncode = 1
        run.return_value.stderr = 'invalid password secret'
        with self.assertRaisesRegex(RuntimeError, 'exit 1') as error:
            backup.postgres('postgresql://alice:secret@localhost/workbench_test', '', 'false')
        self.assertNotIn('secret', str(error.exception))


if __name__ == '__main__':
    unittest.main()
