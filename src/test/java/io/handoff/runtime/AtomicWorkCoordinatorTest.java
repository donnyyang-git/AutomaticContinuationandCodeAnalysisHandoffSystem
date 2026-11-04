package io.handoff.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.handoff.analysis.AnalysisProviderException;
import io.handoff.analysis.AnalysisPromptBuilder;
import io.handoff.analysis.ClaimValidator;
import io.handoff.analysis.OpenAiCompatibleAnalysisProvider;
import io.handoff.evidence.EvidenceManager;
import io.handoff.persistence.ConnectionFactory;
import io.handoff.persistence.SchemaBootstrapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicWorkCoordinatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
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

    @Test
    void validatedAnalysisCommitsAllArtifactsAndReusesCommittedOperation() throws Exception {
        try (AnalysisFixture fixture = openAnalysis("validated.db")) {
            AtomicWorkCoordinator coordinator = new AtomicWorkCoordinator(fixture.connection());
            String schema = Files.readString(Path.of("specs/contracts/schemas/analysis.schema.json"));

            AtomicWorkCoordinator.AnalysisCommitResult first = coordinator.commitAnalysisWorkItem(
                    TASK, OPERATION, FailureInjection.NONE, List.of(fixture.evidence()), fixture.sourceRoot(),
                    invocation(fixture.evidence().evidenceId()), schema, List.of());
            AtomicWorkCoordinator.AnalysisCommitResult repeated = coordinator.commitAnalysisWorkItem(
                    TASK, OPERATION, FailureInjection.NONE, List.of(fixture.evidence()), fixture.sourceRoot(),
                    invocation(fixture.evidence().evidenceId()), schema, List.of());
                new IntegrityValidator(fixture.connection()).validate(TASK);

            assertEquals(1, count(fixture.connection(), "SELECT COUNT(*) FROM evidence"));
            assertEquals(1, count(fixture.connection(), "SELECT COUNT(*) FROM analysis_result"));
            assertEquals(1, count(fixture.connection(), "SELECT COUNT(*) FROM analysis_result_evidence"));
            assertEquals(1, count(fixture.connection(), "SELECT COUNT(*) FROM model_invocation"));
            assertEquals(1, count(fixture.connection(), "SELECT COUNT(*) FROM execution_event"));
            assertEquals(1, count(fixture.connection(), "SELECT COUNT(*) FROM checkpoint"));
            assertEquals(1, count(fixture.connection(), "SELECT COUNT(*) FROM work_item WHERE status='COMMITTED'"));
            assertEquals(first.claims().get(0).resultId(), repeated.claims().get(0).resultId());
            assertEquals(first.eventSequence(), repeated.eventSequence());
        }
    }

    @Test
    void failureAfterValidatedResultsRollsBackEvidenceInvocationAndRuntimeArtifacts() throws Exception {
        try (AnalysisFixture fixture = openAnalysis("validated-failure.db")) {
            AtomicWorkCoordinator coordinator = new AtomicWorkCoordinator(fixture.connection());
            String schema = Files.readString(Path.of("specs/contracts/schemas/analysis.schema.json"));

            assertThrows(InjectedFailureException.class,
                    () -> coordinator.commitAnalysisWorkItem(
                            TASK, OPERATION, FailureInjection.PROCESS_KILL_AFTER_RESULT,
                            List.of(fixture.evidence()), fixture.sourceRoot(),
                            invocation(fixture.evidence().evidenceId()), schema, List.of()));

            assertEquals(1, count(fixture.connection(), "SELECT COUNT(*) FROM work_item WHERE status='PENDING'"));
            assertEquals(0, count(fixture.connection(), "SELECT COUNT(*) FROM evidence"));
            assertEquals(0, count(fixture.connection(), "SELECT COUNT(*) FROM analysis_result"));
            assertEquals(0, count(fixture.connection(), "SELECT COUNT(*) FROM model_invocation"));
            assertEquals(0, count(fixture.connection(), "SELECT COUNT(*) FROM execution_event"));
            assertEquals(0, count(fixture.connection(), "SELECT COUNT(*) FROM checkpoint"));
        }
    }

    private AnalysisFixture openAnalysis(String databaseName) throws Exception {
        Connection connection = ConnectionFactory.open(tempDirectory.resolve(databaseName));
        SchemaBootstrapper.bootstrap(connection);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO source_snapshot(snapshot_id,project_name,manifest_sha256,ignore_rules_json,created_at) "
                        + "VALUES(?,?,?,?,?)")) {
            statement.setString(1, SNAPSHOT);
            statement.setString(2, "fixture");
            statement.setString(3, SHA);
            statement.setString(4, "{}");
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        }
        Path sourceRoot = tempDirectory.resolve(databaseName + "-source");
        Path sourceFile = sourceRoot.resolve("src/Account.java");
        Files.createDirectories(sourceFile.getParent());
        byte[] sourceBytes = "class Account {}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(sourceFile, sourceBytes);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO snapshot_file(snapshot_id,path,size_bytes,sha256) VALUES(?,?,?,?)")) {
            statement.setString(1, SNAPSHOT);
            statement.setString(2, "src/Account.java");
            statement.setLong(3, sourceBytes.length);
            statement.setString(4, sha256(sourceBytes));
            statement.executeUpdate();
        }
        TaskRuntime runtime = new TaskRuntime(connection);
        runtime.createTask(TASK, SNAPSHOT, "analysis");
        runtime.transition(TASK, TaskState.INDEXING, runtime.task(TASK).version());
        runtime.transition(TASK, TaskState.ANALYZING, runtime.task(TASK).version());
        runtime.addWorkItem(OPERATION, TASK, "ANALYSIS", "Account", SHA);
        EvidenceManager.Evidence evidence = new EvidenceManager(connection, sourceRoot)
                .prepareEvidence(TASK, "SOURCE_RANGE", "src/Account.java", 1, 1);
        return new AnalysisFixture(connection, sourceRoot, evidence);
    }

    private static OpenAiCompatibleAnalysisProvider.Invocation invocation(String evidenceId) throws Exception {
        var claim = JSON.createObjectNode();
        claim.put("logical_claim_id", "018f0a00-0000-7000-8000-000000000203");
        claim.put("claim_type", "ARCHITECTURE");
        claim.put("subject", "Account");
        claim.put("claim", "Account models account data.");
        claim.put("confidence", "HIGH");
        claim.put("status", "CONFIRMED");
        claim.putArray("evidence_refs").add(evidenceId);
        claim.put("reasoning", "The source declaration supports this claim.");
        var response = JSON.createObjectNode();
        response.putArray("claims").add(claim);
        response.putArray("unknowns");
        response.putArray("next_steps");
        String rawOutput = JSON.writeValueAsString(response);
        return new OpenAiCompatibleAnalysisProvider.Invocation(
                "lm-studio", "http://127.0.0.1:1234/v1", "openai-compatible", "model-a", null,
                "UNPINNED_MODEL", "claim-v1.0.0", "{\"seed\":42,\"stream\":false,\"temperature\":0,\"top_p\":1}",
                SHA, SHA, rawOutput, sha256(rawOutput.getBytes(StandardCharsets.UTF_8)), response,
                "2026-11-04T00:00:00.000Z", 0, "{}");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
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

    private record AnalysisFixture(Connection connection, Path sourceRoot, EvidenceManager.Evidence evidence)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            connection.close();
        }
    }
}
