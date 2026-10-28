package com.sharedlibs.opencodezen.autoconfigure;

import com.sharedlibs.opencodezen.OpenCodeZenCredentialPolicy;
import com.sharedlibs.opencodezen.OpenCodeZenIdGenerator;
import com.sharedlibs.opencodezen.OpenCodeZenIdentityWindow;
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
 * credential and — via {@link OpenCodeZenProtocolPolicy} — the official prompt and the tool
 * envelope that the free tier requires. Callers never need to know how the identifiers are
 * shaped.
 *
 * <p><b>One identity pair per conversation, held for a window.</b> The tier ledgers its free
 * quota per project id and egress together, and it files what it thinks of a conversation
 * against the session — so the factory hands one (session, project) pair per conversation, held
 * for the window {@link OpenCodeZenIdentityWindow#identityTtl() identityTtl} sets (60 seconds by
 * default; {@code OPENCODE_ZEN_FREE_IDENTITY_TTL_MS=0} restores the per-request minting this
 * library shipped with). A retry is the tier saying this pair's bucket is spent: {@link
 * #newConversationRetryRequest newConversationRetryRequest} replaces the pair wholesale, and the
 * pair that just answered is the one the conversation holds afterwards. The conversation
 * stickiness the factory used to keep is available through {@code sessionAffinity} for a host
 * that wants the affinity a stable session carries.
 *
 * <p><b>The request must leave as Bun-shaped traffic.</b> The tier reads the client's
 * transport fingerprint and refuses everything that is not Bun's with {@code 429
 * FreeUsageLimitError} (measured 2026-10-26: the same shaped request that Bun's fetch answers
 * with 200 is refused from python, curl, httpx and Java HttpClient alike, while magpie —
 * Bun-compiled, same upstream package, public credential — answers 200). When {@code
 * relayUrl} is configured, the request is addressed to that local relay — a dumb transparent
 * tunnel started from Bun — with the real endpoint riding in {@code x-relay-target}, so the
 * bytes leave from Bun's network stack. Without a relay the request leaves directly from
 * this JVM, which the tier currently refuses.
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
    private final boolean sessionAffinity;
    private final String relayUrl;
    private final ConcurrentMap<String, String> conversationSessions = new ConcurrentHashMap<>();
    private final OpenCodeZenIdentityWindow identityWindow = new OpenCodeZenIdentityWindow();

    public OpenCodeZenRequestFactory(String userAgent) {
        this(userAgent, false, null);
    }

    /** @param sessionAffinity true keeps one sticky upstream session per conversation key;
     *     false (the default) mints a fresh session id for every request. */
    public OpenCodeZenRequestFactory(String userAgent, boolean sessionAffinity) {
        this(userAgent, sessionAffinity, null);
    }

    /** @param relayUrl the local Bun relay the requests leave through, or null/blank to
     *     address the endpoint directly from this JVM (which the free tier currently refuses). */
    public OpenCodeZenRequestFactory(String userAgent, boolean sessionAffinity, String relayUrl) {
        this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
        this.sessionAffinity = sessionAffinity;
        this.relayUrl = relayUrl == null || relayUrl.isBlank() ? null : relayUrl.replaceAll("/+$", "");
        OpenCodeZenProtocolPolicy.validateUserAgent(userAgent);
    }

    /** True when this factory keeps one sticky upstream session per conversation. */
    public boolean isSessionAffinity() {
        return sessionAffinity;
    }

    /** The relay the requests leave through, or null when they leave directly. */
    public String relayUrl() {
        return relayUrl;
    }

    /**
     * Returns a request builder with the common identity and authorization headers applied.
     * The caller owns the request body, timeout, and HTTP method. Both identifiers must already
     * carry the frozen client shape; use {@link #newConversationRequest} or
     * {@link #newEphemeralRequest} to have them generated instead.
     */
    public HttpRequest.Builder newRequestBuilder(
            URI endpoint, String resolvedCredential, String sessionId, String requestId) {
        return newRequestBuilder(endpoint, resolvedCredential, sessionId, requestId,
                OpenCodeZenProtocolPolicy.randomProjectId());
    }

    /** The same builder with an explicit project id: the window's pair, or a
     * host-named id riding in through the incoming headers. */
    public HttpRequest.Builder newRequestBuilder(
            URI endpoint, String resolvedCredential, String sessionId, String requestId,
            String projectId) {
        validateEndpoint(endpoint);
        requireSessionShape(sessionId);
        requireRequestShape(requestId);
        // The protocol gates read the endpoint path BEFORE the relay rewrite:
        // a relayed request is addressed to the relay, and the real endpoint
        // rides in x-relay-target.
        boolean anthropic = endpoint.getPath().endsWith(OpenCodeZenProtocolPolicy.ANTHROPIC_MESSAGES_PATH);
        URI target = endpoint;
        HttpRequest.Builder builder;
        if (relayUrl != null) {
            target = URI.create(relayUrl + "/");
            builder = HttpRequest.newBuilder(target)
                    .header(OpenCodeZenProtocolPolicy.RELAY_TARGET_HEADER, endpoint.toString());
        } else {
            builder = HttpRequest.newBuilder(endpoint);
        }
        OpenCodeZenProtocolPolicy.applyIdentityHeaders(builder, sessionId, requestId, userAgent, projectId);
        String credential = resolvedCredential == null || resolvedCredential.isBlank()
                ? OpenCodeZenProtocolPolicy.FREE_TIER_CREDENTIAL : resolvedCredential;
        if (credential.indexOf('\n') >= 0 || credential.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Credential must be single-line");
        }
        // Python V2.4 uses x-api-key + anthropic-version on /messages,
        // not Authorization: Bearer.
        if (anthropic) {
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
     * One-stop builder for consumers that do not want to manage identifier shapes: the request
     * identifier is fresh for every call, and the (session, project) identity pair is the one
     * this conversation's window holds — a fresh pair once the window is over. Under
     * {@code sessionAffinity} the session comes from the sticky ladder and the project id is
     * minted per request.
     */
    public HttpRequest.Builder newConversationRequest(
            URI endpoint, boolean freeModel, String apiKey, UUID conversationKey) {
        Objects.requireNonNull(conversationKey, "conversationKey");
        OpenCodeZenIdentityWindow.Pair pair = sessionAffinity
                ? null
                : identityWindow.current("uuid:" + conversationKey, System.currentTimeMillis());
        String session = pair != null ? pair.session() : sessionFor(conversationKey);
        String project = pair != null ? pair.project() : OpenCodeZenProtocolPolicy.randomProjectId();
        return newRequestBuilder(endpoint,
                OpenCodeZenCredentialPolicy.credentialForModel(freeModel, apiKey),
                session, OpenCodeZenIdGenerator.newRequestId(), project);
    }

    /**
     * The retry's builder: the pair the tier ledgers against is replaced wholesale — the session
     * and the project id minted in one instant — and the pair that just answered is the one this
     * conversation's window holds afterwards, whatever {@code sessionAffinity} says about the
     * session it keeps. Build the retry through this method rather than through {@link
     * #newConversationRequest newConversationRequest}, whose window would hand back the spent
     * pair while it is still young.
     */
    public HttpRequest.Builder newConversationRetryRequest(
            URI endpoint, boolean freeModel, String apiKey, UUID conversationKey) {
        Objects.requireNonNull(conversationKey, "conversationKey");
        OpenCodeZenIdentityWindow.Pair fresh = identityWindow.rotate(
                "uuid:" + conversationKey, System.currentTimeMillis());
        return newRequestBuilder(endpoint,
                OpenCodeZenCredentialPolicy.credentialForModel(freeModel, apiKey),
                fresh.session(), OpenCodeZenIdGenerator.newRequestId(), fresh.project());
    }

    /** One-stop builder for a one-off request (credential probe, model discovery). A request
     * that names no conversation shares the unnamed slot — the single pair this library used
     * before the window existed — for the window identityTtl sets. */
    public HttpRequest.Builder newEphemeralRequest(
            URI endpoint, boolean freeModel, String apiKey) {
        OpenCodeZenIdentityWindow.Pair pair = identityWindow.current(null, System.currentTimeMillis());
        return newRequestBuilder(endpoint,
                OpenCodeZenCredentialPolicy.credentialForModel(freeModel, apiKey),
                pair.session(), OpenCodeZenIdGenerator.newRequestId(), pair.project());
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

    /** The retry's inference POST: the pair is replaced wholesale before the
     * request is built (see {@link #newConversationRetryRequest}). */
    public HttpRequest newConversationRetryInferenceRequest(
            URI endpoint, ZenModelProtocol protocol, boolean freeModel, String apiKey,
            UUID conversationKey, Map<String, Object> payload) {
        return postWithEnvelope(newConversationRetryRequest(
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

    /** Convenience method for standard Chat Completions inference, on the
     * 2.1 client's family-grouped route. */
    public HttpRequest newConversationChatRequest(
            boolean freeModel, String apiKey, UUID conversationKey, Map<String, Object> payload) {
        return newConversationInferenceRequest(
                URI.create(OpenCodeZenProtocolPolicy.inferenceEndpoint(ZenModelProtocol.CHAT)),
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
        OpenCodeZenProtocolPolicy.shapeEnvelope(copy, protocol, true);
        try {
            // The v2 Chat lane leaves in the client's key order; every other
            // lane keeps the body as it was built.
            byte[] bytes = JSON.writeValueAsBytes(protocol == ZenModelProtocol.CHAT
                    ? OpenCodeZenProtocolPolicy.orderedChatBody(copy) : copy);
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
        // Without session affinity, the request is its own session and the
        // host's name for the conversation is not consulted: a fresh id per
        // request arrives as a session the tier has never seen. With affinity,
        // a session the caller states is taken verbatim when it is an id the
        // client could have minted (its counter gate passes); otherwise the
        // session this factory already generated for the conversation stands.
        String session = sessionAffinity ? header(incoming, "x-opencode-session") : null;
        if (session == null && sessionAffinity) session = header(incoming, "x-opencode-session-id");
        if (session == null && sessionAffinity) session = header(incoming, "x-session-affinity");
        if (session == null && sessionAffinity) session = header(incoming, "x-session-id");
        if (session == null && sessionAffinity) session = header(incoming, "session_id");
        if (session == null && sessionAffinity) session = header(incoming, "conversation-id");
        if (session == null && sessionAffinity) session = header(incoming, "conversation_id");
        if (session != null && OpenCodeZenIdGenerator.isV24SessionAffinityValid(session)) {
            builder.setHeader("x-opencode-session", session);
            builder.setHeader("x-opencode-session-id", session);
        }
        // The body may name the conversation too. A host signal is the
        // conversation's own name for itself, and it is not an id the client
        // minted, so it is remembered as the key of a session of our own
        // rather than sent as one: the same signal then answers the same
        // upstream session for the whole conversation, and two different
        // signals never share one. (Affinity mode only.)
        if (session == null && sessionAffinity) {
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
        // The affinity mode hands the conversation back to the sticky ladder;
        // without it the identity window owns the pair and this is not called.
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
