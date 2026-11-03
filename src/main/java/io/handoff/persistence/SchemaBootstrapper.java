package io.handoff.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

public final class SchemaBootstrapper {
    public static final String SCHEMA_VERSION = "1.0.0";
    private static final String SCHEMA_RESOURCE = "/db/migration/schema.sql";

    private SchemaBootstrapper() {}

    public static void bootstrap(Connection connection) throws SQLException {
        bootstrap(connection, loadSchema());
    }

    static void bootstrap(Connection connection, String schemaSql) throws SQLException {
        String existingVersion = existingSchemaVersion(connection);
        if (existingVersion != null) {
            if (!SCHEMA_VERSION.equals(existingVersion)) {
                throw new IllegalStateException("Unsupported schema version: " + existingVersion);
            }
            return;
        }

        String[] statements = schemaSql.split(";");
        try {
            executePragmas(connection, statements);
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                executeDdl(connection, statements);
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO schema_meta(schema_version, applied_at) VALUES (?, ?)")) {
                    insert.setString(1, SCHEMA_VERSION);
                    insert.setString(2, Instant.now().toString());
                    insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException failure) {
            throw failure;
        }
    }

    private static String loadSchema() throws SQLException {
        try (InputStream input = SchemaBootstrapper.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new SQLException("Missing schema resource: " + SCHEMA_RESOURCE);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new SQLException("Unable to read schema resource", failure);
        }
    }

    private static void executePragmas(Connection connection, String[] statements) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                String trimmed = sql.trim();
                if (trimmed.regionMatches(true, 0, "PRAGMA", 0, "PRAGMA".length())) {
                    statement.execute(trimmed);
                }
            }
        }
    }

    private static void executeDdl(Connection connection, String[] statements) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                String trimmed = sql.trim();
                if (!trimmed.isEmpty()
                        && !trimmed.regionMatches(true, 0, "PRAGMA", 0, "PRAGMA".length())) {
                    statement.execute(trimmed);
                }
            }
        }
    }

    private static String existingSchemaVersion(Connection connection) throws SQLException {
        if (!tableExists(connection, "schema_meta")) {
            return null;
        }
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT schema_version FROM schema_meta ORDER BY schema_version LIMIT 1")) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = ?")) {
            statement.setString(1, tableName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }
}
