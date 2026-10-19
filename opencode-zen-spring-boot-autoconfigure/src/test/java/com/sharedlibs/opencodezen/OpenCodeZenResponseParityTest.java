package com.sharedlibs.opencodezen;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenCodeZenResponseParityTest {
    @Test
    void detectsExplicitTrafficRejectionAndNeverRetries() {
        var result = OpenCodeZenUpstreamDiagnostics.classify(403,
                "{\"error\":{\"type\":\"FreeTierError\",\"message\":\"Detect Abuse Traffic\"}}"
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(result).isEqualTo(OpenCodeZenUpstreamDiagnostics.Outcome.ABUSE_REJECTED);
        assertThat(OpenCodeZenUpstreamDiagnostics.shouldRetryAutomatically(result)).isFalse();
    }

    @Test
    void detectsAbusiveTrafficVariantBeforeGenericFreeTierError() {
        // This wording was observed on a rejected network egress, not a JSON schema error.
        String[] upstreamBodies = {
                "{\"error\":{\"type\":\"FreeTierError\",\"message\":\"detected abusive traffic\"}}",
                "{\"error\":{\"type\":\"FreeTierError\",\"message\":\"Forbidden: Detected Abusive Traffic\"}}",
                "FreeTierError: current network has abusive traffic",
                "Forbidden, OpenCode Free Model Gateway Detect Abuse Traffic"
        };
        for (String upstreamBody : upstreamBodies) {
            var outcome = OpenCodeZenUpstreamDiagnostics.classify(
                    403, upstreamBody.getBytes(StandardCharsets.UTF_8));
            assertThat(outcome)
                    .as("403 error classification for %s", upstreamBody)
                    .isEqualTo(OpenCodeZenUpstreamDiagnostics.Outcome.ABUSE_REJECTED);
            assertThat(OpenCodeZenUpstreamDiagnostics.shouldRetryAutomatically(outcome)).isFalse();
        }
        assertThat(OpenCodeZenUpstreamDiagnostics.classify(403,
                "FreeTierError".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(OpenCodeZenUpstreamDiagnostics.Outcome.FREE_TIER_REJECTED);
    }

    @Test
    void distinguishesFreeTierAndOtherHttpErrorsWithoutStoringBody() {
        assertThat(OpenCodeZenUpstreamDiagnostics.classify(403,
                "FreeTierError".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(OpenCodeZenUpstreamDiagnostics.Outcome.FREE_TIER_REJECTED);
        assertThat(OpenCodeZenUpstreamDiagnostics.classify(403, "blocked".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(OpenCodeZenUpstreamDiagnostics.Outcome.FORBIDDEN);
        assertThat(OpenCodeZenUpstreamDiagnostics.classify(429, null))
                .isEqualTo(OpenCodeZenUpstreamDiagnostics.Outcome.RATE_LIMITED);
        assertThat(OpenCodeZenUpstreamDiagnostics.classify(200, null))
                .isEqualTo(OpenCodeZenUpstreamDiagnostics.Outcome.SUCCESS);
    }

    @Test
    void parsesMultilineCrLfEventsAndTerminalDone() throws Exception {
        String sse = ":comment\r\nevent: response.delta\r\ndata: {\"type\":\r\ndata: \"response.output_text.delta\",\"delta\":\"hi\"}\r\n\r\ndata: [DONE]\n\n";
        List<OpenCodeZenSseEventReader.Event> events = new ArrayList<>();
        OpenCodeZenSseEventReader.read(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), events::add);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).name()).isEqualTo("response.delta");
        assertThat(events.get(0).payload().path("delta").asText()).isEqualTo("hi");
        assertThat(events.get(0).done()).isFalse();
        assertThat(events.get(1).done()).isTrue();
    }

    @Test
    void parsesUtf8AcrossChunkBoundaries() throws Exception {
        byte[] data = "data: {\"text\":\"中文\"}\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        var chunks = new ByteArrayInputStream(data) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 1));
            }
        };
        List<OpenCodeZenSseEventReader.Event> events = new ArrayList<>();
        OpenCodeZenSseEventReader.read(chunks, events::add);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).payload().path("text").asText()).isEqualTo("中文");
    }

    @Test
    void recognizesJsonErrorEventWithoutLeakingPayload() {
        var source = new ByteArrayInputStream(
                "event: error\ndata: {\"error\":{\"message\":\"stop\"}}\n\n".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> OpenCodeZenSseEventReader.read(source, event -> {}))
                .isInstanceOf(OpenCodeZenSseEventReader.StreamFailure.class)
                .satisfies(err -> assertThat(
                        ((OpenCodeZenSseEventReader.StreamFailure) err).code())
                        .isEqualTo("upstream_stream_error"));
    }

    @Test
    void rejectsIncompleteFramesAndBadJson() {
        var truncated = new ByteArrayInputStream(
                "data: {\"text\":1}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> OpenCodeZenSseEventReader.read(truncated, event -> {}))
                .isInstanceOf(OpenCodeZenSseEventReader.StreamFailure.class)
                .satisfies(err -> assertThat(
                        ((OpenCodeZenSseEventReader.StreamFailure) err).code())
                        .isEqualTo("truncated_sse"));

        var badJson = new ByteArrayInputStream(
                "data: not-json\n\n".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> OpenCodeZenSseEventReader.read(badJson, event -> {}))
                .isInstanceOf(OpenCodeZenSseEventReader.StreamFailure.class)
                .satisfies(err -> assertThat(
                        ((OpenCodeZenSseEventReader.StreamFailure) err).code())
                        .isEqualTo("bad_sse"));
    }

    @Test
    void capsOversizedUntrustedStreams() {
        byte[] huge = new byte[OpenCodeZenSseEventReader.MAX_SSE_BYTES + 1];
        Arrays.fill(huge, (byte) 'x');
        assertThatThrownBy(() -> OpenCodeZenSseEventReader.read(
                new ByteArrayInputStream(huge), event -> {}))
                .isInstanceOf(OpenCodeZenSseEventReader.StreamFailure.class)
                .satisfies(err -> assertThat(
                        ((OpenCodeZenSseEventReader.StreamFailure) err).code())
                        .isEqualTo("stream_too_large"));
    }
}
