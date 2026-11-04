# PostgreSQL backup and restore readiness

## Scope and limits

The coordinator uses the `workbench_state` table for durable records and archived Git data. PostgreSQL writer fencing prevents stale coordinators from making new mutations; it **does not** provide backups. A database connected to Render is not a backup, nor does a `READY` response prove that recovery works. The provisioned free database currently documented for the demo expires on **2026-11-21** and does not include managed backups.

Before relying on hosted state, select a persistent database service with an appropriate retention and recovery policy, securely connect it, and run a separately authorized restore drill. Do **not** upload dumps, credentials, or restored state to GitHub Actions artifacts, the repository, or public logs.

## Safe automated test

The `scripts/verify-postgres-backup.py` script performs a real PostgreSQL 17 custom-format dump, restores it to a **new disposable test database**, and compares an ordered content fingerprint of the `workbench_state` rows. It uses Docker's host network and is intended for Linux CI. It refuses source databases whose names do not end in `_test`; this guard is necessary but is not a substitute for using an isolated test PostgreSQL instance. The test user must be able to create and drop databases.

Run against a dedicated local or CI PostgreSQL 17 service, **never the hosted database**:

```bash
export TEST_DATABASE_URL='postgresql://test-user:local-test-password@127.0.0.1:5432/workbench_test'
python3 -m unittest discover -s tests/unit -p test_postgres_backup.py
python3 scripts/verify-postgres-backup.py
```

The verifier reads the URL from the environment and passes it to the PostgreSQL client inside a container as an environment variable, not as a shell argument. It writes only a non-sensitive JSON verification receipt to `target/postgres-restore-evidence.json`. A temporary dump and restored database are deleted after verification, including on verification failures. The script cannot prove hosted persistence, replication, point-in-time recovery or continuous backup scheduling.

## Hosted operational restore drill (requires separate authorization)

1. Obtain a private backup or snapshot from the selected hosted database provider, verify its retention policy, and confirm the intended recovery point. Keep encryption keys and backup material outside the repository and public CI.
2. Provision a **separate** isolated PostgreSQL database or instance for the restore. Never restore into the live workbench database. Use matching PostgreSQL major-version client utilities and the provider's documented TLS settings.
3. Restore with the provider's supported procedure or `pg_restore --no-owner --no-acl --exit-on-error`, supplying database credentials through an approved secret mechanism rather than command-line URLs.
4. Verify that `workbench_state` exists, count and hash its rows, and start a **single** isolated coordinator against the recovered database with a temporary application URL and fresh private credentials. Validate an environment receipt, audit history, repository revision and Git archive recovery.
5. Destroy the isolated coordinator and restore database only after saving a redacted recovery report. Never run two writable coordinators against the production database.
6. Record time to recovery, backup age, validated version, lost-record window and explicit limitations in private operational evidence. Do not label the production deployment durable until a hosted restart and restore drill have actually passed.

For local H2 development, back up the **entire data directory with the application stopped**; copying its live database file is not a consistent snapshot. See [runtime and durable state](runtime-and-state.md) and [scaling and activation](scaling-and-activation.md).
