package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * PostgreSQL database-clock lease; embedded H2 uses the shared process clock because its SQL
 * current timestamp is frozen at transaction start. Callers serialize access and own the transaction.
 */
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
        if (!postgres) return Instant.now();
        try (var sql = db.createStatement(); var row = sql.executeQuery(
                "SELECT clock_timestamp()")) {
            row.next();
            return row.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    /** Single mode preserves explicit replacement; lease mode never displaces an unexpired owner. */
    boolean acquire(boolean replace) throws Exception {
        return acquire(replace, 0);
    }

    /** Include epochs written by releases that predate the lease table. */
    boolean acquire(boolean replace, long previousEpoch) throws Exception {
        if (previousEpoch < 0) throw new IllegalStateException("Invalid previous coordinator epoch");
        lockLegacyOwner();
        try (var sql = db.createStatement(); var row = sql.executeQuery(
                "SELECT leader_epoch,lease_until FROM workbench_leadership WHERE singleton=1 FOR UPDATE")) {
            if (!row.next()) throw new IllegalStateException("Missing leadership record");
            var now = now();
            if (!replace && now.isBefore(row.getObject(2, OffsetDateTime.class).toInstant())) return false;
            long storedEpoch = row.getLong(1);
            if (storedEpoch < 0) throw new IllegalStateException("Invalid coordinator epoch");
            epoch = Math.addExact(Math.max(storedEpoch, previousEpoch), 1);
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
        try (var sql = db.prepareStatement(postgres
                ? "UPDATE workbench_leadership SET lease_until=clock_timestamp() + (? * INTERVAL '1 millisecond')"
                        + " WHERE singleton=1 AND lease_until > clock_timestamp()"
                : "UPDATE workbench_leadership SET lease_until=? WHERE singleton=1 AND lease_until > ?")) {
            if (postgres) sql.setLong(1, ttl.toMillis());
            else {
                var current = now();
                sql.setObject(1, current.plus(ttl).atOffset(ZoneOffset.UTC));
                sql.setObject(2, current.atOffset(ZoneOffset.UTC));
            }
            if (sql.executeUpdate() != 1) throw new IllegalStateException("Coordinator lease expired before renewal");
        }
        try (var sql = db.createStatement(); var row = sql.executeQuery(
                "SELECT lease_until FROM workbench_leadership WHERE singleton=1")) {
            row.next();
            expiresAt = row.getObject(1, OffsetDateTime.class).toInstant();
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
