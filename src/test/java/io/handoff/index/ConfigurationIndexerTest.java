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

class ConfigurationIndexerTest {
    @TempDir Path temp;

    @Test
    void indexesMapperXmlSpringXmlAndLiteralJspEntryIdempotently() throws Exception {
        Path source = temp.resolve("project");
        write(source.resolve("src/main/java/example/Mapper.java"), "package example;\n"
                + "interface Mapper { String find(String id); }\n");
        write(source.resolve("src/main/java/example/web/controllers/HomeController.java"), "package example.web.controllers;\n"
                + "class HomeController {\n"
                + "  static final String VIEW = \"home/Home\";\n"
                + "  String home() { return VIEW; }\n"
                + "  String redirect() { return \"redirect:/home\"; }\n"
                + "}\n");
        write(source.resolve("src/main/resources/example/Mapper.xml"), "<mapper namespace=\"example.Mapper\">\n"
                + "  <select id=\"find\">SELECT 1</select>\n"
                + "</mapper>\n");
        write(source.resolve("src/main/resources/application.xml"), "<beans><bean id=\"example\"/></beans>\n");
        write(source.resolve("src/main/webapp/WEB-INF/jsp/home/Home.jsp"), "<html/>\n");
        Path manifest = temp.resolve("manifest.json");
        write(manifest, "{\"files\":["
                + "{\"path\":\"src/main/java/example/Mapper.java\"},"
                + "{\"path\":\"src/main/java/example/web/controllers/HomeController.java\"},"
                + "{\"path\":\"src/main/resources/example/Mapper.xml\"},"
                + "{\"path\":\"src/main/resources/application.xml\"},"
                + "{\"path\":\"src/main/webapp/WEB-INF/jsp/home/Home.jsp\"}]}\n");

        try (Connection connection = ConnectionFactory.open(temp.resolve("config.db"))) {
            SchemaBootstrapper.bootstrap(connection);
            insertSnapshot(connection);
            new JavaIndexer().index(connection, "snapshot-1", source, manifest);
            ConfigurationIndexer.IndexResult result = new ConfigurationIndexer().index(
                    connection, "snapshot-1", source, manifest);
            assertEquals(2, result.parsedFiles());
            assertEquals(1, result.xmlStatements());
            assertEquals(1, result.xmlLinks());
            assertEquals(1, result.viewLinks());
            assertTrue(result.unresolved() >= 1);
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM code_relation WHERE kind='XML_MAPS' AND authority='CONFIG'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM code_relation WHERE kind='VIEW_ENTRY' AND authority='CONFIG'"));
            assertTrue(count(connection, "SELECT COUNT(*) FROM code_relation WHERE kind='UNRESOLVED'") >= 1);
            ConfigurationIndexer.IndexResult second = new ConfigurationIndexer().index(
                    connection, "snapshot-1", source, manifest);
            assertEquals(0, second.xmlLinks());
            assertEquals(3, count(connection, "SELECT COUNT(*) FROM code_relation WHERE kind IN ('XML_MAPS','VIEW_ENTRY','UNRESOLVED')"));
        }
    }

    @Test
    void rejectsAmbiguousMapperStatementWithoutPersistingRelation() throws Exception {
        Path source = temp.resolve("project");
        write(source.resolve("src/main/java/example/Mapper.java"), "package example;\n"
                + "interface Mapper { String find(String id); String find(Integer id); }\n");
        write(source.resolve("src/main/resources/example/Mapper.xml"), "<mapper namespace=\"example.Mapper\">\n"
                + "  <select id=\"find\">SELECT 1</select>\n"
                + "</mapper>\n");
        Path manifest = temp.resolve("manifest.json");
        write(manifest, "{\"files\":["
                + "{\"path\":\"src/main/java/example/Mapper.java\"},"
                + "{\"path\":\"src/main/resources/example/Mapper.xml\"}]}\n");
        try (Connection connection = ConnectionFactory.open(temp.resolve("conflict.db"))) {
            SchemaBootstrapper.bootstrap(connection);
            insertSnapshot(connection);
            new JavaIndexer().index(connection, "snapshot-1", source, manifest);
            try {
                new ConfigurationIndexer().index(connection, "snapshot-1", source, manifest);
            } catch (ConfigurationIndexer.ConflictException expected) {
                assertTrue(expected.getMessage().contains("E_PARSER_CONFLICT"));
            }
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM code_relation WHERE kind='XML_MAPS'"));
        }
    }

    private void insertSnapshot(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO source_snapshot(snapshot_id,project_name,manifest_sha256,ignore_rules_json,created_at) VALUES(?,?,?,?,?)")) {
            statement.setString(1, "snapshot-1");
            statement.setString(2, "fixture");
            statement.setString(3, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            statement.setString(4, "{}");
            statement.setString(5, Instant.EPOCH.toString());
            statement.executeUpdate();
        }
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (var result = connection.createStatement().executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }
}
