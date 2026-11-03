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

class AtomicWorkCoordinatorTest {
    private static final String TASK = "018f0a00-0000-7000-8000-000000000201";
    private static final String SNAPSHOT = "atomic-snapshot";
    private static final String OPERATION = "018f0a00-0000-7000-8000-000000000202";
    private static final String SHA = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    @TempDir
    Path tempDirectory;

    @Test
    void failureBeforeCommitLeavesOperationPendingAndRetryCommitsAllArtifacts() throws Exception {
        try (Connection connection = open("before.db")) {
            AtomicWorkCoordinator coordinator = new AtomicWorkCoordinator(connection);
            assertThrows(InjectedFailureException.class,
                    () -> coordinator.commit(TASK, OPERATION, FailureInjection.PROCESS_KILL_BEFORE_COMMIT));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM work_item WHERE status='PENDING'"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM evidence"));
            AtomicWorkCoordinator.CommitResult committed = coordinator.commit(TASK, OPERATION, FailureInjection.NONE);
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM work_item WHERE status='COMMITTED'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM evidence"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM analysis_result"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM execution_event"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM checkpoint"));
            assertEquals(committed.resultId(), coordinator.commit(TASK, OPERATION, FailureInjection.NONE).resultId());
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM analysis_result"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM execution_event"));
        }
    }

    @Test
    void failureAfterResultRollsBackResultEvidenceEventAndCheckpointTogether() throws Exception {
        try (Connection connection = open("after.db")) {
            AtomicWorkCoordinator coordinator = new AtomicWorkCoordinator(connection);
            assertThrows(InjectedFailureException.class,
                    () -> coordinator.commit(TASK, OPERATION, FailureInjection.PROCESS_KILL_AFTER_RESULT));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM work_item WHERE status='PENDING'"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM evidence"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM analysis_result"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM execution_event"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM checkpoint"));
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
