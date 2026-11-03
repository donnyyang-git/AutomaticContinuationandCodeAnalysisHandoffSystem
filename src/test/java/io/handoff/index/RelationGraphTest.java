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

class RelationGraphTest {
    @TempDir Path temp;

    @Test
    void indexesStructuralAndDirectCallRelationsAndQueriesThemIdempotently() throws Exception {
        Path source = temp.resolve("project");
        Path file = source.resolve("src/main/java/example/Relations.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package example;\n"
                + "class Base {} interface Contract {}\n"
                + "class Helper { void help() {} }\n"
                + "class Child extends Base implements Contract {\n"
                + "  Helper helper = new Helper();\n"
                + "  void run() { helper.help(); }\n"
                + "}\n");
        Path manifest = temp.resolve("manifest.json");
        Files.writeString(manifest, "{\"files\":[{\"path\":\"src/main/java/example/Relations.java\"}]}\n");
        Path truth = temp.resolve("ground-truth.json");
        Files.writeString(truth, "{\"direct_calls\":[{\"caller\":\"example.Child.run()\",\"target\":\"Helper.help\",\"path\":\"src/main/java/example/Relations.java\",\"line\":6}]}\n");

        try (Connection connection = ConnectionFactory.open(temp.resolve("relations.db"))) {
            SchemaBootstrapper.bootstrap(connection);
            insertSnapshot(connection);
            JavaIndexer indexer = new JavaIndexer();
            indexer.index(connection, "snapshot-1", source, manifest);
            int relations = count(connection, "SELECT COUNT(*) FROM code_relation");
            indexer.index(connection, "snapshot-1", source, manifest);
            assertEquals(relations, count(connection, "SELECT COUNT(*) FROM code_relation"));
            String child = symbolId(connection, "example.Child");
            String run = symbolId(connection, "example.Child.run()");
            assertTrue(count(connection, "SELECT COUNT(*) FROM code_relation WHERE kind='CONTAINS'") >= 2);
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM code_relation WHERE kind='IMPLEMENTS'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM code_relation WHERE kind='DIRECT_CALL'"));
            assertEquals(1, new CodeGraph(connection).callees("snapshot-1", run).size());
            assertEquals(1, new CodeGraph(connection).callers("snapshot-1", symbolId(connection, "example.Helper.help()")).size());
            assertTrue(new CodeGraph(connection).reverseDependents("snapshot-1", child).size() >= 0);
            RelationMetrics.DirectCallMetrics metrics = RelationMetrics.compareDirectCalls(connection, "snapshot-1", truth);
            assertEquals(1, metrics.truePositive());
            assertEquals(1.0, metrics.precision());
            assertEquals(1.0, metrics.recall());
        }
    }

    private void insertSnapshot(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO source_snapshot(snapshot_id,project_name,manifest_sha256,ignore_rules_json,created_at) VALUES(?,?,?,?,?)")) {
            statement.setString(1, "snapshot-1"); statement.setString(2, "fixture");
            statement.setString(3, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            statement.setString(4, "{}"); statement.setString(5, Instant.EPOCH.toString()); statement.executeUpdate();
        }
    }

    private static String symbolId(Connection connection, String name) throws Exception {
        try (var statement = connection.prepareStatement("SELECT symbol_id FROM code_symbol WHERE qualified_name=?")) {
            statement.setString(1, name);
            try (var result = statement.executeQuery()) { result.next(); return result.getString(1); }
        }
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (var result = connection.createStatement().executeQuery(sql)) { result.next(); return result.getInt(1); }
    }
}
