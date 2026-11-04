package io.handoff.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.handoff.analysis.AnalysisProviderException;
import io.handoff.analysis.ClaimValidator;
import io.handoff.analysis.OpenAiCompatibleAnalysisProvider;
import io.handoff.evidence.EvidenceManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class AtomicWorkCoordinator {
    private static final String ZERO_HASH = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String PARSER_VERSION = "fixture-parser-1.0.0";
    private static final String PROMPT_VERSION = "fixture-prompt-1.0.0";
    private static final String ANALYSIS_SCHEMA_VERSION = "1.0.0";
    private static final String CHECKPOINT_SCHEMA_VERSION = "1.0.0";
    private static final ObjectMapper JSON = new ObjectMapper();

    public record CommitResult(String operationId, String resultId, String evidenceId, long eventSequence) {}

    public record AnalysisCommitResult(
            List<ClaimValidator.ValidatedClaim> claims, long eventSequence, boolean reviewRequired) {
        public AnalysisCommitResult {
            claims = List.copyOf(claims);
        }
    }

    private final Connection connection;

    public AtomicWorkCoordinator(Connection connection) {
        this.connection = connection;
    }

    public CommitResult commit(String taskId, String operationId, FailureInjection injection)
            throws SQLException, InjectedFailureException, RuntimeException {
        connection.setAutoCommit(false);
        try {
            ExistingCommit existing = existingCommit(operationId);
            if (existing != null) {
                connection.rollback();
                return new CommitResult(operationId, existing.resultId(), existing.evidenceId(), existing.eventSequence());
            }
            WorkItem item = pendingWorkItem(taskId, operationId);
            if (item == null) {
                throw new RuntimeException("E_PRECONDITION", "Unknown or non-pending operation: " + operationId);
            }
            if (injection == FailureInjection.PROCESS_KILL_BEFORE_COMMIT) {
                throw new InjectedFailureException(injection);
            }

            String snapshotId = snapshotId(taskId);
            String evidenceId = stableId("evidence:" + operationId);
            String resultId = stableId("result:" + operationId);
            String logicalClaimId = "claim:" + operationId;
            String now = Instant.now().toString();
            String excerpt = "fixture evidence for " + item.kind() + ":" + item.subject();
            String contentSha = sha256(excerpt);
            insertEvidence(evidenceId, taskId, snapshotId, item, contentSha, excerpt, now);
            insertResult(resultId, logicalClaimId, taskId, snapshotId, operationId, item, now);
            insertResultEvidence(resultId, evidenceId);

            if (injection == FailureInjection.PROCESS_KILL_AFTER_RESULT) {
                throw new InjectedFailureException(injection);
            }

            long sequence = nextEventSequence(taskId);
            String payload = "{\"operation_id\":\"" + operationId + "\",\"result_id\":\"" + resultId + "\"}";
            String payloadSha = sha256(payload);
            String previousHash = previousEventHash(taskId);
            String eventHash = sha256(taskId + "\n" + sequence + "\nWORK_COMMITTED\n" + now + "\n"
                    + payloadSha + "\n" + previousHash);
            insertEvent(taskId, operationId, sequence, now, payload, payloadSha, previousHash, eventHash);

            String taskStateJson = "{\"cursor_operation_id\":\"" + operationId + "\",\"state\":\"ANALYZING\"}";
            String verifiedFactsJson = "{\"operation_id\":\"" + operationId + "\",\"result_id\":\"" + resultId + "\"}";
            String pendingStepsJson = "[]";
            String evidenceRefsJson = "[\"" + evidenceId + "\"]";
            String checkpointContent = taskStateJson + verifiedFactsJson + pendingStepsJson + evidenceRefsJson
                    + operationId + snapshotId + PARSER_VERSION + PROMPT_VERSION + ANALYSIS_SCHEMA_VERSION
                    + CHECKPOINT_SCHEMA_VERSION;
            insertCheckpoint(taskId, sequence, taskStateJson, verifiedFactsJson, pendingStepsJson, evidenceRefsJson,
                    operationId, snapshotId, sha256(checkpointContent), now);
            markCommitted(taskId, operationId, now);
            connection.commit();
            return new CommitResult(operationId, resultId, evidenceId, sequence);
        } catch (SQLException | InjectedFailureException | RuntimeException failure) {
            rollbackQuietly();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public AnalysisCommitResult commitAnalysisWorkItem(
            String taskId,
            String operationId,
            FailureInjection injection,
            List<EvidenceManager.Evidence> preparedEvidence,
            Path sourceRoot,
            OpenAiCompatibleAnalysisProvider.Invocation invocation,
            String analysisSchema,
            List<ClaimValidator.ParserFact> parserFacts)
            throws SQLException, InjectedFailureException, RuntimeException, AnalysisProviderException {
        connection.setAutoCommit(false);
        try {
            AnalysisCommitResult existing = existingAnalysisCommit(taskId, operationId);
            if (existing != null) {
                connection.rollback();
                return existing;
            }
            WorkItem item = pendingWorkItem(taskId, operationId);
            if (item == null) {
                throw new RuntimeException("E_PRECONDITION", "Unknown or non-pending operation: " + operationId);
            }
            if (injection == FailureInjection.PROCESS_KILL_BEFORE_COMMIT) {
                throw new InjectedFailureException(injection);
            }

            String snapshotId = snapshotId(taskId);
            EvidenceManager evidenceManager = new EvidenceManager(connection, sourceRoot);
            for (EvidenceManager.Evidence evidence : preparedEvidence) {
                evidenceManager.persistPreparedEvidence(evidence);
            }
            ClaimValidator.Result validated = new ClaimValidator(connection, evidenceManager, analysisSchema)
                    .persist(taskId, operationId, invocation, parserFacts);
            if (injection == FailureInjection.PROCESS_KILL_AFTER_RESULT) {
                throw new InjectedFailureException(injection);
            }

            String now = Instant.now().toString();
            long sequence = nextEventSequence(taskId);
            String payload = analysisEventPayload(operationId, validated.claims());
            String payloadSha = sha256(payload);
            String previousHash = previousEventHash(taskId);
            String eventHash = sha256(taskId + "\n" + sequence + "\nWORK_COMMITTED\n" + now + "\n"
                    + payloadSha + "\n" + previousHash);
            insertEvent(taskId, operationId, sequence, now, payload, payloadSha, previousHash, eventHash);

            List<String> evidenceIds = new ArrayList<>();
            for (ClaimValidator.ValidatedClaim claim : validated.claims()) {
                evidenceIds.addAll(claim.evidenceRefs());
            }
                String evidenceRefsJson = serializeRuntimeJson(evidenceIds.stream().distinct().sorted().toList());
            String state = validated.reviewRequired() ? TaskState.REQUIRES_REVIEW.name() : TaskState.ANALYZING.name();
                Map<String, Object> taskState = new TreeMap<>();
                taskState.put("cursor_operation_id", operationId);
                taskState.put("state", state);
                String taskStateJson = serializeRuntimeJson(taskState);
                Map<String, Object> verifiedFacts = new TreeMap<>();
                verifiedFacts.put("operation_id", operationId);
                verifiedFacts.put("result_ids", validated.claims().stream()
                    .map(ClaimValidator.ValidatedClaim::resultId).sorted().toList());
                String verifiedFactsJson = serializeRuntimeJson(verifiedFacts);
            String pendingStepsJson = "[]";
            String promptVersion = invocation.promptVersion();
            String checkpointContent = taskStateJson + verifiedFactsJson + pendingStepsJson + evidenceRefsJson
                    + operationId + snapshotId + PARSER_VERSION + promptVersion + ANALYSIS_SCHEMA_VERSION
                    + CHECKPOINT_SCHEMA_VERSION;
            insertCheckpoint(taskId, sequence, taskStateJson, verifiedFactsJson, pendingStepsJson,
                    evidenceRefsJson, operationId, snapshotId, sha256(checkpointContent), now,
                    PARSER_VERSION, promptVersion, ANALYSIS_SCHEMA_VERSION, CHECKPOINT_SCHEMA_VERSION);
            markCommitted(taskId, operationId, now);
            connection.commit();
            return new AnalysisCommitResult(validated.claims(), sequence, validated.reviewRequired());
        } catch (SQLException | InjectedFailureException | RuntimeException | AnalysisProviderException failure) {
            rollbackQuietly();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private ExistingCommit existingCommit(String operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT r.result_id,e.evidence_id,ev.sequence FROM analysis_result r "
                        + "JOIN analysis_result_evidence re ON re.result_id=r.result_id "
                        + "JOIN evidence e ON e.evidence_id=re.evidence_id "
                        + "JOIN execution_event ev ON ev.operation_id=r.operation_id AND ev.event_type='WORK_COMMITTED' "
                        + "WHERE r.operation_id=? AND r.revision=1")) {
            statement.setString(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new ExistingCommit(result.getString(1), result.getString(2), result.getLong(3)) : null;
            }
        }
    }

    private AnalysisCommitResult existingAnalysisCommit(String taskId, String operationId) throws SQLException {
        long sequence;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sequence FROM execution_event WHERE task_id=? AND operation_id=? AND event_type='WORK_COMMITTED'")) {
            statement.setString(1, taskId);
            statement.setString(2, operationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                sequence = result.getLong(1);
            }
        }

        List<ClaimValidator.ValidatedClaim> claims = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT result_id,logical_claim_id,revision,claim_type,subject,claim_text,confidence,status,inference_reason "
                        + "FROM analysis_result WHERE task_id=? AND operation_id=? ORDER BY logical_claim_id,revision")) {
            statement.setString(1, taskId);
            statement.setString(2, operationId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String resultId = result.getString(1);
                    claims.add(new ClaimValidator.ValidatedClaim(
                            resultId,
                            result.getString(2),
                            result.getInt(3),
                            result.getString(4),
                            result.getString(5),
                            result.getString(6),
                            result.getString(7),
                            result.getString(8),
                            result.getString(9),
                            resultEvidenceRefs(resultId)));
                }
            }
        }
        boolean reviewRequired;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state='REQUIRES_REVIEW' FROM task_state WHERE task_id=?")) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                reviewRequired = result.next() && result.getBoolean(1);
            }
        }
        return new AnalysisCommitResult(claims, sequence, reviewRequired);
    }

    private List<String> resultEvidenceRefs(String resultId) throws SQLException {
        List<String> evidenceIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT evidence_id FROM analysis_result_evidence WHERE result_id=? ORDER BY evidence_id")) {
            statement.setString(1, resultId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    evidenceIds.add(result.getString(1));
                }
            }
        }
        return List.copyOf(evidenceIds);
    }

    private static String analysisEventPayload(
            String operationId, List<ClaimValidator.ValidatedClaim> claims) throws SQLException {
        List<Map<String, Object>> eventClaims = claims.stream()
                .sorted(Comparator.comparing(ClaimValidator.ValidatedClaim::resultId))
                .map(claim -> {
                    Map<String, Object> values = new TreeMap<>();
                    values.put("logical_claim_id", claim.logicalClaimId());
                    values.put("result_id", claim.resultId());
                    values.put("revision", claim.revision());
                    values.put("status", claim.status());
                    return values;
                })
                .toList();
        Map<String, Object> payload = new TreeMap<>();
        payload.put("claims", eventClaims);
        payload.put("operation_id", operationId);
        try {
            return JSON.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new SQLException("Unable to serialize analysis event", failure);
        }
    }

    private static String serializeRuntimeJson(Object value) throws SQLException {
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new SQLException("Unable to serialize Runtime checkpoint data", failure);
        }
    }

    private WorkItem pendingWorkItem(String taskId, String operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT operation_id,task_id,kind,subject,status,attempt_count,input_sha256 FROM work_item "
                        + "WHERE task_id=? AND operation_id=? AND status IN ('PENDING','RUNNING')")) {
            statement.setString(1, taskId);
            statement.setString(2, operationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new WorkItem(result.getString(1), result.getString(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getInt(6), result.getString(7)) : null;
            }
        }
    }

    private String snapshotId(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT snapshot_id FROM task_state WHERE task_id=?")) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Unknown task: " + taskId);
                return result.getString(1);
            }
        }
    }

    private void insertEvidence(String evidenceId, String taskId, String snapshotId, WorkItem item, String contentSha,
            String excerpt, String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO evidence(evidence_id,task_id,snapshot_id,kind,path,start_line,end_line,content_sha256,redacted_excerpt,status,created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,'VALID',?)")) {
            statement.setString(1, evidenceId);
            statement.setString(2, taskId);
            statement.setString(3, snapshotId);
            statement.setString(4, "FIXTURE");
            statement.setString(5, item.subject());
            statement.setInt(6, 1);
            statement.setInt(7, 1);
            statement.setString(8, contentSha);
            statement.setString(9, excerpt);
            statement.setString(10, now);
            statement.executeUpdate();
        }
    }

    private void insertResult(String resultId, String logicalClaimId, String taskId, String snapshotId,
            String operationId, WorkItem item, String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO analysis_result(result_id,logical_claim_id,task_id,snapshot_id,operation_id,revision,claim_type,subject,claim_text,confidence,status,inference_reason,model_tag,prompt_version,schema_version,created_at) "
                        + "VALUES(?,?,?,?,?,1,?,?,?,'HIGH','CONFIRMED',NULL,NULL,?,?,?)")) {
            statement.setString(1, resultId);
            statement.setString(2, logicalClaimId);
            statement.setString(3, taskId);
            statement.setString(4, snapshotId);
            statement.setString(5, operationId);
            statement.setString(6, item.kind());
            statement.setString(7, item.subject());
            statement.setString(8, "Fixture confirms " + item.kind() + ":" + item.subject());
            statement.setString(9, PROMPT_VERSION);
            statement.setString(10, ANALYSIS_SCHEMA_VERSION);
            statement.setString(11, now);
            statement.executeUpdate();
        }
    }

    private void insertResultEvidence(String resultId, String evidenceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO analysis_result_evidence(result_id,evidence_id) VALUES(?,?)")) {
            statement.setString(1, resultId);
            statement.setString(2, evidenceId);
            statement.executeUpdate();
        }
    }

    private long nextEventSequence(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(sequence),0)+1 FROM execution_event WHERE task_id=?")) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private String previousEventHash(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT event_hash FROM execution_event WHERE task_id=? ORDER BY sequence DESC LIMIT 1")) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : ZERO_HASH;
            }
        }
    }

    private void insertEvent(String taskId, String operationId, long sequence, String now, String payload,
            String payloadSha, String previousHash, String eventHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO execution_event(event_id,task_id,operation_id,sequence,event_type,occurred_at,payload_json,payload_sha256,prev_hash,event_hash) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, stableId("event:" + taskId + ":" + sequence));
            statement.setString(2, taskId);
            statement.setString(3, operationId);
            statement.setLong(4, sequence);
            statement.setString(5, "WORK_COMMITTED");
            statement.setString(6, now);
            statement.setString(7, payload);
            statement.setString(8, payloadSha);
            statement.setString(9, previousHash);
            statement.setString(10, eventHash);
            statement.executeUpdate();
        }
    }

    private void insertCheckpoint(String taskId, long sequence, String taskStateJson, String verifiedFactsJson,
            String pendingStepsJson, String evidenceRefsJson, String cursor, String snapshotId, String contentSha,
            String now) throws SQLException {
        insertCheckpoint(taskId, sequence, taskStateJson, verifiedFactsJson, pendingStepsJson,
            evidenceRefsJson, cursor, snapshotId, contentSha, now,
            PARSER_VERSION, PROMPT_VERSION, ANALYSIS_SCHEMA_VERSION, CHECKPOINT_SCHEMA_VERSION);
        }

        private void insertCheckpoint(String taskId, long sequence, String taskStateJson, String verifiedFactsJson,
            String pendingStepsJson, String evidenceRefsJson, String cursor, String snapshotId, String contentSha,
            String now, String parserVersion, String promptVersion, String analysisSchemaVersion,
            String checkpointSchemaVersion) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO checkpoint(checkpoint_id,task_id,sequence,task_state_json,verified_facts_json,pending_steps_json,evidence_refs_json,cursor_operation_id,snapshot_id,parser_version,prompt_version,analysis_schema_version,checkpoint_schema_version,content_sha256,created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, stableId("checkpoint:" + taskId + ":" + sequence));
            statement.setString(2, taskId);
            statement.setLong(3, sequence);
            statement.setString(4, taskStateJson);
            statement.setString(5, verifiedFactsJson);
            statement.setString(6, pendingStepsJson);
            statement.setString(7, evidenceRefsJson);
            statement.setString(8, cursor);
            statement.setString(9, snapshotId);
            statement.setString(10, parserVersion);
            statement.setString(11, promptVersion);
            statement.setString(12, analysisSchemaVersion);
            statement.setString(13, checkpointSchemaVersion);
            statement.setString(14, contentSha);
            statement.setString(15, now);
            statement.executeUpdate();
        }
    }

    private void markCommitted(String taskId, String operationId, String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE work_item SET status='COMMITTED',committed_at=? WHERE task_id=? AND operation_id=? AND status IN ('PENDING','RUNNING')")) {
            statement.setString(1, now);
            statement.setString(2, taskId);
            statement.setString(3, operationId);
            if (statement.executeUpdate() != 1) throw new SQLException("Operation is no longer writable: " + operationId);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE task_state SET cursor_operation_id=?,version=version+1,updated_at=? WHERE task_id=?")) {
            statement.setString(1, operationId);
            statement.setString(2, now);
            statement.setString(3, taskId);
            statement.executeUpdate();
        }
    }

    private static String stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
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

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original transaction failure.
        }
    }

    private record ExistingCommit(String resultId, String evidenceId, long eventSequence) {}
}
