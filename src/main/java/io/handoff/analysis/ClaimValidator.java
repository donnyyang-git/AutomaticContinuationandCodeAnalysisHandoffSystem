package io.handoff.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.handoff.evidence.EvidenceManager;
import io.handoff.runtime.RuntimeException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ClaimValidator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ANALYSIS_SCHEMA_VERSION = "1.0.0";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final Pattern DECLARATION_KIND = Pattern.compile(
            "\\b(class|interface|enum|record|annotation)\\b", Pattern.CASE_INSENSITIVE);
        private static final Pattern NEGATED_DECLARATION = Pattern.compile(
            "\\b(?:not|never|isn't|is not|cannot be)\\s+(?:a\\s+|an\\s+)?$",
            Pattern.CASE_INSENSITIVE);
            private static final Pattern NEGATED_CALL = Pattern.compile(
                "\\b(?:does not|doesn't|never|cannot|can't|no)\\s+(?:directly\\s+)?call\\b",
                Pattern.CASE_INSENSITIVE);
    private static final Map<String, Set<String>> ALLOWED_EVIDENCE_KINDS = Map.of(
            "ARCHITECTURE", Set.of("SOURCE_RANGE", "DECLARATION", "SYMBOL"),
            "RESPONSIBILITY", Set.of("SOURCE_RANGE", "DECLARATION", "SYMBOL"),
            "DATA_FLOW", Set.of("SOURCE_RANGE", "DIRECT_CALL"),
            "CALL_RELATIONSHIP_INTERPRETATION", Set.of("DIRECT_CALL", "SOURCE_RANGE"),
            "ENTRY_POINT", Set.of("SOURCE_RANGE", "DECLARATION", "SYMBOL"),
            "RISK", Set.of("SOURCE_RANGE", "DIRECT_CALL", "XML_MAPS", "VIEW_ENTRY"));

    public record ParserFact(String factType, String subject, String authoritativeValue) {}

    public record ValidatedClaim(
            String resultId,
            String logicalClaimId,
            int revision,
            String claimType,
            String subject,
            String claim,
            String confidence,
            String status,
            String reasoning,
            List<String> evidenceRefs) {}

    public record Result(List<ValidatedClaim> claims, boolean reviewRequired) {
        public Result {
            claims = List.copyOf(claims);
        }
    }

    private final Connection connection;
    private final EvidenceManager evidenceManager;
    private final Schema schema;

    public ClaimValidator(Connection connection, EvidenceManager evidenceManager, String schemaJson)
            throws AnalysisProviderException {
        this.connection = connection;
        this.evidenceManager = evidenceManager;
        try {
            this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(schemaJson, InputFormat.JSON);
        } catch (java.lang.RuntimeException failure) {
            throw new AnalysisProviderException("E_SCHEMA_INVALID", "Analysis JSON Schema is invalid", failure);
        }
    }

    public Result persist(String taskId, String operationId,
            OpenAiCompatibleAnalysisProvider.Invocation invocation, List<ParserFact> parserFacts)
            throws SQLException, AnalysisProviderException {
        requireCallerTransaction();
        TaskContext task = taskContext(taskId, operationId);
        JsonNode parsed = validateInvocation(invocation);
        List<PreparedClaim> claims = validateClaims(task, parsed, parserFacts == null ? List.of() : parserFacts);

        String now = TIMESTAMP_FORMAT.format(Instant.now());
        persistInvocation(operationId, invocation, now);
        List<ValidatedClaim> persisted = new ArrayList<>();
        boolean reviewRequired = false;
        for (PreparedClaim claim : claims) {
            insertResult(task, operationId, invocation, claim, now);
            for (String evidenceId : claim.evidenceRefs()) {
                insertEvidenceLink(claim.resultId(), evidenceId);
            }
            if (claim.reviewReason() != null) {
                openReview(taskId, claim.reviewReason(), task.state(), now);
                reviewRequired = true;
            }
            persisted.add(claim.toValidatedClaim());
        }
        if (reviewRequired) {
            markTaskForReview(taskId, now);
        }
        return new Result(persisted, reviewRequired);
    }

    private void requireCallerTransaction() throws AnalysisProviderException, SQLException {
        if (connection.getAutoCommit()) {
            throw new AnalysisProviderException(
                    "E_PRECONDITION", "Claim persistence requires an active Runtime transaction");
        }
    }

    private TaskContext taskContext(String taskId, String operationId)
            throws SQLException, AnalysisProviderException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT t.snapshot_id,t.state,w.status FROM task_state t "
                        + "JOIN work_item w ON w.task_id=t.task_id "
                        + "WHERE t.task_id=? AND w.operation_id=?")) {
            statement.setString(1, taskId);
            statement.setString(2, operationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AnalysisProviderException("E_PRECONDITION", "Task operation does not exist");
                }
                String status = result.getString(3);
                if (!("PENDING".equals(status) || "RUNNING".equals(status))) {
                    throw new AnalysisProviderException("E_PRECONDITION", "Task operation is not writable");
                }
                return new TaskContext(taskId, result.getString(1), result.getString(2));
            }
        }
    }

    private JsonNode validateInvocation(OpenAiCompatibleAnalysisProvider.Invocation invocation)
            throws AnalysisProviderException {
        if (invocation == null || invocation.parsedResponse() == null || invocation.rawOutput() == null) {
            throw schemaFailure("Analysis invocation is incomplete");
        }
        List<com.networknt.schema.Error> errors = schema.validate(invocation.parsedResponse().toString(), InputFormat.JSON);
        if (!errors.isEmpty()) {
            throw schemaFailure("Analysis response does not match the claim schema");
        }
        try {
            JsonNode parsedRawOutput = JSON.readTree(invocation.rawOutput());
            if (!invocation.parsedResponse().equals(parsedRawOutput)
                    || !sha256(invocation.rawOutput()).equals(invocation.rawOutputSha256())) {
                throw schemaFailure("Analysis invocation raw output does not match its parsed response");
            }
            return parsedRawOutput;
        } catch (JsonProcessingException failure) {
            throw new AnalysisProviderException("E_SCHEMA_INVALID", "Analysis invocation raw output is invalid JSON", failure);
        }
    }

    private List<PreparedClaim> validateClaims(TaskContext task, JsonNode response, List<ParserFact> parserFacts)
            throws SQLException, AnalysisProviderException {
        List<PreparedClaim> claims = new ArrayList<>();
        Set<String> seenLogicalClaimIds = new HashSet<>();
        for (JsonNode claim : response.path("claims")) {
            String logicalClaimId = claim.path("logical_claim_id").asText();
            if (!seenLogicalClaimIds.add(logicalClaimId)) {
                throw schemaFailure("Analysis response contains a duplicate logical claim ID");
            }
            String claimType = claim.path("claim_type").asText();
            String subject = AnalysisPromptBuilder.redactSensitiveContent(claim.path("subject").asText());
            String claimText = AnalysisPromptBuilder.redactSensitiveContent(claim.path("claim").asText());
            String reasoning = AnalysisPromptBuilder.redactSensitiveContent(claim.path("reasoning").asText());
            String status = claim.path("status").asText();
            List<String> evidenceRefs = new ArrayList<>();
            for (JsonNode reference : claim.path("evidence_refs")) {
                String evidenceId = reference.asText();
                EvidenceManager.Evidence evidence;
                try {
                    evidence = evidenceManager.validateEvidence(task.taskId(), evidenceId);
                } catch (RuntimeException failure) {
                    throw new AnalysisProviderException("E_EVIDENCE_INVALID", "Claim references invalid evidence");
                }
                if (!evidence.snapshotId().equals(task.snapshotId())
                        || !ALLOWED_EVIDENCE_KINDS.get(claimType).contains(evidence.kind())) {
                    throw new AnalysisProviderException("E_EVIDENCE_INVALID", "Claim evidence is outside its allowed kind or snapshot");
                }
                evidenceRefs.add(evidenceId);
            }

            String reviewReason = null;
            if ("CONFIRMED".equals(status) && evidenceRefs.isEmpty()) {
                status = "UNSUPPORTED";
                reviewReason = "UNSUPPORTED_CONFIRMED_CLAIM";
            } else if ("INFERRED".equals(status) && evidenceRefs.isEmpty()) {
                throw new AnalysisProviderException("E_EVIDENCE_INVALID", "Inferred claim requires evidence");
            } else if ("INFERRED".equals(status) && reasoning.isBlank()) {
                throw schemaFailure("Inferred claim requires an explicit reason");
            }

            if (hasParserContradiction(claimType, subject, claimText, parserFacts)) {
                status = "REQUIRES_REVIEW";
                reviewReason = "PARSER_CONTRADICTION";
            }
            if ("REQUIRES_REVIEW".equals(status) || "CONFLICTING".equals(status)) {
                reviewReason = "MODEL_REQUIRES_REVIEW";
            }

            Revision revision = nextRevision(logicalClaimId, task.taskId(), task.snapshotId(),
                    claimType, subject, claimText, claim.path("confidence").asText());
            if (revision.conflicts()) {
                status = "CONFLICTING";
                reviewReason = "CONFLICTING_CLAIM_REVISION";
            }
            claims.add(new PreparedClaim(
                    uuidV7(), logicalClaimId, revision.number(), claimType, subject, claimText,
                    claim.path("confidence").asText(), status,
                    "INFERRED".equals(status) ? reasoning : null,
                    List.copyOf(evidenceRefs), reviewReason));
        }
        return claims;
    }

    private Revision nextRevision(String logicalClaimId, String taskId, String snapshotId,
            String claimType, String subject, String claimText, String confidence)
            throws SQLException, AnalysisProviderException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT revision,task_id,snapshot_id,claim_type,subject,claim_text,confidence "
                        + "FROM analysis_result WHERE logical_claim_id=? ORDER BY revision DESC LIMIT 1")) {
            statement.setString(1, logicalClaimId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return new Revision(1, false);
                }
                if (!taskId.equals(result.getString(2)) || !snapshotId.equals(result.getString(3))) {
                    throw new AnalysisProviderException("E_EVIDENCE_INVALID", "Logical claim belongs to another task snapshot");
                }
                boolean conflicts = !normalize(result.getString(4)).equals(normalize(claimType))
                        || !normalize(result.getString(5)).equals(normalize(subject))
                        || !normalize(result.getString(6)).equals(normalize(claimText))
                        || !normalize(result.getString(7)).equals(normalize(confidence));
                return new Revision(result.getInt(1) + 1, conflicts);
            }
        }
    }

    private void persistInvocation(String operationId,
            OpenAiCompatibleAnalysisProvider.Invocation invocation, String now) throws SQLException {
        String safeJson;
        try {
            safeJson = JSON.writeValueAsString(redactJson(JSON.readTree(invocation.rawOutput())));
        } catch (JsonProcessingException failure) {
            throw new SQLException("Validated model output could not be sanitized for persistence", failure);
        }
        String safeRawOutputHash = sha256(safeJson);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO model_invocation(invocation_id,operation_id,provider,endpoint,api_mode,model_tag,model_digest,"
                        + "model_pin_status,prompt_version,input_sha256,evidence_query_sha256,sampling_json,raw_output,"
                        + "raw_output_sha256,schema_valid,error_text,retry_number,started_at,ended_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,NULL,?,?,?)")) {
            statement.setString(1, uuidV7());
            statement.setString(2, operationId);
            statement.setString(3, invocation.provider());
            statement.setString(4, invocation.endpoint());
            statement.setString(5, invocation.apiMode());
            statement.setString(6, invocation.modelTag());
            statement.setString(7, invocation.modelDigest());
            statement.setString(8, invocation.modelPinStatus());
            statement.setString(9, invocation.promptVersion());
            statement.setString(10, invocation.inputSha256());
            statement.setString(11, invocation.evidenceQuerySha256());
            statement.setString(12, AnalysisPromptBuilder.redactSensitiveContent(invocation.samplingJson()));
            statement.setString(13, safeJson);
            statement.setString(14, safeRawOutputHash);
            statement.setInt(15, invocation.retryNumber());
            statement.setString(16, invocation.startedAt());
            statement.setString(17, now);
            statement.executeUpdate();
        }
    }

    private static JsonNode redactJson(JsonNode value) {
        if (value.isObject()) {
            ObjectNode redacted = JSON.createObjectNode();
            value.fields().forEachRemaining(field -> redacted.set(field.getKey(), redactJson(field.getValue())));
            return redacted;
        }
        if (value.isArray()) {
            ArrayNode redacted = JSON.createArrayNode();
            value.forEach(item -> redacted.add(redactJson(item)));
            return redacted;
        }
        if (value.isTextual()) {
            return JSON.getNodeFactory().textNode(
                    AnalysisPromptBuilder.redactSensitiveContent(value.asText()));
        }
        return value.deepCopy();
    }

    private void insertResult(TaskContext task, String operationId,
            OpenAiCompatibleAnalysisProvider.Invocation invocation, PreparedClaim claim, String now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO analysis_result(result_id,logical_claim_id,task_id,snapshot_id,operation_id,revision,"
                        + "claim_type,subject,claim_text,confidence,status,inference_reason,model_tag,prompt_version,"
                        + "schema_version,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, claim.resultId());
            statement.setString(2, claim.logicalClaimId());
            statement.setString(3, task.taskId());
            statement.setString(4, task.snapshotId());
            statement.setString(5, operationId);
            statement.setInt(6, claim.revision());
            statement.setString(7, claim.claimType());
            statement.setString(8, claim.subject());
            statement.setString(9, claim.claim());
            statement.setString(10, claim.confidence());
            statement.setString(11, claim.status());
            statement.setString(12, claim.reasoning());
            statement.setString(13, invocation.modelTag());
            statement.setString(14, invocation.promptVersion());
            statement.setString(15, ANALYSIS_SCHEMA_VERSION);
            statement.setString(16, now);
            statement.executeUpdate();
        }
    }

    private void insertEvidenceLink(String resultId, String evidenceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO analysis_result_evidence(result_id,evidence_id) VALUES(?,?)")) {
            statement.setString(1, resultId);
            statement.setString(2, evidenceId);
            statement.executeUpdate();
        }
    }

    private void openReview(String taskId, String reason, String originalState, String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO review(review_id,task_id,reason_code,status,original_state,created_at) "
                        + "VALUES(?,?,?,'OPEN',?,?)")) {
            statement.setString(1, uuidV7());
            statement.setString(2, taskId);
            statement.setString(3, reason);
            statement.setString(4, originalState);
            statement.setString(5, now);
            statement.executeUpdate();
        }
    }

    private void markTaskForReview(String taskId, String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE task_state SET state='REQUIRES_REVIEW',version=version+1,updated_at=? WHERE task_id=?")) {
            statement.setString(1, now);
            statement.setString(2, taskId);
            statement.executeUpdate();
        }
    }

    private static boolean hasParserContradiction(
            String claimType, String subject, String claimText, List<ParserFact> facts) {
        for (ParserFact fact : facts) {
            if (!subject.equals(fact.subject())) {
                continue;
            }
            if ("DECLARATION".equals(fact.factType())) {
                Matcher mentionedKind = DECLARATION_KIND.matcher(claimText);
                while (mentionedKind.find()) {
                    String prefix = claimText.substring(0, mentionedKind.start());
                    boolean negated = NEGATED_DECLARATION.matcher(prefix).find();
                    boolean matches = normalize(mentionedKind.group(1)).equals(normalize(fact.authoritativeValue()));
                    if (negated == matches) {
                        return true;
                    }
                }
            } else if ("DIRECT_CALL".equals(fact.factType()) && NEGATED_CALL.matcher(claimText).find()) {
                String target = fact.authoritativeValue();
                int arrow = target.lastIndexOf("->");
                if (arrow >= 0) {
                    target = target.substring(arrow + 2).trim();
                }
                if (normalize(claimText).contains(normalize(target))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static AnalysisProviderException schemaFailure(String message) {
        return new AnalysisProviderException("E_SCHEMA_INVALID", message);
    }

    private static String uuidV7() {
        long timestamp = Instant.now().toEpochMilli();
        long random = ThreadLocalRandom.current().nextLong();
        long mostSignificantBits = (timestamp << 16) | 0x7000L | (random & 0x0fffL);
        long leastSignificantBits = (ThreadLocalRandom.current().nextLong() & 0x3fffffffffffffffL)
                | 0x8000000000000000L;
        return new UUID(mostSignificantBits, leastSignificantBits).toString();
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record TaskContext(String taskId, String snapshotId, String state) {}

    private record Revision(int number, boolean conflicts) {}

    private record PreparedClaim(
            String resultId,
            String logicalClaimId,
            int revision,
            String claimType,
            String subject,
            String claim,
            String confidence,
            String status,
            String reasoning,
            List<String> evidenceRefs,
            String reviewReason) {
        private ValidatedClaim toValidatedClaim() {
            return new ValidatedClaim(resultId, logicalClaimId, revision, claimType, subject,
                    claim, confidence, status, reasoning, evidenceRefs);
        }
    }
}