package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import jakarta.annotation.PreDestroy;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;

/**
 * Single active coordinator with durable replacement epochs. The ownership row is locked through
 * every read/write transaction; replacing a coordinator cannot race a checked state mutation.
 * This is replacement fencing, not a lease-based election or a multi-active coordinator service.
 */
public final class StateStore implements AutoCloseable {
    private final JsonMapper json;
    private final Connection connection;
    private final Map<Key, String> volatileRows = new HashMap<>();
    private final Path directory;
    private boolean failedClosed;
    private final boolean postgres;
    private final String coordinator = UUID.randomUUID().toString();
    private final long epoch;
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
        this.json = Objects.requireNonNull(json);
        postgres = databaseUrl != null && !databaseUrl.isBlank();
        Connection opened = null;
        try {
            directory =
                    "memory".equals(location)
                            ? null
                            : Path.of(location).toAbsolutePath().normalize();
            if (directory != null) {
                Files.createDirectories(directory);
                Files.setPosixFilePermissions(
                        directory,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            }
            String url =
                    directory == null
                            ? "jdbc:h2:mem:" + UUID.randomUUID()
                            : "jdbc:h2:file:"
                                    + directory.resolve("state")
                                    + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
            opened =
                    postgres
                            ? postgresConnection(databaseUrl)
                            : DriverManager.getConnection(url, "sa", "");
            connection = opened;
            try (var statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS workbench_state (bucket VARCHAR(80), item_id"
                                + " VARCHAR(120), payload "
                                + (postgres ? "TEXT" : "CLOB")
                                + " NOT NULL, PRIMARY KEY(bucket, item_id))");
            }
            connection.setAutoCommit(false);
            epoch = claimLeadership();
        } catch (Exception e) {
            // A failed migration/claim must not leave a live connection or H2 file lock behind.
            if (opened != null) {
                try {
                    if (!opened.getAutoCommit()) opened.rollback();
                } catch (SQLException ignored) {
                }
                try {
                    opened.close();
                } catch (SQLException ignored) {
                }
            }
            throw new IllegalStateException(
                    "Cannot open workbench state. Check the data directory and ensure only one"
                            + " server uses it.",
                    postgres ? null : e);
        }
    }

    private long claimLeadership() throws SQLException {
        // Keep the legacy raw UUID owner row so a pre-epoch PostgreSQL writer is also fenced.
        // The upsert obtains the ownership lock before inspecting or incrementing the epoch.
        writePayload("coordinator", "owner", coordinator);
        String previous = readPayload("coordinator", "epoch");
        String format = readPayload("coordinator", "epoch-format");
        if ((format != null && !format.equals("1")) || (format != null && previous == null))
            throw new IllegalStateException("Coordinator epoch metadata is missing or unsupported");
        long last = previous == null ? 0 : Long.parseLong(previous);
        if (last < 0) throw new IllegalStateException("Invalid coordinator epoch");
        long next = Math.addExact(last, 1);
        writePayload("coordinator", "epoch", Long.toString(next));
        writePayload("coordinator", "epoch-format", "1");
        connection.commit();
        return next;
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
        epoch = 1;
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
                "leaderEpoch", epoch);
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

    private void assertFence() throws SQLException {
        try (var query = connection.createStatement();
                var row =
                        query.executeQuery(
                                "SELECT payload FROM workbench_state WHERE bucket='coordinator' AND"
                                        + " item_id='owner' FOR UPDATE")) {
            if (!row.next() || !coordinator.equals(row.getString(1)))
                throw new StorageFailure();
        }
        if (!Long.toString(epoch).equals(readPayload("coordinator", "epoch"))
                || !"1".equals(readPayload("coordinator", "epoch-format")))
            throw new StorageFailure();
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
            if (connection != null) connection.commit();
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
                "Durable state is unavailable or this coordinator epoch has been replaced.");
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        if (activeTransaction != null)
            throw new IllegalStateException("Cannot close a store inside its transaction");
        failedClosed = true;
        if (connection != null)
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
    }
}
