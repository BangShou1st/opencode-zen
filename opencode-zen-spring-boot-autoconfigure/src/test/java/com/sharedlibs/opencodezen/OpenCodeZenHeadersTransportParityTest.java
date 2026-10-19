package com.sharedlibs.opencodezen;

import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenHttpClients;
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;
import org.junit.jupiter.api.Test;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenCodeZenHeadersTransportParityTest {
    private final OpenCodeZenRequestFactory requests =
            new OpenCodeZenRequestFactory(OpenCodeZenProtocolPolicy.USER_AGENT);

    @Test
    void forwardsOnlyKnownV24HeadersAndVerifiedSession() {
        String realSession = OpenCodeZenIdGenerator.newSessionId();
        assertThat(OpenCodeZenIdGenerator.isV24SessionAffinityValid(realSession)).isTrue();
        HttpRequest request = requests.newEphemeralInferenceRequest(
                URI.create(OpenCodeZenProtocolPolicy.DEFAULT_BASE_URL + "/messages"),
                ZenModelProtocol.ANTHROPIC, true, null,
                Map.of("model", "example-model", "max_tokens", 16,
                        "messages", List.of(Map.of("role", "user", "content", "hello"))),
                Map.of("X-OpenCode-Project", "project-x",
                        "X-OpenCode-Session", realSession,
                        "Anthropic-Version", "2023-06-01",
                        "Anthropic-Beta", "example-feature",
                        "Authorization", "DO_NOT_FORWARD",
                        "x-api-key", "NOT_AUTHORIZED"));
        assertThat(request.headers().firstValue("x-opencode-project")).contains("project-x");
        assertThat(request.headers().firstValue("x-opencode-session")).contains(realSession);
        assertThat(request.headers().firstValue("x-opencode-session-id")).contains(realSession);
        assertThat(request.headers().firstValue("anthropic-beta")).contains("example-feature");
        assertThat(request.headers().firstValue("anthropic-version")).contains("2023-06-01");
        assertThat(request.headers().firstValue("x-api-key")).contains("public");
        assertThat(request.headers().firstValue("Authorization")).isEmpty();
    }

    @Test
    void declinesSessionAffinityThatFailsPythonCounterGate() {
        String invalidCounter = "ses_0123456780000123456789abcd";
        assertThat(OpenCodeZenIdGenerator.isSessionShaped(invalidCounter)).isTrue();
        assertThat(OpenCodeZenIdGenerator.isV24SessionAffinityValid(invalidCounter)).isFalse();
        HttpRequest request = requests.newEphemeralInferenceRequest(
                URI.create(OpenCodeZenProtocolPolicy.DEFAULT_BASE_URL + "/chat/completions"),
                ZenModelProtocol.CHAT, true, null, Map.of("model", "example-model"),
                Map.of("x-opencode-session", invalidCounter));
        assertThat(request.headers().firstValue("x-opencode-session")).isPresent()
                .doesNotContain(invalidCounter);
    }

    @Test
    void blocksHeaderInjection() {
        assertThatThrownBy(() -> requests.newEphemeralInferenceRequest(
                URI.create(OpenCodeZenProtocolPolicy.DEFAULT_BASE_URL + "/chat/completions"),
                ZenModelProtocol.CHAT, true, null, Map.of("model", "example-model"),
                Map.of("x-opencode-project", "bad\ninjected")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("project");
    }

    @Test
    void explicitProxyMustBeSelectedByJavaSenderNotByGit() {
        var client = OpenCodeZenHttpClients.explicitHttpProxy("127.0.0.1", 7897);
        var selection = client.proxy().orElseThrow()
                .select(URI.create("https://opencode.ai/inference/v1/models"));
        assertThat(selection).hasSize(1);
        assertThat(selection.get(0).type()).isEqualTo(Proxy.Type.HTTP);
        assertThat(selection.get(0).address().toString()).contains("127.0.0.1:7897");
        assertThat(OpenCodeZenHttpClients.direct().proxy().orElseThrow().select(
                URI.create("https://opencode.ai/inference/v1/models")))
                .containsExactly(Proxy.NO_PROXY);
        assertThatThrownBy(() -> OpenCodeZenHttpClients.explicitHttpProxy("", 7897))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
