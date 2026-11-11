package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Database-clock lease. Callers serialize access to the connection and own the transaction. */
final class CoordinatorLease {
    private final Connection db;
    private final boolean postgres;
    private final String owner;
    private final Duration ttl;
    private long epoch;
    private Instant expiresAt;

    CoordinatorLease(Connection db, boolean postgres, String owner, Duration ttl) {
        if (ttl.compareTo(Duration.ofSeconds(5)) < 0 || ttl.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Coordinator lease must be 5–300 seconds");
        this.db = db;
        this.postgres = postgres;
        this.owner = owner;
        this.ttl = ttl;
    }

    void initialize() throws Exception {
        try (var sql = db.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS workbench_leadership (singleton INTEGER PRIMARY KEY,"
                    + " leader_epoch BIGINT NOT NULL, owner VARCHAR(80) NOT NULL,"
                    + " lease_until TIMESTAMP WITH TIME ZONE NOT NULL, CHECK (singleton=1))");
        }
        // Separate autocommit statements make concurrent first startup safe on both databases.
        try (var insert = db.prepareStatement("INSERT INTO workbench_leadership VALUES (1,0,'',?)")) {
            insert.setObject(1, Instant.EPOCH.atOffset(ZoneOffset.UTC));
            insert.executeUpdate();
        } catch (java.sql.SQLException exists) {
            if (!"23505".equals(exists.getSQLState())) throw exists;
        }
        if (postgres) {
            try (var sql = db.createStatement()) {
                // Retain the old release's owner row: an old binary must also be fenced on upgrade.
                sql.execute("INSERT INTO workbench_state(bucket,item_id,payload)"
                        + " VALUES ('coordinator','owner','') ON CONFLICT DO NOTHING");
            }
        }
    }

    private void lockLegacyOwner() throws Exception {
        if (!postgres) return;
        try (var sql = db.createStatement(); var row = sql.executeQuery(
                "SELECT payload FROM workbench_state WHERE bucket='coordinator' AND item_id='owner' FOR UPDATE")) {
            if (!row.next()) throw new IllegalStateException("Missing coordinator owner");
            if (epoch != 0 && !owner.equals(row.getString(1)))
                throw new IllegalStateException("Coordinator replaced by another release");
        }
    }

    private Instant now() throws Exception {
        try (var sql = db.createStatement(); var row = sql.executeQuery(
                postgres ? "SELECT clock_timestamp()" : "SELECT CURRENT_TIMESTAMP")) {
            row.next();
            return row.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    /** Single mode preserves explicit replacement; lease mode never displaces an unexpired owner. */
    boolean acquire(boolean replace) throws Exception {
        lockLegacyOwner();
        try (var sql = db.createStatement(); var row = sql.executeQuery(
                "SELECT leader_epoch,lease_until FROM workbench_leadership WHERE singleton=1 FOR UPDATE")) {
            if (!row.next()) throw new IllegalStateException("Missing leadership record");
            var now = now();
            if (!replace && now.isBefore(row.getObject(2, OffsetDateTime.class).toInstant())) return false;
            epoch = Math.addExact(row.getLong(1), 1);
            expiresAt = now.plus(ttl);
        }
        try (var sql = db.prepareStatement("UPDATE workbench_leadership SET leader_epoch=?,owner=?,lease_until=? WHERE singleton=1")) {
            sql.setLong(1, epoch);
            sql.setString(2, owner);
            sql.setObject(3, expiresAt.atOffset(ZoneOffset.UTC));
            sql.executeUpdate();
        }
        if (postgres) {
            try (var sql = db.prepareStatement("UPDATE workbench_state SET payload=? WHERE bucket='coordinator' AND item_id='owner'")) {
                sql.setString(1, owner);
                sql.executeUpdate();
            }
        }
        return true;
    }

    /** The row lock remains held through the associated state commit, including a final expiry check. */
    void assertOwned() throws Exception {
        lockLegacyOwner();
        try (var sql = db.createStatement(); var row = sql.executeQuery(
                "SELECT leader_epoch,owner,lease_until FROM workbench_leadership WHERE singleton=1 FOR UPDATE")) {
            if (!row.next() || epoch == 0 || row.getLong(1) != epoch || !owner.equals(row.getString(2))
                    || !now().isBefore(row.getObject(3, OffsetDateTime.class).toInstant()))
                throw new IllegalStateException("Coordinator epoch or lease is stale");
        }
    }

    void renew() throws Exception {
        assertOwned(); // Expired owners cannot revive their own epoch, even without a successor.
        expiresAt = now().plus(ttl);
        try (var sql = db.prepareStatement("UPDATE workbench_leadership SET lease_until=? WHERE singleton=1")) {
            sql.setObject(1, expiresAt.atOffset(ZoneOffset.UTC));
            sql.executeUpdate();
        }
    }

    void release() throws Exception {
        assertOwned();
        try (var sql = db.prepareStatement("UPDATE workbench_leadership SET lease_until=? WHERE singleton=1")) {
            sql.setObject(1, now().atOffset(ZoneOffset.UTC));
            sql.executeUpdate();
        }
    }

    long epoch() { return epoch; }
    Instant expiresAt() { return expiresAt; }
}
