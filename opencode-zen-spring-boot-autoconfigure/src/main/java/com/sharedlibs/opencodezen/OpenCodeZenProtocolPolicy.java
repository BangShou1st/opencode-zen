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
 * <p><b>Reference posture (2026-10-16).</b> The known-good client base URL is
 * {@code https://opencode.ai/inference/v1}. {@code https://opencode.ai/zen/v1} is a
 * legacy <i>discovery-only</i> endpoint: its {@code /models} listing may still be used as a
 * catalogue fallback, but inference traffic must never be sent there. The request must also
 * carry the full CLI core tool envelope — {@code bash, edit, glob, grep, read} — otherwise the
 * free tier answers {@code 403 FreeTierError}. With that envelope and the shaped
 * {@code ses_}/{@code msg_} identifiers the upstream answers {@code 200} (or {@code 429}
 * when the free quota is exhausted), which is the acceptance contract consumers rely on.
 */
public final class OpenCodeZenProtocolPolicy {

    /** Catalogue/inference base URL declared by the official model directory. */
    public static final String INFERENCE_BASE_URL = "https://opencode.ai/inference/v1";

    /** Legacy discovery-only base URL. Never send inference traffic here. */
    public static final String LEGACY_DISCOVERY_BASE_URL = "https://opencode.ai/zen/v1";

    /** Base URL used by default. Inference must always target this. */
    public static final String DEFAULT_BASE_URL = INFERENCE_BASE_URL;

    public static final String USER_AGENT =
            "opencode/1.19.0 ai-sdk/provider-utils/4.0.23 runtime/bun/1.3.14";

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
    public static final String GLOBAL_PROJECT = "global";
    public static final String FREE_TIER_CREDENTIAL = "public";

    /** The CLI sends this accept header; keep it for byte-level parity. */
    public static final String ACCEPT_VALUE = "*/*";

    private static final String RESERVED_TOOL_DESCRIPTION =
            "Not available. Never call this tool.";

    /**
     * The CLI core tool names, in the verified wire order. A request that omits this envelope
     * is rejected by the free tier with {@code 403 FreeTierError}; the set is therefore part of
     * the request contract, not an optional decoration.
     */
    private static final List<String> RESERVED_TOOL_NAMES =
            List.of("bash", "edit", "glob", "grep", "read");

    private static final List<String> RESERVED_TOOL_ARGUMENTS =
            List.of("command", "filePath", "pattern", "pattern", "filePath");

    private static final List<Map<String, Object>> RESERVED_TOOLS = buildReservedTools();

    private OpenCodeZenProtocolPolicy() {
    }

    /** Adds the verified OpenCode CLI identity headers to an outgoing request. */
    public static void applyIdentityHeaders(
            HttpRequest.Builder builder, String sessionId, String requestId) {
        applyIdentityHeaders(builder, sessionId, requestId, USER_AGENT);
    }

    /** Adds the verified CLI identity headers with an application-configured User-Agent. */
    public static void applyIdentityHeaders(
            HttpRequest.Builder builder, String sessionId, String requestId, String userAgent) {
        Objects.requireNonNull(builder, "builder");
        requireSingleLine(sessionId, "session id");
        requireSingleLine(requestId, "request id");
        validateUserAgent(userAgent);
        builder.header("User-Agent", userAgent)
                .header("Accept", ACCEPT_VALUE)
                .header(CLIENT_HEADER, CLIENT_ID)
                .header(REQUEST_HEADER, requestId)
                .header(PROJECT_HEADER, GLOBAL_PROJECT)
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

    /** The immutable reserved tool declarations required by the verified free-tier envelope. */
    public static List<Map<String, Object>> reservedToolDeclarations() {
        return RESERVED_TOOLS;
    }

    /** The reserved CLI core tool names, in wire order. */
    public static List<String> reservedToolNames() {
        return RESERVED_TOOL_NAMES;
    }

    /** Adds the required CLI-shaped envelope to an outgoing chat payload. */
    public static void applyRequiredChatEnvelope(Map<String, Object> payload, boolean includeUsage) {
        Objects.requireNonNull(payload, "payload");
        payload.put("stream", true);
        if (includeUsage) {
            payload.put("stream_options", Map.of("include_usage", true));
        }
        payload.put("tools", RESERVED_TOOLS);
    }

    /**
     * Appends Zen's reserved declarations after application-authorized tools. A reserved name
     * that the application already declared is skipped, so the combined list can never contain a
     * duplicate definition (a duplicate is a protocol violation upstream). Declarations that do
     * not carry the chat {@code function.name} shape are passed through untouched.
     */
    public static List<Object> appendReservedTools(Collection<?> applicationTools) {
        List<Object> tools = new ArrayList<>();
        Set<String> present = new java.util.HashSet<>();
        if (applicationTools != null) {
            for (Object tool : applicationTools) {
                tools.add(tool);
                String name = chatToolName(tool);
                if (name != null) {
                    present.add(name);
                }
            }
        }
        for (int index = 0; index < RESERVED_TOOL_NAMES.size(); index++) {
            if (present.contains(RESERVED_TOOL_NAMES.get(index))) {
                continue;
            }
            tools.add(RESERVED_TOOLS.get(index));
        }
        return List.copyOf(tools);
    }

    private static String chatToolName(Object tool) {
        if (!(tool instanceof Map<?, ?> map) || !(map.get("function") instanceof Map<?, ?> function)) {
            return null;
        }
        Object name = function.get("name");
        return name instanceof String text && !text.isBlank() ? text : null;
    }

    /** True when a user-authorized tool attempts to claim a provider-reserved name. */
    public static boolean hasReservedToolName(Collection<String> toolNames) {
        if (toolNames == null) {
            return false;
        }
        Set<String> reserved = Set.copyOf(RESERVED_TOOL_NAMES);
        return toolNames.stream().anyMatch(reserved::contains);
    }

    private static List<Map<String, Object>> buildReservedTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (int index = 0; index < RESERVED_TOOL_NAMES.size(); index++) {
            tools.add(functionTool(RESERVED_TOOL_NAMES.get(index), RESERVED_TOOL_ARGUMENTS.get(index)));
        }
        return List.copyOf(tools);
    }

    private static Map<String, Object> functionTool(String name, String propertyName) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", RESERVED_TOOL_DESCRIPTION,
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(propertyName, Map.of("type", "string")),
                                "additionalProperties", false)));
    }

    private static void requireSingleLine(String value, String label) {
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("OpenCode " + label + " must be non-blank and single-line");
        }
    }
}
