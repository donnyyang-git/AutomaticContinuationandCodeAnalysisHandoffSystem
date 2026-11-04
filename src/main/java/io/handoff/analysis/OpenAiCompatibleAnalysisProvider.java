package io.handoff.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

public final class OpenAiCompatibleAnalysisProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TOKENS = 1024;
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    public enum Provider {
        OLLAMA("ollama"),
        LM_STUDIO("lm-studio");

        private final String value;

        Provider(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public static final class Settings {
        private final Provider provider;
        private final URI endpoint;
        private final String apiMode;
        private final String modelTag;
        private final String modelDigest;
        private final Duration timeout;

        private Settings(Provider provider, URI endpoint, String apiMode, String modelTag,
                String modelDigest, Duration timeout) {
            this.provider = provider;
            this.endpoint = endpoint;
            this.apiMode = apiMode;
            this.modelTag = modelTag;
            this.modelDigest = modelDigest;
            this.timeout = timeout;
        }

        public static Settings fromEnvironmentManifest(
                JsonNode manifest, String modelTag, String modelDigest, Duration timeout)
                throws AnalysisProviderException {
            if (manifest == null || !"READY".equals(manifest.path("status").asText())) {
                throw new AnalysisProviderException("E_ENV_VERSION", "Environment manifest is not READY");
            }
            JsonNode llm = manifest.path("llm");
            Provider provider = OpenAiCompatibleAnalysisProvider.provider(llm.path("provider").asText());
            String endpointValue = llm.path("endpoint").asText();
            String apiMode = llm.path("api_mode").asText();
            if (!"openai-compatible".equals(apiMode)) {
                throw new AnalysisProviderException("E_ENV_VERSION", "Environment manifest API mode is unsupported");
            }
            if (modelTag == null || modelTag.isBlank() || !manifestListsModel(manifest, modelTag)) {
                throw new AnalysisProviderException("E_ENV_VERSION", "Model identifier is not listed in the READY environment manifest");
            }
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("A positive provider timeout is required");
            }
            try {
                URI endpoint = URI.create(endpointValue);
                requestUri(endpoint);
                return new Settings(provider, endpoint, apiMode, modelTag, modelDigest, timeout);
            } catch (IllegalArgumentException failure) {
                throw new AnalysisProviderException("E_ENV_VERSION", "Environment manifest endpoint is invalid", failure);
            }
        }

        public Provider provider() { return provider; }
        public URI endpoint() { return endpoint; }
        public String apiMode() { return apiMode; }
        public String modelTag() { return modelTag; }
        public String modelDigest() { return modelDigest; }
        public Duration timeout() { return timeout; }
    }

    private static Provider provider(String value) throws AnalysisProviderException {
        for (Provider candidate : Provider.values()) {
            if (candidate.value().equals(value)) {
                return candidate;
            }
        }
        throw new AnalysisProviderException("E_ENV_VERSION", "Environment manifest provider is unsupported");
    }

    private static boolean manifestListsModel(JsonNode manifest, String modelTag) {
        JsonNode models = manifest.path("models");
        if (!models.isArray()) {
            return false;
        }
        for (JsonNode model : models) {
            if (modelTag.equals(model.path("tag").asText())) {
                return true;
            }
        }
        return false;
    }

    public record Invocation(
            String provider,
            String endpoint,
            String apiMode,
            String modelTag,
            String modelDigest,
            String modelPinStatus,
            String promptVersion,
            String samplingJson,
            String inputSha256,
            String evidenceQuerySha256,
            String rawOutput,
            String rawOutputSha256,
            JsonNode parsedResponse,
            String startedAt,
            int retryNumber,
            String requestBody) {}

    private final AnalysisPromptBuilder promptBuilder;
    private final HttpClient httpClient;

    public OpenAiCompatibleAnalysisProvider() {
        this(new AnalysisPromptBuilder(), HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build());
    }

    OpenAiCompatibleAnalysisProvider(AnalysisPromptBuilder promptBuilder, HttpClient httpClient) {
        this.promptBuilder = promptBuilder;
        this.httpClient = httpClient;
    }

    public Invocation analyze(Settings settings, AnalysisPromptBuilder.Input input, JsonNode claimSchema)
            throws AnalysisProviderException {
        URI requestUri = requestUri(settings.endpoint());
        AnalysisPromptBuilder.Prompt prompt = promptBuilder.build(input, claimSchema);
        String startedAt = TIMESTAMP_FORMAT.format(Instant.now());
        String requestBody = requestBody(settings.modelTag(), prompt, claimSchema);
        String rawResponse = send(settings, requestUri, requestBody);
        String rawOutput = extractContent(rawResponse);
        JsonNode parsedResponse = parseCompletion(rawOutput);
        String samplingJson = "{\"max_tokens\":1024,\"reasoning_effort\":\"none\",\"seed\":42,"
            + "\"stream\":false,\"temperature\":0,\"top_p\":1}";
        String promptInput = prompt.systemMessage() + "\n" + prompt.userMessage();

        return new Invocation(
                settings.provider().value(),
                settings.endpoint().toString(),
                settings.apiMode(),
                settings.modelTag(),
                settings.modelDigest(),
                settings.modelDigest() == null || settings.modelDigest().isBlank() ? "UNPINNED_MODEL" : "PINNED",
                AnalysisPromptBuilder.PROMPT_VERSION,
                samplingJson,
                sha256(promptInput),
                sha256(prompt.evidenceQueryJson()),
                rawOutput,
                sha256(rawOutput),
                parsedResponse,
                startedAt,
                0,
                requestBody);
    }

    private String send(Settings settings, URI requestUri, String requestBody) throws AnalysisProviderException {
        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(settings.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String code = response.body().toLowerCase().contains("model not found")
                        ? "E_MODEL_MISSING" : "E_LLM_UNAVAILABLE";
                throw new AnalysisProviderException(code, "Local analysis provider returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AnalysisProviderException("E_LLM_UNAVAILABLE", "Local analysis request was interrupted", failure);
        } catch (IOException failure) {
            throw new AnalysisProviderException("E_LLM_UNAVAILABLE", "Local analysis provider is unavailable", failure);
        }
    }

    private static URI requestUri(URI endpoint) throws AnalysisProviderException {
        String host = endpoint.getHost();
        boolean loopback = host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]"));
        String path = endpoint.getPath() == null ? "" : endpoint.getPath();
        if (!"http".equalsIgnoreCase(endpoint.getScheme()) || !loopback
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !(path.isEmpty() || path.equals("/") || path.equals("/v1"))) {
            throw new AnalysisProviderException("E_ENV_VERSION", "Analysis provider endpoint must be a loopback HTTP URL");
        }
        try {
            return new URI("http", null, host, endpoint.getPort(), "/v1/chat/completions", null, null);
        } catch (java.net.URISyntaxException failure) {
            throw new AnalysisProviderException("E_ENV_VERSION", "Analysis provider endpoint is invalid", failure);
        }
    }

    private static String requestBody(String modelTag, AnalysisPromptBuilder.Prompt prompt, JsonNode schema)
            throws AnalysisProviderException {
        try {
            ObjectNode request = JSON.createObjectNode();
            request.put("model", modelTag);
            ArrayNode messages = request.putArray("messages");
            messages.addObject().put("role", "system").put("content", prompt.systemMessage());
            messages.addObject().put("role", "user").put("content", prompt.userMessage());
            request.put("temperature", 0);
            request.put("top_p", 1);
            request.put("seed", 42);
            request.put("max_tokens", MAX_TOKENS);
            request.put("reasoning_effort", "none");
            request.put("stream", false);
            ObjectNode responseFormat = request.putObject("response_format");
            responseFormat.put("type", "json_schema");
            ObjectNode jsonSchema = responseFormat.putObject("json_schema");
            jsonSchema.put("name", "analysis_claims");
            jsonSchema.put("strict", true);
            jsonSchema.set("schema", schema.deepCopy());
            return JSON.writeValueAsString(request);
        } catch (JsonProcessingException failure) {
            throw new AnalysisProviderException("E_SCHEMA_INVALID", "Unable to build structured analysis request", failure);
        }
    }

    private static String extractContent(String rawResponse) throws AnalysisProviderException {
        try {
            JsonNode root = JSON.readTree(rawResponse);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                throw new AnalysisProviderException("E_SCHEMA_INVALID", "Provider response has no message content");
            }
            return content.asText();
        } catch (JsonProcessingException failure) {
            throw new AnalysisProviderException("E_SCHEMA_INVALID", "Provider response is not valid JSON", failure);
        }
    }

    private static JsonNode parseCompletion(String rawOutput) throws AnalysisProviderException {
        try {
            return JSON.readTree(rawOutput);
        } catch (JsonProcessingException failure) {
            throw new AnalysisProviderException("E_SCHEMA_INVALID", "Model completion is not valid JSON", failure);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}