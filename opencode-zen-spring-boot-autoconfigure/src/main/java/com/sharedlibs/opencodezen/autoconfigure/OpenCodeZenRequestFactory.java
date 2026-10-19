package com.sharedlibs.opencodezen.autoconfigure;

import com.sharedlibs.opencodezen.OpenCodeZenCredentialPolicy;
import com.sharedlibs.opencodezen.OpenCodeZenIdGenerator;
import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import com.sharedlibs.opencodezen.ZenModelProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashMap;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Builds requests with the shared Zen identity and bearer-header policy.
 *
 * <p><b>Consumers only need this class.</b> A project that hands a free/paid flag, an optional
 * API key and a conversation key to {@link #newConversationRequest} (or
 * {@link #newEphemeralRequest} for one-off calls) receives a fully conforming request: shaped
 * {@code ses_}/{@code msg_} identifiers generated here, the CLI identity headers, the bearer
 * credential and — via {@link OpenCodeZenProtocolPolicy} — the tool envelope that the free tier
 * requires. Callers never need to know how the identifiers are shaped.
 *
 * <p>Caller-supplied identifiers are validated fail-closed: an identifier that does not carry
 * the frozen client shape (for example a UUID) is rejected locally instead of being sent
 * upstream, where it would be answered with {@code 403 FreeTierError}.
 */
public final class OpenCodeZenRequestFactory {

    private static final int MAX_CACHED_CONVERSATIONS = 8192;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String userAgent;
    private final ConcurrentMap<UUID, String> conversationSessions = new ConcurrentHashMap<>();

    public OpenCodeZenRequestFactory(String userAgent) {
        this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
        OpenCodeZenProtocolPolicy.validateUserAgent(userAgent);
    }

    /**
     * Returns a request builder with the common identity and authorization headers applied.
     * The caller owns the request body, timeout, and HTTP method. Both identifiers must already
     * carry the frozen client shape; use {@link #newConversationRequest} or
     * {@link #newEphemeralRequest} to have them generated instead.
     */
    public HttpRequest.Builder newRequestBuilder(
            URI endpoint, String resolvedCredential, String sessionId, String requestId) {
        validateEndpoint(endpoint);
        requireSessionShape(sessionId);
        requireRequestShape(requestId);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint);
        OpenCodeZenProtocolPolicy.applyIdentityHeaders(builder, sessionId, requestId, userAgent);
        String credential = resolvedCredential == null || resolvedCredential.isBlank()
                ? OpenCodeZenProtocolPolicy.FREE_TIER_CREDENTIAL : resolvedCredential;
        if (credential.indexOf('\n') >= 0 || credential.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Credential must be single-line");
        }
        // Python V2.4 uses x-api-key + anthropic-version on /messages,
        // not Authorization: Bearer.
        if (endpoint.getPath().endsWith(OpenCodeZenProtocolPolicy.ANTHROPIC_MESSAGES_PATH)) {
            return builder.header("x-api-key", credential)
                    .header("anthropic-version", "2023-06-01");
        }
        return builder.header("Authorization", "Bearer " + credential);
    }

    /**
     * Resolves free/public versus paid/key credentials before applying request headers.
     * The identifiers must already carry the frozen client shape.
     */
    public HttpRequest.Builder newModelRequestBuilder(
            URI endpoint, boolean freeModel, String apiKey, String sessionId, String requestId) {
        return newRequestBuilder(endpoint,
                OpenCodeZenCredentialPolicy.credentialForModel(freeModel, apiKey),
                sessionId, requestId);
    }

    /**
     * One-stop builder for consumers that do not want to manage identifier shapes: the session
     * identifier is generated here and cached per conversation, the request identifier is fresh
     * for every call.
     */
    public HttpRequest.Builder newConversationRequest(
            URI endpoint, boolean freeModel, String apiKey, UUID conversationKey) {
        Objects.requireNonNull(conversationKey, "conversationKey");
        return newRequestBuilder(endpoint,
                OpenCodeZenCredentialPolicy.credentialForModel(freeModel, apiKey),
                sessionFor(conversationKey), OpenCodeZenIdGenerator.newRequestId());
    }

    /** One-stop builder for a one-off request (credential probe, model discovery). */
    public HttpRequest.Builder newEphemeralRequest(
            URI endpoint, boolean freeModel, String apiKey) {
        return newRequestBuilder(endpoint,
                OpenCodeZenCredentialPolicy.credentialForModel(freeModel, apiKey),
                OpenCodeZenIdGenerator.newSessionId(), OpenCodeZenIdGenerator.newRequestId());
    }

    /**
     * Builds a complete inference POST (not only headers) from the proven Python V2.4 wire
     * format. The input payload is copied, and tool placeholders are never executed here.
     * Always handle the returned upstream SSE in the consuming adapter.
     */
    public HttpRequest newConversationInferenceRequest(
            URI endpoint, ZenModelProtocol protocol, boolean freeModel, String apiKey,
            UUID conversationKey, Map<String, Object> payload) {
        return postWithEnvelope(newConversationRequest(
                endpoint, freeModel, apiKey, conversationKey), endpoint, protocol, payload);
    }

    public HttpRequest newEphemeralInferenceRequest(
            URI endpoint, ZenModelProtocol protocol, boolean freeModel, String apiKey,
            Map<String, Object> payload) {
        return postWithEnvelope(newEphemeralRequest(
                endpoint, freeModel, apiKey), endpoint, protocol, payload);
    }

    /** Convenience method for standard Chat Completions inference. */
    public HttpRequest newConversationChatRequest(
            boolean freeModel, String apiKey, UUID conversationKey, Map<String, Object> payload) {
        return newConversationInferenceRequest(
                URI.create(OpenCodeZenProtocolPolicy.DEFAULT_BASE_URL
                        + OpenCodeZenProtocolPolicy.CHAT_COMPLETIONS_PATH),
                ZenModelProtocol.CHAT, freeModel, apiKey, conversationKey, payload);
    }

    private static HttpRequest postWithEnvelope(
            HttpRequest.Builder builder, URI endpoint, ZenModelProtocol protocol,
            Map<String, Object> payload) {
        Objects.requireNonNull(payload, "payload");
        String expectedPath = OpenCodeZenProtocolPolicy.inferencePath(protocol);
        if (!endpoint.getPath().endsWith(expectedPath)) {
            throw new IllegalArgumentException(
                    "Endpoint path does not match " + protocol + " protocol: " + expectedPath);
        }
        Map<String, Object> copy = new LinkedHashMap<>(payload);
        if (!(copy.get("model") instanceof String model) || model.isBlank()) {
            throw new IllegalArgumentException("model must be a non-empty string");
        }
        OpenCodeZenProtocolPolicy.applyRequiredEnvelope(copy, protocol, true);
        try {
            byte[] bytes = JSON.writeValueAsBytes(copy);
            return builder.header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(180))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload cannot be serialized as JSON", e);
        }
    }

    public String userAgent() {
        return userAgent;
    }

    private String sessionFor(UUID conversationKey) {
        String cached = conversationSessions.get(conversationKey);
        if (cached != null) {
            return cached;
        }
        if (conversationSessions.size() >= MAX_CACHED_CONVERSATIONS) {
            // Provider-side affinity is only metadata: dropping it is harmless, unbounded
            // growth is not.
            conversationSessions.clear();
        }
        String generated = OpenCodeZenIdGenerator.newSessionId();
        String existing = conversationSessions.putIfAbsent(conversationKey, generated);
        return existing == null ? generated : existing;
    }

    private static void validateEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        String scheme = endpoint.getScheme();
        if (!endpoint.isAbsolute() || endpoint.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("OpenCode Zen endpoint must be an absolute HTTP(S) URI");
        }
    }

    private static void requireSessionShape(String sessionId) {
        if (!OpenCodeZenIdGenerator.isSessionShaped(sessionId)) {
            throw new IllegalArgumentException(
                    "OpenCode Zen session id must carry the client shape ses_[0-9a-f]{12}[0-9A-Za-z]{14};"
                            + " generate it with OpenCodeZenIdGenerator (a UUID or arbitrary string is"
                            + " answered upstream with 403 FreeTierError)");
        }
    }

    private static void requireRequestShape(String requestId) {
        if (!OpenCodeZenIdGenerator.isRequestShaped(requestId)) {
            throw new IllegalArgumentException(
                    "OpenCode Zen request id must carry the client shape msg_[0-9a-f]{12}[0-9A-Za-z]{14};"
                            + " generate it with OpenCodeZenIdGenerator (a UUID or arbitrary string is"
                            + " answered upstream with 403 FreeTierError)");
        }
    }
}
