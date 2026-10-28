package com.sharedlibs.opencodezen;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared, versioned OpenCode Zen wire policy.
 *
 * <p>This class contains provider protocol facts only. Applications own credential
 * storage, persistence, routing, budgets, and provider-specific business flows.
 *
 * <p><b>Reference posture (2026-10-26).</b> The known-good client base URL is
 * {@code https://opencode.ai/inference/v1}; the 2.1 client groups its inference
 * routes by provider family ({@code /inference/openai/v1/...} for chat and
 * Responses, {@code /inference/anthropic/v1/...} for Messages — see
 * {@link #inferenceEndpoint}). {@code https://opencode.ai/zen/v1} is a
 * legacy <i>discovery-only</i> endpoint: its {@code /models} listing may still be used as a
 * catalogue fallback, but inference traffic must never be sent there. Every request
 * leaves as the 2.x generation — the 1.x lane was measured retired on 2026-10-25 —
 * and carries the official 2.x prompt and the complete official tool set: the
 * host's tools leave under {@code clint_} names and the official set follows them.
 * Passing offline compatibility checks does not imply upstream admission. The
 * upstream may independently return 403 or 429.
 */
public final class OpenCodeZenProtocolPolicy {

    /** Catalogue/inference base URL declared by the official model directory. */
    public static final String INFERENCE_BASE_URL = "https://opencode.ai/inference/v1";

    /** Legacy discovery-only base URL. Never send inference traffic here. */
    public static final String LEGACY_DISCOVERY_BASE_URL = "https://opencode.ai/zen/v1";

    /** Base URL used by default. Inference must always target this. */
    public static final String DEFAULT_BASE_URL = INFERENCE_BASE_URL;

    public static final String USER_AGENT =
            OpenCodeZenClientProfile.userAgent();

    public static final String MODELS_PATH = "/models";
    public static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    public static final String RESPONSES_PATH = "/responses";
    public static final String ANTHROPIC_MESSAGES_PATH = "/messages";

    public static final String CLIENT_HEADER = "x-opencode-client";
    public static final String REQUEST_HEADER = "x-opencode-request";
    public static final String PROJECT_HEADER = "x-opencode-project";
    public static final String SESSION_HEADER = "x-opencode-session";
    public static final String SESSION_ID_HEADER = "x-opencode-session-id";

    public static final String CLIENT_ID = "cli";
    /** A project id in the shape the CLI sends — the working directory's git
     * commit hash, 40 hex. The tier's free quota is ledged per project id and
     * egress together, and nothing is known to read the id beyond its shape, so
     * a host that does not name its project gets one of its own, held for the
     * window {@link OpenCodeZenIdentityWindow#identityTtl() identityTtl} sets:
     * a ledger no other traffic shares, wherever the request lands. The old
     * shared {@code global} bucket on popular exits arrived pre-burned. */
    public static final String GLOBAL_PROJECT = "global";
    public static final String FREE_TIER_CREDENTIAL = "public";

    /** The header that names the real endpoint when a request leaves through
     * the local Bun relay. */
    public static final String RELAY_TARGET_HEADER = "x-relay-target";

    /** The CLI sends this accept header; keep it for byte-level parity. */
    public static final String ACCEPT_VALUE = "*/*";

    /** A 2.x Chat body carries the client's own completion ceiling when the
     * caller did not ask for one. An explicit max_tokens or
     * max_completion_tokens still wins: the ceiling is the client's default,
     * not this library's opinion. */
    public static final int V2_COMPLETION_LIMIT = 32000;

    private static final java.util.random.RandomGenerator PROJECT_RANDOM =
            new java.security.SecureRandom();

    private OpenCodeZenProtocolPolicy() {
    }

    /** A fresh 40-hex project id for one request. */
    public static String randomProjectId() {
        byte[] bytes = new byte[20];
        PROJECT_RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder(40);
        for (byte item : bytes) {
            out.append(Character.forDigit((item >> 4) & 0xF, 16));
            out.append(Character.forDigit(item & 0xF, 16));
        }
        return out.toString();
    }

    /**
     * The 2.1 client groups /inference routes by provider family: chat and
     * Responses go to {@code /inference/openai/v1/...}, Messages to
     * {@code /inference/anthropic/v1/...}. A custom base URL and every other
     * origin keep the flat route, because the grouping belongs to the
     * /inference gateway alone. The Messages route also carries the beta query
     * the 2.x client appends.
     */
    public static String inferenceEndpoint(ZenModelProtocol protocol) {
        String path = inferencePath(protocol);
        if (!INFERENCE_BASE_URL.equals(DEFAULT_BASE_URL)) {
            return DEFAULT_BASE_URL + path;
        }
        String root = INFERENCE_BASE_URL.substring(0, INFERENCE_BASE_URL.length() - "/v1".length());
        if (protocol == ZenModelProtocol.ANTHROPIC) {
            return root + "/anthropic/v1" + path + "?beta=true";
        }
        return root + "/openai/v1" + path;
    }

    /** Adds the verified OpenCode CLI identity headers to an outgoing request. */
    public static void applyIdentityHeaders(
            HttpRequest.Builder builder, String sessionId, String requestId) {
        applyIdentityHeaders(builder, sessionId, requestId, USER_AGENT);
    }

    /** Adds the verified CLI identity headers with an application-configured User-Agent. */
    public static void applyIdentityHeaders(
            HttpRequest.Builder builder, String sessionId, String requestId, String userAgent) {
        applyIdentityHeaders(builder, sessionId, requestId, userAgent, randomProjectId());
    }

    /** Adds the verified CLI identity headers with an explicit project id: a
     * host that names its project keeps its own name; one that does not gets
     * an id of its own for the request. */
    public static void applyIdentityHeaders(
            HttpRequest.Builder builder, String sessionId, String requestId, String userAgent,
            String projectId) {
        Objects.requireNonNull(builder, "builder");
        requireSingleLine(sessionId, "session id");
        requireSingleLine(requestId, "request id");
        validateUserAgent(userAgent);
        requireSingleLine(projectId, "project id");
        builder.header("User-Agent", userAgent)
                .header("Accept", ACCEPT_VALUE)
                .header(CLIENT_HEADER, CLIENT_ID)
                .header(PROJECT_HEADER, projectId)
                .header(SESSION_HEADER, sessionId)
                .header(SESSION_ID_HEADER, sessionId);
    }

    /** Validates an application-configured User-Agent before request construction. */
    public static void validateUserAgent(String userAgent) {
        requireSingleLine(userAgent, "User-Agent");
    }

    /** Builds the bearer value, using the public credential when no key is configured. */
    public static String authorizationHeader(String apiKey) {
        String credential = apiKey == null || apiKey.isBlank() ? FREE_TIER_CREDENTIAL : apiKey;
        return "Bearer " + credential;
    }

    /**
     * The official 2.x tool names, in wire order. A request whose tools do not
     * read as an OpenCode session is rejected by the free tier with
     * {@code 403 FreeTierError}; the set is therefore part of the request
     * contract, not an optional decoration. A caller tool that names one of
     * these is not refused — it leaves under a {@code clint_} name, and the
     * official set follows it.
     */
    public static List<String> reservedToolNames() {
        return OpenCodeZenClientProfile.officialToolNames();
    }

    /** Pinned OpenCode 2.x shell/read declarations from the included profile.
     *
     * @deprecated the request path no longer stands shell/read placeholders in:
     *     the complete official set is appended after the renamed client tools
     *     (see {@link OpenCodeZenV24ToolNormalizer}). */
    @Deprecated(since = "v1.5.0", forRemoval = false)
    public static List<Map<String, Object>> reservedToolDeclarations() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (String name : List.of("shell", "read")) {
            tools.add(OpenCodeZenClientProfile.toolDeclaration(name, ZenModelProtocol.CHAT));
        }
        return List.copyOf(tools);
    }

    /** Adds the required CLI-shaped envelope to an outgoing chat payload. */
    public static void applyRequiredChatEnvelope(Map<String, Object> payload, boolean includeUsage) {
        Objects.requireNonNull(payload, "payload");
        applyRequiredEnvelope(payload, ZenModelProtocol.CHAT, includeUsage);
    }

    /**
     * Appends the shaped tools after the caller's. Deprecated alias of the
     * shaping path, kept for callers that only want the tool list.
     */
    public static List<Object> appendReservedTools(Collection<?> applicationTools) {
        return appendRequiredTools(applicationTools, ZenModelProtocol.CHAT);
    }

    /** True when a caller tool names one of the official set's names. A tool
     * that does is not refused: it leaves under a {@code clint_} name and the
     * official set follows it. Kept for callers that gate on the collision. */
    public static boolean hasReservedToolName(Collection<String> toolNames) {
        if (toolNames == null) {
            return false;
        }
        Set<String> reserved = Set.copyOf(reservedToolNames());
        return toolNames.stream().anyMatch(reserved::contains);
    }

    /**
     * Protocol-specific envelope. It preserves the caller's tools under clint_
     * renames, appends the complete official set, injects the official 2.x
     * prompt in front of the host's own system text, and defaults the chat
     * completion ceiling when the caller did not ask for one.
     */
    public static void applyRequiredEnvelope(
            Map<String, Object> payload, ZenModelProtocol protocol, boolean includeUsage) {
        shapeEnvelope(payload, protocol, includeUsage);
    }

    /**
     * The shaping path with the renames returned: the reply names the wire
     * spelling, so a host that reads tool calls restores the names it declared
     * through {@link OpenCodeZenToolRenames#restore}.
     */
    public static OpenCodeZenToolRenames shapeEnvelope(
            Map<String, Object> payload, ZenModelProtocol protocol, boolean includeUsage) {
        Objects.requireNonNull(payload, "payload");
        if (protocol != ZenModelProtocol.CHAT && protocol != ZenModelProtocol.RESPONSES
                && protocol != ZenModelProtocol.ANTHROPIC) {
            throw new IllegalArgumentException("Unsupported inference protocol: " + protocol);
        }
        payload.put("stream", true);
        if (protocol == ZenModelProtocol.CHAT && includeUsage) {
            Object current = payload.get("stream_options");
            if (current != null && !(current instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("stream_options must be an object");
            }
            Map<String, Object> options = new java.util.LinkedHashMap<>();
            if (current instanceof Map<?, ?> existing) {
                for (Map.Entry<?, ?> e : existing.entrySet()) {
                    if (!(e.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("stream_options keys must be strings");
                    }
                    options.put(key, e.getValue());
                }
            }
            options.put("include_usage", true);
            payload.put("stream_options", options);
        }
        OpenCodeZenV24ToolNormalizer.ShapedTools shaped =
                OpenCodeZenV24ToolNormalizer.shape(payload, protocol);
        payload.put("tools", shaped.tools());
        if (protocol == ZenModelProtocol.CHAT
                && !payload.containsKey("max_completion_tokens")
                && !payload.containsKey("max_tokens")) {
            payload.put("max_completion_tokens", V2_COMPLETION_LIMIT);
        }
        // The prompt carries today's date, and a conversation seed that
        // included it would answer a new Zen session every UTC day; the
        // session id is keyed by the caller before shaping, so the injection
        // runs after the identity is settled.
        OpenCodeZenOfficialPrompt.inject(payload, protocol,
                payload.get("model") instanceof String model ? model : null);
        return shaped.renames();
    }

    public static java.util.Set<String> callerDeclaredToolNames(
            Map<String, Object> payload, ZenModelProtocol protocol) {
        return OpenCodeZenV24ToolNormalizer.callerDeclaredNames(payload, protocol);
    }

    public static List<Object> appendRequiredTools(
            Collection<?> applicationTools, ZenModelProtocol protocol) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("tools", applicationTools == null ? List.of() : new ArrayList<>(applicationTools));
        return OpenCodeZenV24ToolNormalizer.shape(body, protocol).tools();
    }

    /** The key order a 2.1 Chat client writes: model first, the completion
     * ceiling after the stream options, and any other field after that. JSON
     * parsing does not care, but the gate fingerprints the body as well as the
     * headers, so the bytes leave in the client's own layout. */
    public static Map<String, Object> orderedChatBody(Map<String, Object> body) {
        List<String> order = List.of("model", "messages", "tools", "stream",
                "stream_options", "max_completion_tokens");
        Map<String, Object> ordered = new java.util.LinkedHashMap<>();
        for (String key : order) {
            if (body.containsKey(key)) {
                ordered.put(key, body.get(key));
            }
        }
        List<String> rest = new ArrayList<>(body.keySet());
        rest.removeAll(order);
        java.util.Collections.sort(rest);
        for (String key : rest) {
            ordered.put(key, body.get(key));
        }
        return ordered;
    }

    public static String inferencePath(ZenModelProtocol protocol) {
        return switch (protocol) {
            case CHAT -> CHAT_COMPLETIONS_PATH;
            case RESPONSES -> RESPONSES_PATH;
            case ANTHROPIC -> ANTHROPIC_MESSAGES_PATH;
            default -> throw new IllegalArgumentException("Unsupported inference protocol: " + protocol);
        };
    }

    private static void requireSingleLine(String value, String label) {
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("OpenCode " + label + " must be non-blank and single-line");
        }
    }
}
