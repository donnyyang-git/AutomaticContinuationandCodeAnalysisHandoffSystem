package io.handoff.index;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Deep read interface for parser-owned relation queries. */
public final class CodeGraph {
    private final Connection connection;

    public CodeGraph(Connection connection) {
        this.connection = Objects.requireNonNull(connection);
    }

    public List<Relation> callers(String snapshotId, String symbolId) throws SQLException {
        return query(snapshotId, "target_symbol_id=? AND kind='DIRECT_CALL'", symbolId);
    }

    public List<Relation> callees(String snapshotId, String symbolId) throws SQLException {
        return query(snapshotId, "source_symbol_id=? AND kind='DIRECT_CALL'", symbolId);
    }

    public List<Relation> reverseDependents(String snapshotId, String symbolId) throws SQLException {
        return query(snapshotId, "target_symbol_id=?", symbolId);
    }

    private List<Relation> query(String snapshotId, String predicate, String symbolId) throws SQLException {
        List<Relation> result = new ArrayList<>();
        String sql = "SELECT relation_id,kind,source_symbol_id,target_symbol_id,target_external_name,evidence_path,evidence_line "
                + "FROM code_relation WHERE snapshot_id=? AND " + predicate
                + " ORDER BY evidence_path,evidence_line,relation_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, snapshotId);
            statement.setString(2, symbolId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new Relation(rows.getString(1), rows.getString(2), rows.getString(3),
                            rows.getString(4), rows.getString(5), rows.getString(6), rows.getInt(7)));
                }
            }
        }
        return List.copyOf(result);
    }

    public record Relation(String relationId, String kind, String sourceSymbolId, String targetSymbolId,
            String targetExternalName, String evidencePath, int evidenceLine) {}
}
