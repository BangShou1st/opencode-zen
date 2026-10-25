package com.sharedlibs.opencodezen;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pinned 2.x client identity and the official 2.x prompt and tool set.
 *
 * <p>The identity (the User-Agent composition) comes from
 * {@code zen-client-profile.json}; the prompt ({@code opencode-prompt-v2.txt})
 * and the tool definitions ({@code official-tools.json}) come verbatim from the
 * OpenCode-Zen-FreeX capture. They are data, not code, and are never hand-edited:
 * Zen reads the system prompt and the tool definitions from the request body, so
 * a paraphrase is a different request as far as the free tier is concerned.
 */
public final class OpenCodeZenClientProfile {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode PROFILE = loadProfile();
    private static final JsonNode OFFICIAL_SET = loadOfficialSet();
    private static final String OFFICIAL_PROMPT_V2 = loadOfficialPrompt();

    /** The sentence every shaped prompt carries. A body that already holds one
     * came from an OpenCode-shaped client and is left alone, so repeated shaping
     * cannot duplicate the prompt. */
    public static final String OPENCODE_PROMPT_MARKER = "You are opencode, an interactive CLI tool";
    public static final String OPENCODE_PROMPT_MARKER_V2 = "You are an AI agent running in OpenCode";

    /** The prefix every client tool carries on the way out. It keeps client
     * tools distinct from the official set and gives the response path a name
     * to restore. */
    public static final String CLIENT_TOOL_PREFIX = "clint_";
    public static final int TOOL_NAME_LIMIT = 64;

    private OpenCodeZenClientProfile() { }

    private static JsonNode loadProfile() {
        try (InputStream in = OpenCodeZenClientProfile.class.getResourceAsStream(
                "/zen-client-profile.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing zen-client-profile.json resource");
            }
            JsonNode root = MAPPER.readTree(in);
            if (!"2.x".equals(root.path("generation").asText())
                    || !root.path("agent").path("opencode").isTextual()
                    || !root.path("tools").has("shell") || !root.path("tools").has("read")) {
                throw new IllegalStateException("Invalid 2.x Zen client profile");
            }
            return root;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load Zen client profile", e);
        }
    }

    private static JsonNode loadOfficialSet() {
        try (InputStream in = OpenCodeZenClientProfile.class.getResourceAsStream(
                "/official-tools.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing official-tools.json resource");
            }
            JsonNode root = MAPPER.readTree(in);
            JsonNode set = root.path("2.x");
            if (!set.isObject() || !set.path("order").isArray() || set.path("order").size() == 0
                    || !set.path("tools").isObject()) {
                throw new IllegalStateException("official-tools.json does not carry a 2.x set");
            }
            return set;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load official-tools.json", e);
        }
    }

    private static String loadOfficialPrompt() {
        try (InputStream in = OpenCodeZenClientProfile.class.getResourceAsStream(
                "/opencode-prompt-v2.txt")) {
            if (in == null) {
                throw new IllegalStateException("Missing opencode-prompt-v2.txt resource");
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load opencode-prompt-v2.txt", e);
        }
    }

    /** The OpenCode CLI version the pinned identity presents. */
    public static String agentVersion() {
        return PROFILE.path("agent").path("opencode").asText();
    }

    /** All named tools from the captured identity profile, preserving source order. */
    public static java.util.Set<String> pinnedToolNames() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        PROFILE.path("tools").fieldNames().forEachRemaining(names::add);
        return java.util.Collections.unmodifiableSet(names);
    }

    /** Unmodified captured description of an identity-profile tool. */
    public static String capturedDescription(String name) {
        JsonNode entry = PROFILE.path("tools").path(name);
        if (!entry.isObject() || !entry.path("description").isTextual()) {
            throw new IllegalArgumentException("Unknown captured tool: " + name);
        }
        return entry.path("description").asText();
    }

    /** The names of the official 2.x set, in the order the capture holds them.
     * Order is not validated upstream, but keeping it makes the wire body stable. */
    public static List<String> officialToolNames() {
        List<String> names = new ArrayList<>();
        OFFICIAL_SET.path("order").forEach(name -> names.add(name.asText()));
        return List.copyOf(names);
    }

    /** The 2.1.4 prompt capture, with placeholders for the model it names, the
     * UTC date above the environment block, and the environment block itself. */
    public static String officialPromptV2() {
        return OFFICIAL_PROMPT_V2;
    }

    /** The complete official 2.x prompt: the captured text with its model
     * section, its date and its environment block filled through the
     * placeholders they were captured with. */
    public static String officialSystemPrompt(String model, String workingDirectory,
            String platform, boolean gitRepository, String tempDirectory,
            java.time.ZonedDateTime now) {
        String named = model == null ? "" : model.strip();
        if (named.startsWith("opencode/")) {
            named = named.substring("opencode/".length()).strip();
        }
        if (named.isEmpty()) {
            named = "unknown";
        }
        String directory = workingDirectory == null || workingDirectory.isBlank()
                || ".".equals(workingDirectory) ? "/workspace" : workingDirectory;
        String platformName = platform == null || platform.isBlank() ? "linux" : platform;
        String temp = tempDirectory == null || tempDirectory.isBlank()
                ? "/tmp/opencode" : tempDirectory;
        String environment = String.join("\n",
                "<env>",
                "  Working directory: " + directory,
                "  Workspace root folder: " + directory,
                "  Is directory a git repo: " + (gitRepository ? "yes" : "no"),
                "  Platform: " + platformName,
                "  Prefer " + temp + " over generic system temporary directories such as /tmp; "
                        + "it is pre-created and approved for external access.",
                "</env>");
        return OFFICIAL_PROMPT_V2
                .replace("{{MODEL_NAME}}", displayName(named))
                .replace("{{MODEL_ID}}", named)
                .replace("{{DATE}}", utcDate(now))
                .replace("{{ENV}}", environment);
    }

    /** The 2.x prompt's model section carries the model's display name; this
     * library knows the id, so the id is titled — right for deepseek, imperfect
     * for glm, and the tier has not been measured to read this line at all. */
    private static String displayName(String model) {
        String[] parts = model.split("-");
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            if (index > 0) {
                out.append(' ');
            }
            String part = parts[index];
            out.append(part.isEmpty() ? part
                    : Character.toUpperCase(part.charAt(0)) + part.substring(1));
        }
        return out.toString();
    }

    /** The date the CLI writes: {@code Sun Oct 25 2026}, in UTC. */
    public static String utcDate(java.time.ZonedDateTime now) {
        java.time.ZonedDateTime stamp = now == null
                ? java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                : now.withZoneSameInstant(java.time.ZoneOffset.UTC);
        String[] days = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        String[] months = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        return "%s %s %02d %d".formatted(days[stamp.getDayOfWeek().getValue() % 7],
                months[stamp.getMonthValue() - 1], stamp.getDayOfMonth(), stamp.getYear());
    }

    /** Reduce a client name to the character set the upstreams accept,
     * collapsing every invalid run into one underscore and trimming
     * separators from the ends. */
    public static String sanitizeToolName(String name) {
        String cleaned = (name == null ? "" : name).replaceAll("[^A-Za-z0-9_-]+", "_");
        return cleaned.replaceAll("^[-_]+|[-_]+$", "");
    }

    /** Build the wire name for one client tool, keeping it within the 64-byte
     * limit and away from every name already used by this request. An empty
     * sanitized name becomes {@code tool}; a collision gets a {@code _2},
     * {@code _3}, ... suffix. */
    public static String clientToolWireName(String name, java.util.Set<String> used) {
        java.util.Set<String> seen = used == null ? new java.util.HashSet<>() : used;
        int max = TOOL_NAME_LIMIT - CLIENT_TOOL_PREFIX.length();
        String clean = sanitizeToolName(name);
        if (clean.isEmpty()) {
            clean = "tool";
        }
        if (clean.length() > max) {
            clean = clean.substring(0, max);
        }
        String base = CLIENT_TOOL_PREFIX + clean;
        if (!seen.contains(base)) {
            seen.add(base);
            return base;
        }
        for (int suffix = 2; ; suffix++) {
            String tag = "_" + suffix;
            String candidate = base.substring(0, Math.min(base.length(), TOOL_NAME_LIMIT - tag.length())) + tag;
            if (!seen.contains(candidate)) {
                seen.add(candidate);
                return candidate;
            }
        }
    }

    /** One official tool in the protocol's wire shape. The 2.x Chat shape marks
     * every function {@code strict:false}, as the captured client does. */
    public static Map<String, Object> officialToolDeclaration(String name, ZenModelProtocol protocol) {
        JsonNode node = OFFICIAL_SET.path("tools").path(name);
        if (!node.isObject() || !node.path("parameters").isObject()
                || !node.path("description").isTextual()) {
            throw new IllegalArgumentException("Missing official tool: " + name);
        }
        String description = node.path("description").asText();
        if (isWindows() && "shell".equals(name)) {
            // The 2.1.4 desktop client words its own shell after bash on both
            // platforms, so only the platform name moves (captured 2026-10-25
            // from the 2.1.4 desktop CLI).
            description = description.replace("on Linux using bash", "on Windows using bash");
        }
        Map<String, Object> schema = MAPPER.convertValue(
                node.path("parameters"), new TypeReference<Map<String, Object>>() { });
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", name);
        definition.put("description", description);
        return switch (protocol) {
            case CHAT -> {
                definition.put("parameters", schema);
                definition.put("strict", false);
                yield Map.of("type", "function", "function", definition);
            }
            case RESPONSES -> {
                definition.put("type", "function");
                definition.put("parameters", schema);
                yield definition;
            }
            case ANTHROPIC -> {
                definition.put("input_schema", schema);
                yield definition;
            }
            default -> throw new IllegalArgumentException("Unsupported inference protocol: " + protocol);
        };
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("windows");
    }

    /**
     * Synthetic declarations are protocol placeholders, not executable host tools.
     *
     * @deprecated the request path no longer stands synthetic tools in for the
     *     required names: the host's tools leave under {@code clint_} names and
     *     the complete official set follows them (see
     *     {@link OpenCodeZenV24ToolNormalizer}). Retained for callers that still
     *     read a single captured declaration.
     */
    @Deprecated(since = "v1.5.0", forRemoval = false)
    public static Map<String, Object> toolDeclaration(String name, ZenModelProtocol protocol) {
        JsonNode node = PROFILE.path("tools").path(name);
        if (!node.isObject() || !node.path("parameters").isObject()) {
            throw new IllegalArgumentException("Missing pinned tool: " + name);
        }
        String disclaimer = "This one is a placeholder that satisfies OpenCode Zen's request and is not "
                + "connected to anything: do not call it, and use the other tools you have.";
        Map<String, Object> schema = MAPPER.convertValue(
                node.path("parameters"), new TypeReference<Map<String, Object>>() { });
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", name);
        definition.put("description", node.path("description").asText() + "\n\n" + disclaimer);
        return switch (protocol) {
            case CHAT -> {
                definition.put("parameters", schema);
                yield Map.of("type", "function", "function", definition);
            }
            case RESPONSES -> {
                definition.put("type", "function");
                definition.put("parameters", schema);
                yield definition;
            }
            case ANTHROPIC -> {
                definition.put("input_schema", schema);
                yield definition;
            }
            default -> throw new IllegalArgumentException("Unsupported inference protocol: " + protocol);
        };
    }
}
