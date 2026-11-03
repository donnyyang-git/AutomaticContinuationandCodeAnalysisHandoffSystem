package io.handoff.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.handoff.persistence.ConnectionFactory;
import io.handoff.persistence.SchemaBootstrapper;
import io.handoff.runtime.TaskRuntime;
import io.handoff.runtime.TaskState;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class HandoffRuntimeCliTest {
    private static final String TASK = "018f0a00-0000-7000-8000-000000000101";
    private static final String SNAPSHOT = "cli-snapshot";
    private static final String SHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @TempDir
    Path tempDirectory;

    @Test
    void cliRunPauseAndResumePreserveDurableWorkCounts() throws Exception {
        Path database = tempDirectory.resolve("cli-smoke.db");
        try (Connection connection = open(database)) {
            TaskRuntime runtime = new TaskRuntime(connection);
            runtime.createTask(TASK, SNAPSHOT, "analysis");
            runtime.addWorkItem("018f0a00-0000-7000-8000-000000000103", TASK, "B", "z", SHA);
            runtime.addWorkItem("018f0a00-0000-7000-8000-000000000102", TASK, "A", "z", SHA);
            runtime.addWorkItem("018f0a00-0000-7000-8000-000000000101", TASK, "A", "a", SHA);
        }

        CommandLine cli = new CommandLine(new HandoffCommand());
        assertEquals(0, cli.execute("--db", database.toString(), "run", TASK, "--pause-after", "1"));
        try (Connection connection = ConnectionFactory.open(database)) {
            assertEquals(TaskState.PAUSED, new TaskRuntime(connection).task(TASK).state());
            assertEquals(1, count(connection, "COMMITTED"));
            assertEquals(2, count(connection, "PENDING"));
        }

        assertEquals(0, new CommandLine(new HandoffCommand()).execute(
                "--db", database.toString(), "resume", TASK));
        try (Connection connection = ConnectionFactory.open(database)) {
            assertEquals(TaskState.COMPLETED, new TaskRuntime(connection).task(TASK).state());
            assertEquals(3, count(connection, "COMMITTED"));
            assertEquals(0, count(connection, "PENDING"));
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
        return connection;
    }

    private static int count(Connection connection, String status) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM work_item WHERE task_id=? AND status=?")) {
            statement.setString(1, TASK);
            statement.setString(2, status);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }
}
