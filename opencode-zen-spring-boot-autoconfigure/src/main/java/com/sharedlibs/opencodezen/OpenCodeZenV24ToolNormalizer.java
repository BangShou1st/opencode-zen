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
 * Normalizes the caller's tool declarations into the shape Zen reads.
 *
 * <p>Mirrors OpenCode-Zen-FreeX v0.3.1's {@code normalizeTools} (and the Python
 * V3.0 proxy's {@code _normalize_tools}) without changing the caller's objects.
 * Synthetic placeholders never become authorized tool implementations.
 *
 * <p><b>The legacy {@code functions} array is folded into {@code tools} and
 * dropped.</b> The legacy top-level {@code functions} array is the older way of
 * declaring the same tools, and one a chat host may still use. Its names are
 * declarations, so they count as declared — but every declaration has to go
 * out in {@code tools}, because that is the array Zen reads. Left where it was,
 * a declaration is one Zen may not read; and a declaration in both arrays is a
 * tool sent twice, once with OpenCode's wording and once with the host's.
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

        // The legacy declarations, in the chat wire shape. A name already in
        // `tools` is one declaration under two spellings: it goes out once.
        List<Map<String, Object>> legacy = new ArrayList<>();
        Set<String> given = new LinkedHashSet<>();
        if (rawLegacy instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map) || !(map.get("name") instanceof String name)) {
                    continue; // invalid legacy function entries are ignored, as upstream does
                }
                Map<String, Object> copy = stringMap(map);
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
                given.add(name);
                firstPositions.putIfAbsent(name, details);
                result.add(outer);
            }
        }

        // Every legacy declaration goes out in `tools`, in the chat shape, with
        // the caller's own parameters. The host's parameters are kept: Zen does
        // not read those, and the call the model makes has to fit the tool that
        // will run it.
        for (Map<String, Object> function : legacy) {
            String name = (String) function.get("name");
            if (firstPositions.containsKey(name)) {
                continue; // already declared in `tools`: one declaration, sent once
            }
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("name", name);
            if (function.containsKey("description")) {
                definition.put("description", function.get("description"));
            }
            definition.put("parameters", function.get("parameters"));
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", definition);
            firstPositions.put(name, definition);
            result.add(tool);
        }

        // A tool the caller declared keeps its own schema and takes only the
        // captured wording: Zen reads the wording of a tool it knows by name,
        // and a host that is not OpenCode describes its tools in its own words.
        for (String name : OpenCodeZenClientProfile.pinnedToolNames()) {
            String captured = OpenCodeZenClientProfile.capturedDescription(name);
            Map<String, Object> declared = firstPositions.get(name);
            if (declared != null) {
                declared.put("description", captured);
            } else if (OpenCodeZenProtocolPolicy.reservedToolNames().contains(name)) {
                // The names in `required` are stood in for: a host that is not
                // OpenCode has no tool by these names, and Zen reads a request
                // as belonging to a generation partly by the tools it carries.
                result.add(OpenCodeZenClientProfile.toolDeclaration(name, protocol));
            }
        }

        // The legacy array is dropped: every declaration it carried is in
        // `tools` now, and leaving it would send one tool twice.
        if (protocol == ZenModelProtocol.CHAT) {
            body.remove("functions");
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

    private static void checkProtocol(ZenModelProtocol protocol) {
        if (protocol != ZenModelProtocol.CHAT
                && protocol != ZenModelProtocol.RESPONSES
                && protocol != ZenModelProtocol.ANTHROPIC) {
            throw new IllegalArgumentException("Unsupported inference protocol: " + protocol);
        }
    }
}
