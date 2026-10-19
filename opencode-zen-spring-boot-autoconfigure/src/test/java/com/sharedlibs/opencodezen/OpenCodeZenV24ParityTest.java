package com.sharedlibs.opencodezen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Flow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No network traffic: asserts Java's outbound wire format against Python V2.4. */
class OpenCodeZenV24ParityTest {
    private final OpenCodeZenRequestFactory factory =
            new OpenCodeZenRequestFactory(OpenCodeZenProtocolPolicy.USER_AGENT);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void buildsCompleteChatPostWith2xToolsAndNoRequestHeader() throws Exception {
        HttpRequest request = factory.newConversationChatRequest(
                true, null, UUID.randomUUID(),
                Map.of("model", "big-pickle",
                        "messages", List.of(Map.of("role", "user", "content", "hello"))));
        JsonNode body = json.readTree(bodyBytes(request));
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer public");
        assertThat(request.headers().firstValue("User-Agent")).contains("opencode/latest/2.0.29/cli");
        assertThat(request.headers().firstValue("x-opencode-request")).isEmpty();
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("stream_options").path("include_usage").asBoolean()).isTrue();
        assertThat(body.path("tools").size()).isEqualTo(2);
        assertThat(body.path("tools").get(0).path("function").path("name").asText()).isEqualTo("shell");
        assertThat(body.path("tools").get(1).path("function").path("name").asText()).isEqualTo("read");
        assertThat(body.path("tools").get(0).path("function").path("parameters")
                .path("required").get(0).asText()).isEqualTo("command");
    }

    @Test
    void supportsNativeResponsesAndAnthropicToolShapes() throws Exception {
        for (ZenModelProtocol protocol : List.of(ZenModelProtocol.RESPONSES, ZenModelProtocol.ANTHROPIC)) {
            URI endpoint = URI.create(OpenCodeZenProtocolPolicy.DEFAULT_BASE_URL
                    + OpenCodeZenProtocolPolicy.inferencePath(protocol));
            HttpRequest request = factory.newEphemeralInferenceRequest(
                    endpoint, protocol, true, null, Map.of("model", "test-model", "input", "hello"));
            JsonNode body = json.readTree(bodyBytes(request));
            assertThat(body.path("stream").asBoolean()).isTrue();
            assertThat(body.path("tools").size()).isEqualTo(2);
            if (protocol == ZenModelProtocol.RESPONSES) {
                assertThat(body.path("tools").get(0).path("name").asText()).isEqualTo("shell");
                assertThat(body.path("tools").get(0).path("parameters").isObject()).isTrue();
                assertThat(request.headers().firstValue("Authorization")).contains("Bearer public");
            } else {
                assertThat(body.path("tools").get(0).path("name").asText()).isEqualTo("shell");
                assertThat(body.path("tools").get(0).path("input_schema").isObject()).isTrue();
                assertThat(request.headers().firstValue("Authorization")).isEmpty();
                assertThat(request.headers().firstValue("x-api-key")).contains("public");
                assertThat(request.headers().firstValue("anthropic-version")).contains("2023-06-01");
            }
        }
    }

    @Test
    void preservesClientToolAndDoesNotMutateCallersPayload() throws Exception {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("model", "big-pickle");
        original.put("tools", List.of(Map.of("type", "function",
                "function", Map.of("name", "read", "description", "real tool",
                        "parameters", Map.of("type", "object")))));
        original.put("stream_options", Map.of("trace", true));
        HttpRequest request = factory.newEphemeralInferenceRequest(
                URI.create(OpenCodeZenProtocolPolicy.DEFAULT_BASE_URL + "/chat/completions"),
                ZenModelProtocol.CHAT, true, null, original);
        JsonNode body = json.readTree(bodyBytes(request));
        assertThat(body.path("tools").size()).isEqualTo(2);
        assertThat(body.path("tools").get(0).path("function").path("name").asText()).isEqualTo("read");
        assertThat(body.path("tools").get(0).path("function").path("description").asText())
                .isEqualTo("real tool");
        assertThat(body.path("stream_options").path("trace").asBoolean()).isTrue();
        assertThat(original).doesNotContainKey("stream");
    }

    @Test
    void invalidPayloadIsRejectedBeforeAnyNetworkCall() {
        assertThatThrownBy(() -> factory.newEphemeralInferenceRequest(
                URI.create(OpenCodeZenProtocolPolicy.DEFAULT_BASE_URL + "/messages"),
                ZenModelProtocol.CHAT, true, null, Map.of("model", "m")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match");
        Map<String, Object> badTools = new LinkedHashMap<>();
        badTools.put("model", "big-pickle");
        badTools.put("tools", "bad");
        assertThatThrownBy(() -> factory.newEphemeralInferenceRequest(
                URI.create(OpenCodeZenProtocolPolicy.DEFAULT_BASE_URL + "/chat/completions"),
                ZenModelProtocol.CHAT, true, null, badTools))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("array");
    }

    private static byte[] bodyBytes(HttpRequest request) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription sub) { sub.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) {
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                out.writeBytes(part);
            }
            @Override public void onError(Throwable error) { throw new RuntimeException(error); }
            @Override public void onComplete() { }
        });
        return out.toByteArray();
    }
}
