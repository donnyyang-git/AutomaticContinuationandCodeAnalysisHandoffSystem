package io.handoff.runtime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class TaskRuntime {
    @FunctionalInterface
    public interface WorkExecutor {
        void execute(WorkItem item) throws Exception;
    }

    public record TaskInfo(String taskId, TaskState state, String cursorOperationId, boolean pauseRequested, long version) {}

    private final Connection connection;

    public TaskRuntime(Connection connection) {
        this.connection = connection;
    }

    public void createTask(String taskId, String snapshotId, String phase) throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO task_state(task_id,snapshot_id,state,phase,created_at,updated_at) VALUES(?,?, 'CREATED',?,?,?)")) {
            statement.setString(1, taskId);
            statement.setString(2, snapshotId);
            statement.setString(3, phase);
            statement.setString(4, now);
            statement.setString(5, now);
            statement.executeUpdate();
        }
    }

    public void addWorkItem(String operationId, String taskId, String kind, String subject, String inputSha256)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO work_item(operation_id,task_id,kind,subject,status,input_sha256) VALUES(?,?,?,?,'PENDING',?)")) {
            statement.setString(1, operationId);
            statement.setString(2, taskId);
            statement.setString(3, kind);
            statement.setString(4, subject);
            statement.setString(5, inputSha256);
            statement.executeUpdate();
        }
    }

    public TaskInfo task(String taskId) throws SQLException, RuntimeException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state,cursor_operation_id,pause_requested,version FROM task_state WHERE task_id=?")) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new RuntimeException("E_PRECONDITION", "Unknown task: " + taskId);
                }
                return new TaskInfo(taskId, TaskState.valueOf(result.getString(1)), result.getString(2),
                        result.getInt(3) != 0, result.getLong(4));
            }
        }
    }

    public List<WorkItem> orderedWork(String taskId) throws SQLException {
        List<WorkItem> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT operation_id,task_id,kind,subject,status,attempt_count,input_sha256 "
                        + "FROM work_item WHERE task_id=? ORDER BY CASE kind WHEN 'JAVA_CLASS' THEN 0 WHEN 'CONFIG_FILE' THEN 1 ELSE 2 END,subject,operation_id")) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    items.add(new WorkItem(result.getString(1), result.getString(2), result.getString(3),
                            result.getString(4), result.getString(5), result.getInt(6), result.getString(7)));
                }
            }
        }
        return List.copyOf(items);
    }

    public void transition(String taskId, TaskState target, long expectedVersion)
            throws SQLException, RuntimeException {
        connection.setAutoCommit(false);
        try {
            TaskInfo current = task(taskId);
            if (current.version() != expectedVersion) {
                throw new RuntimeException("E_CONCURRENT_TASK", "Task version changed: " + taskId);
            }
            if (!current.state().canTransitionTo(target)) {
                throw new RuntimeException("E_STATE_TRANSITION",
                        "Illegal transition: " + current.state() + " -> " + target);
            }
            updateState(taskId, target, current.cursorOperationId(), false, expectedVersion);
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            rollbackQuietly();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public void requestPause(String taskId, long expectedVersion) throws SQLException, RuntimeException {
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE task_state SET pause_requested=1,version=version+1,updated_at=? WHERE task_id=? AND version=?")) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, taskId);
            statement.setLong(3, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new RuntimeException("E_CONCURRENT_TASK", "Task version changed: " + taskId);
            }
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            rollbackQuietly();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public void run(String taskId, WorkExecutor executor) throws Exception {
        recoverRunningWork(taskId);
        TaskInfo current = task(taskId);
        if (current.state() == TaskState.COMPLETED) {
            return;
        }
        if (current.state() == TaskState.PAUSED || current.state() == TaskState.INTERRUPTED
                || current.state() == TaskState.REQUIRES_REVIEW) {
            transition(taskId, TaskState.RESUMING, current.version());
            current = task(taskId);
        }
        if (current.state() == TaskState.RESUMING) {
            transition(taskId, TaskState.ANALYZING, current.version());
            current = task(taskId);
        }
        if (current.state() == TaskState.CREATED) {
            transition(taskId, TaskState.INDEXING, current.version());
            current = task(taskId);
        }
        if (current.state() == TaskState.INDEXING) {
            transition(taskId, TaskState.ANALYZING, current.version());
        } else if (current.state() != TaskState.ANALYZING) {
            throw new RuntimeException("E_STATE_TRANSITION", "Cannot run task in state " + current.state());
        }

        for (WorkItem item : orderedWork(taskId)) {
            TaskInfo before = task(taskId);
            if (before.pauseRequested()) {
                pauseAtBoundary(taskId, before);
                return;
            }
            if (!item.status().equals("PENDING")) {
                continue;
            }
            markRunning(item, before.version());
            try {
                executor.execute(item);
                markCommitted(item, task(taskId).version());
            } catch (Exception failure) {
                markFailed(item, task(taskId).version());
                throw failure;
            }
        }
        TaskInfo finished = task(taskId);
        if (finished.pauseRequested()) {
            pauseAtBoundary(taskId, finished);
        } else {
            transition(taskId, TaskState.CHECKPOINTING, finished.version());
            transition(taskId, TaskState.COMPLETED, task(taskId).version());
        }
    }

    /** Converts work left RUNNING by a terminated process back to retryable work. */
    public int recoverRunningWork(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE work_item SET status='PENDING' WHERE task_id=? AND status='RUNNING'")) {
            statement.setString(1, taskId);
            return statement.executeUpdate();
        }
    }


    private void pauseAtBoundary(String taskId, TaskInfo current) throws SQLException, RuntimeException {
        connection.setAutoCommit(false);
        try {
            updateState(taskId, TaskState.PAUSED, current.cursorOperationId(), true, current.version());
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            rollbackQuietly();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void markRunning(WorkItem item, long expectedVersion) throws SQLException, RuntimeException {
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE work_item SET status='RUNNING',attempt_count=attempt_count+1 WHERE operation_id=? AND status='PENDING'")) {
            statement.setString(1, item.operationId());
            if (statement.executeUpdate() != 1) {
                throw new RuntimeException("E_CONCURRENT_WORK", "Work item is no longer pending: " + item.operationId());
            }
            updateStateVersionOnly(item.taskId(), expectedVersion);
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            rollbackQuietly();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void markCommitted(WorkItem item, long expectedVersion) throws SQLException, RuntimeException {
        updateWorkStatus(item, "COMMITTED", expectedVersion);
    }

    private void markFailed(WorkItem item, long expectedVersion) throws SQLException {
        updateWorkStatusUnchecked(item, "FAILED", expectedVersion);
    }

    private void updateWorkStatus(WorkItem item, String status, long expectedVersion) throws SQLException, RuntimeException {
        updateWorkStatusUnchecked(item, status, expectedVersion);
    }

    private void updateWorkStatusUnchecked(WorkItem item, String status, long expectedVersion) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE work_item SET status=?,committed_at=CASE WHEN ?='COMMITTED' THEN ? ELSE committed_at END "
                        + "WHERE operation_id=? AND status='RUNNING'")) {
            String now = Instant.now().toString();
            statement.setString(1, status);
            statement.setString(2, status);
            statement.setString(3, now);
            statement.setString(4, item.operationId());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Work item is no longer running: " + item.operationId());
            }
            updateStateVersionOnly(item.taskId(), expectedVersion);
            if (status.equals("COMMITTED")) {
                try (PreparedStatement cursor = connection.prepareStatement(
                        "UPDATE task_state SET cursor_operation_id=? WHERE task_id=?")) {
                    cursor.setString(1, item.operationId());
                    cursor.setString(2, item.taskId());
                    cursor.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException failure) {
            rollbackQuietly();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void updateStateVersionOnly(String taskId, long expectedVersion) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE task_state SET version=version+1,updated_at=? WHERE task_id=? AND version=?")) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, taskId);
            statement.setLong(3, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Task version changed: " + taskId);
            }
        }
    }

    private void updateState(String taskId, TaskState target, String cursor, boolean pause, long expectedVersion)
            throws SQLException, RuntimeException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE task_state SET state=?,cursor_operation_id=?,pause_requested=?,version=version+1,updated_at=? "
                        + "WHERE task_id=? AND version=?")) {
            statement.setString(1, target.name());
            statement.setString(2, cursor);
            statement.setInt(3, pause ? 1 : 0);
            statement.setString(4, Instant.now().toString());
            statement.setString(5, taskId);
            statement.setLong(6, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new RuntimeException("E_CONCURRENT_TASK", "Task version changed: " + taskId);
            }
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original failure.
        }
    }
}
