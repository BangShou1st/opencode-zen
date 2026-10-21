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

    /** Claude Code's per-conversation id at the tail of {@code metadata.user_id}. */
    private static final java.util.regex.Pattern CLAUDE_SESSION =
            java.util.regex.Pattern.compile("_session_([0-9A-Fa-f-]{36})$");

    private final String userAgent;
    private final ConcurrentMap<String, String> conversationSessions = new ConcurrentHashMap<>();

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

    /** For proxy adapters that receive real OpenCode session/project/Anthropic headers. */
    public HttpRequest newConversationInferenceRequest(
            URI endpoint, ZenModelProtocol protocol, boolean freeModel, String apiKey,
            UUID conversationKey, Map<String, Object> payload,
            Map<String, String> incomingHeaders) {
        return postWithEnvelope(newConversationRequest(
                endpoint, freeModel, apiKey, conversationKey),
                endpoint, protocol, payload, incomingHeaders);
    }

    public HttpRequest newEphemeralInferenceRequest(
            URI endpoint, ZenModelProtocol protocol, boolean freeModel, String apiKey,
            Map<String, Object> payload, Map<String, String> incomingHeaders) {
        return postWithEnvelope(newEphemeralRequest(endpoint, freeModel, apiKey),
                endpoint, protocol, payload, incomingHeaders);
    }

    /** Convenience method for standard Chat Completions inference. */
    public HttpRequest newConversationChatRequest(
            boolean freeModel, String apiKey, UUID conversationKey, Map<String, Object> payload) {
        return newConversationInferenceRequest(
                URI.create(OpenCodeZenProtocolPolicy.DEFAULT_BASE_URL
                        + OpenCodeZenProtocolPolicy.CHAT_COMPLETIONS_PATH),
                ZenModelProtocol.CHAT, freeModel, apiKey, conversationKey, payload);
    }

    private HttpRequest postWithEnvelope(
            HttpRequest.Builder builder, URI endpoint, ZenModelProtocol protocol,
            Map<String, Object> payload) {
        return postWithEnvelope(builder, endpoint, protocol, payload, Map.of());
    }

    private HttpRequest postWithEnvelope(
            HttpRequest.Builder builder, URI endpoint, ZenModelProtocol protocol,
            Map<String, Object> payload, Map<String, String> incomingHeaders) {
        Objects.requireNonNull(payload, "payload");
        applySafeIncomingHeaders(builder, protocol, incomingHeaders, payload);
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

    private void applySafeIncomingHeaders(
            HttpRequest.Builder builder, ZenModelProtocol protocol,
            Map<String, String> incoming, Map<String, Object> payload) {
        String project = header(incoming, "x-opencode-project");
        if (project != null) {
            builder.setHeader("x-opencode-project", singleLine(project, "project"));
        }
        // A session the caller states is taken verbatim when it is an id the
        // client could have minted (its counter gate passes); otherwise the
        // session this factory already generated for the conversation stands.
        String session = header(incoming, "x-opencode-session");
        if (session == null) session = header(incoming, "x-opencode-session-id");
        if (session == null) session = header(incoming, "x-session-affinity");
        if (session == null) session = header(incoming, "x-session-id");
        if (session == null) session = header(incoming, "session_id");
        if (session == null) session = header(incoming, "conversation-id");
        if (session == null) session = header(incoming, "conversation_id");
        if (session != null && OpenCodeZenIdGenerator.isV24SessionAffinityValid(session)) {
            builder.setHeader("x-opencode-session", session);
            builder.setHeader("x-opencode-session-id", session);
        }
        // The body may name the conversation too. A host signal is the
        // conversation's own name for itself, and it is not an id the client
        // minted, so it is remembered as the key of a session of our own
        // rather than sent as one: the same signal then answers the same
        // upstream session for the whole conversation, and two different
        // signals never share one.
        if (session == null) {
            String named = bodyConversationSignal(payload);
            if (named != null) {
                String derived = sessionForSignal(named);
                if (derived != null) {
                    builder.setHeader("x-opencode-session", derived);
                    builder.setHeader("x-opencode-session-id", derived);
                }
            }
        }
        if (protocol == ZenModelProtocol.ANTHROPIC) {
            String version = header(incoming, "anthropic-version");
            if (version != null) {
                builder.setHeader("anthropic-version", singleLine(version, "anthropic-version"));
            }
            String beta = header(incoming, "anthropic-beta");
            if (beta != null) {
                builder.setHeader("anthropic-beta", singleLine(beta, "anthropic-beta"));
            }
        }
    }

    /**
     * The name a request body gives its conversation, in the order the free
     * tier's own identity rule reads it: the host's conversation id first,
     * then the metadata name, then the Claude Code user id.
     *
     * <p>An empty name is no name — the request is keyed the way any unnamed
     * one is, and not by a key every host that sends an empty name shares.
     */
    private static String bodyConversationSignal(Map<String, Object> payload) {
        Object conversation = payload.get("conversation_id");
        if (conversation instanceof String text && !text.isEmpty()) {
            return text;
        }
        Object metadata = payload.get("metadata");
        if (metadata instanceof Map<?, ?> map) {
            Object named = map.get("session_id");
            if (named instanceof String text && !text.isEmpty()) {
                return text;
            }
            String claude = claudeSessionId(map.get("user_id"));
            if (claude != null) {
                return claude;
            }
        }
        return null;
    }

    /**
     * Claude Code names its conversation in the tail of {@code metadata.user_id}:
     * {@code user_<hash>_account_<uuid>_session_<uuid>}. Only the last part is
     * per conversation — the rest is per user, so it cannot name one. A JSON
     * string or object carrying a {@code session_id} is the same signal.
     */
    private static String claudeSessionId(Object userId) {
        if (userId instanceof String text && !text.isEmpty()) {
            java.util.regex.Matcher matcher = CLAUDE_SESSION.matcher(text);
            if (matcher.find()) {
                return matcher.group(1);
            }
            try {
                com.fasterxml.jackson.databind.JsonNode parsed = JSON.readTree(text);
                if (parsed != null && parsed.isObject()) {
                    com.fasterxml.jackson.databind.JsonNode named = parsed.get("session_id");
                    if (named != null && named.isTextual() && !named.asText().isEmpty()) {
                        return named.asText();
                    }
                }
            } catch (JsonProcessingException ignored) {
                // not JSON, and the underscore form above was its only other chance
            }
            return null;
        }
        if (userId instanceof Map<?, ?> map) {
            Object named = map.get("session_id");
            if (named instanceof String text && !text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> item : headers.entrySet()) {
            if (item.getKey() != null && item.getKey().equalsIgnoreCase(name)
                    && item.getValue() != null && !item.getValue().isBlank()) {
                return item.getValue();
            }
        }
        return null;
    }

    private static String singleLine(String value, String label) {
        if (value == null || value.isBlank()
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Invalid " + label + " header");
        }
        return value;
    }

    public String userAgent() {
        return userAgent;
    }

    private String sessionFor(UUID conversationKey) {
        return sessionForSignal("uuid:" + conversationKey);
    }

    /**
     * The session id kept for a conversation key, minted on first sight and
     * reused while the key stays.
     */
    private String sessionForSignal(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String cached = conversationSessions.get(key);
        if (cached != null) {
            return cached;
        }
        if (conversationSessions.size() >= MAX_CACHED_CONVERSATIONS) {
            // Provider-side affinity is only metadata: dropping it is harmless, unbounded
            // growth is not.
            conversationSessions.clear();
        }
        String generated = OpenCodeZenIdGenerator.newSessionId();
        String existing = conversationSessions.putIfAbsent(key, generated);
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
