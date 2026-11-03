package io.handoff.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class PersistenceSchemaBootstrapperTest {
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "schema_meta",
            "source_snapshot",
            "snapshot_file",
            "task_state",
            "work_item",
            "code_symbol",
            "code_relation",
            "evidence",
            "analysis_result",
            "analysis_result_evidence",
            "model_invocation",
            "execution_event",
            "checkpoint",
            "review",
            "failure_injection",
            "report");

    @TempDir
    Path tempDirectory;

    @Test
    void connectionFactoryAppliesRequiredPragmas() throws Exception {
        try (Connection connection = open("pragmas.db")) {
            assertEquals(1, pragmaInt(connection, "foreign_keys"));
            assertEquals("wal", pragmaText(connection, "journal_mode").toLowerCase());
            assertEquals(2, pragmaInt(connection, "synchronous"));
            assertEquals(5000, pragmaInt(connection, "busy_timeout"));
        }
    }

    @Test
    void bootstrapCreatesAllStrictTablesAndIsIdempotent() throws Exception {
        try (Connection connection = open("schema.db")) {
            SchemaBootstrapper.bootstrap(connection);
            assertEquals(REQUIRED_TABLES, tableNames(connection));
            assertTrue(allTablesAreStrict(connection));
            assertEquals(1, count(connection, "schema_meta"));

            SchemaBootstrapper.bootstrap(connection);
            assertEquals(1, count(connection, "schema_meta"));
            assertEquals(REQUIRED_TABLES, tableNames(connection));
        }
    }

    @Test
    void foreignKeysAreEnforcedByTheDatabase() throws Exception {
        try (Connection connection = open("foreign-keys.db")) {
            SchemaBootstrapper.bootstrap(connection);
            assertThrows(
                    SQLException.class,
                    () -> connection.createStatement().executeUpdate(
                            "INSERT INTO snapshot_file(snapshot_id,path,size_bytes,sha256) "
                                    + "VALUES ('missing','a',0,'0000000000000000000000000000000000000000000000000000000000000000')"));
        }
    }

    @Test
    void malformedMigrationRollsBackAllChanges() throws Exception {
        try (Connection connection = open("rollback.db")) {
            assertThrows(
                    SQLException.class,
                    () -> SchemaBootstrapper.bootstrap(
                            connection, "CREATE TABLE temporary_table (id TEXT) STRICT; INVALID SQL;"));
            assertFalse(tableNames(connection).contains("temporary_table"));
        }
    }

    @Test
    void incompatibleSchemaVersionIsRejectedWithoutMutation() throws Exception {
        try (Connection connection = open("version.db")) {
            connection.createStatement().executeUpdate(
                    "CREATE TABLE schema_meta (schema_version TEXT PRIMARY KEY, applied_at TEXT NOT NULL) STRICT");
            connection.createStatement().executeUpdate(
                    "INSERT INTO schema_meta VALUES ('2.0.0','2026-01-01T00:00:00.000Z')");

            assertThrows(IllegalStateException.class, () -> SchemaBootstrapper.bootstrap(connection));
            assertEquals(1, count(connection, "schema_meta"));
        }
    }

    private Connection open(String fileName) throws SQLException {
        return ConnectionFactory.open(tempDirectory.resolve(fileName));
    }

    private static int pragmaInt(Connection connection, String name) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA " + name)) {
            return result.getInt(1);
        }
    }

    private static String pragmaText(Connection connection, String name) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA " + name)) {
            return result.getString(1);
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.getInt(1);
        }
    }

    private static Set<String> tableNames(Connection connection) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
            while (result.next()) {
                names.add(result.getString(1));
            }
        }
        return names;
    }

    private static boolean allTablesAreStrict(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA table_list")) {
            while (result.next()) {
                if (!result.getString("name").startsWith("sqlite_") && result.getInt("strict") != 1) {
                    return false;
                }
            }
        }
        return true;
    }
}
