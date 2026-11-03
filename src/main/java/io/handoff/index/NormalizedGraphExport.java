package io.handoff.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** Exports only normalized graph facts; volatile IDs and timestamps are excluded. */
public final class NormalizedGraphExport {
    private NormalizedGraphExport() {}

    public static String export(Connection connection, String snapshotId) throws Exception {
        List<String> lines = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT kind,qualified_name,path,start_line,end_line,COALESCE(signature,'') "
                        + "FROM code_symbol WHERE snapshot_id=? ORDER BY kind,qualified_name,path,start_line,end_line,signature")) {
            statement.setString(1, snapshotId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) lines.add(String.join("\t", "SYMBOL", rows.getString(1), rows.getString(2),
                        rows.getString(3), Integer.toString(rows.getInt(4)), Integer.toString(rows.getInt(5)), rows.getString(6)));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT r.kind,s.qualified_name,COALESCE(t.qualified_name,r.target_external_name),r.authority,r.evidence_path,COALESCE(r.evidence_line,0) "
                        + "FROM code_relation r JOIN code_symbol s ON s.symbol_id=r.source_symbol_id "
                        + "LEFT JOIN code_symbol t ON t.symbol_id=r.target_symbol_id WHERE r.snapshot_id=? "
                        + "ORDER BY r.kind,s.qualified_name,COALESCE(t.qualified_name,r.target_external_name),r.authority,r.evidence_path,r.evidence_line")) {
            statement.setString(1, snapshotId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) lines.add(String.join("\t", "RELATION", rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getString(4), rows.getString(5), Integer.toString(rows.getInt(6))));
            }
        }
        return String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
    }

    public static void write(Connection connection, String snapshotId, Path output) throws Exception {
        Files.writeString(output, export(connection, snapshotId), StandardCharsets.UTF_8);
    }
}
