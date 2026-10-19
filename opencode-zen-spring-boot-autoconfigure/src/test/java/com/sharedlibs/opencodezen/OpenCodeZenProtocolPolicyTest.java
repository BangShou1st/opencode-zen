package com.sharedlibs.opencodezen;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeZenProtocolPolicyTest {

    @Test
    void defaultBaseUrlIsTheInferenceEndpointAndLegacyIsDiscoveryOnly() {
        assertThat(OpenCodeZenProtocolPolicy.DEFAULT_BASE_URL)
                .isEqualTo("https://opencode.ai/inference/v1");
        assertThat(OpenCodeZenProtocolPolicy.INFERENCE_BASE_URL)
                .isEqualTo(OpenCodeZenProtocolPolicy.DEFAULT_BASE_URL);
        // The legacy base must stay available for catalogue fallback but must never be the
        // default inference target.
        assertThat(OpenCodeZenProtocolPolicy.LEGACY_DISCOVERY_BASE_URL)
                .isEqualTo("https://opencode.ai/zen/v1")
                .isNotEqualTo(OpenCodeZenProtocolPolicy.DEFAULT_BASE_URL);
    }

    @Test
    void appliesVerifiedClientIdentityAndBearerFallback() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://example.test/models")).GET();
        OpenCodeZenProtocolPolicy.applyIdentityHeaders(builder, "ses_0123456789ab0123456789abcd", "msg_0123456789ab0123456789abcd");
        HttpRequest request = builder.header("Authorization",
                OpenCodeZenProtocolPolicy.authorizationHeader(" ")).build();

        assertThat(request.headers().firstValue("User-Agent")).contains(OpenCodeZenProtocolPolicy.USER_AGENT);
        assertThat(request.headers().firstValue("Accept"))
                .contains(OpenCodeZenProtocolPolicy.ACCEPT_VALUE);
        assertThat(request.headers().firstValue(OpenCodeZenProtocolPolicy.CLIENT_HEADER)).contains("cli");
        assertThat(request.headers().firstValue(OpenCodeZenProtocolPolicy.PROJECT_HEADER)).contains("global");
        assertThat(request.headers().firstValue(OpenCodeZenProtocolPolicy.SESSION_HEADER))
                .contains("ses_0123456789ab0123456789abcd");
        assertThat(request.headers().firstValue(OpenCodeZenProtocolPolicy.SESSION_ID_HEADER))
                .contains("ses_0123456789ab0123456789abcd");
        assertThat(request.headers().firstValue(OpenCodeZenProtocolPolicy.REQUEST_HEADER))
                .isEmpty(); // Python V2.4 omits x-opencode-request
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer public");
        assertThat(OpenCodeZenProtocolPolicy.authorizationHeader("configured-key"))
                .isEqualTo("Bearer configured-key");
    }

    @Test
    void appliesRequiredChatEnvelopeWithTheFullCliCoreToolSet() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", "model-id");
        OpenCodeZenProtocolPolicy.applyRequiredChatEnvelope(payload, true);

        assertThat(payload).containsEntry("stream", true)
                .containsEntry("stream_options", Map.of("include_usage", true));
        // Omitting the CLI core envelope is answered upstream with 403 FreeTierError, so the
        // whole five-tool set is part of the request contract.
        assertThat(toolNames(payload.get("tools")))
                .containsExactly("shell", "read");
        assertThat(parameters(payload.get("tools")))
                .allSatisfy(parameters -> assertThat(parameters).containsKey("required"));
    }

    @Test
    void reservedToolNamesCoverTheWholeCliCoreSet() {
        assertThat(OpenCodeZenProtocolPolicy.reservedToolNames())
                .containsExactly("shell", "read");
        assertThat(OpenCodeZenProtocolPolicy.hasReservedToolName(List.of("search", "read"))).isTrue();
        assertThat(OpenCodeZenProtocolPolicy.hasReservedToolName(List.of("search", "shell"))).isTrue();
        assertThat(OpenCodeZenProtocolPolicy.hasReservedToolName(List.of("search", "grep"))).isFalse();
        assertThat(OpenCodeZenProtocolPolicy.hasReservedToolName(List.of("search"))).isFalse();
        assertThat(OpenCodeZenProtocolPolicy.hasReservedToolName(null)).isFalse();
    }

    @Test
    void appendingReservedToolsNeverDuplicatesAReservedName() {
        // A plain (non chat-shaped) declaration is passed through untouched: 1 + 2 reserved.
        assertThat(OpenCodeZenProtocolPolicy.appendReservedTools(List.of(Map.of("name", "search"))))
                .hasSize(3);
        // An application tool that already claims a reserved name is not appended twice.
        Map<String, Object> alreadyRead = Map.of("type", "function",
                "function", Map.of("name", "read"));
        List<Object> combined = OpenCodeZenProtocolPolicy.appendReservedTools(List.of(alreadyRead));
        assertThat(combined).hasSize(2);
        assertThat(toolNames(combined)).containsExactly("read", "shell");
    }

    @SuppressWarnings("unchecked")
    private static List<String> toolNames(Object value) {
        return ((List<Map<String, Object>>) value).stream()
                .map(tool -> (String) ((Map<String, Object>) tool.get("function")).get("name"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parameters(Object value) {
        return ((List<Map<String, Object>>) value).stream()
                .map(tool -> (Map<String, Object>) ((Map<String, Object>) tool.get("function")).get("parameters"))
                .toList();
    }
}
