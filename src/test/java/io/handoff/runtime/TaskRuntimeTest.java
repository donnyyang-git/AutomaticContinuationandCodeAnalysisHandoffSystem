package io.handoff.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.handoff.persistence.ConnectionFactory;
import io.handoff.persistence.SchemaBootstrapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskRuntimeTest {
    private static final String TASK = "018f0a00-0000-7000-8000-000000000001";
    private static final String SNAPSHOT = "snapshot-1";
    private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @TempDir
    Path tempDirectory;

    @Test
    void schedulerUsesDeterministicOrderAndPausesAtTransactionBoundary() throws Exception {
        try (Connection connection = open()) {
            TaskRuntime runtime = new TaskRuntime(connection);
            runtime.createTask(TASK, SNAPSHOT, "analysis");
            runtime.addWorkItem("018f0a00-0000-7000-8000-000000000003", TASK, "B", "z", SHA);
            runtime.addWorkItem("018f0a00-0000-7000-8000-000000000002", TASK, "A", "z", SHA);
            runtime.addWorkItem("018f0a00-0000-7000-8000-000000000001", TASK, "A", "a", SHA);

            List<String> executed = new java.util.ArrayList<>();
            runtime.run(TASK, item -> {
                executed.add(item.kind() + ":" + item.subject());
                if (executed.size() == 1) {
                    runtime.requestPause(TASK, runtime.task(TASK).version());
                }
            });

            assertEquals(List.of("A:a"), executed);
            assertEquals(TaskState.PAUSED, runtime.task(TASK).state());
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM work_item WHERE task_id=? AND status='COMMITTED'", TASK));
            assertEquals(2, count(connection, "SELECT COUNT(*) FROM work_item WHERE task_id=? AND status='PENDING'", TASK));

            runtime.run(TASK, item -> executed.add(item.kind() + ":" + item.subject()));
            assertEquals(List.of("A:a", "A:z", "B:z"), executed);
            assertEquals(TaskState.COMPLETED, runtime.task(TASK).state());
            assertEquals("018f0a00-0000-7000-8000-000000000003", runtime.task(TASK).cursorOperationId());
        }
    }

    @Test
    void illegalTransitionDoesNotChangeStateOrVersion() throws Exception {
        try (Connection connection = open()) {
            TaskRuntime runtime = new TaskRuntime(connection);
            runtime.createTask(TASK, SNAPSHOT, "analysis");
            TaskRuntime.TaskInfo before = runtime.task(TASK);
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> runtime.transition(TASK, TaskState.COMPLETED, before.version()));
            assertEquals("E_STATE_TRANSITION", failure.code());
            TaskRuntime.TaskInfo after = runtime.task(TASK);
            assertEquals(before.state(), after.state());
            assertEquals(before.version(), after.version());
        }
    }


    private Connection open() throws Exception {
        Connection connection = ConnectionFactory.open(tempDirectory.resolve("runtime.db"));
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

    private static int count(Connection connection, String sql, String taskId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }
}
