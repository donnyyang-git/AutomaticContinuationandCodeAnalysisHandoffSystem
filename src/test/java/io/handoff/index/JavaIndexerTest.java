package io.handoff.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.handoff.persistence.ConnectionFactory;
import io.handoff.persistence.SchemaBootstrapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaIndexerTest {
    @TempDir Path temp;

    @Test
    void indexesOnlyManifestJavaFilesAndRepeatingIsIdempotent() throws Exception {
        Path source = temp.resolve("project");
        Path java = source.resolve("src/main/java/example/Included.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "package example; public class Included { public void run() {} class Nested { void hidden() {} } }\n");
        Files.writeString(source.resolve("Ignored.java"), "class Ignored {}\n");
        Path manifest = temp.resolve("manifest.json");
        Files.writeString(manifest, "{\"files\":[{\"path\":\"src/main/java/example/Included.java\"},{\"path\":\"Ignored.java\"}]}\n");

        try (Connection connection = ConnectionFactory.open(temp.resolve("index.db"))) {
            SchemaBootstrapper.bootstrap(connection);
            insertSnapshot(connection, "snapshot-1");
            JavaIndexer.IndexResult first = new JavaIndexer().index(connection, "snapshot-1", source, manifest);
            int symbols = count(connection, "code_symbol");
            JavaIndexer.IndexResult second = new JavaIndexer().index(connection, "snapshot-1", source, manifest);
            assertEquals(1, first.parsedFiles());
            assertEquals(first.symbols(), second.symbols());
            assertEquals(symbols, count(connection, "code_symbol"));
            assertEquals(2, count(connection, "code_symbol"));
            assertTrue(first.diagnostics() >= 0);
        }
    }

    private static void insertSnapshot(Connection connection, String id) throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO source_snapshot(snapshot_id,project_name,manifest_sha256,ignore_rules_json,created_at) VALUES(?,?,?,?,?)")) {
            statement.setString(1, id);
            statement.setString(2, "fixture");
            statement.setString(3, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            statement.setString(4, "{}");
            statement.setString(5, Instant.EPOCH.toString());
            statement.executeUpdate();
        }
    }

    private static int count(Connection connection, String table) throws Exception {
        try (var result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }
}
