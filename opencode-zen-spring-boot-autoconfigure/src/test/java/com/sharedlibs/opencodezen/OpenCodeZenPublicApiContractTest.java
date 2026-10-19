package com.sharedlibs.opencodezen;

import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * This test intentionally compiles against public API signatures as a guard
 * against consumer API regressions. It performs no network requests.
 */
class OpenCodeZenPublicApiContractTest {
    @Test
    void methodsReportedMissingAreActuallyExposedByTheCurrentSource() {
        OpenCodeZenRequestFactory factory =
                new OpenCodeZenRequestFactory(OpenCodeZenProtocolPolicy.USER_AGENT);
        HttpRequest request = factory.newEphemeralInferenceRequest(
                URI.create(OpenCodeZenProtocolPolicy.DEFAULT_BASE_URL + "/chat/completions"),
                ZenModelProtocol.CHAT, true, null,
                Map.of("model", "big-pickle"));
        assertThat(request.method()).isEqualTo("POST");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", "big-pickle");
        OpenCodeZenProtocolPolicy.applyRequiredEnvelope(payload, ZenModelProtocol.CHAT, true);
        assertThat(payload).containsEntry("stream", true).containsKey("tools");
    }
}
