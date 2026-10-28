package com.sharedlibs.opencodezen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Streaming SSE reader aligned with Python V2.4's _stream_events, without
 * changing HTTP identity, authorization or retry decisions.
 *
 * This reader never retries an upstream rejection. The client supplies the
 * stream from its own authorized HttpClient.
 */
public final class OpenCodeZenSseEventReader {
    public static final int MAX_SSE_BYTES = 32 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Event(String name, JsonNode payload, boolean done) {}

    public static final class StreamFailure extends IOException {
        private final String code;
        public StreamFailure(String code, String message) {
            super(message);
            this.code = code;
        }
        public StreamFailure(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
        public String code() { return code; }
    }

    private OpenCodeZenSseEventReader() {}

    /**
     * Emit parsed events as they arrive. Consumes at most 32 MiB in total.
     * Rejects malformed event JSON, explicit upstream error events, and
     * streams ending in the middle of a line.
     */
    public static void read(InputStream upstream, Consumer<Event> receiver) throws IOException {
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(receiver, "receiver");
        ByteArrayOutputStream currentLine = new ByteArrayOutputStream();
        ByteArrayOutputStream frameData = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        String[] eventName = {""};
        boolean[] firstData = {true};
        int total = 0;

        for (int count; (count = upstream.read(buffer)) != -1;) {
            if (count == 0) {
                continue;
            }
            total += count;
            if (total > MAX_SSE_BYTES) {
                throw new StreamFailure("stream_too_large", "Upstream SSE exceeded 32 MiB");
            }
            for (int i = 0; i < count; i++) {
                byte current = buffer[i];
                if (current == '\n') {
                    byte[] bytes = currentLine.toByteArray();
                    currentLine.reset();
                    int length = bytes.length;
                    if (length > 0 && bytes[length - 1] == '\r') {
                        length--;
                    }
                    if (length == 0) {
                        emit(frameData, eventName, firstData, receiver, false);
                    } else if (startsWith(bytes, length, "data:")) {
                        int offset = 5;
                        while (offset < length && bytes[offset] == ' ') {
                            offset++;
                        }
                        if (!firstData[0]) {
                            frameData.write('\n');
                        }
                        frameData.write(bytes, offset, length - offset);
                        firstData[0] = false;
                    } else if (startsWith(bytes, length, "event:")) {
                        int offset = 6;
                        String name = new String(bytes, offset, length - offset, StandardCharsets.UTF_8);
                        eventName[0] = name.trim();
                    }
                } else {
                    currentLine.write(current);
                }
            }
        }
        // The stream ended without the newline that would have completed the
        // last line. The pending bytes are dispatched as they stand when they
        // are a whole event — the completion marker among them is a whole
        // event too — and bytes that are not a whole event fail as they
        // always have. Half an event is not made acceptable by this.
        if (currentLine.size() > 0) {
            byte[] bytes = currentLine.toByteArray();
            int length = bytes.length;
            if (length > 0 && bytes[length - 1] == '\r') {
                length--;
            }
            if (length > 0) {
                if (startsWith(bytes, length, "data:")) {
                    int offset = 5;
                    while (offset < length && bytes[offset] == ' ') {
                        offset++;
                    }
                    if (!firstData[0]) {
                        frameData.write('\n');
                    }
                    frameData.write(bytes, offset, length - offset);
                    firstData[0] = false;
                } else if (startsWith(bytes, length, "event:")) {
                    eventName[0] = new String(bytes, 6, length - 6, StandardCharsets.UTF_8).trim();
                } else {
                    throw new StreamFailure("truncated_sse",
                            "Upstream SSE stream ended before an event was complete");
                }
            }
        }
        emit(frameData, eventName, firstData, receiver, true);
    }

    private static boolean startsWith(byte[] data, int length, String ascii) {
        if (length < ascii.length()) return false;
        for (int i = 0; i < ascii.length(); i++) {
            if (data[i] != (byte) ascii.charAt(i)) return false;
        }
        return true;
    }

    private static void emit(
            ByteArrayOutputStream data, String[] eventName, boolean[] firstData,
            Consumer<Event> receiver, boolean endOfStream) throws IOException {
        if (firstData[0]) {
            eventName[0] = "";
            return;
        }
        byte[] raw = data.toByteArray();
        boolean done = raw.length == 6 && new String(raw, StandardCharsets.UTF_8).equals("[DONE]");
        JsonNode json = null;
        if (!done) {
            try {
                json = JSON.readTree(raw);
                if (json == null) {
                    throw new IOException("empty JSON event");
                }
            } catch (IOException error) {
                throw new StreamFailure(
                        endOfStream ? "truncated_sse" : "bad_sse",
                        endOfStream
                                ? "Upstream SSE stream ended before an event was complete"
                                : "Invalid upstream SSE event JSON", error);
            }
        }
        String name = eventName[0];
        if ("error".equals(name) || (json != null && (json.hasNonNull("error")
                || "error".equals(json.path("type").asText())
                || "response.failed".equals(json.path("type").asText())))) {
            throw new StreamFailure("upstream_stream_error", "Upstream reported an error event");
        }
        receiver.accept(new Event(name, json, done));
        data.reset();
        eventName[0] = "";
        firstData[0] = true;
    }
}
