package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Clock;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PostgresStateTest {
    @Test
    void replacementCoordinatorFencesOldWritesAndRestoresGitFromDatabase() {
        var json = new WorkbenchConfiguration().jsonMapper();
        try (var first = new StateStore(json, "memory", System.getenv("TEST_DATABASE_URL"))) {
            var workspace = new RepositoryWorkspace(first, new CommandRunner(), Clock.systemUTC());
            var task = new RepairTasks().examples().getFirst();
            var repo = workspace.create(task.name(), task.files(), task.testClass(), task.id());
            try (var replacement =
                    new StateStore(json, "memory", System.getenv("TEST_DATABASE_URL"))) {
                assertEquals(
                        "STATE_UNAVAILABLE",
                        assertThrows(
                                        PlatformException.class,
                                        () -> first.put("test", "stale", task))
                                .code());
                var restored =
                        new RepositoryWorkspace(
                                replacement, new CommandRunner(), Clock.systemUTC());
                assertEquals(repo.head(), restored.head(repo.id()));
                assertEquals(task.files(), restored.files(repo.id()));
                replacement.put("test", "receipt", task);
                assertEquals(task, replacement.all("test", CodeModel.Task.class).getFirst());
                replacement.remove("test", "receipt");
            }
        }
    }
}
