package io.handoff.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** Compares observed parser direct calls with the independent ground-truth JSON. */
public final class RelationMetrics {
    private static final ObjectMapper JSON = new ObjectMapper();

    private RelationMetrics() {}

    public static DirectCallMetrics compareDirectCalls(Connection connection, String snapshotId, Path groundTruth)
            throws IOException, java.sql.SQLException {
        List<Call> expected = new ArrayList<>();
        JsonNode root = JSON.readTree(Files.readString(groundTruth));
        for (JsonNode node : root.path("direct_calls")) {
            expected.add(new Call(node.path("caller").asText(), node.path("target").asText(),
                    node.path("path").asText(), node.path("line").asInt()));
        }
        List<Call> observed = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT s.qualified_name,COALESCE(r.target_external_name,t.qualified_name),r.evidence_path,r.evidence_line "
                        + "FROM code_relation r JOIN code_symbol s ON s.symbol_id=r.source_symbol_id "
                        + "LEFT JOIN code_symbol t ON t.symbol_id=r.target_symbol_id "
                        + "WHERE r.snapshot_id=? AND r.kind='DIRECT_CALL' ORDER BY r.evidence_path,r.evidence_line,r.relation_id")) {
            statement.setString(1, snapshotId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) observed.add(new Call(rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4)));
            }
        }
        boolean[] used = new boolean[observed.size()];
        int truePositive = 0;
        for (Call wanted : expected) {
            for (int i = 0; i < observed.size(); i++) {
                if (!used[i] && wanted.matches(observed.get(i))) {
                    used[i] = true;
                    truePositive++;
                    break;
                }
            }
        }
        int falsePositive = observed.size() - truePositive;
        int falseNegative = expected.size() - truePositive;
        double precision = observed.isEmpty() ? 1.0 : (double) truePositive / observed.size();
        double recall = expected.isEmpty() ? 1.0 : (double) truePositive / expected.size();
        return new DirectCallMetrics(expected.size(), observed.size(), truePositive, falsePositive, falseNegative, precision, recall);
    }

    private record Call(String caller, String target, String path, int line) {
        boolean matches(Call actual) {
            return line == actual.line && path.equals(actual.path)
                    && suffix(normalizeCaller(caller), normalizeCaller(actual.caller))
                    && suffix(target, actual.target);
        }

        private static String normalizeCaller(String value) { return value.replace("#", ".").replace(" ", ""); }
        private static boolean suffix(String expected, String actual) {
            if (actual == null) return false;
            String expectedBase = expected.replace("#", ".").replace(" ", "");
            String actualBase = actual.replace("#", ".").replace(" ", "");
            int expectedParams = expectedBase.indexOf('(');
            int actualParams = actualBase.indexOf('(');
            if (expectedParams >= 0) expectedBase = expectedBase.substring(0, expectedParams);
            if (actualParams >= 0) actualBase = actualBase.substring(0, actualParams);
            String expectedMember = expectedBase.substring(expectedBase.lastIndexOf('.') + 1);
            String actualMember = actualBase.substring(actualBase.lastIndexOf('.') + 1);
            return actualBase.equals(expectedBase) || actualBase.endsWith(expectedBase)
                    || expectedBase.endsWith(actualBase) || actualMember.equals(expectedMember);
        }
    }

    public record DirectCallMetrics(int expected, int observed, int truePositive, int falsePositive,
            int falseNegative, double precision, double recall) {}
}
