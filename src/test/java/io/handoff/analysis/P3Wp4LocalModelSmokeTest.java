package io.handoff.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.handoff.evidence.EvidenceManager;
import io.handoff.persistence.ConnectionFactory;
import io.handoff.persistence.SchemaBootstrapper;
import io.handoff.runtime.AtomicWorkCoordinator;
import io.handoff.runtime.FailureInjection;
import io.handoff.runtime.IntegrityValidator;
import io.handoff.runtime.TaskRuntime;
import io.handoff.runtime.TaskState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class P3Wp4LocalModelSmokeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TASK_ID = "018f0a00-0000-7000-8000-000000000901";
    private static final String OPERATION_ID = "018f0a00-0000-7000-8000-000000000902";
    private static final String CORPUS_ROOT = "target/p2-corpus";
    private static final String ENVIRONMENT_MANIFEST = "specs/inputs/environment-manifest.json";
    private static final String ANALYSIS_SCHEMA = "specs/contracts/schemas/analysis.schema.json";

    @TempDir
    Path tempDirectory;

    @Test
        @Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    void analyzesOneFixedCorpusSubjectWithTheSelectedLocalModel() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("handoff.localModelSmoke"),
                "Enable explicitly with -Dhandoff.localModelSmoke=true");

        JsonNode environment = JSON.readTree(Files.readString(Path.of(ENVIRONMENT_MANIFEST)));
        String modelTag = System.getProperty(
                "handoff.localModelTag", environment.path("models").path(0).path("tag").asText());
        String requestedSubject = System.getProperty("handoff.smoke.subject", "account");
        CorpusSubject subject = CorpusSubject.forName(requestedSubject);
        Path sourceRoot = Path.of(CORPUS_ROOT).toAbsolutePath().normalize();
        Path sourcePath = sourceRoot.resolve(subject.path());
        byte[] sourceBytes = Files.readAllBytes(sourcePath);
        String snapshotId = sha256(sourceBytes);
        String schema = Files.readString(Path.of(ANALYSIS_SCHEMA));

        try (Connection connection = openDatabase(sourcePath, sourceBytes, snapshotId)) {
            EvidenceManager evidenceManager = new EvidenceManager(connection, sourceRoot);
                        int startLine = subject.startLine();
                        int endLine = Math.min(subject.endLine(), Files.readAllLines(sourcePath).size());
            EvidenceManager.Evidence evidence = evidenceManager.prepareEvidence(
                                        TASK_ID, "SOURCE_RANGE", subject.path(), startLine, endLine);
            var promptInput = new AnalysisPromptBuilder.Input(
                    TASK_ID,
                    subject.qualifiedName(),
                    List.of("Parser fact: " + subject.qualifiedName() + " is a Java class."),
                    List.of(new AnalysisPromptBuilder.EvidenceExcerpt(
                            evidence.evidenceId(), evidence.path(), evidence.startLine(), evidence.endLine(),
                            evidence.redactedExcerpt())));
            var settings = OpenAiCompatibleAnalysisProvider.Settings.fromEnvironmentManifest(
                    environment, modelTag, null, Duration.ofMinutes(10));
            OpenAiCompatibleAnalysisProvider.Invocation invocation =
                    new OpenAiCompatibleAnalysisProvider().analyze(
                            settings, promptInput, JSON.readTree(schema));

            assertEquals("UNPINNED_MODEL", invocation.modelPinStatus());
            assertFalse(invocation.rawOutput().isBlank());
            assertNotNull(invocation.parsedResponse());
            assertFalse(invocation.parsedResponse().path("claims").isEmpty(),
                    "Local model must return at least one structured claim");

            AtomicWorkCoordinator coordinator = new AtomicWorkCoordinator(connection);
            List<ClaimValidator.ParserFact> parserFacts =
                    List.of(new ClaimValidator.ParserFact("DECLARATION", subject.qualifiedName(), "CLASS"));
            if ("account".equals(subject.name())) {
                OpenAiCompatibleAnalysisProvider.Invocation failedInvocation = invocation;
                assertThrows(io.handoff.runtime.InjectedFailureException.class,
                        () -> coordinator.commitAnalysisWorkItem(
                                TASK_ID, OPERATION_ID, FailureInjection.PROCESS_KILL_AFTER_RESULT,
                                List.of(evidence), sourceRoot, failedInvocation, schema, parserFacts));
                assertEquals(1, count(connection,
                        "SELECT COUNT(*) FROM work_item WHERE operation_id=? AND status='PENDING'", OPERATION_ID));
                assertEquals(0, count(connection, "SELECT COUNT(*) FROM evidence"));
                assertEquals(0, count(connection, "SELECT COUNT(*) FROM analysis_result"));
                assertEquals(0, count(connection, "SELECT COUNT(*) FROM model_invocation"));
                assertEquals(0, count(connection, "SELECT COUNT(*) FROM execution_event"));
                assertEquals(0, count(connection, "SELECT COUNT(*) FROM checkpoint"));
                invocation = new OpenAiCompatibleAnalysisProvider().analyze(
                        settings, promptInput, JSON.readTree(schema));
            }

            var committed = coordinator.commitAnalysisWorkItem(
                    TASK_ID,
                    OPERATION_ID,
                    FailureInjection.NONE,
                    List.of(evidence),
                    sourceRoot,
                    invocation,
                    schema,
                    parserFacts);

            assertFalse(committed.claims().isEmpty());
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM model_invocation"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM execution_event"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM checkpoint"));
            assertEquals(1, count(connection,
                    "SELECT COUNT(*) FROM work_item WHERE operation_id=? AND status='COMMITTED'", OPERATION_ID));
            new IntegrityValidator(connection).validate(TASK_ID);
        }
    }

    private Connection openDatabase(Path sourcePath, byte[] sourceBytes, String snapshotId) throws Exception {
        Connection connection = ConnectionFactory.open(tempDirectory.resolve("local-model-smoke.db"));
        SchemaBootstrapper.bootstrap(connection);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO source_snapshot(snapshot_id,project_name,manifest_sha256,ignore_rules_json,created_at) "
                        + "VALUES(?,?,?,?,?)")) {
            statement.setString(1, snapshotId);
            statement.setString(2, "jpetstore");
            statement.setString(3, sha256(sourceBytes));
            statement.setString(4, "{}");
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        }
        String relativePath = Path.of(CORPUS_ROOT).toAbsolutePath().normalize()
                .relativize(sourcePath.toAbsolutePath().normalize()).toString().replace('\\', '/');
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO snapshot_file(snapshot_id,path,size_bytes,sha256) VALUES(?,?,?,?)")) {
            statement.setString(1, snapshotId);
            statement.setString(2, relativePath);
            statement.setLong(3, sourceBytes.length);
            statement.setString(4, sha256(sourceBytes));
            statement.executeUpdate();
        }
        TaskRuntime runtime = new TaskRuntime(connection);
        runtime.createTask(TASK_ID, snapshotId, "analysis");
        runtime.transition(TASK_ID, TaskState.INDEXING, runtime.task(TASK_ID).version());
        runtime.transition(TASK_ID, TaskState.ANALYZING, runtime.task(TASK_ID).version());
        runtime.addWorkItem(OPERATION_ID, TASK_ID, "ANALYSIS", sourcePath.getFileName().toString(), sha256(sourceBytes));
        return connection;
    }

    private static int count(Connection connection, String sql) throws Exception {
        return count(connection, sql, null);
    }

    private static int count(Connection connection, String sql, String parameter) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (parameter != null) {
                statement.setString(1, parameter);
            }
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

        private record CorpusSubject(String name, String qualifiedName, String path, int startLine, int endLine) {
        private static CorpusSubject forName(String name) {
            return switch (name.toLowerCase(java.util.Locale.ROOT)) {
                case "account" -> new CorpusSubject(
                        "account", "org.mybatis.jpetstore.domain.Account",
                        "src/main/java/org/mybatis/jpetstore/domain/Account.java", 23, 35);
                case "catalog" -> new CorpusSubject(
                        "catalog", "org.mybatis.jpetstore.service.CatalogService",
                        "src/main/java/org/mybatis/jpetstore/service/CatalogService.java", 33, 45);
                case "order" -> new CorpusSubject(
                        "order", "org.mybatis.jpetstore.domain.Order",
                        "src/main/java/org/mybatis/jpetstore/domain/Order.java", 30, 42);
                default -> throw new IllegalArgumentException("Unsupported smoke subject: " + name);
            };
        }
    }
}