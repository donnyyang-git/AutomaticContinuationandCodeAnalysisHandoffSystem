package io.handoff.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import io.handoff.runtime.TaskRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Schedules manifest files as durable, retryable runtime operations. */
public final class RuntimeIndexingService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JavaIndexer javaIndexer = new JavaIndexer();
    private final ConfigurationIndexer configurationIndexer = new ConfigurationIndexer();

    public record ScheduleResult(int javaOperations, int configurationOperations) {}

    public ScheduleResult schedule(Connection connection, String taskId, String snapshotId,
            Path sourceRoot, Path manifestPath) throws IOException, SQLException {
        List<String> java = javaOperations(sourceRoot, manifestPath);
        List<String> config = paths(manifestPath, false);
        TaskRuntime runtime = new TaskRuntime(connection);
        for (String subject : java) add(runtime, taskId, "JAVA_CLASS", subject, sourceRoot);
        for (String path : config) add(runtime, taskId, "CONFIG_FILE", path, sourceRoot);
        return new ScheduleResult(java.size(), config.size());
    }

    public void run(Connection connection, String taskId, String snapshotId,
            Path sourceRoot, Path manifestPath) throws Exception {
        schedule(connection, taskId, snapshotId, sourceRoot, manifestPath);
        List<String> java = javaOperations(sourceRoot, manifestPath);
        TaskRuntime runtime = new TaskRuntime(connection);
        runtime.run(taskId, item -> {
            Path itemManifest = sourceRoot.resolve(".handoff-runtime-" + item.operationId() + ".json");
            try {
                String path = item.kind().equals("JAVA_CLASS") ? pathOf(item.subject()) : item.subject();
                Files.writeString(itemManifest, manifestFor(path, java), StandardCharsets.UTF_8);
                if (item.kind().equals("JAVA_CLASS")) {
                    javaIndexer.index(connection, snapshotId, sourceRoot, itemManifest);
                } else {
                    configurationIndexer.index(connection, snapshotId, sourceRoot, itemManifest, taskId);
                }
            } finally {
                Files.deleteIfExists(itemManifest);
            }
        });
    }

    private static List<String> javaOperations(Path sourceRoot, Path manifest) throws IOException {
        List<String> files = paths(manifest, true);
        JavaParser parser = new JavaParser(new ParserConfiguration());
        List<String> result = new ArrayList<>();
        for (String path : files) {
            Path file = sourceRoot.resolve(path).normalize();
            if (!Files.isRegularFile(file)) {
                result.add(path + "#<missing>");
                continue;
            }
            CompilationUnit unit = parser.parse(file).getResult().orElse(null);
            if (unit == null || unit.getTypes().isEmpty()) {
                result.add(path + "#<unresolved>");
                continue;
            }
            unit.getTypes().forEach(type -> result.add(path + "#"
                    + type.getFullyQualifiedName().orElse(type.getNameAsString())));
        }
        return result.stream().distinct().sorted().toList();
    }

    private static String pathOf(String subject) {
        int separator = subject.indexOf('#');
        return separator < 0 ? subject : subject.substring(0, separator);
    }

    private static void add(TaskRuntime runtime, String taskId, String kind, String subject, Path root) throws SQLException {
        String hash;
        try {
            hash = sha256(Files.readAllBytes(root.resolve(pathOf(subject)).normalize()));
        } catch (IOException failure) {
            hash = sha256((subject + "\nmissing").getBytes(StandardCharsets.UTF_8));
        }
        runtime.addWorkItem(UUID.nameUUIDFromBytes((taskId + "\n" + kind + "\n" + subject + "\n" + hash)
                .getBytes(StandardCharsets.UTF_8)).toString(), taskId, kind, subject, hash);
    }

    private static List<String> paths(Path manifest, boolean java) throws IOException {
        JsonNode files = JSON.readTree(Files.readString(manifest)).path("files");
        if (!files.isArray()) throw new IOException("Manifest files must be an array");
        List<String> result = new ArrayList<>();
        for (JsonNode file : files) {
            String path = file.path("path").asText("").replace('\\', '/');
            boolean isJava = path.endsWith(".java") && (path.startsWith("src/main/java/") || path.startsWith("src/test/java/"));
            boolean isConfig = path.endsWith(".xml") || path.endsWith(".jsp");
            if ((java && isJava) || (!java && isConfig)) result.add(path);
        }
        return result.stream().distinct().sorted(Comparator.naturalOrder()).toList();
    }

    private static String manifestFor(String subject, List<String> javaSubjects) {
        StringBuilder json = new StringBuilder("{\"files\":[");
        boolean first = true;
        if (subject.endsWith(".xml") || subject.endsWith(".jsp")) {
            for (String operation : javaSubjects) {
                String path = pathOf(operation);
                if (!first) json.append(',');
                json.append("{\"path\":\"").append(path).append("\"}");
                first = false;
            }
        }
        if (!first) json.append(',');
        return json.append("{\"path\":\"").append(subject).append("\"}]}\n").toString();
    }

    private static String sha256(byte[] input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
