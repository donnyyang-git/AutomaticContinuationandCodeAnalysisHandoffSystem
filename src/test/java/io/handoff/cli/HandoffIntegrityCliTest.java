package io.handoff.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.handoff.persistence.ConnectionFactory;
import io.handoff.persistence.SchemaBootstrapper;
import io.handoff.runtime.AtomicWorkCoordinator;
import io.handoff.runtime.FailureInjection;
import io.handoff.runtime.TaskRuntime;
import io.handoff.runtime.TaskState;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class HandoffIntegrityCliTest {
    private static final String TASK = "018f0a00-0000-7000-8000-000000000401";
    private static final String SNAPSHOT = "cli-integrity-snapshot";
    private static final String OPERATION = "018f0a00-0000-7000-8000-000000000402";
    private static final String SHA = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";

    @TempDir
    Path tempDirectory;

    @Test
    void resumeReturns30AndDoesNotExecuteAfterCheckpointTamper() throws Exception {
        Path database = tempDirectory.resolve("integrity-cli.db");
        try (Connection connection = open(database)) {
            new AtomicWorkCoordinator(connection).commit(TASK, OPERATION, FailureInjection.NONE);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE checkpoint SET content_sha256=? WHERE task_id=?")) {
                statement.setString(1, SHA);
                statement.setString(2, TASK);
                statement.executeUpdate();
            }
        }

        int exitCode = new CommandLine(new HandoffCommand()).execute(
                "--db", database.toString(), "--json", "resume", TASK);
        assertEquals(30, exitCode);
        try (Connection connection = ConnectionFactory.open(database)) {
            assertEquals(TaskState.REQUIRES_REVIEW, new TaskRuntime(connection).task(TASK).state());
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM review WHERE status='OPEN'"));
        }
    }

    private Connection open(Path database) throws Exception {
        Connection connection = ConnectionFactory.open(database);
        SchemaBootstrapper.bootstrap(connection);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO source_snapshot(snapshot_id,project_name,manifest_sha256,ignore_rules_json,created_at) VALUES(?,?,?,?,?)")) {
            statement.setString(1, SNAPSHOT);
            statement.setString(2, "fixture");
            statement.setString(3, SHA);
            statement.setString(4, "{}");
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        }
        TaskRuntime runtime = new TaskRuntime(connection);
        runtime.createTask(TASK, SNAPSHOT, "analysis");
        runtime.addWorkItem(OPERATION, TASK, "SYMBOL", "fixture.Subject", SHA);
        return connection;
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                var result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }
}
