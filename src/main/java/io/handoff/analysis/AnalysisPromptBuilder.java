package io.handoff.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AnalysisPromptBuilder {
    public static final String PROMPT_VERSION = "claim-v1.0.0";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PEM_PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [^-\\r\\n]*PRIVATE KEY-----.*?-----END [^-\\r\\n]*PRIVATE KEY-----",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern JDBC_USER_INFO = Pattern.compile(
            "(?i)(jdbc:[^:\\s]+://)([^/@:\\s]+:[^@/\\s]+)(@)");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?im)(\\b(?:password|passwd|secret|token|api[_-]?key|authorization)\\b[\\\"']?\\s*[:=]\\s*)"
                    + "(?:\\\"([^\\\"\\r\\n]*)\\\"|'([^'\\r\\n]*)'|([^\\r\\n]*))");
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile(
            "(?i)(?:[a-z]:[\\\\/]|\\\\\\\\)[^\\s\\\"'<>|,;]+\\S*");
    private static final Pattern UNIX_ABSOLUTE_PATH = Pattern.compile(
            "(?<![:/\\w])/(?:[^/\\s\\\"'<>|]+/)*[^/\\s\\\"'<>|]+");

    public record EvidenceExcerpt(String evidenceId, String path, int startLine, int endLine, String content) {
        public EvidenceExcerpt {
            if (evidenceId == null || evidenceId.isBlank()
                    || path == null || path.isBlank()
                    || startLine < 1 || endLine < startLine
                    || content == null) {
                throw new IllegalArgumentException("Evidence excerpt is incomplete");
            }
        }
    }

    public record Input(
            String taskId,
            String subject,
            List<String> authoritativeParserFacts,
            List<EvidenceExcerpt> evidence) {
        public Input {
            if (taskId == null || taskId.isBlank() || subject == null || subject.isBlank()) {
                throw new IllegalArgumentException("Task ID and subject are required");
            }
            authoritativeParserFacts = List.copyOf(authoritativeParserFacts);
            evidence = List.copyOf(evidence);
        }
    }

    public record Prompt(String systemMessage, String userMessage, String evidenceQueryJson) {}

    public Prompt build(Input input, JsonNode claimSchema) {
        if (input == null || claimSchema == null || claimSchema.isNull()) {
            throw new IllegalArgumentException("Bounded analysis input and claim schema are required");
        }
        try {
            ObjectNode context = JSON.createObjectNode();
            context.put("task_id", sanitize(input.taskId()));
            context.put("subject", sanitize(input.subject()));

            ArrayNode parserFacts = context.putArray("authoritative_parser_facts");
            for (String fact : input.authoritativeParserFacts()) {
                parserFacts.add(sanitize(fact));
            }

            ArrayNode evidence = context.putArray("evidence");
            for (EvidenceExcerpt excerpt : input.evidence()) {
                ObjectNode item = evidence.addObject();
                item.put("evidence_id", excerpt.evidenceId());
                item.put("path", sanitize(excerpt.path()));
                item.put("start_line", excerpt.startLine());
                item.put("end_line", excerpt.endLine());
                item.put("content", sanitize(excerpt.content()));
            }

            String evidenceQueryJson = JSON.writeValueAsString(evidence);
            context.set("claim_schema", claimSchema.deepCopy());
            String userMessage = JSON.writeValueAsString(context);
            return new Prompt(systemMessage(), userMessage, evidenceQueryJson);
        } catch (JsonProcessingException impossible) {
            throw new IllegalArgumentException("Unable to serialize bounded analysis input", impossible);
        }
    }

    public static String redactSensitiveContent(String value) {
        return sanitize(value);
    }

    private static String systemMessage() {
        return "Analyze only the supplied task, subject, authoritative parser facts, and bounded evidence. "
                + "Parser facts are authoritative and must not be contradicted. Do not infer facts beyond the "
                + "provided evidence. Return only JSON matching the supplied claim schema. When evidence is "
                + "insufficient, use UNKNOWN or UNSUPPORTED claims and explain the uncertainty in unknowns. "
                + "Do not include unsupported claims as CONFIRMED.";
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String redacted = replace(PEM_PRIVATE_KEY, value, match -> placeholder(match.group()));
        redacted = replace(JDBC_USER_INFO, redacted,
                match -> match.group(1) + placeholder(match.group(2)) + match.group(3));
        redacted = replace(WINDOWS_ABSOLUTE_PATH, redacted, match -> pathPlaceholder(match.group()));
        redacted = replace(UNIX_ABSOLUTE_PATH, redacted, match -> pathPlaceholder(match.group()));
        return replace(SENSITIVE_ASSIGNMENT, redacted, match -> {
            String secret = match.group(2) != null ? match.group(2)
                    : match.group(3) != null ? match.group(3) : match.group(4);
            String quote = match.group(2) != null ? "\"" : match.group(3) != null ? "'" : "";
            return match.group(1) + quote + placeholder(secret) + quote;
        });
    }

    private static String replace(Pattern pattern, String value, Replacement replacement) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement.replace(matcher)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String placeholder(String value) {
        return "[REDACTED:" + sha256(value).substring(0, 12) + "]";
    }

    private static String pathPlaceholder(String value) {
        return "[ABSOLUTE_PATH:" + sha256(value).substring(0, 12) + "]";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @FunctionalInterface
    private interface Replacement {
        String replace(Matcher matcher);
    }
}