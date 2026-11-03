package io.handoff.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.handoff.persistence.ConnectionFactory;
import io.handoff.persistence.SchemaBootstrapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntegrityValidatorTest {
    private static final String TASK = "018f0a00-0000-7000-8000-000000000301";
    private static final String SNAPSHOT = "integrity-snapshot";
    private static final String OPERATION = "018f0a00-0000-7000-8000-000000000302";
    private static final String PENDING_OPERATION = "018f0a00-0000-7000-8000-000000000303";
    private static final String SHA = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";

    @TempDir
    Path tempDirectory;

    @Test
    void validEventChainAndCheckpointPass() throws Exception {
        try (Connection connection = open("valid.db")) {
            AtomicWorkCoordinator coordinator = new AtomicWorkCoordinator(connection);
            coordinator.commit(TASK, OPERATION, FailureInjection.NONE);
            new IntegrityValidator(connection).validate(TASK);
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM review"));
        }
    }

    @Test
    void tamperedEventPayloadCreatesReviewAndBlocksResume() throws Exception {
        try (Connection connection = open("event.db")) {
            AtomicWorkCoordinator coordinator = new AtomicWorkCoordinator(connection);
            coordinator.commit(TASK, OPERATION, FailureInjection.NONE);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE execution_event SET payload_json='tampered' WHERE task_id=?")) {
                statement.setString(1, TASK);
                statement.executeUpdate();
            }
            IntegrityFailure failure = assertThrows(IntegrityFailure.class,
                    () -> new IntegrityValidator(connection).validate(TASK));
            assertEquals(true, failure.getMessage().contains("payload hash"));
            assertEquals(TaskState.REQUIRES_REVIEW, new TaskRuntime(connection).task(TASK).state());
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM review WHERE status='OPEN'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM work_item WHERE status='PENDING'"));
        }
    }

    @Test
    void tamperedCheckpointCreatesReviewAndBlocksResume() throws Exception {
        try (Connection connection = open("checkpoint.db")) {
            AtomicWorkCoordinator coordinator = new AtomicWorkCoordinator(connection);
            coordinator.commit(TASK, OPERATION, FailureInjection.NONE);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE checkpoint SET content_sha256=? WHERE task_id=?")) {
                statement.setString(1, SHA);
                statement.setString(2, TASK);
                statement.executeUpdate();
            }
            IntegrityFailure failure = assertThrows(IntegrityFailure.class,
                    () -> new IntegrityValidator(connection).validate(TASK));
            assertEquals(true, failure.getMessage().contains("Checkpoint hash"));
            assertEquals(TaskState.REQUIRES_REVIEW, new TaskRuntime(connection).task(TASK).state());
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM review WHERE status='OPEN'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM work_item WHERE status='PENDING'"));
        }
    }

    private Connection open(String fileName) throws Exception {
        Connection connection = ConnectionFactory.open(tempDirectory.resolve(fileName));
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
        runtime.addWorkItem(PENDING_OPERATION, TASK, "SYMBOL", "fixture.Pending", SHA);
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
