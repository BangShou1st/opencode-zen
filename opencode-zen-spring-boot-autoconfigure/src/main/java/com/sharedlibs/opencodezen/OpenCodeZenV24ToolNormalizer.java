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
 * <p>Mirrors OpenCode-Zen-FreeX v0.5.9's {@code renameAndCompleteTools} (and
 * the Python V3.1 proxy's {@code _rename_and_complete_tools}) without changing
 * the caller's objects. The official set rides in the tools array beside the
 * host's own; nothing here is an authorized tool implementation — a call to an
 * official entry is answered by the host as the unknown tool it is, which is
 * the cue the model reads (the official prompt's tool availability note says
 * so in as many words).
 *
 * <p><b>The legacy {@code functions} array is folded into {@code tools} and
 * dropped.</b> The legacy top-level {@code functions} array is the older way of
 * declaring the same tools, and one a chat host may still use. Its names are
 * declarations, so they count as declared — but every declaration has to go
 * out in {@code tools}, because that is the array Zen reads.
 *
 * <p><b>Every named client tool leaves under a {@code clint_} name.</b> The
 * host's tools keep their parameters and their meaning, but go out renamed
 * (sanitized, collision-free, within the 64-byte limit), and the complete
 * official set for the generation follows them. Zen reads the request as an
 * OpenCode session; {@link OpenCodeZenToolRenames#restore} hands the host back
 * the names it declared, so nothing downstream sees the wire names.
 */
final class OpenCodeZenV24ToolNormalizer {
    private OpenCodeZenV24ToolNormalizer() { }

    /** The tools and the renames of one shaped request. */
    record ShapedTools(List<Object> tools, OpenCodeZenToolRenames renames) { }

    static List<Object> normalize(Map<String, Object> body, ZenModelProtocol protocol) {
        return shape(body, protocol).tools();
    }

    static ShapedTools shape(Map<String, Object> body, ZenModelProtocol protocol) {
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
        Set<String> declared = new LinkedHashSet<>();
        if (rawLegacy instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map) || !(map.get("name") instanceof String name)) {
                    continue; // invalid legacy function entries are ignored, as upstream does
                }
                Map<String, Object> copy = stringMap(map);
                legacy.add(copy);
                declared.add(name);
            }
        }

        OpenCodeZenToolRenames renames = new OpenCodeZenToolRenames();
        Set<String> used = new LinkedHashSet<>();
        List<Object> shaped = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object tool : list) {
                if (!(tool instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException("Invalid tool definition");
                }
                if (map.containsKey("function") && !(map.get("function") instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("function must be an object");
                }
                String client = clientNameOf(map, protocol);
                if (client == null) {
                    // A built-in or server tool carries a type of its own and
                    // is left untouched.
                    shaped.add(tool);
                    continue;
                }
                declared.add(client);
                Map<String, Object> outer = stringMap(map);
                Map<String, Object> details = outer;
                if (map.get("function") instanceof Map<?, ?> function) {
                    details = stringMap(function);
                    outer.put("function", details);
                }
                String wire = OpenCodeZenClientProfile.clientToolWireName(client, used);
                renames.add(client, wire);
                if (!wire.equals(client)) {
                    setToolName(outer, details, wire);
                }
                // The 2.x client writes strict:false on every tool; a client
                // that stated a value has stated one, and only an omitted
                // field is filled.
                if (protocol == ZenModelProtocol.CHAT && !details.containsKey("strict")) {
                    details.put("strict", false);
                }
                shaped.add(outer);
            }
        }

        // Every legacy declaration goes out in `tools`, in the chat shape, with
        // the caller's own parameters. The host's parameters are kept: Zen does
        // not read those, and the call the model makes has to fit the tool that
        // will run it.
        for (Map<String, Object> function : legacy) {
            String name = (String) function.get("name");
            if (renames.wire(name) != null) {
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
            String wire = OpenCodeZenClientProfile.clientToolWireName(name, used);
            renames.add(name, wire);
            if (!wire.equals(name)) {
                definition.put("name", wire);
            }
            shaped.add(tool);
        }

        // The complete official set follows the client tools: Zen reads the
        // request as an OpenCode session partly by the tools it carries.
        for (String name : OpenCodeZenClientProfile.officialToolNames()) {
            shaped.add(OpenCodeZenClientProfile.officialToolDeclaration(name, protocol));
        }

        // The legacy array is dropped: every declaration it carried is in
        // `tools` now, and leaving it would send one tool twice.
        if (protocol == ZenModelProtocol.CHAT) {
            body.remove("functions");
        }
        rewriteToolReferences(body, protocol, renames);
        return new ShapedTools(shaped, renames);
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

    /** The client name of one tool definition, or null for a tool that is not
     * a named client function: Responses built-in tools and Anthropic server
     * tools carry a type of their own and are left untouched. */
    private static String clientNameOf(Map<?, ?> tool, ZenModelProtocol protocol) {
        Object type = tool.get("type");
        if (protocol == ZenModelProtocol.RESPONSES
                && type != null && !"function".equals(type) && !"custom".equals(type)) {
            return null;
        }
        if (protocol == ZenModelProtocol.ANTHROPIC
                && type != null && !"custom".equals(type)) {
            return null;
        }
        Object function = tool.get("function");
        Object name = function instanceof Map<?, ?> nested
                ? firstText(nested.get("name"), tool.get("name"))
                : firstText(tool.get("name"), function instanceof Map<?, ?> nested2 ? nested2.get("name") : null);
        return name instanceof String text && !text.isBlank() ? text : null;
    }

    private static Object firstText(Object first, Object second) {
        if (first instanceof String text && !text.isBlank()) {
            return text;
        }
        return second;
    }

    private static void setToolName(Map<String, Object> outer, Map<String, Object> details, String name) {
        if (outer.containsKey("function") && outer.get("function") == details) {
            details.put("name", name);
        } else if (outer.containsKey("name") || !outer.containsKey("function")) {
            outer.put("name", name);
        } else {
            details.put("name", name);
        }
    }

    /** Every outbound reference to a client tool has to name the wire spelling,
     * or the request contradicts itself: a named tool_choice, the legacy
     * function_call, and the tool-call history each protocol keeps. String
     * choices ("auto", "required", "none") carry no name and are left alone.
     * A nested map that has to be rewritten is copied once and written back
     * into its parent, because the caller's containers may be immutable. */
    private static void rewriteToolReferences(
            Map<String, Object> body, ZenModelProtocol protocol, OpenCodeZenToolRenames renames) {
        if (renames.isEmpty()) {
            return;
        }
        rewriteToolReferencesChecked(body, protocol, renames);
    }

    @SuppressWarnings("unchecked")
    private static void rewriteToolReferencesChecked(
            Map<String, Object> body, ZenModelProtocol protocol, OpenCodeZenToolRenames renames) {
        switch (protocol) {
            case CHAT -> {
                if (body.get("tool_choice") instanceof Map<?, ?> choice) {
                    Map<String, Object> container = asMutableMap(choice);
                    if (container.get("function") instanceof Map<?, ?> function) {
                        container.put("function", asMutableMap(function));
                        renameNamed((Map<String, Object>) container.get("function"), renames);
                    } else {
                        renameNamed(container, renames);
                    }
                    body.put("tool_choice", container);
                }
                if (body.get("function_call") instanceof Map<?, ?> call) {
                    Map<String, Object> container = asMutableMap(call);
                    renameNamed(container, renames);
                    body.put("function_call", container);
                }
                if (body.get("messages") instanceof List<?> messages) {
                    List<Object> rewrittenMessages = new ArrayList<>(messages.size());
                    boolean changed = false;
                    for (Object message : messages) {
                        if (!(message instanceof Map<?, ?> record)) {
                            rewrittenMessages.add(message);
                            continue;
                        }
                        Map<String, Object> mutable = asMutableMap(record);
                        Map<String, Object> messageOut = mutable;
                        if (mutable.get("function_call") instanceof Map<?, ?> call) {
                            Map<String, Object> container = asMutableMap(call);
                            renameNamed(container, renames);
                            mutable.put("function_call", container);
                        }
                        if (mutable.get("tool_calls") instanceof List<?> calls) {
                            List<Object> rewrittenCalls = new ArrayList<>(calls.size());
                            boolean callsChanged = false;
                            for (Object item : calls) {
                                if (item instanceof Map<?, ?> call
                                        && asMutableMap(call).get("function") instanceof Map<?, ?> function) {
                                    Map<String, Object> callOut = asMutableMap(call);
                                    Map<String, Object> functionOut = asMutableMap(function);
                                    renameNamed(functionOut, renames);
                                    callOut.put("function", functionOut);
                                    rewrittenCalls.add(callOut);
                                    callsChanged = true;
                                } else {
                                    rewrittenCalls.add(item);
                                }
                            }
                            if (callsChanged) {
                                mutable.put("tool_calls", rewrittenCalls);
                            }
                        }
                        // A copy is only written back when something under it
                        // was rewritten; an untouched message keeps its identity.
                        if (messageOut != record) {
                            changed = true;
                        }
                        rewrittenMessages.add(messageOut);
                    }
                    if (changed) {
                        body.put("messages", rewrittenMessages);
                    }
                }
            }
            case RESPONSES -> {
                if (body.get("tool_choice") instanceof Map<?, ?> choice) {
                    Map<String, Object> container = asMutableMap(choice);
                    renameNamed(container, renames);
                    body.put("tool_choice", container);
                }
                if (body.get("input") instanceof List<?> items) {
                    List<Object> rewritten = new ArrayList<>(items.size());
                    boolean changed = false;
                    for (Object item : items) {
                        if (item instanceof Map<?, ?> record
                                && ("function_call".equals(record.get("type"))
                                    || "custom_tool_call".equals(record.get("type")))) {
                            Map<String, Object> container = asMutableMap(record);
                            renameNamed(container, renames);
                            rewritten.add(container);
                            changed = true;
                        } else {
                            rewritten.add(item);
                        }
                    }
                    if (changed) {
                        body.put("input", rewritten);
                    }
                }
            }
            case ANTHROPIC -> {
                if (body.get("tool_choice") instanceof Map<?, ?> choice) {
                    Map<String, Object> container = asMutableMap(choice);
                    renameNamed(container, renames);
                    body.put("tool_choice", container);
                }
                if (body.get("messages") instanceof List<?> messages) {
                    List<Object> rewrittenMessages = new ArrayList<>(messages.size());
                    boolean changed = false;
                    for (Object message : messages) {
                        if (!(message instanceof Map<?, ?> record)
                                || !(record.get("content") instanceof List<?> blocks)) {
                            rewrittenMessages.add(message);
                            continue;
                        }
                        Map<String, Object> mutable = asMutableMap(record);
                        List<Object> rewrittenBlocks = new ArrayList<>(blocks.size());
                        boolean blocksChanged = false;
                        for (Object block : blocks) {
                            if (block instanceof Map<?, ?> entry
                                    && "tool_use".equals(entry.get("type"))) {
                                Map<String, Object> container = asMutableMap(entry);
                                renameNamed(container, renames);
                                rewrittenBlocks.add(container);
                                blocksChanged = true;
                            } else {
                                rewrittenBlocks.add(block);
                            }
                        }
                        if (blocksChanged) {
                            mutable.put("content", rewrittenBlocks);
                            changed = true;
                        }
                        rewrittenMessages.add(mutable);
                    }
                    if (changed) {
                        body.put("messages", rewrittenMessages);
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unsupported inference protocol: " + protocol);
        }
    }

    private static void renameNamed(Map<String, Object> container, OpenCodeZenToolRenames renames) {
        String wire = renames.wire(container.get("name") instanceof String text ? text : null);
        if (wire != null) {
            container.put("name", wire);
        }
    }

    /** The payload maps come from the caller and may be immutable views; the
     * reference rewrite mutates in place, so a read-only map is copied once. */
    private static Map<String, Object> asMutableMap(Map<?, ?> raw) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() instanceof String key) {
                copy.put(key, entry.getValue());
            }
        }
        return copy;
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
