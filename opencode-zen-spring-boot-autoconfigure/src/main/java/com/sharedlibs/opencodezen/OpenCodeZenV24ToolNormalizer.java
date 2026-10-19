package com.sharedlibs.opencodezen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Mirrors Python V2.4 _names_for_tools without changing the caller's objects.
 * Synthetic placeholders never become authorized tool implementations.
 */
final class OpenCodeZenV24ToolNormalizer {
    private OpenCodeZenV24ToolNormalizer() { }

    static List<Object> normalize(Map<String, Object> body, ZenModelProtocol protocol) {
        Objects.requireNonNull(body, "body");
        checkProtocol(protocol);
        Object raw = body.get("tools");
        if (raw != null && !(raw instanceof List<?>)) {
            throw new IllegalArgumentException("tools must be an array");
        }
        Object rawLegacy = protocol == ZenModelProtocol.CHAT ? body.get("functions") : null;
        if (rawLegacy != null && !(rawLegacy instanceof List<?>)) {
            throw new IllegalArgumentException("functions must be an array");
        }

        List<Map<String, Object>> legacy = new ArrayList<>();
        Set<String> given = new LinkedHashSet<>();
        if (rawLegacy instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map) || !(map.get("name") instanceof String name)) {
                    continue; // Python V2.4 ignores invalid legacy function entries
                }
                Map<String, Object> copy = stringMap(map);
                fillDescription(copy);
                legacy.add(copy);
                given.add(name);
            }
        }

        List<Object> result = new ArrayList<>();
        Map<String, Map<String, Object>> firstPositions = new LinkedHashMap<>();
        if (raw instanceof List<?> list) {
            for (Object tool : list) {
                if (!(tool instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException("Invalid tool definition");
                }
                if (map.containsKey("function") && !(map.get("function") instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("function must be an object");
                }
                Map<String, Object> outer = stringMap(map);
                Map<String, Object> details = outer;
                if (map.get("function") instanceof Map<?, ?> function) {
                    details = stringMap(function);
                    outer.put("function", details);
                }
                Object rawName = details.get("name");
                if (!(rawName instanceof String name)) {
                    throw new IllegalArgumentException("Invalid tool definition");
                }
                fillDescription(details);
                given.add(name);
                firstPositions.putIfAbsent(name, details);
                result.add(outer);
            }
        }

        for (String name : OpenCodeZenClientProfile.pinnedToolNames()) {
            String captured = OpenCodeZenClientProfile.capturedDescription(name);
            if (firstPositions.containsKey(name)) {
                firstPositions.get(name).put("description", captured);
            } else if (given.contains(name)) {
                for (Map<String, Object> function : legacy) {
                    if (name.equals(function.get("name"))) {
                        function.put("description", captured);
                    }
                }
            } else if (OpenCodeZenProtocolPolicy.reservedToolNames().contains(name)) {
                result.add(OpenCodeZenClientProfile.toolDeclaration(name, protocol));
            }
        }
        if (rawLegacy != null && protocol == ZenModelProtocol.CHAT) {
            body.put("functions", legacy);
        }
        return result;
    }

    static Set<String> callerDeclaredNames(Map<String, Object> body, ZenModelProtocol protocol) {
        Objects.requireNonNull(body, "body");
        checkProtocol(protocol);
        Set<String> result = new LinkedHashSet<>();
        Object tools = body.get("tools");
        if (tools instanceof Collection<?> list) {
            for (Object tool : list) {
                if (tool instanceof Map<?, ?> map) {
                    Object function = map.get("function");
                    Object name = function instanceof Map<?, ?> nested
                            ? nested.get("name") : map.get("name");
                    if (name instanceof String text) {
                        result.add(text);
                    }
                }
            }
        }
        if (protocol == ZenModelProtocol.CHAT && body.get("functions") instanceof Collection<?> list) {
            for (Object fn : list) {
                if (fn instanceof Map<?, ?> map && map.get("name") instanceof String text) {
                    result.add(text);
                }
            }
        }
        return Set.copyOf(result);
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("tool property key must be a string");
            }
            copy.put(key, entry.getValue());
        }
        return copy;
    }

    private static void fillDescription(Map<String, Object> fn) {
        Object value = fn.get("description");
        if (value == null || Boolean.FALSE.equals(value) || "".equals(value)) {
            fn.put("description", "Tool function");
        }
    }

    private static void checkProtocol(ZenModelProtocol protocol) {
        if (protocol != ZenModelProtocol.CHAT
                && protocol != ZenModelProtocol.RESPONSES
                && protocol != ZenModelProtocol.ANTHROPIC) {
            throw new IllegalArgumentException("Unsupported inference protocol: " + protocol);
        }
    }
}
