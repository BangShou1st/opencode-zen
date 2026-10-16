package com.sharedlibs.opencodezen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic contract tests for the shared SSE frame decoder.
 *
 * These lock the provider-agnostic parsing rules the transport depends on:
 * multiple {@code data:} lines are joined, SSE comment/event/id/retry lines are
 * ignored, a blank line dispatches the frame, a final frame without a trailing
 * blank line is flushed by {@link SseFrameDecoder#finish()}, and the decoder
 * resets after each dispatch. No lenient compatibility branches are asserted —
 * only the documented behaviour.
 */
class SseFrameDecoderTest {

    @Test
    void joinsDataLinesAndIgnoresOtherFields() {
        SseFrameDecoder decoder = new SseFrameDecoder();

        assertThat(decoder.accept(": comment")).isEmpty();
        assertThat(decoder.accept("event: message")).isEmpty();
        assertThat(decoder.accept("id: 42")).isEmpty();
        assertThat(decoder.accept("retry: 1000")).isEmpty();
        assertThat(decoder.accept("data: first")).isEmpty();
        assertThat(decoder.accept("data: second")).isEmpty();
        assertThat(decoder.accept("")).contains("first\nsecond");
        assertThat(decoder.finish()).isEmpty();
    }

    @Test
    void flushesFinalFrameAtEndOfStream() {
        SseFrameDecoder decoder = new SseFrameDecoder();
        decoder.accept("data: [DONE]");

        assertThat(decoder.finish()).contains("[DONE]");
        assertThat(decoder.finish()).isEmpty();
    }

    @Test
    void acceptsDataWithAndWithoutLeadingSpace() {
        SseFrameDecoder decoder = new SseFrameDecoder();
        decoder.accept("data: payload");
        assertThat(decoder.accept("")).contains("payload");
        // No space after the colon is also valid SSE.
        decoder.accept("data:raw");
        assertThat(decoder.accept("")).contains("raw");
    }

    @Test
    void dispatchesConsecutiveFramesIndependently() {
        SseFrameDecoder decoder = new SseFrameDecoder();
        decoder.accept("data: {\"a\":1}");
        assertThat(decoder.accept("")).contains("{\"a\":1}");
        decoder.accept("data: {\"b\":2}");
        assertThat(decoder.accept("")).contains("{\"b\":2}");
    }

    @Test
    void emptyDataFieldYieldsNoFrame() {
        SseFrameDecoder decoder = new SseFrameDecoder();
        assertThat(decoder.accept("data:")).isEmpty();
        assertThat(decoder.accept("")).isEmpty();
        assertThat(decoder.finish()).isEmpty();
        // A blank delimiter with no buffered data is a no-op, not an empty frame.
        assertThat(decoder.accept("")).isEmpty();
    }

    @Test
    void rejectsNullLine() {
        SseFrameDecoder decoder = new SseFrameDecoder();
        assertThatThrownBy(() -> decoder.accept(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
