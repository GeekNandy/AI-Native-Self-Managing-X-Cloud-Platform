package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Atomic state transactions fenced by a persisted epoch and a database-clock lease. */
public final class StateStore implements AutoCloseable {
    private final JsonMapper json;
    private Connection connection;
    private CoordinatorLease lease;
    private ScheduledExecutorService renewal;
    private final Map<String, String> volatileRows = new HashMap<>();
    private final Path directory;
    private boolean failedClosed;
    private final boolean postgres;
    private final String mode;

    public record Write(String bucket, String id, Object value) {}

    public StateStore(JsonMapper json, String location) { this(json, location, ""); }

    public StateStore(JsonMapper json, String location, String databaseUrl) {
        this(json, location, databaseUrl, "single", 30, 0);
    }

    public StateStore(JsonMapper json, String location, String databaseUrl,
            String mode, int leaseSeconds, int acquireTimeoutSeconds) {
        this.json = json;
        this.postgres = databaseUrl != null && !databaseUrl.isBlank();
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
            lease = new CoordinatorLease(connection, postgres, UUID.randomUUID().toString(), Duration.ofSeconds(leaseSeconds));
            lease.initialize();
            connection.setAutoCommit(false);
            long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(acquireTimeoutSeconds);
            while (true) {
                boolean acquired;
                try {
                    acquired = lease.acquire(mode.equals("single"));
                } catch (java.sql.SQLException locked) {
                    // A paused leader may hold the row until PostgreSQL's idle transaction
                    // timeout releases it. Only a never-acquired candidate may retry this lock.
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
            close();
            throw new IllegalStateException("Cannot acquire workbench state. Check storage and coordinator ownership.",
                    postgres ? null : failure);
        }
    }

    private static Connection postgresConnection(String value) throws Exception {
        var uri = java.net.URI.create(value);
        if (value.startsWith("jdbc:postgresql://")) return DriverManager.getConnection(value);
        if (!Set.of("postgres", "postgresql").contains(uri.getScheme())
                || uri.getHost() == null || uri.getUserInfo() == null)
            throw new IllegalArgumentException("Use a PostgreSQL connection URL");
        var parts = uri.getRawUserInfo().split(":", 2);
        var properties = new Properties();
        properties.setProperty("user", java.net.URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8));
        properties.setProperty("password", parts.length == 2
                ? java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8) : "");
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "15");
        return DriverManager.getConnection("jdbc:postgresql://" + uri.getHost()
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()), properties);
    }

    private StateStore() {
        json = new WorkbenchConfiguration().jsonMapper();
        directory = null;
        postgres = false;
        mode = "single";
    }

    public static StateStore volatileStore() { return new StateStore(); }
    public Path directory() { return directory; }
    public long leaderEpoch() { return lease == null ? 1 : lease.epoch(); }

    public synchronized Map<String, Object> status() {
        return Map.of("backend", postgres ? "postgresql" : connection == null ? "memory" : "h2",
                "available", !failedClosed, "writerFenced", connection != null,
                "coordinationMode", mode, "leaderEpoch", leaderEpoch(),
                "leaseUntil", lease == null ? "process-local" : lease.expiresAt().toString());
    }

    private void checkOpen() { if (failedClosed) throw unavailable(); }

    public synchronized void requireAvailable() {
        checkOpen();
        if (connection == null) return;
        try {
            lease.assertOwned();
            connection.commit();
        } catch (Exception failure) { fail(); }
    }

    private synchronized void renewLease() {
        if (failedClosed) return;
        try {
            lease.renew();
            connection.commit();
        } catch (Exception failure) {
            failedClosed = true;
            rollback(); // A stale/expired instance stays fenced until it is restarted and reloads state.
        }
    }

    private void commit() throws Exception {
        lease.assertOwned(); // Check expiry again after the writes, under the same ownership row lock.
        connection.commit();
    }

    public synchronized void put(String bucket, String id, Object value) {
        putBatch(List.of(new Write(bucket, id, value)));
    }

    public synchronized void putBatch(List<Write> writes) {
        checkOpen();
        var payloads = writes.stream()
                .map(w -> new Write(w.bucket(), w.id(), json.writeValueAsString(w.value()))).toList();
        if (connection == null) {
            payloads.forEach(w -> volatileRows.put(w.bucket() + "/" + w.id(), (String) w.value()));
            return;
        }
        try {
            lease.assertOwned();
            write(payloads);
            commit();
        } catch (Exception failure) { fail(); }
    }

    private void write(List<Write> payloads) throws Exception {
        try (var sql = connection.prepareStatement(postgres
                ? "INSERT INTO workbench_state(bucket,item_id,payload) VALUES (?,?,?) ON CONFLICT(bucket,item_id) DO UPDATE SET payload=EXCLUDED.payload"
                : "MERGE INTO workbench_state(bucket,item_id,payload) KEY(bucket,item_id) VALUES (?,?,?)")) {
            for (var w : payloads) {
                sql.setString(1, w.bucket());
                sql.setString(2, w.id());
                sql.setString(3, (String) w.value());
                sql.addBatch();
            }
            sql.executeBatch();
        }
    }

    /** Compare decoded values under the ownership/record locks, including explicit schema migration. */
    public synchronized <T> boolean compareAndSet(String bucket, String id, T expected, T value, Class<T> type) {
        checkOpen();
        String key = bucket + "/" + id;
        if (connection == null) {
            var raw = volatileRows.get(key);
            if (!Objects.equals(raw == null ? null : decode(bucket, raw, type), expected)) return false;
            volatileRows.put(key, json.writeValueAsString(value));
            return true;
        }
        try {
            lease.assertOwned();
            try (var sql = connection.prepareStatement("SELECT payload FROM workbench_state WHERE bucket=? AND item_id=? FOR UPDATE")) {
                sql.setString(1, bucket);
                sql.setString(2, id);
                try (var row = sql.executeQuery()) {
                    T current = row.next() ? decode(bucket, row.getString(1), type) : null;
                    if (!Objects.equals(current, expected)) {
                        connection.rollback();
                        return false;
                    }
                }
            }
            write(List.of(new Write(bucket, id, json.writeValueAsString(value))));
            commit();
            return true;
        } catch (Exception failure) { fail(); return false; }
    }

    public synchronized void remove(String bucket, String id) {
        checkOpen();
        if (connection == null) { volatileRows.remove(bucket + "/" + id); return; }
        try {
            lease.assertOwned();
            try (var sql = connection.prepareStatement("DELETE FROM workbench_state WHERE bucket=? AND item_id=?")) {
                sql.setString(1, bucket);
                sql.setString(2, id);
                sql.executeUpdate();
            }
            commit();
        } catch (Exception failure) { fail(); }
    }

    public synchronized <T> List<T> all(String bucket, Class<T> type) {
        checkOpen();
        var result = new ArrayList<T>();
        if (connection == null) {
            volatileRows.forEach((key, payload) -> {
                if (key.startsWith(bucket + "/")) result.add(decode(bucket, payload, type));
            });
            return result;
        }
        try {
            lease.assertOwned();
            try (var sql = connection.prepareStatement("SELECT payload FROM workbench_state WHERE bucket=? ORDER BY item_id")) {
                sql.setString(1, bucket);
                try (var rows = sql.executeQuery()) {
                    while (rows.next()) result.add(decode(bucket, rows.getString(1), type));
                }
            }
            commit();
            return result;
        } catch (Exception failure) { fail(); return List.of(); }
    }

    private <T> T decode(String bucket, String payload, Class<T> type) {
        // Explicit migrations affect persisted records only; API/model parsing remains strict.
        if (bucket.equals("worker-jobs") && type == RemoteJobBroker.Job.class) {
            var root = (tools.jackson.databind.node.ObjectNode) json.readTree(payload);
            if (!root.has("leaderEpoch")) root.put("leaderEpoch", 0);
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

    private void rollback() { try { connection.rollback(); } catch (Exception ignored) {} }
    private void fail() { failedClosed = true; rollback(); throw unavailable(); }
    private PlatformException unavailable() {
        return new PlatformException(503, "STATE_UNAVAILABLE",
                "Coordinator ownership expired or durable state is unavailable. Stop mutations and restart after recovery.");
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        if (renewal != null) renewal.shutdownNow();
        if (connection != null) {
            try {
                if (!failedClosed && lease != null && lease.epoch() > 0) {
                    lease.release();
                    connection.commit();
                }
            } catch (Exception ignored) { rollback(); }
            try { connection.close(); } catch (Exception ignored) {}
        }
        failedClosed = true;
    }
}
