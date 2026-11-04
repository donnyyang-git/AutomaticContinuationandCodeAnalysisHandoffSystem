package io.handoff.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.handoff.evidence.EvidenceManager;
import io.handoff.persistence.ConnectionFactory;
import io.handoff.persistence.SchemaBootstrapper;
import io.handoff.runtime.TaskRuntime;
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

class ClaimValidatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TASK = "018f0a00-0000-7000-8000-000000000701";
    private static final String SNAPSHOT = "claim-snapshot";
    private static final String OPERATION = "018f0a00-0000-7000-8000-000000000702";
    private static final String CLAIM_ID = "018f0a00-0000-7000-8000-000000000703";
    private static final String FILE_PATH = "src/Account.java";
    private static final String SOURCE = "class Account {}\n";

    @TempDir
    Path tempDirectory;

    @Test
    void persistsValidatedClaimAndInvocationWithinCallerTransaction() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.connection().setAutoCommit(false);
            ClaimValidator.Result result = validator(fixture).persist(
                    TASK, OPERATION, invocation(response("CONFIRMED", List.of(fixture.evidenceId()),
                        "Account exposes api_key=local-secret.")), List.of());

            assertEquals("CONFIRMED", result.claims().get(0).status());
            assertEquals(1, count(fixture.connection(), "SELECT COUNT(*) FROM analysis_result"));
            assertEquals(1, count(fixture.connection(), "SELECT COUNT(*) FROM analysis_result_evidence"));
            assertEquals(1, count(fixture.connection(), "SELECT COUNT(*) FROM model_invocation"));
            assertEquals(0, count(fixture.connection(), "SELECT COUNT(*) FROM review WHERE status='OPEN'"));
                String storedClaim = scalar(fixture.connection(),
                    "SELECT claim_text FROM analysis_result WHERE logical_claim_id=?", CLAIM_ID);
                String storedOutput = scalar(fixture.connection(),
                    "SELECT raw_output FROM model_invocation WHERE operation_id=?", OPERATION);
                String storedOutputHash = scalar(fixture.connection(),
                    "SELECT raw_output_sha256 FROM model_invocation WHERE operation_id=?", OPERATION);
                assertFalse(storedClaim.contains("local-secret"));
                assertFalse(storedOutput.contains("local-secret"));
                assertEquals(sha256(storedOutput.getBytes(StandardCharsets.UTF_8)), storedOutputHash);

            fixture.connection().rollback();
            assertEquals(0, count(fixture.connection(), "SELECT COUNT(*) FROM analysis_result"));
            assertEquals(0, count(fixture.connection(), "SELECT COUNT(*) FROM model_invocation"));
        }
    }

    @Test
    void downgradesConfirmedClaimWithoutEvidenceAndOpensReview() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.connection().setAutoCommit(false);
            ClaimValidator.Result result = validator(fixture).persist(
                    TASK, OPERATION, invocation(response("CONFIRMED", List.of(), "Unsupported claim.")), List.of());

            assertEquals("UNSUPPORTED", result.claims().get(0).status());
            assertEquals(1, count(fixture.connection(), "SELECT COUNT(*) FROM review WHERE status='OPEN'"));
            assertEquals("REQUIRES_REVIEW", taskState(fixture.connection()));
            fixture.connection().commit();
        }
    }

    @Test
    void persistsInferredClaimWithReasonAndEvidence() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.connection().setAutoCommit(false);
            ClaimValidator.Result result = validator(fixture).persist(
                    TASK, OPERATION, invocation(response("INFERRED", List.of(fixture.evidenceId()),
                            "Account likely stores profile data.")), List.of());

            assertEquals("INFERRED", result.claims().get(0).status());
            assertEquals("Based on the supplied source evidence.", result.claims().get(0).reasoning());
            assertEquals(1, count(fixture.connection(), "SELECT COUNT(*) FROM analysis_result_evidence"));
            fixture.connection().commit();
        }
    }

    @Test
    void rejectsInferredClaimWithoutExplicitReason() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.connection().setAutoCommit(false);
            JsonNode parsed = response("INFERRED", List.of(fixture.evidenceId()), "Account stores profile data.");
            ((ObjectNode) parsed.path("claims").get(0)).put("reasoning", "");
            AnalysisProviderException failure = assertThrows(AnalysisProviderException.class,
                    () -> validator(fixture).persist(TASK, OPERATION, invocation(parsed), List.of()));

            assertEquals("E_SCHEMA_INVALID", failure.code());
            assertEquals(0, count(fixture.connection(), "SELECT COUNT(*) FROM analysis_result"));
            fixture.connection().rollback();
        }
    }

    @Test
    void materiallyDifferentRetryAppendsConflictingRevisionAndOpensReview() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.connection().setAutoCommit(false);
            ClaimValidator claimValidator = validator(fixture);
            claimValidator.persist(TASK, OPERATION,
                    invocation(response("CONFIRMED", List.of(fixture.evidenceId()), "Account models profile data.")),
                    List.of());
            ClaimValidator.Result retry = claimValidator.persist(TASK, OPERATION,
                    invocation(response("CONFIRMED", List.of(fixture.evidenceId()), "Account stores credentials.")),
                    List.of());

            assertEquals(2, retry.claims().get(0).revision());
            assertEquals("CONFLICTING", retry.claims().get(0).status());
            assertEquals(2, count(fixture.connection(), "SELECT COUNT(*) FROM analysis_result"));
            assertEquals(2, count(fixture.connection(), "SELECT COUNT(*) FROM model_invocation"));
            assertEquals(1, count(fixture.connection(), "SELECT COUNT(*) FROM review WHERE status='OPEN'"));
            fixture.connection().commit();
        }
    }

    @Test
    void rejectsEvidenceKindNotAllowedForClaimType() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.connection().setAutoCommit(false);
            EvidenceManager.Evidence viewEvidence = new EvidenceManager(fixture.connection(), fixture.sourceRoot())
                    .createEvidence(TASK, "VIEW_ENTRY", FILE_PATH, 1, 1);
            AnalysisProviderException failure = assertThrows(AnalysisProviderException.class,
                    () -> validator(fixture).persist(
                            TASK, OPERATION,
                            invocation(response("CONFIRMED", List.of(viewEvidence.evidenceId()),
                                    "Account defines the system architecture.")),
                            List.of()));

            assertEquals("E_EVIDENCE_INVALID", failure.code());
            assertEquals(0, count(fixture.connection(), "SELECT COUNT(*) FROM analysis_result"));
            fixture.connection().rollback();
        }
    }

    @Test
    void rejectsMalformedResponseWithoutWritingResults() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.connection().setAutoCommit(false);
            JsonNode malformed = JSON.readTree("{\"claims\":[],\"unknowns\":[]}");
            AnalysisProviderException failure = assertThrows(AnalysisProviderException.class,
                    () -> validator(fixture).persist(TASK, OPERATION, invocation(malformed), List.of()));

            assertEquals("E_SCHEMA_INVALID", failure.code());
            assertEquals(0, count(fixture.connection(), "SELECT COUNT(*) FROM analysis_result"));
            assertEquals(0, count(fixture.connection(), "SELECT COUNT(*) FROM model_invocation"));
            fixture.connection().rollback();
        }
    }

    @Test
    void rejectsDuplicateLogicalClaimIdsBeforeWritingAnyRows() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.connection().setAutoCommit(false);
            JsonNode parsed = response("CONFIRMED", List.of(fixture.evidenceId()), "Account claim.");
            ((ArrayNode) parsed.path("claims")).add(parsed.path("claims").get(0).deepCopy());
            AnalysisProviderException failure = assertThrows(AnalysisProviderException.class,
                    () -> validator(fixture).persist(TASK, OPERATION, invocation(parsed), List.of()));

            assertEquals("E_SCHEMA_INVALID", failure.code());
            assertEquals(0, count(fixture.connection(), "SELECT COUNT(*) FROM analysis_result"));
            assertEquals(0, count(fixture.connection(), "SELECT COUNT(*) FROM model_invocation"));
            fixture.connection().rollback();
        }
    }

    @Test
    void rejectsEvidenceFromAnotherTaskSnapshotWithoutWritingResults() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.connection().setAutoCommit(false);
            String otherEvidenceId = createEvidenceForOtherSnapshot(fixture);
            JsonNode claim = response("CONFIRMED", List.of(otherEvidenceId), "Account claim.");
            AnalysisProviderException failure = assertThrows(AnalysisProviderException.class,
                    () -> validator(fixture).persist(TASK, OPERATION, invocation(claim), List.of()));

            assertEquals("E_EVIDENCE_INVALID", failure.code());
            assertEquals(0, count(fixture.connection(), "SELECT COUNT(*) FROM analysis_result"));
            assertEquals(0, count(fixture.connection(), "SELECT COUNT(*) FROM model_invocation"));
            fixture.connection().rollback();
        }
    }

    @Test
    void marksClaimContradictingParserDeclarationForReview() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.connection().setAutoCommit(false);
            ClaimValidator.Result result = validator(fixture).persist(
                    TASK,
                    OPERATION,
                    invocation(response("CONFIRMED", List.of(fixture.evidenceId()),
                            "Account is an interface.")),
                    List.of(new ClaimValidator.ParserFact("DECLARATION", "Account", "CLASS")));

            assertEquals("REQUIRES_REVIEW", result.claims().get(0).status());
            assertEquals(1, count(fixture.connection(), "SELECT COUNT(*) FROM review WHERE status='OPEN'"));
            assertTrue(result.reviewRequired());
            fixture.connection().commit();
        }
    }

    @Test
    void marksClaimNegatingParserDeclarationForReview() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.connection().setAutoCommit(false);
            ClaimValidator.Result result = validator(fixture).persist(
                    TASK,
                    OPERATION,
                    invocation(response("CONFIRMED", List.of(fixture.evidenceId()),
                            "Account is not a class.")),
                    List.of(new ClaimValidator.ParserFact("DECLARATION", "Account", "CLASS")));

            assertEquals("REQUIRES_REVIEW", result.claims().get(0).status());
            assertEquals(1, count(fixture.connection(), "SELECT COUNT(*) FROM review WHERE status='OPEN'"));
            fixture.connection().commit();
        }
    }

    @Test
    void marksClaimDenyingParserAuthoritativeCallForReview() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.connection().setAutoCommit(false);
            EvidenceManager.Evidence callEvidence = new EvidenceManager(fixture.connection(), fixture.sourceRoot())
                    .createEvidence(TASK, "DIRECT_CALL", FILE_PATH, 1, 1);
            ClaimValidator.Result result = validator(fixture).persist(
                    TASK,
                    OPERATION,
                    invocation(response("CONFIRMED", List.of(callEvidence.evidenceId()),
                            "Account does not call Ledger.find.", "CALL_RELATIONSHIP_INTERPRETATION")),
                    List.of(new ClaimValidator.ParserFact("DIRECT_CALL", "Account", "Account -> Ledger.find")));

            assertEquals("REQUIRES_REVIEW", result.claims().get(0).status());
            assertEquals(1, count(fixture.connection(), "SELECT COUNT(*) FROM review WHERE status='OPEN'"));
            fixture.connection().commit();
        }
    }

    private String createEvidenceForOtherSnapshot(Fixture fixture) throws Exception {
        String otherTaskId = "018f0a00-0000-7000-8000-000000000704";
        String otherSnapshotId = "other-claim-snapshot";
        try (PreparedStatement statement = fixture.connection().prepareStatement(
                "INSERT INTO source_snapshot(snapshot_id,project_name,manifest_sha256,ignore_rules_json,created_at) "
                        + "VALUES(?,?,?,?,?)")) {
            statement.setString(1, otherSnapshotId);
            statement.setString(2, "other-fixture");
            statement.setString(3, "f".repeat(64));
            statement.setString(4, "{}");
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        }
        byte[] sourceBytes = SOURCE.getBytes(StandardCharsets.UTF_8);
        try (PreparedStatement statement = fixture.connection().prepareStatement(
                "INSERT INTO snapshot_file(snapshot_id,path,size_bytes,sha256) VALUES(?,?,?,?)")) {
            statement.setString(1, otherSnapshotId);
            statement.setString(2, FILE_PATH);
            statement.setLong(3, sourceBytes.length);
            statement.setString(4, sha256(sourceBytes));
            statement.executeUpdate();
        }
        new TaskRuntime(fixture.connection()).createTask(otherTaskId, otherSnapshotId, "analysis");
        return new EvidenceManager(fixture.connection(), fixture.sourceRoot())
                .createEvidence(otherTaskId, "SOURCE_RANGE", FILE_PATH, 1, 1)
                .evidenceId();
    }

    private ClaimValidator validator(Fixture fixture) throws Exception {
        return new ClaimValidator(
            fixture.connection(),
            new EvidenceManager(fixture.connection(), fixture.sourceRoot()),
            Files.readString(Path.of("specs/contracts/schemas/analysis.schema.json")));
    }

    private Fixture fixture() throws Exception {
        Path sourceRoot = tempDirectory.resolve("snapshot");
        Path sourceFile = sourceRoot.resolve(FILE_PATH);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, SOURCE, StandardCharsets.UTF_8);
        Connection connection = ConnectionFactory.open(tempDirectory.resolve("claims.db"));
        SchemaBootstrapper.bootstrap(connection);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO source_snapshot(snapshot_id,project_name,manifest_sha256,ignore_rules_json,created_at) "
                        + "VALUES(?,?,?,?,?)")) {
            statement.setString(1, SNAPSHOT);
            statement.setString(2, "fixture");
            statement.setString(3, "b".repeat(64));
            statement.setString(4, "{}");
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        }
        byte[] sourceBytes = SOURCE.getBytes(StandardCharsets.UTF_8);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO snapshot_file(snapshot_id,path,size_bytes,sha256) VALUES(?,?,?,?)")) {
            statement.setString(1, SNAPSHOT);
            statement.setString(2, FILE_PATH);
            statement.setLong(3, sourceBytes.length);
            statement.setString(4, sha256(sourceBytes));
            statement.executeUpdate();
        }
        TaskRuntime runtime = new TaskRuntime(connection);
        runtime.createTask(TASK, SNAPSHOT, "analysis");
        runtime.addWorkItem(OPERATION, TASK, "ANALYSIS", "Account", "c".repeat(64));
        EvidenceManager.Evidence evidence = new EvidenceManager(connection, sourceRoot)
                .createEvidence(TASK, "SOURCE_RANGE", FILE_PATH, 1, 1);
        return new Fixture(connection, sourceRoot, evidence.evidenceId());
    }

    private static JsonNode response(String status, List<String> evidenceRefs, String claimText) {
        return response(status, evidenceRefs, claimText, "ARCHITECTURE");
    }

    private static JsonNode response(String status, List<String> evidenceRefs, String claimText, String claimType) {
        var claim = JSON.createObjectNode();
        claim.put("logical_claim_id", CLAIM_ID);
        claim.put("claim_type", claimType);
        claim.put("subject", "Account");
        claim.put("claim", claimText);
        claim.put("confidence", "HIGH");
        claim.put("status", status);
        var refs = claim.putArray("evidence_refs");
        evidenceRefs.forEach(refs::add);
        claim.put("reasoning", "Based on the supplied source evidence.");
        var root = JSON.createObjectNode();
        root.putArray("claims").add(claim);
        root.putArray("unknowns");
        root.putArray("next_steps");
        return root;
    }

    private static OpenAiCompatibleAnalysisProvider.Invocation invocation(JsonNode parsed) throws Exception {
        String rawOutput = JSON.writeValueAsString(parsed);
        return new OpenAiCompatibleAnalysisProvider.Invocation(
                "lm-studio", "http://127.0.0.1:1234/v1", "openai-compatible", "model-a", null,
                "UNPINNED_MODEL", "claim-v1.0.0", "{\"seed\":42,\"stream\":false,\"temperature\":0,\"top_p\":1}",
                "d".repeat(64), "e".repeat(64), rawOutput, sha256(rawOutput.getBytes(StandardCharsets.UTF_8)),
                parsed, "2026-11-04T00:00:00.000Z", 0, "{}");
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                var result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String scalar(Connection connection, String sql, String parameter) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static String taskState(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT state FROM task_state WHERE task_id=?")) {
            statement.setString(1, TASK);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Fixture(Connection connection, Path sourceRoot, String evidenceId) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            connection.close();
        }
    }
}