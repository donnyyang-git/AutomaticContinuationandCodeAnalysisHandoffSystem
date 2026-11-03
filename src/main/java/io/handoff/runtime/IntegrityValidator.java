package io.handoff.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IntegrityValidator {
    private static final String ZERO_HASH = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final Pattern JSON_STRING = Pattern.compile("\\\"([^\\\"]+)\\\"");

    private final Connection connection;

    public IntegrityValidator(Connection connection) {
        this.connection = connection;
    }

    public void validate(String taskId) throws SQLException, IntegrityFailure {
        try {
            String snapshotId = snapshotId(taskId);
            validateEvents(taskId);
            Checkpoint checkpoint = latestCheckpoint(taskId);
            if (checkpoint == null) {
                if (eventCount(taskId) == 0) return;
                throw new IntegrityFailure("Missing latest checkpoint");
            }
            String expectedCheckpointHash = sha256(checkpoint.taskStateJson() + checkpoint.verifiedFactsJson()
                    + checkpoint.pendingStepsJson() + checkpoint.evidenceRefsJson() + checkpoint.cursorOperationId()
                    + snapshotId + checkpoint.parserVersion() + checkpoint.promptVersion()
                    + checkpoint.analysisSchemaVersion() + checkpoint.checkpointSchemaVersion());
            if (!expectedCheckpointHash.equals(checkpoint.contentSha256())) {
                throw new IntegrityFailure("Checkpoint hash mismatch at sequence " + checkpoint.sequence());
            }
            validateEvidenceReferences(taskId, snapshotId, checkpoint.evidenceRefsJson());
        } catch (IntegrityFailure failure) {
            recover(taskId, failure.getMessage());
            throw failure;
        }
    }

    private long eventCount(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM execution_event WHERE task_id=?")) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void validateEvents(String taskId) throws SQLException, IntegrityFailure {
        long expectedSequence = 1;
        String previousHash = ZERO_HASH;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sequence,event_type,occurred_at,payload_json,payload_sha256,prev_hash,event_hash "
                        + "FROM execution_event WHERE task_id=? ORDER BY sequence")) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long sequence = result.getLong(1);
                    String eventType = result.getString(2);
                    String occurredAt = result.getString(3);
                    String payload = result.getString(4);
                    String payloadHash = result.getString(5);
                    String previous = result.getString(6);
                    String eventHash = result.getString(7);
                    if (sequence != expectedSequence || !previousHash.equals(previous)) {
                        throw new IntegrityFailure("Event sequence or predecessor mismatch at sequence " + sequence);
                    }
                    if (!sha256(payload).equals(payloadHash)) {
                        throw new IntegrityFailure("Event payload hash mismatch at sequence " + sequence);
                    }
                    String expectedHash = sha256(taskId + "\n" + sequence + "\n" + eventType + "\n" + occurredAt
                            + "\n" + payloadHash + "\n" + previousHash);
                    if (!expectedHash.equals(eventHash)) {
                        throw new IntegrityFailure("Event hash mismatch at sequence " + sequence);
                    }
                    previousHash = eventHash;
                    expectedSequence++;
                }
            }
        }
    }

    private Checkpoint latestCheckpoint(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sequence,task_state_json,verified_facts_json,pending_steps_json,evidence_refs_json,"
                        + "cursor_operation_id,snapshot_id,parser_version,prompt_version,analysis_schema_version,"
                        + "checkpoint_schema_version,content_sha256 FROM checkpoint WHERE task_id=? ORDER BY sequence DESC LIMIT 1")) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new Checkpoint(result.getLong(1), result.getString(2), result.getString(3), result.getString(4),
                        result.getString(5), result.getString(6), result.getString(7), result.getString(8),
                        result.getString(9), result.getString(10), result.getString(11), result.getString(12));
            }
        }
    }

    private void validateEvidenceReferences(String taskId, String snapshotId, String refs)
            throws SQLException, IntegrityFailure {
        List<String> references = new ArrayList<>();
        Matcher matcher = JSON_STRING.matcher(refs);
        while (matcher.find()) references.add(matcher.group(1));
        for (String evidenceId : references) {
            if (evidenceId.equals("evidence_refs_json")) continue;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM evidence WHERE evidence_id=? AND task_id=? AND snapshot_id=? AND status='VALID'")) {
                statement.setString(1, evidenceId);
                statement.setString(2, taskId);
                statement.setString(3, snapshotId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    if (result.getInt(1) != 1) {
                        throw new IntegrityFailure("Missing or invalid evidence reference: " + evidenceId);
                    }
                }
            }
        }
    }

    private String snapshotId(String taskId) throws SQLException, IntegrityFailure {
        try (PreparedStatement statement = connection.prepareStatement("SELECT snapshot_id FROM task_state WHERE task_id=?")) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IntegrityFailure("Unknown task: " + taskId);
                return result.getString(1);
            }
        }
    }

    private void recover(String taskId, String reason) throws SQLException {
        connection.setAutoCommit(false);
        try {
            String originalState;
            try (PreparedStatement statement = connection.prepareStatement("SELECT state FROM task_state WHERE task_id=?")) {
                statement.setString(1, taskId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        connection.rollback();
                        return;
                    }
                    originalState = result.getString(1);
                }
            }
            String now = Instant.now().toString();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE task_state SET state='REQUIRES_REVIEW',version=version+1,updated_at=? WHERE task_id=?")) {
                statement.setString(1, now);
                statement.setString(2, taskId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO review(review_id,task_id,reason_code,status,original_state,created_at) VALUES(?,?,?,'OPEN',?,?)")) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, taskId);
                statement.setString(3, "INTEGRITY_FAILURE:" + reason);
                statement.setString(4, originalState);
                statement.setString(5, now);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException failure) {
            try { connection.rollback(); } catch (SQLException ignored) { }
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(64);
            for (byte valueByte : digest) output.append(String.format("%02x", valueByte));
            return output.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private record Checkpoint(long sequence, String taskStateJson, String verifiedFactsJson, String pendingStepsJson,
            String evidenceRefsJson, String cursorOperationId, String snapshotId, String parserVersion,
            String promptVersion, String analysisSchemaVersion, String checkpointSchemaVersion, String contentSha256) {}
}
