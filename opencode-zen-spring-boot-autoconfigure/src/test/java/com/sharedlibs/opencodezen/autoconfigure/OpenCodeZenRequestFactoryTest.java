package com.sharedlibs.opencodezen.autoconfigure;

import com.sharedlibs.opencodezen.OpenCodeZenIdGenerator;
import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consumers that only use this factory must always produce a conforming request: shaped
 * identifiers, the CLI identity headers (including the accept header), the bearer credential
 * and the request envelope policy. Identifiers supplied by hand are validated fail-closed.
 */
class OpenCodeZenRequestFactoryTest {

    private static final URI ENDPOINT = URI.create(
            OpenCodeZenProtocolPolicy.INFERENCE_BASE_URL + OpenCodeZenProtocolPolicy.CHAT_COMPLETIONS_PATH);

    private final OpenCodeZenRequestFactory factory =
            new OpenCodeZenRequestFactory(OpenCodeZenProtocolPolicy.USER_AGENT);

    @Test
    void oneStopConversationRequestGeneratesConformingIdentifiersAndUsesThePublicCredential() {
        UUID conversation = UUID.randomUUID();
        HttpRequest request = factory.newConversationRequest(ENDPOINT, true, null, conversation).build();

        assertThat(request.uri()).isEqualTo(ENDPOINT);
        assertThat(request.headers().firstValue(OpenCodeZenProtocolPolicy.SESSION_HEADER))
                .hasValueSatisfying(id -> assertThat(OpenCodeZenIdGenerator.isSessionShaped(id)).isTrue());
        assertThat(request.headers().firstValue(OpenCodeZenProtocolPolicy.SESSION_ID_HEADER))
                .isEqualTo(request.headers().firstValue(OpenCodeZenProtocolPolicy.SESSION_HEADER));
        assertThat(request.headers().firstValue(OpenCodeZenProtocolPolicy.REQUEST_HEADER))
                .hasValueSatisfying(id -> assertThat(OpenCodeZenIdGenerator.isRequestShaped(id)).isTrue());
        assertThat(request.headers().firstValue("Accept"))
                .contains(OpenCodeZenProtocolPolicy.ACCEPT_VALUE);
        assertThat(request.headers().firstValue("Authorization"))
                .contains("Bearer " + OpenCodeZenProtocolPolicy.FREE_TIER_CREDENTIAL);
    }

    @Test
    void conversationSessionIsStableWhileTheRequestIdChanges() {
        UUID conversation = UUID.randomUUID();
        HttpRequest first = factory.newConversationRequest(ENDPOINT, true, null, conversation).build();
        HttpRequest second = factory.newConversationRequest(ENDPOINT, true, null, conversation).build();

        assertThat(second.headers().firstValue(OpenCodeZenProtocolPolicy.SESSION_HEADER))
                .isEqualTo(first.headers().firstValue(OpenCodeZenProtocolPolicy.SESSION_HEADER));
        assertThat(second.headers().firstValue(OpenCodeZenProtocolPolicy.REQUEST_HEADER))
                .isNotEqualTo(first.headers().firstValue(OpenCodeZenProtocolPolicy.REQUEST_HEADER));
    }

    @Test
    void paidModelUsesTheStoredKeyAndFreeModelNeverLeaksIt() {
        HttpRequest paid = factory.newEphemeralRequest(ENDPOINT, false, "sk-paid-key").build();
        assertThat(paid.headers().firstValue("Authorization")).contains("Bearer sk-paid-key");

        // A free model always uses the public credential even when a key is configured.
        HttpRequest free = factory.newEphemeralRequest(ENDPOINT, true, "sk-paid-key").build();
        assertThat(free.headers().firstValue("Authorization"))
                .contains("Bearer " + OpenCodeZenProtocolPolicy.FREE_TIER_CREDENTIAL);
    }

    @Test
    void paidModelWithoutAKeyFailsClosed() {
        assertThatThrownBy(() -> factory.newEphemeralRequest(ENDPOINT, false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API key is required");
    }

    /** A hand-supplied identifier that upstream would answer with 403 is rejected locally. */
    @Test
    void callerSuppliedIdentifiersAreValidatedFailClosed() {
        assertThatThrownBy(() -> factory.newRequestBuilder(
                ENDPOINT, "public", "12345678-1234-1234-1234-123456789abc",
                OpenCodeZenIdGenerator.newRequestId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session id must carry the client shape");
        assertThatThrownBy(() -> factory.newRequestBuilder(
                ENDPOINT, "public", OpenCodeZenIdGenerator.newSessionId(), "msg-probe-0001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request id must carry the client shape");
    }

    @Test
    void nonAbsoluteEndpointIsRejected() {
        assertThatThrownBy(() -> factory.newEphemeralRequest(
                URI.create("/chat/completions"), true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute HTTP(S) URI");
    }
}
