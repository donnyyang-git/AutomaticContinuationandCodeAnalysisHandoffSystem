package io.handoff.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class ConnectionFactory {
    private ConnectionFactory() {}

    public static Connection open(Path databasePath) throws SQLException {
        try {
            Path absolutePath = databasePath.toAbsolutePath().normalize();
            Path parent = absolutePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + absolutePath);
            try {
                applyPragmas(connection);
                return connection;
            } catch (SQLException failure) {
                connection.close();
                throw failure;
            }
        } catch (IOException failure) {
            throw new SQLException("Unable to create database directory", failure);
        }
    }

    private static void applyPragmas(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
    }
}
