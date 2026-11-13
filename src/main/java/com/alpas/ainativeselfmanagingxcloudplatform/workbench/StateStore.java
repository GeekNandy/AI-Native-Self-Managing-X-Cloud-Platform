package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import jakarta.annotation.PreDestroy;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.*;
import java.util.function.Function;

/**
 * Atomic state transactions fenced by a persisted epoch and a database-clock lease.
 * Ownership stays locked through every transaction, with expiry checked again before commit.
 */
public final class StateStore implements AutoCloseable {
    private final JsonMapper json;
    private Connection connection;
    private CoordinatorLease lease;
    private ScheduledExecutorService renewal;
    private final Map<Key, String> volatileRows = new HashMap<>();
    private final Path directory;
    private boolean failedClosed;
    private final boolean postgres;
    private final String coordinator = UUID.randomUUID().toString();
    private long epoch = 1;
    private final String mode;
    private StateTransaction activeTransaction;

    private record Key(String bucket, String id) {}

    public record Write(String bucket, String id, Object value) {}

    /**
     * A short state-only transaction. A caller can read, compare and write atomically while the
     * coordinator fence is held. Do not perform external effects or call StateStore methods inside
     * the callback. Handles cannot be retained or used from another thread.
     */
    public interface Transaction {
        <T> T get(String bucket, String id, Class<T> type);

        <T> List<T> all(String bucket, Class<T> type);

        void put(String bucket, String id, Object value);

        void remove(String bucket, String id);
    }

    public StateStore(JsonMapper json, String location) {
        this(json, location, "");
    }

    public StateStore(JsonMapper json, String location, String databaseUrl) {
        this(json, location, databaseUrl, "single", 30, 0);
    }

    public StateStore(JsonMapper json, String location, String databaseUrl,
            String mode, int leaseSeconds, int acquireTimeoutSeconds) {
        this.json = Objects.requireNonNull(json);
        postgres = databaseUrl != null && !databaseUrl.isBlank();
        this.mode = mode;
        if (!Set.of("single", "lease").contains(mode) || (mode.equals("lease") && !postgres)
                || acquireTimeoutSeconds < 0 || acquireTimeoutSeconds > 600)
            throw new IllegalArgumentException("Lease coordination requires PostgreSQL and a 0–600 second acquisition timeout");
        directory = "memory".equals(location) ? null : Path.of(location).toAbsolutePath().normalize();
        try {
            if (directory != null) {
                Files.createDirectories(directory);
                Files.setPosixFilePermissions(directory,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            }
            String url = directory == null ? "jdbc:h2:mem:" + UUID.randomUUID()
                    : "jdbc:h2:file:" + directory.resolve("state") + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
            connection = postgres ? postgresConnection(databaseUrl) : DriverManager.getConnection(url, "sa", "");
            try (var sql = connection.createStatement()) {
                if (postgres) {
                    sql.execute("SET lock_timeout = '5s'");
                    sql.execute("SET statement_timeout = '10s'");
                    sql.execute("SET idle_in_transaction_session_timeout = '15s'");
                } else sql.execute("SET LOCK_TIMEOUT 5000");
                sql.execute("CREATE TABLE IF NOT EXISTS workbench_state (bucket VARCHAR(80), item_id VARCHAR(120),"
                        + " payload " + (postgres ? "TEXT" : "CLOB")
                        + " NOT NULL, PRIMARY KEY(bucket,item_id))");
            }
            lease = new CoordinatorLease(connection, postgres, coordinator, Duration.ofSeconds(leaseSeconds));
            lease.initialize();
            // Preserve the raw owner used by both older releases. Insert, never replace, before
            // taking the lock so concurrent startup cannot displace an active lease.
            try (var sql = connection.createStatement()) {
                sql.executeUpdate("INSERT INTO workbench_state(bucket,item_id,payload) VALUES ('coordinator','owner','')");
            } catch (SQLException exists) {
                if (!"23505".equals(exists.getSQLState())) throw exists;
            }
            connection.setAutoCommit(false);
            long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(acquireTimeoutSeconds);
            while (true) {
                boolean acquired;
                try {
                    acquired = claimLeadership();
                } catch (SQLException locked) {
                    // Only a never-acquired lease candidate may retry a paused owner's row lock.
                    if (!mode.equals("lease") || lease.epoch() != 0 || !"55P03".equals(locked.getSQLState())) throw locked;
                    acquired = false;
                }
                if (acquired) break;
                connection.rollback();
                if (System.nanoTime() >= stop) throw new IllegalStateException("Coordinator lease is held");
                Thread.sleep(200);
            }
            connection.commit();
            renewal = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("coordinator-lease").factory());
            renewal.scheduleWithFixedDelay(this::renewLease, leaseSeconds * 1000L / 3,
                    leaseSeconds * 1000L / 3, TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            failedClosed = true;
            rollback();
            close();
            throw new IllegalStateException("Cannot acquire workbench state. Check storage and coordinator ownership.",
                    postgres ? null : failure);
        }
    }

    private boolean claimLeadership() throws Exception {
        // Use the same lock order as previous releases, including pre-lease epoch writers.
        lockLegacyOwner(false);
        String previous = readPayload("coordinator", "epoch");
        String format = readPayload("coordinator", "epoch-format");
        if ((format != null && !format.equals("1")) || (format != null && previous == null))
            throw new IllegalStateException("Coordinator epoch metadata is missing or unsupported");
        long last = previous == null ? 0 : Long.parseLong(previous);
        if (last < 0) throw new IllegalStateException("Invalid coordinator epoch");
        if (!lease.acquire(mode.equals("single"), last)) return false;
        epoch = lease.epoch();
        writePayload("coordinator", "owner", coordinator);
        writePayload("coordinator", "epoch", Long.toString(epoch));
        writePayload("coordinator", "epoch-format", "1");
        return true;
    }

    private static Connection postgresConnection(String value) throws Exception {
        var uri = java.net.URI.create(value);
        if (value.startsWith("jdbc:postgresql://")) return DriverManager.getConnection(value);
        if (!Set.of("postgres", "postgresql").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() == null)
            throw new IllegalArgumentException("Use a PostgreSQL connection URL");
        var parts = uri.getRawUserInfo().split(":", 2);
        var properties = new java.util.Properties();
        properties.setProperty(
                "user",
                java.net.URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8));
        properties.setProperty(
                "password",
                parts.length == 2
                        ? java.net.URLDecoder.decode(
                                parts[1], java.nio.charset.StandardCharsets.UTF_8)
                        : "");
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "15");
        return DriverManager.getConnection(
                "jdbc:postgresql://"
                        + uri.getHost()
                        + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                        + uri.getRawPath()
                        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()),
                properties);
    }

    private StateStore() {
        json = new WorkbenchConfiguration().jsonMapper();
        connection = null;
        directory = null;
        postgres = false;
        mode = "single";
    }

    public static StateStore volatileStore() {
        return new StateStore();
    }

    public Path directory() {
        return directory;
    }

    public synchronized Map<String, Object> status() {
        return Map.of(
                "backend", postgres ? "postgresql" : connection == null ? "memory" : "h2",
                "available", !failedClosed,
                "writerFenced", connection != null,
                "leaderEpoch", epoch,
                "coordinationMode", mode,
                "leaseUntil", lease == null ? "process-local" : lease.expiresAt().toString());
    }

    public synchronized long leaderEpoch() {
        requireAvailable();
        return epoch;
    }

    public synchronized void requireAvailable() {
        requireEpoch(epoch);
    }

    public synchronized void requireEpoch(long expected) {
        transaction(expected, tx -> null);
    }

    private void lockLegacyOwner(boolean requireOwner) throws SQLException {
        try (var query = connection.createStatement();
                var row =
                        query.executeQuery(
                                "SELECT payload FROM workbench_state WHERE bucket='coordinator' AND"
                                        + " item_id='owner' FOR UPDATE")) {
            if (!row.next() || (requireOwner && !coordinator.equals(row.getString(1))))
                throw new StorageFailure();
        }
    }

    private void assertFence() throws SQLException {
        lockLegacyOwner(true);
        if (!Long.toString(epoch).equals(readPayload("coordinator", "epoch"))
                || !"1".equals(readPayload("coordinator", "epoch-format")))
            throw new StorageFailure();
        try {
            lease.assertOwned();
        } catch (Exception failure) {
            throw new StorageFailure();
        }
    }

    private synchronized void renewLease() {
        if (failedClosed) return;
        try {
            assertFence();
            lease.renew();
            assertFence();
            connection.commit();
        } catch (Exception failure) {
            failedClosed = true;
            rollback();
        }
    }

    public synchronized <T> T transaction(long expectedEpoch, Function<Transaction, T> work) {
        Objects.requireNonNull(work);
        if (activeTransaction != null)
            throw new IllegalStateException("Nested state transactions are not supported");
        if (failedClosed || expectedEpoch != epoch) throw unavailable();
        var tx = new StateTransaction();
        try {
            if (connection != null) assertFence();
            activeTransaction = tx;
            T result = work.apply(tx);
            if (tx.storageFailed) throw new StorageFailure();
            if (connection != null) {
                assertFence(); // Expiry may have passed while the callback held the ownership lock.
                connection.commit();
            }
            else {
                volatileRows.clear();
                volatileRows.putAll(tx.rows);
            }
            return result;
        } catch (SQLException | StorageFailure e) {
            failedClosed = true;
            rollback();
            throw unavailable();
        } catch (RuntimeException | Error e) {
            // Domain/CAS rejection rolls back the entire batch without poisoning healthy storage.
            rollback();
            throw e;
        } finally {
            activeTransaction = null;
        }
    }

    public synchronized void put(String bucket, String id, Object value) {
        transaction(epoch, tx -> { tx.put(bucket, id, value); return null; });
    }

    public synchronized void putBatch(List<Write> writes) {
        transaction(epoch, tx -> {
            for (var write : writes) tx.put(write.bucket(), write.id(), write.value());
            return null;
        });
    }

    /** Compare persisted values and commit the transition under the same ownership lock. */
    public synchronized <T> boolean compareAndSet(String bucket, String id, T expected, T value, Class<T> type) {
        return transaction(epoch, tx -> {
            if (!Objects.equals(tx.get(bucket, id, type), expected)) return false;
            tx.put(bucket, id, value);
            return true;
        });
    }

    public synchronized void remove(String bucket, String id) {
        transaction(epoch, tx -> { tx.remove(bucket, id); return null; });
    }

    public synchronized <T> List<T> all(String bucket, Class<T> type) {
        return transaction(epoch, tx -> tx.all(bucket, type));
    }

    private final class StateTransaction implements Transaction {
        private final Map<Key, String> rows = connection == null ? new HashMap<>(volatileRows) : null;
        private boolean storageFailed;

        private void check() {
            if (activeTransaction != this || !Thread.holdsLock(StateStore.this))
                throw new IllegalStateException("State transaction is no longer active");
        }

        @Override
        public <T> T get(String bucket, String id, Class<T> type) {
            check();
            validate(bucket, id);
            try {
                String payload = connection == null ? rows.get(new Key(bucket, id)) : readPayload(bucket, id);
                return payload == null ? null : decode(bucket, payload, type);
            } catch (SQLException e) {
                storageFailed = true;
                throw new StorageFailure();
            }
        }

        @Override
        public <T> List<T> all(String bucket, Class<T> type) {
            check();
            validateBucket(bucket);
            var result = new ArrayList<T>();
            if (connection == null) {
                rows.entrySet().stream()
                        .filter(entry -> entry.getKey().bucket().equals(bucket))
                        .sorted(Comparator.comparing(entry -> entry.getKey().id()))
                        .forEach(entry -> result.add(decode(bucket, entry.getValue(), type)));
                return result;
            }
            try (var statement = connection.prepareStatement(
                    "SELECT payload FROM workbench_state WHERE bucket=? ORDER BY item_id")) {
                statement.setString(1, bucket);
                try (var records = statement.executeQuery()) {
                    while (records.next()) result.add(decode(bucket, records.getString(1), type));
                }
                return result;
            } catch (SQLException e) {
                storageFailed = true;
                throw new StorageFailure();
            }
        }

        @Override
        public void put(String bucket, String id, Object value) {
            check();
            validate(bucket, id);
            String payload = json.writeValueAsString(value);
            try {
                if (connection == null) rows.put(new Key(bucket, id), payload);
                else writePayload(bucket, id, payload);
            } catch (SQLException e) {
                storageFailed = true;
                throw new StorageFailure();
            }
        }

        @Override
        public void remove(String bucket, String id) {
            check();
            validate(bucket, id);
            if (connection == null) {
                rows.remove(new Key(bucket, id));
                return;
            }
            try (var statement = connection.prepareStatement(
                    "DELETE FROM workbench_state WHERE bucket=? AND item_id=?")) {
                statement.setString(1, bucket);
                statement.setString(2, id);
                statement.executeUpdate();
            } catch (SQLException e) {
                storageFailed = true;
                throw new StorageFailure();
            }
        }
    }

    private static void validateBucket(String bucket) {
        if (bucket == null || bucket.isBlank() || bucket.length() > 80 || bucket.equals("coordinator"))
            throw new IllegalArgumentException("Invalid or reserved state bucket");
    }

    private static void validate(String bucket, String id) {
        validateBucket(bucket);
        if (id == null || id.isBlank() || id.length() > 120)
            throw new IllegalArgumentException("Invalid state record identifier");
    }

    private String readPayload(String bucket, String id) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT payload FROM workbench_state WHERE bucket=? AND item_id=?")) {
            query.setString(1, bucket);
            query.setString(2, id);
            try (var row = query.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
    }

    private void writePayload(String bucket, String id, String payload) throws SQLException {
        try (var statement = connection.prepareStatement(
                postgres
                        ? "INSERT INTO workbench_state(bucket,item_id,payload) VALUES (?,?,?)"
                                + " ON CONFLICT(bucket,item_id) DO UPDATE SET payload=EXCLUDED.payload"
                        : "MERGE INTO workbench_state (bucket,item_id,payload)"
                                + " KEY(bucket,item_id) VALUES (?,?,?)")) {
            statement.setString(1, bucket);
            statement.setString(2, id);
            statement.setString(3, payload);
            statement.executeUpdate();
        }
    }

    private <T> T decode(String bucket, String payload, Class<T> type) {
        // Explicit persisted-record migrations; model/request parsing stays strict.
        if (bucket.equals("worker-jobs") && type == RemoteJobBroker.Job.class) {
            var root = json.readTree(payload);
            if (root instanceof tools.jackson.databind.node.ObjectNode record && !record.has("leaderEpoch"))
                record.put("leaderEpoch", 0);
            return json.treeToValue(root, type);
        }
        if (bucket.equals("evaluations") && type == EvaluationService.History.class) {
            var root = json.readTree(payload);
            for (var report : root.path("reports")) {
                if (report instanceof tools.jackson.databind.node.ObjectNode record) {
                    if (!record.has("provider")) record.put("provider", "unrecorded");
                    if (!record.has("model")) record.put("model", "unrecorded");
                    if (!record.has("harnessVersion")) record.put("harnessVersion", "harness-v1");
                }
            }
            return json.treeToValue(root, type);
        }
        return json.readValue(payload, type);
    }

    private static final class StorageFailure extends RuntimeException {}

    private void rollback() {
        if (connection == null) return;
        try {
            connection.rollback();
        } catch (SQLException e) {
            failedClosed = true;
        }
    }

    private PlatformException unavailable() {
        return new PlatformException(
                503,
                "STATE_UNAVAILABLE",
                "Coordinator ownership expired or durable state is unavailable. Stop mutations and restart after recovery.");
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        if (activeTransaction != null)
            throw new IllegalStateException("Cannot close a store inside its transaction");
        if (renewal != null) renewal.shutdownNow();
        if (connection != null) {
            try {
                if (!failedClosed && lease != null && lease.epoch() > 0) {
                    assertFence();
                    lease.release();
                    connection.commit();
                }
            } catch (Exception ignored) {
                rollback();
            }
            try { connection.close(); } catch (SQLException ignored) {}
        }
        failedClosed = true;
    }
}
