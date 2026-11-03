package io.handoff.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Parses only manifest-listed Java files and writes stable parser facts. */
public final class JavaIndexer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] JAVA_ROOT_MARKERS = {"src/main/java", "src/test/java"};

    public IndexResult index(Connection connection, String snapshotId, Path sourceRoot, Path manifestPath)
            throws IOException, SQLException {
        Objects.requireNonNull(connection);
        Objects.requireNonNull(snapshotId);
        Objects.requireNonNull(sourceRoot);
        Path root = sourceRoot.toAbsolutePath().normalize();
        List<String> paths = manifestPaths(manifestPath);
        List<Diagnostic> diagnostics = new ArrayList<>();
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver());
        solverRoots(root, paths).forEach(path -> solver.add(new JavaParserTypeSolver(path)));
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(solver));
        JavaParser parser = new JavaParser(configuration);
        int parsed = 0;
        int symbols = 0;
        connection.setAutoCommit(false);
        try {
            for (String relative : paths) {
                Path file = root.resolve(relative).normalize();
                if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                    diagnostics.add(new Diagnostic(relative, null, "PARSE", "Manifest-listed Java file is missing or escapes source root"));
                    continue;
                }
                try {
                    var result = parser.parse(file);
                    if (!result.isSuccessful() || result.getResult().isEmpty()) {
                        result.getProblems().forEach(problem -> diagnostics.add(
                                new Diagnostic(relative, null, "PARSE", problem.getMessage())));
                        if (result.getProblems().isEmpty()) {
                            diagnostics.add(new Diagnostic(relative, null, "PARSE", "JavaParser returned no compilation unit"));
                        }
                        continue;
                    }
                    CompilationUnit unit = result.getResult().orElseThrow();
                    symbols += persistSymbols(connection, snapshotId, relative, unit, diagnostics);
                    RelationIndexer.index(connection, snapshotId, relative, unit, diagnostics);
                    parsed++;
                } catch (RuntimeException failure) {
                    diagnostics.add(new Diagnostic(relative, null, "PARSE", message(failure)));
                }
            }
            persistDiagnostics(connection, snapshotId, diagnostics);
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
        return new IndexResult(paths.size(), parsed, symbols, diagnostics.size());
    }

    private static List<String> manifestPaths(Path manifestPath) throws IOException {
        JsonNode root = JSON.readTree(Files.readString(manifestPath));
        JsonNode files = root.path("files");
        if (!files.isArray()) throw new IOException("Manifest files must be an array");
        List<String> paths = new ArrayList<>();
        for (JsonNode file : files) {
            String path = file.path("path").asText("").replace('\\', '/');
            if (path.endsWith(".java") && isJavaSourcePath(path)) paths.add(path);
        }
        paths.sort(Comparator.naturalOrder());
        return paths.stream().distinct().toList();
    }

    private static boolean isJavaSourcePath(String path) {
        for (String marker : JAVA_ROOT_MARKERS) {
            if (path.startsWith(marker + "/")) return true;
        }
        return false;
    }

    private static List<Path> solverRoots(Path sourceRoot, List<String> paths) {
        List<Path> roots = new ArrayList<>();
        roots.add(sourceRoot.toAbsolutePath().normalize());
        for (String path : paths) {
            String normalized = path.replace('\\', '/');
            for (String marker : JAVA_ROOT_MARKERS) {
                int index = normalized.indexOf(marker + "/");
                if (index >= 0) {
                    roots.add(sourceRoot.resolve(normalized.substring(0, index + marker.length())).normalize());
                }
            }
        }
        return roots.stream().distinct().toList();
    }

    private static int persistSymbols(Connection connection, String snapshotId, String path, CompilationUnit unit,
            List<Diagnostic> diagnostics) throws SQLException {
        List<Symbol> symbols = new ArrayList<>();
        unit.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).stream().filter(JavaIndexer::topLevel).forEach(declaration ->
                symbols.add(symbol("TYPE", declaration.getFullyQualifiedName().orElse(declaration.getNameAsString()), path, declaration, null)));
        unit.findAll(com.github.javaparser.ast.body.EnumDeclaration.class).stream().filter(JavaIndexer::topLevel).forEach(declaration ->
                symbols.add(symbol("TYPE", declaration.getFullyQualifiedName().orElse(declaration.getNameAsString()), path, declaration, null)));
        unit.findAll(com.github.javaparser.ast.body.AnnotationDeclaration.class).stream().filter(JavaIndexer::topLevel).forEach(declaration ->
                symbols.add(symbol("TYPE", declaration.getFullyQualifiedName().orElse(declaration.getNameAsString()), path, declaration, null)));
        unit.findAll(com.github.javaparser.ast.body.RecordDeclaration.class).stream().filter(JavaIndexer::topLevel).forEach(declaration ->
                symbols.add(symbol("TYPE", declaration.getFullyQualifiedName().orElse(declaration.getNameAsString()), path, declaration, null)));
        unit.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream().filter(JavaIndexer::topLevel).forEach(declaration -> {
            String owner = owner(declaration);
            symbols.add(symbol("METHOD", owner + "." + declaration.getSignature().asString(), path, declaration, declaration.getSignature().asString()));
            try { declaration.resolve(); } catch (RuntimeException failure) {
                diagnostics.add(new Diagnostic(path, line(declaration), "SYMBOL_SOLVER", message(failure)));
            }
        });
        unit.findAll(com.github.javaparser.ast.body.ConstructorDeclaration.class).stream().filter(JavaIndexer::topLevel).forEach(declaration -> {
            String owner = owner(declaration);
            symbols.add(symbol("CONSTRUCTOR", owner + "." + declaration.getSignature().asString(), path, declaration, declaration.getSignature().asString()));
            try { declaration.resolve(); } catch (RuntimeException failure) {
                diagnostics.add(new Diagnostic(path, line(declaration), "SYMBOL_SOLVER", message(failure)));
            }
        });
        unit.findAll(FieldDeclaration.class).stream().filter(JavaIndexer::topLevel).forEach(declaration -> {
            String owner = owner(declaration);
            for (VariableDeclarator variable : declaration.getVariables()) {
                symbols.add(symbol("FIELD", owner + "." + variable.getNameAsString(), path, declaration, declaration.toString()));
            }
        });
        symbols.sort(Comparator.comparing(Symbol::kind).thenComparing(Symbol::qualifiedName)
                .thenComparing(Symbol::path).thenComparingInt(Symbol::startLine));
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO code_symbol(symbol_id,snapshot_id,kind,qualified_name,path,start_line,end_line,signature) VALUES(?,?,?,?,?,?,?,?)")) {
            for (Symbol symbol : symbols) {
                statement.setString(1, id("symbol:" + snapshotId + ":" + symbol.canonical()));
                statement.setString(2, snapshotId);
                statement.setString(3, symbol.kind());
                statement.setString(4, symbol.qualifiedName());
                statement.setString(5, symbol.path());
                statement.setInt(6, symbol.startLine());
                statement.setInt(7, symbol.endLine());
                statement.setString(8, symbol.signature());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        return symbols.size();
    }

    private static Symbol symbol(String kind, String name, String path, com.github.javaparser.ast.Node node, String signature) {
        return new Symbol(kind, name, path, line(node), endLine(node), signature);
    }
    private static String owner(com.github.javaparser.ast.Node node) {
        return node.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                .map(type -> type.getFullyQualifiedName().orElse(type.getNameAsString()))
                .or(() -> node.findAncestor(com.github.javaparser.ast.body.EnumDeclaration.class)
                        .map(type -> type.getFullyQualifiedName().orElse(type.getNameAsString())))
                .orElse("<unknown>");
    }

    private static boolean topLevel(com.github.javaparser.ast.Node node) {
        com.github.javaparser.ast.Node type = node instanceof com.github.javaparser.ast.body.TypeDeclaration<?>
                ? node : node.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class).orElse(null);
        return type != null && type.getParentNode()
                .map(parent -> parent instanceof CompilationUnit).orElse(false);
    }

    private static void probeSymbolResolution(CompilationUnit unit, String path, List<Diagnostic> diagnostics) {
        unit.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).forEach(call -> {
            try { call.resolve(); } catch (RuntimeException failure) {
                diagnostics.add(new Diagnostic(path, line(call), "SYMBOL_SOLVER", message(failure)));
            }
        });
    }

    private static void persistDiagnostics(Connection connection, String snapshotId, List<Diagnostic> diagnostics)
            throws SQLException {
        diagnostics.sort(Comparator.comparing(Diagnostic::path).thenComparing(d -> Optional.ofNullable(d.line()).orElse(0))
                .thenComparing(Diagnostic::kind).thenComparing(Diagnostic::message));
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO unresolved_diagnostic(diagnostic_id,snapshot_id,path,line,kind,message) VALUES(?,?,?,?,?,?)")) {
            for (Diagnostic diagnostic : diagnostics) {
                statement.setString(1, id("diagnostic:" + snapshotId + ":" + diagnostic.canonical()));
                statement.setString(2, snapshotId);
                statement.setString(3, diagnostic.path());
                if (diagnostic.line() == null) statement.setNull(4, java.sql.Types.INTEGER); else statement.setInt(4, diagnostic.line());
                statement.setString(5, diagnostic.kind());
                statement.setString(6, diagnostic.message());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static int line(com.github.javaparser.ast.Node node) { return node.getBegin().map(p -> p.line).orElse(1); }
    private static int endLine(com.github.javaparser.ast.Node node) { return node.getEnd().map(p -> p.line).orElse(line(node)); }
    private static String message(Throwable failure) { return failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage(); }
    private static String id(String value) { try { var digest = MessageDigest.getInstance("SHA-256"); return hex(digest.digest(value.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
    private static String hex(byte[] bytes) { StringBuilder result = new StringBuilder(bytes.length * 2); for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value)); return result.toString(); }

    public record IndexResult(int manifestJavaFiles, int parsedFiles, int symbols, int diagnostics) {}
    public record Diagnostic(String path, Integer line, String kind, String message) { String canonical() { return path + "\u0000" + line + "\u0000" + kind + "\u0000" + message; } }
    private record Symbol(String kind, String qualifiedName, String path, int startLine, int endLine, String signature) {
        String canonical() { return kind + "\u0000" + qualifiedName + "\u0000" + path + "\u0000" + startLine + "\u0000" + endLine + "\u0000" + signature; }
    }
}
