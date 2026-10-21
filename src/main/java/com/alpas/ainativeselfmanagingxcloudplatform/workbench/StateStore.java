package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Single-writer embedded store. H2 locks the file across processes; each record is an atomic transaction. */
public final class StateStore implements AutoCloseable {
    private final JsonMapper json;
    private final Connection connection;
    private final Map<String, String> volatileRows = new HashMap<>();
    private final Path directory;
    private boolean failedClosed;

    public StateStore(JsonMapper json, String location) {
        this.json = json;
        try {
            directory = "memory".equals(location) ? null : Path.of(location).toAbsolutePath().normalize();
            if (directory != null) { Files.createDirectories(directory); Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
            String url = directory == null ? "jdbc:h2:mem:" + UUID.randomUUID()
                    : "jdbc:h2:file:" + directory.resolve("state") + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
            connection = DriverManager.getConnection(url, "sa", "");
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS workbench_state (bucket VARCHAR(80), item_id VARCHAR(120), payload CLOB NOT NULL, PRIMARY KEY(bucket, item_id))");
            }
            connection.setAutoCommit(false);
        } catch (Exception e) { throw new IllegalStateException("Cannot open workbench state. Check the data directory and ensure only one server uses it.", e); }
    }
    private StateStore() { json = new WorkbenchConfiguration().jsonMapper(); connection = null; directory = null; }
    public static StateStore volatileStore() { return new StateStore(); }
    public Path directory() { return directory; }

    public synchronized void requireAvailable() { if (failedClosed) throw unavailable(); }
    public synchronized void put(String bucket, String id, Object value) {
        requireAvailable();
        String payload = json.writeValueAsString(value);
        if (connection == null) { volatileRows.put(bucket + "/" + id, payload); return; }
        try (var statement = connection.prepareStatement("MERGE INTO workbench_state (bucket,item_id,payload) KEY(bucket,item_id) VALUES (?,?,?)")) {
            statement.setString(1, bucket); statement.setString(2, id); statement.setString(3, payload);
            statement.executeUpdate(); connection.commit();
        } catch (Exception e) { failedClosed = true; rollback(); throw unavailable(); }
    }
    public synchronized void remove(String bucket, String id) {
        requireAvailable();
        if (connection == null) { volatileRows.remove(bucket + "/" + id); return; }
        try (var statement = connection.prepareStatement("DELETE FROM workbench_state WHERE bucket=? AND item_id=?")) {
            statement.setString(1, bucket); statement.setString(2, id); statement.executeUpdate(); connection.commit();
        } catch (Exception e) { failedClosed = true; rollback(); throw unavailable(); }
    }
    public synchronized <T> List<T> all(String bucket, Class<T> type) {
        var result = new ArrayList<T>();
        if (connection == null) {
            volatileRows.forEach((key, payload) -> { if (key.startsWith(bucket + "/")) result.add(json.readValue(payload, type)); });
            return result;
        }
        try (var statement = connection.prepareStatement("SELECT payload FROM workbench_state WHERE bucket=? ORDER BY item_id")) {
            statement.setString(1, bucket);
            try (var rows = statement.executeQuery()) { while (rows.next()) result.add(json.readValue(rows.getString(1), type)); }
            connection.commit(); return result;
        } catch (Exception e) { rollback(); throw new IllegalStateException("Cannot restore workbench state; stored records must match the release schema.", e); }
    }
    private void rollback() { try { connection.rollback(); } catch (Exception ignored) {} }
    private PlatformException unavailable() { return new PlatformException(503, "STATE_UNAVAILABLE", "Durable state could not be committed. Stop mutations and inspect the data directory."); }
    @PreDestroy @Override public synchronized void close() { failedClosed = true; if (connection != null) try { connection.close(); } catch (Exception ignored) {} }
}
