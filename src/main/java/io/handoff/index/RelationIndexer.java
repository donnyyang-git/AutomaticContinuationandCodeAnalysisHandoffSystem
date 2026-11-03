package io.handoff.index;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Persists parser-owned structural and direct-call relations with source locations. */
final class RelationIndexer {
    private RelationIndexer() {}

    static void index(Connection connection, String snapshotId, String path, CompilationUnit unit,
            List<JavaIndexer.Diagnostic> diagnostics) throws SQLException {
        List<SymbolRef> symbols = loadSymbols(connection, snapshotId, path);
        List<SymbolRef> allSymbols = loadSymbols(connection, snapshotId, null);
        Map<String, SymbolRef> byName = new HashMap<>();
        allSymbols.forEach(symbol -> byName.put(symbol.qualifiedName(), symbol));
        List<Relation> relations = new ArrayList<>();
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            String ownerName = type.getFullyQualifiedName().orElse(type.getNameAsString());
            SymbolRef owner = byName.get(ownerName);
            if (owner == null) continue;
            for (BodyDeclaration<?> member : type.getMembers()) {
                findMember(symbols, member).ifPresent(target -> relations.add(
                        relation(snapshotId, owner, target, "CONTAINS", path, line(member), null)));
            }
            if (type instanceof ClassOrInterfaceDeclaration declaration) {
                declaration.getExtendedTypes().forEach(extended -> addTypeRelation(
                        snapshotId, owner, "EXTENDS", extended, path, byName, relations, diagnostics));
                declaration.getImplementedTypes().forEach(implemented -> addTypeRelation(
                        snapshotId, owner, "IMPLEMENTS", implemented, path, byName, relations, diagnostics));
            } else if (type instanceof EnumDeclaration declaration) {
                declaration.getImplementedTypes().forEach(implemented -> addTypeRelation(
                        snapshotId, owner, "IMPLEMENTS", implemented, path, byName, relations, diagnostics));
            }
        }
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            CallableDeclaration<?> callerNode = call.findAncestor(CallableDeclaration.class).orElse(null);
            if (callerNode == null) continue;
            SymbolRef caller = findMember(symbols, callerNode).orElse(null);
            if (caller == null) continue;
            try {
                ResolvedMethodLikeDeclaration target = call.resolve();
                String targetName = target.getQualifiedName();
                SymbolRef internal = findByResolvedName(connection, snapshotId, targetName);
                relations.add(relation(snapshotId, caller, internal, "DIRECT_CALL", path, line(call),
                        internal == null ? target.getQualifiedSignature() : null));
            } catch (RuntimeException failure) {
                diagnostics.add(new JavaIndexer.Diagnostic(path, line(call), "SYMBOL_SOLVER", message(failure)));
            }
        }
        relations.sort(Comparator.comparing(Relation::canonical));
        persist(connection, relations);
    }

    private static void addTypeRelation(String snapshotId, SymbolRef source, String kind, ClassOrInterfaceType type,
            String path, Map<String, SymbolRef> byName, List<Relation> relations,
            List<JavaIndexer.Diagnostic> diagnostics) {
        try {
            ResolvedReferenceType resolved = type.resolve().asReferenceType();
            String qualifiedName = resolved.getQualifiedName();
            SymbolRef target = byName.get(qualifiedName);
            relations.add(relation(snapshotId, source, target, kind, path, line(type),
                    target == null ? qualifiedName : null));
        } catch (RuntimeException failure) {
            diagnostics.add(new JavaIndexer.Diagnostic(path, line(type), "SYMBOL_SOLVER", message(failure)));
        }
    }

    private static List<SymbolRef> loadSymbols(Connection connection, String snapshotId, String path) throws SQLException {
        List<SymbolRef> result = new ArrayList<>();
        String sql = path == null
                ? "SELECT symbol_id,kind,qualified_name,start_line FROM code_symbol WHERE snapshot_id=?"
                : "SELECT symbol_id,kind,qualified_name,start_line FROM code_symbol WHERE snapshot_id=? AND path=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, snapshotId);
            if (path != null) statement.setString(2, path);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new SymbolRef(rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4)));
            }
        }
        return result;
    }

    private static java.util.Optional<SymbolRef> findMember(List<SymbolRef> symbols, Node node) {
        int start = line(node);
        String kind = node instanceof MethodDeclaration ? "METHOD" : node instanceof com.github.javaparser.ast.body.ConstructorDeclaration ? "CONSTRUCTOR" : "FIELD";
        return symbols.stream().filter(symbol -> symbol.kind().equals(kind) && symbol.startLine() == start).findFirst();
    }

    private static SymbolRef findByResolvedName(Connection connection, String snapshotId, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT symbol_id,kind,qualified_name,start_line FROM code_symbol WHERE snapshot_id=? AND kind IN ('METHOD','CONSTRUCTOR') AND (qualified_name=? OR qualified_name LIKE ?) ORDER BY qualified_name LIMIT 1")) {
            statement.setString(1, snapshotId);
            statement.setString(2, name);
            statement.setString(3, name + "(%");
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? new SymbolRef(rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4)) : null;
            }
        }
    }

    private static Relation relation(String snapshotId, SymbolRef source, SymbolRef target, String kind,
            String path, int line, String externalName) {
        return new Relation(id("relation:" + snapshotId + ":" + source.id() + ":" + kind + ":"
                + (target == null ? externalName : target.id()) + ":" + path + ":" + line), snapshotId,
                kind, source.id(), target == null ? null : target.id(), externalName, path, line);
    }

    private static void persist(Connection connection, List<Relation> relations) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO code_relation(relation_id,snapshot_id,source_symbol_id,target_symbol_id,target_external_name,kind,authority,evidence_path,evidence_line) VALUES(?,?,?,?,?,?,?,?,?)")) {
            for (Relation relation : relations) {
                statement.setString(1, relation.id());
                statement.setString(2, relation.snapshotId());
                statement.setString(3, relation.sourceId());
                if (relation.targetId() == null) statement.setNull(4, java.sql.Types.VARCHAR); else statement.setString(4, relation.targetId());
                if (relation.externalName() == null) statement.setNull(5, java.sql.Types.VARCHAR); else statement.setString(5, relation.externalName());
                statement.setString(6, relation.kind());
                statement.setString(7, "PARSER");
                statement.setString(8, relation.path());
                statement.setInt(9, relation.line());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static int line(Node node) { return node.getBegin().map(position -> position.line).orElse(1); }
    private static String message(Throwable failure) { return failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage(); }
    private static String id(String value) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) { StringBuilder result = new StringBuilder(bytes.length * 2); for (byte value : bytes) result.append(String.format("%02x", value)); return result.toString(); }

    private record SymbolRef(String id, String kind, String qualifiedName, int startLine) {}
    private record Relation(String id, String snapshotId, String kind, String sourceId, String targetId,
            String externalName, String path, int line) {
        String canonical() { return kind + "\u0000" + sourceId + "\u0000" + targetId + "\u0000" + externalName + "\u0000" + path + "\u0000" + line; }
    }
}
