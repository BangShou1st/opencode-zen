package com.sharedlibs.opencodezen;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pinned 2.x shell/read profile shared with the working Python V2.4 proxy. */
public final class OpenCodeZenClientProfile {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode PROFILE = load();

    private OpenCodeZenClientProfile() { }

    private static JsonNode load() {
        try (InputStream in = OpenCodeZenClientProfile.class.getResourceAsStream(
                "/zen-client-profile.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing zen-client-profile.json resource");
            }
            JsonNode root = MAPPER.readTree(in);
            if (!"2.x".equals(root.path("generation").asText())
                    || !"2.0.29".equals(root.path("agent").path("opencode").asText())
                    || !root.path("tools").has("shell") || !root.path("tools").has("read")) {
                throw new IllegalStateException("Invalid 2.x Zen client profile");
            }
            return root;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load Zen client profile", e);
        }
    }

    /** All named tools from the captured profile, preserving source order. */
    public static java.util.Set<String> pinnedToolNames() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        PROFILE.path("tools").fieldNames().forEachRemaining(names::add);
        return java.util.Collections.unmodifiableSet(names);
    }

    /** Unmodified captured description, without the synthetic tool disclaimer. */
    public static String capturedDescription(String name) {
        JsonNode entry = PROFILE.path("tools").path(name);
        if (!entry.isObject() || !entry.path("description").isTextual()) {
            throw new IllegalArgumentException("Unknown captured tool: " + name);
        }
        return entry.path("description").asText();
    }

    /** Synthetic declarations are protocol placeholders, not executable host tools. */
    public static Map<String, Object> toolDeclaration(String name, ZenModelProtocol protocol) {
        JsonNode node = PROFILE.path("tools").path(name);
        if (!node.isObject() || !node.path("parameters").isObject()) {
            throw new IllegalArgumentException("Missing pinned tool: " + name);
        }
        String disclaimer = "This one is a placeholder that satisfies OpenCode Zen's request and is not "
                + "connected to anything: do not call it, and use the other tools you have.";
        Map<String, Object> schema = MAPPER.convertValue(
                node.path("parameters"), new TypeReference<Map<String, Object>>() {});
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
