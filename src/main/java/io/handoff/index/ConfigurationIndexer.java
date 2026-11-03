package io.handoff.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Parses selected XML/JSP configuration and persists only explicit CONFIG relations. */
public final class ConfigurationIndexer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] JAVA_ROOT_MARKERS = {"src/main/java", "src/test/java"};

    public IndexResult index(Connection connection, String snapshotId, Path sourceRoot, Path manifestPath)
            throws IOException, SQLException {
        return index(connection, snapshotId, sourceRoot, manifestPath, null);
    }

    public IndexResult index(Connection connection, String snapshotId, Path sourceRoot, Path manifestPath,
            String taskId) throws IOException, SQLException {
        Objects.requireNonNull(connection);
        Objects.requireNonNull(snapshotId);
        Objects.requireNonNull(sourceRoot);
        Path root = sourceRoot.toAbsolutePath().normalize();
        List<String> paths = manifestPaths(manifestPath);
        List<String> javaPaths = manifestJavaPaths(manifestPath);
        Set<String> selected = Set.copyOf(paths);
        List<JavaIndexer.Diagnostic> diagnostics = new ArrayList<>();
        int xmlStatements = 0;
        int xmlLinks = 0;
        int viewLinks = 0;
        int unresolved = 0;
        int parsed = 0;
        connection.setAutoCommit(false);
        try {
            for (String relative : paths) {
                Path file = root.resolve(relative).normalize();
                if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                    diagnostics.add(new JavaIndexer.Diagnostic(relative, null, "PARSE",
                            "Manifest-listed configuration file is missing or escapes source root"));
                    continue;
                }
                try {
                    if (relative.endsWith(".xml")) {
                        Document document = parseXml(file);
                        parsed++;
                        if (isMapper(document)) {
                            int[] counts = indexMapper(connection, snapshotId, relative, file, document, diagnostics);
                            xmlStatements += counts[0];
                            xmlLinks += counts[1];
                        }
                    }
                } catch (SAXException | IOException | RuntimeException failure) {
                    diagnostics.add(new JavaIndexer.Diagnostic(relative, null, "PARSE", message(failure)));
                }
            }
            for (String relative : javaPaths) {
                Path file = root.resolve(relative).normalize();
                if (!Files.isRegularFile(file)) continue;
                try {
                    CompilationUnit unit = new JavaParser(new ParserConfiguration()).parse(file).getResult().orElseThrow();
                    int[] counts = indexViews(connection, snapshotId, relative, unit, selected, diagnostics);
                    viewLinks += counts[0];
                    unresolved += counts[1];
                } catch (RuntimeException failure) {
                    diagnostics.add(new JavaIndexer.Diagnostic(relative, null, "PARSE", message(failure)));
                }
            }
            persistDiagnostics(connection, snapshotId, diagnostics);
            connection.commit();
        } catch (ConflictException failure) {
            connection.rollback();
            markReview(connection, taskId);
            throw failure;
        } catch (SQLException | RuntimeException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
        return new IndexResult(parsed, xmlStatements, xmlLinks, viewLinks, unresolved, diagnostics.size());
    }

    private static int[] indexMapper(Connection connection, String snapshotId, String path, Path file, Document document,
            List<JavaIndexer.Diagnostic> diagnostics) throws SQLException, IOException {
        Element root = document.getDocumentElement();
        String namespace = root.getAttribute("namespace");
        int statements = 0;
        int links = 0;
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element statement)) continue;
            String kind = statement.getLocalName() == null ? statement.getTagName() : statement.getLocalName();
            kind = kind.toUpperCase(java.util.Locale.ROOT);
            if (!Set.of("SELECT", "INSERT", "UPDATE", "DELETE").contains(kind)) continue;
            statements++;
            String id = statement.getAttribute("id");
            List<Symbol> candidates = mapperMethods(connection, snapshotId, namespace, id);
            if (candidates.size() > 1) {
                throw new ConflictException("E_PARSER_CONFLICT: XML statement " + namespace + "#" + id
                        + " matches multiple Java mapper methods");
            }
            if (candidates.isEmpty()) {
                diagnostics.add(new JavaIndexer.Diagnostic(path, xmlLine(file, kind, id), "PARSE",
                        "E_CONFIG_UNRESOLVED: no Java mapper method for " + namespace + "#" + id));
                continue;
            }
            Symbol source = candidates.get(0);
            if (persistRelation(connection, snapshotId, "XML_MAPS", "CONFIG", source.id(), namespace + "#" + id,
                    path, xmlLine(file, kind, id))) links++;
        }
        return new int[] {statements, links};
    }

    private static int[] indexViews(Connection connection, String snapshotId, String path, CompilationUnit unit,
            Set<String> selected, List<JavaIndexer.Diagnostic> diagnostics) throws SQLException {
        if (!path.contains("/web/controllers/") || !path.endsWith(".java")) return new int[] {0, 0};
        int links = 0;
        int unresolved = 0;
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            if (!topLevel(type) || !(type instanceof ClassOrInterfaceDeclaration controller)) continue;
            String controllerName = unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("")
                    + controller.getNameAsString();
            String controllerId = symbolId(connection, snapshotId, "TYPE", controllerName);
            if (controllerId == null) continue;
            Map<String, String> constants = constants(controller);
            for (MethodDeclaration method : controller.getMethods()) {
                for (ReturnStmt returned : method.findAll(ReturnStmt.class)) {
                    String view = compileTimeString(returned.getExpression().orElse(null), constants);
                    if (view == null) {
                        if (persistRelation(connection, snapshotId, "UNRESOLVED", "UNKNOWN", controllerId,
                                "JSP_DYNAMIC_ROUTE:" + method.getNameAsString(), path, line(returned))) unresolved++;
                        continue;
                    }
                    if (view.startsWith("redirect:") || view.startsWith("forward:")) {
                        if (persistRelation(connection, snapshotId, "UNRESOLVED", "UNKNOWN", controllerId, view,
                                path, line(returned))) unresolved++;
                        continue;
                    }
                    String jsp = "src/main/webapp/WEB-INF/jsp/" + view + ".jsp";
                    if (!selected.contains(jsp)) {
                        if (persistRelation(connection, snapshotId, "UNRESOLVED", "UNKNOWN", controllerId, view,
                                path, line(returned))) unresolved++;
                        continue;
                    }
                    if (persistRelation(connection, snapshotId, "VIEW_ENTRY", "CONFIG", controllerId, view, jsp, 1)) links++;
                }
            }
        }
        return new int[] {links, unresolved};
    }

    private static Map<String, String> constants(ClassOrInterfaceDeclaration type) {
        Map<String, String> values = new HashMap<>();
        for (FieldDeclaration field : type.getFields()) {
            if (!field.isStatic() || !field.isFinal()) continue;
            field.getVariables().forEach(variable -> variable.getInitializer()
                    .filter(StringLiteralExpr.class::isInstance)
                    .map(StringLiteralExpr.class::cast)
                    .ifPresent(value -> values.put(variable.getNameAsString(), value.asString())));
        }
        return values;
    }

    private static String compileTimeString(Expression expression, Map<String, String> constants) {
        if (expression instanceof StringLiteralExpr literal) return literal.asString();
        if (expression instanceof NameExpr name) return constants.get(name.getNameAsString());
        return null;
    }

    private static List<Symbol> mapperMethods(Connection connection, String snapshotId, String namespace, String id)
            throws SQLException {
        List<Symbol> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT symbol_id,qualified_name FROM code_symbol WHERE snapshot_id=? AND kind='METHOD' "
                        + "AND qualified_name LIKE ? ORDER BY qualified_name")) {
            statement.setString(1, snapshotId);
            statement.setString(2, namespace + "." + id + "(%");
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new Symbol(rows.getString(1), rows.getString(2)));
            }
        }
        return result;
    }

    private static String symbolId(Connection connection, String snapshotId, String kind, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT symbol_id FROM code_symbol WHERE snapshot_id=? AND kind=? AND qualified_name=? LIMIT 1")) {
            statement.setString(1, snapshotId);
            statement.setString(2, kind);
            statement.setString(3, name);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        }
    }

    private static boolean persistRelation(Connection connection, String snapshotId, String kind, String authority,
            String sourceId, String externalName, String path, int line) throws SQLException {
        String relationId = id("relation:" + snapshotId + ":" + sourceId + ":" + kind + ":" + externalName + ":" + path + ":" + line);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO code_relation(relation_id,snapshot_id,source_symbol_id,target_external_name,kind,authority,evidence_path,evidence_line) VALUES(?,?,?,?,?,?,?,?)")) {
            statement.setString(1, relationId);
            statement.setString(2, snapshotId);
            statement.setString(3, sourceId);
            statement.setString(4, externalName);
            statement.setString(5, kind);
            statement.setString(6, authority);
            statement.setString(7, path);
            statement.setInt(8, line);
            return statement.executeUpdate() == 1;
        }
    }

    private static void persistDiagnostics(Connection connection, String snapshotId,
            List<JavaIndexer.Diagnostic> diagnostics) throws SQLException {
        diagnostics.sort(Comparator.comparing(JavaIndexer.Diagnostic::path)
                .thenComparing(d -> d.line() == null ? 0 : d.line())
                .thenComparing(JavaIndexer.Diagnostic::kind).thenComparing(JavaIndexer.Diagnostic::message));
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO unresolved_diagnostic(diagnostic_id,snapshot_id,path,line,kind,message) VALUES(?,?,?,?,?,?)")) {
            for (JavaIndexer.Diagnostic diagnostic : diagnostics) {
                statement.setString(1, id("diagnostic:" + snapshotId + ":" + diagnostic.path() + ":"
                        + diagnostic.line() + ":" + diagnostic.kind() + ":" + diagnostic.message()));
                statement.setString(2, snapshotId);
                statement.setString(3, diagnostic.path());
                if (diagnostic.line() == null) statement.setNull(4, java.sql.Types.INTEGER);
                else statement.setInt(4, diagnostic.line());
                statement.setString(5, diagnostic.kind());
                statement.setString(6, diagnostic.message());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void markReview(Connection connection, String taskId) throws SQLException {
        if (taskId == null || taskId.isBlank()) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE task_state SET state='REQUIRES_REVIEW',version=version+1,updated_at=? WHERE task_id=?")) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, taskId);
            statement.executeUpdate();
        }
    }

    private static Document parseXml(Path file) throws SAXException, IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(file.toFile());
        } catch (javax.xml.parsers.ParserConfigurationException | RuntimeException failure) {
            throw new IOException("Unable to configure secure XML parser", failure);
        }
    }

    private static boolean isMapper(Document document) {
        String name = document.getDocumentElement().getLocalName();
        return "mapper".equals(name) || "mapper".equals(document.getDocumentElement().getTagName());
    }

    private static List<String> manifestPaths(Path manifestPath) throws IOException {
        JsonNode files = JSON.readTree(Files.readString(manifestPath)).path("files");
        if (!files.isArray()) throw new IOException("Manifest files must be an array");
        List<String> paths = new ArrayList<>();
        for (JsonNode file : files) {
            String path = file.path("path").asText("").replace('\\', '/');
            if (path.endsWith(".xml") || path.endsWith(".jsp")) paths.add(path);
        }
        paths.sort(String::compareTo);
        return paths.stream().distinct().toList();
    }

    private static List<String> manifestJavaPaths(Path manifestPath) throws IOException {
        JsonNode files = JSON.readTree(Files.readString(manifestPath)).path("files");
        List<String> paths = new ArrayList<>();
        if (!files.isArray()) return paths;
        for (JsonNode file : files) {
            String path = file.path("path").asText("").replace('\\', '/');
            for (String marker : JAVA_ROOT_MARKERS) {
                if (path.startsWith(marker + "/") && path.endsWith(".java")) paths.add(path);
            }
        }
        paths.sort(String::compareTo);
        return paths.stream().distinct().toList();
    }

    private static int xmlLine(Path file, String kind, String id) throws IOException {
        String marker = "<" + kind.toLowerCase(java.util.Locale.ROOT);
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(marker) && lines.get(i).contains("id=\"" + id + "\"")) return i + 1;
        }
        return 1;
    }

    private static boolean topLevel(Node node) {
        return node.getParentNode().map(parent -> parent instanceof CompilationUnit).orElse(false);
    }

    private static int line(Node node) { return node.getBegin().map(position -> position.line).orElse(1); }
    private static String message(Throwable failure) { return failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage(); }
    private static String id(String value) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) { StringBuilder result = new StringBuilder(bytes.length * 2); for (byte value : bytes) result.append(String.format("%02x", value)); return result.toString(); }

    public record IndexResult(int parsedFiles, int xmlStatements, int xmlLinks, int viewLinks, int unresolved, int diagnostics) {}
    private record Symbol(String id, String qualifiedName) {}
    public static final class ConflictException extends SQLException {
        public ConflictException(String message) { super(message); }
    }
}
