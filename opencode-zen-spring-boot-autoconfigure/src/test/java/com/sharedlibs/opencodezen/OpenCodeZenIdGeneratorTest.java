package com.sharedlibs.opencodezen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeZenIdGeneratorTest {

    @Test
    void createsTimestampShapedSessionAndRequestIds() {
        assertThat(OpenCodeZenIdGenerator.newSessionId())
                .matches("ses_[0-9a-f]{12}[0-9A-Za-z]{14}");
        assertThat(OpenCodeZenIdGenerator.newRequestId())
                .matches("msg_[0-9a-f]{12}[0-9A-Za-z]{14}");
    }

    /**
     * The upstream free tier answers 403 FreeTierError for identifiers that do not carry the
     * frozen client shape (a UUID, an arbitrary string) or that carry a large counter. Every
     * generated identifier must pass the shared shape check, and the descending session
     * counter must stay small so the mid-prefix gate holds.
     */
    @Test
    void generatedIdsAlwaysPassTheSharedShapeCheckAndCounterGate() {
        for (int index = 0; index < 128; index++) {
            String session = OpenCodeZenIdGenerator.newSessionId();
            String request = OpenCodeZenIdGenerator.newRequestId();
            assertThat(OpenCodeZenIdGenerator.isSessionShaped(session)).isTrue();
            assertThat(OpenCodeZenIdGenerator.isRequestShaped(request)).isTrue();

            String payload = session.substring("ses_".length());
            int counter = 0xFFF - Integer.parseInt(payload.substring(9, 12), 16);
            assertThat(counter).isBetween(0, 0xF);
        }
    }

    @Test
    void shapeCheckRejectsUuidsAndArbitraryStrings() {
        assertThat(OpenCodeZenIdGenerator.isSessionShaped(
                "12345678-1234-1234-1234-123456789abc")).isFalse();
        assertThat(OpenCodeZenIdGenerator.isSessionShaped("ses_" + "0".repeat(32))).isFalse();
        assertThat(OpenCodeZenIdGenerator.isSessionShaped("ses_0123456789ab0123456789abcd")).isTrue();
        assertThat(OpenCodeZenIdGenerator.isSessionShaped(null)).isFalse();
        assertThat(OpenCodeZenIdGenerator.isRequestShaped("msg_0123456789ab0123456789abcd")).isTrue();
        assertThat(OpenCodeZenIdGenerator.isRequestShaped("ses_0123456789ab0123456789abcd")).isFalse();
    }
}
