package io.handoff.evidence;

import io.handoff.runtime.RuntimeException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EvidenceManager {
    private static final Pattern PEM_PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [^-\\r\\n]*PRIVATE KEY-----.*?-----END [^-\\r\\n]*PRIVATE KEY-----",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern JDBC_USER_INFO = Pattern.compile(
            "(?i)(jdbc:[^:\\s]+://)([^/@:\\s]+:[^@/\\s]+)(@)");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?im)(\\b(?:password|passwd|secret|token|api[_-]?key|authorization)\\b[\\\"']?\\s*[:=]\\s*)"
                + "(?:\\\"([^\\\"\\r\\n]*)\\\"|'([^'\\r\\n]*)'|([^\\r\\n]*))");

    private final Connection connection;
    private final Path sourceRoot;

    public record Evidence(
            String evidenceId,
            String taskId,
            String snapshotId,
            String kind,
            String path,
            int startLine,
            int endLine,
            String contentSha256,
            String redactedExcerpt,
            String status) {}

    public EvidenceManager(Connection connection, Path sourceRoot) {
        this.connection = connection;
        this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
    }

    public Evidence createEvidence(String taskId, String kind, String path, int startLine, int endLine)
            throws SQLException, RuntimeException {
        if (taskId == null || taskId.isBlank() || kind == null || kind.isBlank()) {
            throw invalidEvidence("Task and evidence kind are required");
        }
        String normalizedPath = normalizeRepositoryPath(path);
        if (startLine < 1 || endLine < startLine) {
            throw invalidEvidence("Evidence line range is invalid");
        }

        String snapshotId = taskSnapshot(taskId);
        SnapshotFile snapshotFile = snapshotFile(snapshotId, normalizedPath);
        byte[] fileBytes = readSnapshotFile(normalizedPath);
        if (fileBytes.length != snapshotFile.sizeBytes()
                || !sha256(fileBytes).equals(snapshotFile.sha256())) {
            throw invalidEvidence("Source file does not match the immutable snapshot");
        }

        String content = decodeUtf8(fileBytes);
        LineSpan span = lineSpan(content, startLine, endLine);
        byte[] rangeBytes = content.substring(span.startOffset(), span.endOffset())
                .getBytes(StandardCharsets.UTF_8);
        String contentSha256 = sha256(rangeBytes);
        String excerpt = content.substring(span.startOffset(), span.endOffset());
        String redactedExcerpt = redact(excerpt);
        String status = redactedExcerpt.equals(excerpt) ? "VALID" : "REDACTED";
        String evidenceId = stableEvidenceId(
                snapshotId, kind, normalizedPath, startLine, endLine, contentSha256);

        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO evidence(evidence_id,task_id,snapshot_id,kind,path,start_line,end_line,"
                        + "content_sha256,redacted_excerpt,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            insert.setString(1, evidenceId);
            insert.setString(2, taskId);
            insert.setString(3, snapshotId);
            insert.setString(4, kind);
            insert.setString(5, normalizedPath);
            insert.setInt(6, startLine);
            insert.setInt(7, endLine);
            insert.setString(8, contentSha256);
            insert.setString(9, redactedExcerpt);
            insert.setString(10, status);
            insert.setString(11, Instant.now().toString());
            insert.executeUpdate();
        }
        return findEvidence(evidenceId);
    }

    private String taskSnapshot(String taskId) throws SQLException, RuntimeException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT snapshot_id FROM task_state WHERE task_id=?")) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new RuntimeException("E_PRECONDITION", "Unknown task");
                }
                return result.getString(1);
            }
        }
    }

    private SnapshotFile snapshotFile(String snapshotId, String path) throws SQLException, RuntimeException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT size_bytes,sha256 FROM snapshot_file WHERE snapshot_id=? AND path=?")) {
            statement.setString(1, snapshotId);
            statement.setString(2, path);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw invalidEvidence("File is not part of the task snapshot");
                }
                return new SnapshotFile(result.getLong(1), result.getString(2));
            }
        }
    }

    private byte[] readSnapshotFile(String relativePath) throws RuntimeException {
        try {
            Path realRoot = sourceRoot.toRealPath();
            Path realFile = sourceRoot.resolve(relativePath).normalize().toRealPath();
            if (!realFile.startsWith(realRoot) || !Files.isRegularFile(realFile)) {
                throw invalidEvidence("Source path is outside the snapshot root");
            }
            return Files.readAllBytes(realFile);
        } catch (IOException failure) {
            throw invalidEvidence("Snapshot source file is unavailable");
        }
    }

    private Evidence findEvidence(String evidenceId) throws SQLException, RuntimeException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT evidence_id,task_id,snapshot_id,kind,path,start_line,end_line,content_sha256,"
                        + "redacted_excerpt,status FROM evidence WHERE evidence_id=?")) {
            statement.setString(1, evidenceId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw invalidEvidence("Evidence could not be persisted");
                }
                return new Evidence(
                        result.getString(1),
                        result.getString(2),
                        result.getString(3),
                        result.getString(4),
                        result.getString(5),
                        result.getInt(6),
                        result.getInt(7),
                        result.getString(8),
                        result.getString(9),
                        result.getString(10));
            }
        }
    }

    private static String normalizeRepositoryPath(String path) throws RuntimeException {
        if (path == null || path.isBlank() || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            throw invalidEvidence("Evidence path must be a normalized repository-relative path");
        }
        try {
            Path parsed = Path.of(path);
            Path normalized = parsed.normalize();
            if (parsed.isAbsolute()
                    || normalized.getNameCount() == 0
                    || normalized.startsWith("..")
                    || !normalized.toString().replace('\\', '/').equals(path)) {
                throw invalidEvidence("Evidence path must be a normalized repository-relative path");
            }
            return path;
        } catch (InvalidPathException failure) {
            throw invalidEvidence("Evidence path is invalid");
        }
    }

    private static String decodeUtf8(byte[] bytes) throws RuntimeException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw invalidEvidence("Snapshot source file is not valid UTF-8");
        }
    }

    private static LineSpan lineSpan(String content, int startLine, int endLine) throws RuntimeException {
        List<Integer> lineStarts = new ArrayList<>();
        lineStarts.add(0);
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current == '\r') {
                if (index + 1 < content.length() && content.charAt(index + 1) == '\n') {
                    index++;
                }
                lineStarts.add(index + 1);
            } else if (current == '\n') {
                lineStarts.add(index + 1);
            }
        }

        boolean endsWithLineBreak = content.endsWith("\n") || content.endsWith("\r");
        int lineCount = content.isEmpty() ? 0 : lineStarts.size() - (endsWithLineBreak ? 1 : 0);
        if (endLine > lineCount) {
            throw invalidEvidence("Evidence line range exceeds the snapshot file");
        }
        int startOffset = lineStarts.get(startLine - 1);
        int endOffset = endLine < lineStarts.size() ? lineStarts.get(endLine) : content.length();
        return new LineSpan(startOffset, endOffset);
    }

    private static String redact(String content) {
        String redacted = replaceMatches(PEM_PRIVATE_KEY, content, match -> placeholder(match.group()));
        redacted = replaceMatches(JDBC_USER_INFO, redacted,
                match -> match.group(1) + placeholder(match.group(2)) + match.group(3));
        return replaceMatches(SENSITIVE_ASSIGNMENT, redacted,
            match -> {
                String secret = match.group(2) != null ? match.group(2)
                    : match.group(3) != null ? match.group(3) : match.group(4);
                String quote = match.group(2) != null ? "\"" : match.group(3) != null ? "'" : "";
                return match.group(1) + quote + placeholder(secret) + quote;
            });
    }

    private static String replaceMatches(Pattern pattern, String content, Replacement replacement) {
        Matcher matcher = pattern.matcher(content);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement.replace(matcher)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String placeholder(String sensitiveContent) {
        return "[REDACTED:" + sha256(sensitiveContent.getBytes(StandardCharsets.UTF_8)).substring(0, 12) + "]";
    }

    private static String stableEvidenceId(
            String snapshotId, String kind, String path, int startLine, int endLine, String contentSha256) {
        String identity = String.join("\0", snapshotId, kind, path,
                Integer.toString(startLine), Integer.toString(endLine), contentSha256);
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static RuntimeException invalidEvidence(String message) {
        return new RuntimeException("E_EVIDENCE_INVALID", message);
    }

    private record SnapshotFile(long sizeBytes, String sha256) {}

    private record LineSpan(int startOffset, int endOffset) {}

    @FunctionalInterface
    private interface Replacement {
        String replace(Matcher matcher);
    }
}