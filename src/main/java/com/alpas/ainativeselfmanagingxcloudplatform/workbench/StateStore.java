package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import jakarta.annotation.PreDestroy;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;

/**
 * Single-writer embedded store. H2 locks the file across processes; each record is an atomic
 * transaction.
 */
public final class StateStore implements AutoCloseable {
    private final JsonMapper json;
    private final Connection connection;
    private final Map<String, String> volatileRows = new HashMap<>();
    private final Path directory;
    private boolean failedClosed;
    private final boolean postgres;
    private final String coordinator = UUID.randomUUID().toString();

    public record Write(String bucket, String id, Object value) {}

    public StateStore(JsonMapper json, String location) {
        this(json, location, "");
    }

    public StateStore(JsonMapper json, String location, String databaseUrl) {
        this.json = json;
        postgres = databaseUrl != null && !databaseUrl.isBlank();
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
            connection =
                    postgres
                            ? postgresConnection(databaseUrl)
                            : DriverManager.getConnection(url, "sa", "");
            try (var statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS workbench_state (bucket VARCHAR(80), item_id"
                                + " VARCHAR(120), payload "
                                + (postgres ? "TEXT" : "CLOB")
                                + " NOT NULL, PRIMARY KEY(bucket,"
                                + " item_id))");
            }
            connection.setAutoCommit(false);
            if (postgres) {
                try (var claim =
                        connection.prepareStatement(
                                "INSERT INTO workbench_state(bucket,item_id,payload) VALUES"
                                    + " ('coordinator','owner',?) ON CONFLICT(bucket,item_id) DO"
                                    + " UPDATE SET payload=EXCLUDED.payload")) {
                    claim.setString(1, coordinator);
                    claim.executeUpdate();
                    connection.commit();
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot open workbench state. Check the data directory and ensure only one"
                            + " server uses it.",
                    postgres ? null : e);
        }
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
                "writerFenced", postgres);
    }

    public synchronized void requireAvailable() {
        if (failedClosed) throw unavailable();
        if (postgres)
            try {
                assertFence();
                connection.commit();
            } catch (Exception e) {
                failedClosed = true;
                rollback();
                throw unavailable();
            }
    }

    private void assertFence() throws Exception {
        if (!postgres) return;
        try (var query = connection.createStatement();
                var row =
                        query.executeQuery(
                                "SELECT payload FROM workbench_state WHERE bucket='coordinator' AND"
                                        + " item_id='owner' FOR UPDATE")) {
            if (!row.next() || !coordinator.equals(row.getString(1)))
                throw new IllegalStateException("Coordinator has been replaced");
        }
    }

    public synchronized void put(String bucket, String id, Object value) {
        putBatch(List.of(new Write(bucket, id, value)));
    }

    public synchronized void putBatch(List<Write> writes) {
        requireAvailable();
        var payloads =
                writes.stream()
                        .map(w -> new Write(w.bucket(), w.id(), json.writeValueAsString(w.value())))
                        .toList();
        if (connection == null) {
            payloads.forEach(w -> volatileRows.put(w.bucket() + "/" + w.id(), (String) w.value()));
            return;
        }
        try {
            assertFence();
            try (var statement =
                    connection.prepareStatement(
                            postgres
                                    ? "INSERT INTO workbench_state(bucket,item_id,payload) VALUES"
                                            + " (?,?,?) ON CONFLICT(bucket,item_id) DO UPDATE SET"
                                            + " payload=EXCLUDED.payload"
                                    : "MERGE INTO workbench_state (bucket,item_id,payload)"
                                            + " KEY(bucket,item_id) VALUES (?,?,?)")) {
                for (var w : payloads) {
                    statement.setString(1, w.bucket());
                    statement.setString(2, w.id());
                    statement.setString(3, (String) w.value());
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            }
        } catch (Exception e) {
            failedClosed = true;
            rollback();
            throw unavailable();
        }
    }

    public synchronized void remove(String bucket, String id) {
        requireAvailable();
        if (connection == null) {
            volatileRows.remove(bucket + "/" + id);
            return;
        }
        try (var statement =
                connection.prepareStatement(
                        "DELETE FROM workbench_state WHERE bucket=? AND item_id=?")) {
            assertFence();
            statement.setString(1, bucket);
            statement.setString(2, id);
            statement.executeUpdate();
            connection.commit();
        } catch (Exception e) {
            failedClosed = true;
            rollback();
            throw unavailable();
        }
    }

    public synchronized <T> List<T> all(String bucket, Class<T> type) {
        requireAvailable();
        var result = new ArrayList<T>();
        if (connection == null) {
            volatileRows.forEach(
                    (key, payload) -> {
                        if (key.startsWith(bucket + "/")) result.add(decode(bucket, payload, type));
                    });
            return result;
        }
        try (var statement =
                connection.prepareStatement(
                        "SELECT payload FROM workbench_state WHERE bucket=? ORDER BY item_id")) {
            statement.setString(1, bucket);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(decode(bucket, rows.getString(1), type));
            }
            connection.commit();
            return result;
        } catch (Exception e) {
            rollback();
            throw new IllegalStateException(
                    "Cannot restore workbench state; stored records must match the release schema.",
                    e);
        }
    }

    private <T> T decode(String bucket, String payload, Class<T> type) {
        // Explicit v1 -> v2 evaluation metadata migration. Model/request parsing stays strict.
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

    private void rollback() {
        try {
            connection.rollback();
        } catch (Exception ignored) {
        }
    }

    private PlatformException unavailable() {
        return new PlatformException(
                503,
                "STATE_UNAVAILABLE",
                "Durable state could not be committed. Stop mutations and inspect the data"
                        + " directory.");
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        failedClosed = true;
        if (connection != null)
            try {
                connection.close();
            } catch (Exception ignored) {
            }
    }
}
