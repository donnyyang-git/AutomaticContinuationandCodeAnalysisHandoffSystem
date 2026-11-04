package io.handoff.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.handoff.persistence.ConnectionFactory;
import io.handoff.persistence.SchemaBootstrapper;
import io.handoff.runtime.RuntimeException;
import io.handoff.runtime.TaskRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceManagerTest {
    private static final String TASK = "018f0a00-0000-7000-8000-000000000501";
    private static final String SNAPSHOT = "evidence-snapshot";
    private static final String FILE_PATH = "src/Example.java";

    @TempDir
    Path tempDirectory;

    @Test
    void createsStableEvidenceForInclusiveLinesAndRedactsSecrets() throws Exception {
        byte[] fileBytes = ("class Example {}\n"
            + "\"password\": \"do-not-persist\"\n"
                + "Authorization: Bearer do-not-persist-either\n"
            + "jdbc:mysql://dbuser:jdbc-secret@localhost/app\n"
                + "-----BEGIN PRIVATE KEY-----\n"
                + "private-key-material\n"
                + "-----END PRIVATE KEY-----\n")
                .getBytes(StandardCharsets.UTF_8);
        Path sourceRoot = writeSource(fileBytes);

        try (Connection connection = openDatabase(fileBytes)) {
            EvidenceManager manager = new EvidenceManager(connection, sourceRoot);

            EvidenceManager.Evidence first = manager.createEvidence(TASK, "SOURCE_RANGE", FILE_PATH, 2, 7);
            EvidenceManager.Evidence repeated = manager.createEvidence(TASK, "SOURCE_RANGE", FILE_PATH, 2, 7);

            assertEquals(first.evidenceId(), repeated.evidenceId());
            assertEquals(sha256(fileBytes), wholeFileHash(connection));
            assertEquals(
                        sha256(("\"password\": \"do-not-persist\"\n"
                            + "Authorization: Bearer do-not-persist-either\n"
                            + "jdbc:mysql://dbuser:jdbc-secret@localhost/app\n"
                            + "-----BEGIN PRIVATE KEY-----\n"
                            + "private-key-material\n"
                            + "-----END PRIVATE KEY-----\n").getBytes(StandardCharsets.UTF_8)),
                    first.contentSha256());
            assertEquals("REDACTED", first.status());
            assertFalse(first.redactedExcerpt().contains("do-not-persist"));
            assertFalse(first.redactedExcerpt().contains("dbuser"));
            assertFalse(first.redactedExcerpt().contains("jdbc-secret"));
            assertFalse(first.redactedExcerpt().contains("private-key-material"));
            assertTrue(first.redactedExcerpt().contains("[REDACTED:"));
            assertEquals(1, countEvidence(connection));
        }
    }

    @Test
    void rejectsFilesThatChangedAfterSnapshotCreation() throws Exception {
        byte[] snapshotBytes = "class Example {}\n".getBytes(StandardCharsets.UTF_8);
        Path sourceRoot = writeSource("class Changed {}\n".getBytes(StandardCharsets.UTF_8));

        try (Connection connection = openDatabase(snapshotBytes)) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> new EvidenceManager(connection, sourceRoot)
                            .createEvidence(TASK, "SOURCE_RANGE", FILE_PATH, 1, 1));

            assertEquals("E_EVIDENCE_INVALID", failure.code());
            assertEquals(0, countEvidence(connection));
        }
    }

    @Test
    void rejectsUnlistedFilesAndInvalidInclusiveRanges() throws Exception {
        byte[] fileBytes = "class Example {}\n".getBytes(StandardCharsets.UTF_8);
        Path sourceRoot = writeSource(fileBytes);

        try (Connection connection = openDatabase(fileBytes)) {
            EvidenceManager manager = new EvidenceManager(connection, sourceRoot);

            RuntimeException unlisted = assertThrows(RuntimeException.class,
                    () -> manager.createEvidence(TASK, "SOURCE_RANGE", "src/Other.java", 1, 1));
            RuntimeException invalidRange = assertThrows(RuntimeException.class,
                    () -> manager.createEvidence(TASK, "SOURCE_RANGE", FILE_PATH, 2, 1));

            assertEquals("E_EVIDENCE_INVALID", unlisted.code());
            assertEquals("E_EVIDENCE_INVALID", invalidRange.code());
            assertEquals(0, countEvidence(connection));
        }
    }

    private Path writeSource(byte[] bytes) throws Exception {
        Path sourceRoot = tempDirectory.resolve("snapshot");
        Path sourceFile = sourceRoot.resolve(FILE_PATH);
        Files.createDirectories(sourceFile.getParent());
        Files.write(sourceFile, bytes);
        return sourceRoot;
    }

    private Connection openDatabase(byte[] snapshotFileBytes) throws Exception {
        Connection connection = ConnectionFactory.open(tempDirectory.resolve("evidence.db"));
        SchemaBootstrapper.bootstrap(connection);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO source_snapshot(snapshot_id,project_name,manifest_sha256,ignore_rules_json,created_at) "
                        + "VALUES(?,?,?,?,?)")) {
            statement.setString(1, SNAPSHOT);
            statement.setString(2, "fixture");
            statement.setString(3, "a".repeat(64));
            statement.setString(4, "{}");
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO snapshot_file(snapshot_id,path,size_bytes,sha256) VALUES(?,?,?,?)")) {
            statement.setString(1, SNAPSHOT);
            statement.setString(2, FILE_PATH);
            statement.setLong(3, snapshotFileBytes.length);
            statement.setString(4, sha256(snapshotFileBytes));
            statement.executeUpdate();
        }
        new TaskRuntime(connection).createTask(TASK, SNAPSHOT, "analysis");
        return connection;
    }

    private static String wholeFileHash(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sha256 FROM snapshot_file WHERE snapshot_id=? AND path=?")) {
            statement.setString(1, SNAPSHOT);
            statement.setString(2, FILE_PATH);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static int countEvidence(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM evidence");
                var result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}