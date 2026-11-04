package io.handoff.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleAnalysisProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COMPLETION = "{\"claims\":[],\"unknowns\":[],\"next_steps\":[]}";
    private static final JsonNode CLAIM_SCHEMA = claimSchema();

    @Test
    void bothProvidersUseLoopbackChatCompletionsAndReturnParsedJson() throws Exception {
        for (OpenAiCompatibleAnalysisProvider.Provider provider : OpenAiCompatibleAnalysisProvider.Provider.values()) {
            AtomicReference<String> requestBody = new AtomicReference<>();
            HttpServer server = startServer(exchange -> {
                assertEquals("POST", exchange.getRequestMethod());
                assertEquals("/v1/chat/completions", exchange.getRequestURI().getPath());
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":"
                        + JSON.writeValueAsString(COMPLETION) + "}}]}");
            });
            try {
                URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                        + (provider == OpenAiCompatibleAnalysisProvider.Provider.LM_STUDIO ? "/v1" : ""));
                var settings = settings(provider, endpoint, "local/model-a", null, Duration.ofSeconds(2));

                var invocation = new OpenAiCompatibleAnalysisProvider().analyze(
                        settings, input(), CLAIM_SCHEMA);

                JsonNode request = JSON.readTree(requestBody.get());
                assertEquals("local/model-a", request.path("model").asText());
                assertEquals(0, request.path("temperature").asInt());
                assertEquals(1, request.path("top_p").asInt());
                assertEquals(42, request.path("seed").asInt());
                assertFalse(request.path("stream").asBoolean());
                assertEquals("json_schema", request.path("response_format").path("type").asText());
                assertFalse(requestBody.get().contains("local-secret"));
                assertEquals(provider.value(), invocation.provider());
                assertEquals(endpoint.toString(), invocation.endpoint());
                assertEquals("openai-compatible", invocation.apiMode());
                assertEquals("UNPINNED_MODEL", invocation.modelPinStatus());
                assertEquals(COMPLETION, invocation.rawOutput());
                assertTrue(invocation.parsedResponse().path("claims").isArray());
                assertEquals("claim-v1.0.0", invocation.promptVersion());
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void promptContainsOnlyBoundedContextAndRedactsSecretsAndAbsolutePaths() throws Exception {
        var prompt = new AnalysisPromptBuilder().build(input(), CLAIM_SCHEMA);
        String content = prompt.systemMessage() + prompt.userMessage();

        assertTrue(content.contains("Account service"));
        assertTrue(content.contains("parser: Account is a class"));
        assertTrue(content.contains("evidence-1"));
        assertTrue(content.contains("unknowns"));
        assertFalse(content.contains("C:\\private\\source"));
        assertFalse(content.contains("local-secret"));
        assertFalse(content.contains("conversation history"));
    }

    @Test
        void rejectsNonLoopbackEndpointsBeforeSendingRequests() {
        AnalysisProviderException failure = assertThrows(AnalysisProviderException.class,
            () -> settings(OpenAiCompatibleAnalysisProvider.Provider.OLLAMA,
                URI.create("https://example.com/v1"), "model-a", null, Duration.ofSeconds(1)));

        assertEquals("E_ENV_VERSION", failure.code());
    }

        @Test
        void rejectsModelsNotListedInTheReadyEnvironmentManifest() throws Exception {
        ObjectNode manifest = environmentManifest(
            OpenAiCompatibleAnalysisProvider.Provider.OLLAMA,
            URI.create("http://127.0.0.1:11434"),
            "allowed-model");

        AnalysisProviderException failure = assertThrows(AnalysisProviderException.class,
            () -> OpenAiCompatibleAnalysisProvider.Settings.fromEnvironmentManifest(
                manifest, "unlisted-model", null, Duration.ofSeconds(1)));

        assertEquals("E_ENV_VERSION", failure.code());
        }

    @Test
    void malformedCompletionIsRejectedAsInvalidSchemaOutput() throws Exception {
        HttpServer server = startServer(exchange -> respond(exchange, 200,
                "{\"choices\":[{\"message\":{\"content\":\"not-json\"}}]}"));
        try {
                var settings = settings(
                    OpenAiCompatibleAnalysisProvider.Provider.OLLAMA,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "local/model-a", "sha256:0123", Duration.ofSeconds(2));

            AnalysisProviderException failure = assertThrows(AnalysisProviderException.class,
                    () -> new OpenAiCompatibleAnalysisProvider().analyze(settings, input(), CLAIM_SCHEMA));

            assertEquals("E_SCHEMA_INVALID", failure.code());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsProviderAvailabilityAndMissingModelResponsesToContractErrors() throws Exception {
        int[] statuses = {503, 404};
        String[] bodies = {"provider unavailable", "model not found"};
        String[] expectedCodes = {"E_LLM_UNAVAILABLE", "E_MODEL_MISSING"};

        for (int index = 0; index < statuses.length; index++) {
            int status = statuses[index];
            String body = bodies[index];
            HttpServer server = startServer(exchange -> respond(exchange, status, body));
            try {
                var settings = settings(
                        OpenAiCompatibleAnalysisProvider.Provider.OLLAMA,
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        "local/model-a", null, Duration.ofSeconds(2));

                AnalysisProviderException failure = assertThrows(AnalysisProviderException.class,
                        () -> new OpenAiCompatibleAnalysisProvider().analyze(settings, input(), CLAIM_SCHEMA));

                assertEquals(expectedCodes[index], failure.code());
                assertFalse(failure.getMessage().contains(body));
            } finally {
                server.stop(0);
            }
        }
    }

    private static AnalysisPromptBuilder.Input input() {
        return new AnalysisPromptBuilder.Input(
                "task-1",
                "Account service",
                List.of("parser: Account is a class at C:\\private\\source\\Account.java"),
                List.of(new AnalysisPromptBuilder.EvidenceExcerpt(
                        "evidence-1", "src/Account.java", 2, 3, "api_key=local-secret\nfield: accountName\n")));
    }

    private static JsonNode claimSchema() {
        ObjectNode schema = JSON.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("claims").put("type", "array");
        return schema;
    }

    private static OpenAiCompatibleAnalysisProvider.Settings settings(
            OpenAiCompatibleAnalysisProvider.Provider provider,
            URI endpoint,
            String modelTag,
            String modelDigest,
            Duration timeout) throws AnalysisProviderException {
        return OpenAiCompatibleAnalysisProvider.Settings.fromEnvironmentManifest(
                environmentManifest(provider, endpoint, modelTag), modelTag, modelDigest, timeout);
    }

    private static ObjectNode environmentManifest(
            OpenAiCompatibleAnalysisProvider.Provider provider, URI endpoint, String modelTag) {
        ObjectNode manifest = JSON.createObjectNode().put("status", "READY");
        ObjectNode llm = manifest.putObject("llm");
        llm.put("provider", provider.value());
        llm.put("endpoint", endpoint.toString());
        llm.put("api_mode", "openai-compatible");
        manifest.putArray("models").addObject().put("tag", modelTag);
        return manifest;
    }

    private static HttpServer startServer(HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
        return server;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    @FunctionalInterface
    private interface HttpHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }
}