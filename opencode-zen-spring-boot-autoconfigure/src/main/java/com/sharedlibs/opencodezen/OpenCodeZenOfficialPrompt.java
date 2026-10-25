package com.sharedlibs.opencodezen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The system prompts OpenCode CLI sessions send, and the protocol-aware
 * injection that puts one in front of a host's own instructions.
 *
 * <p>Zen's free tier accepts only agent-shaped traffic and reads the system
 * prompt from the request body, so a request from any other host has to carry
 * it. The base text is OpenCode's own (MIT), captured verbatim; the
 * environment block names the requested model, the working directory, the
 * platform and the UTC date.
 *
 * <p>No skill listing follows the environment block, though the CLI's own
 * prompt carries one. A host that is not OpenCode has none of OpenCode's
 * built-in skills: an advertised skill is a call waiting to happen, and there
 * is no tool behind it here.
 */
final class OpenCodeZenOfficialPrompt {

    private OpenCodeZenOfficialPrompt() { }

    /** Flatten a system or instruction value into its text: a string, a list
     * of content blocks, or a block carrying text or content. */
    static List<String> textFragments(Object value) {
        List<String> out = new ArrayList<>();
        collectFragments(value, out);
        return out;
    }

    private static void collectFragments(Object value, List<String> out) {
        if (value instanceof String text) {
            if (!text.isEmpty()) {
                out.add(text);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                collectFragments(item, out);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.get("text") instanceof String text) {
                collectFragments(text, out);
            } else if (map.containsKey("content")) {
                collectFragments(map.get("content"), out);
            }
        }
    }

    static boolean carriesOfficialPrompt(Object value) {
        for (String part : textFragments(value)) {
            if (part.contains(OpenCodeZenClientProfile.OPENCODE_PROMPT_MARKER)
                    || part.contains(OpenCodeZenClientProfile.OPENCODE_PROMPT_MARKER_V2)) {
                return true;
            }
        }
        return false;
    }

    /** The truth about tools this injection adds after the official prompt. The
     * prompt teaches Code Mode — "use `execute` to call the tools below" — and
     * the official set rides in the tools array beside the host's own, so a
     * model that believes its prompt calls {@code execute} first and is
     * answered {@code unknown tool}. The prompt's own convention is that a
     * {@code <system-reminder>} is harness instruction, so the correction
     * travels in one: the callable tools are the host's, under their clint_
     * names, and every unprefixed entry is an OpenCode built-in nothing here
     * implements. The names are read from the body as it stands — the request
     * path renames the host's tools before the prompt goes in, so the clint_
     * names the note lists are the wire names the model must call. */
    static String toolAvailabilityNote(Map<String, Object> body) {
        List<String> names = new ArrayList<>();
        if (body.get("tools") instanceof List<?> tools) {
            for (Object tool : tools) {
                if (!(tool instanceof Map<?, ?> record)) {
                    continue;
                }
                String name = toolName(record);
                if (name != null && name.startsWith(OpenCodeZenClientProfile.CLIENT_TOOL_PREFIX)) {
                    names.add(name);
                }
            }
        }
        String callable = names.isEmpty()
                ? "This host implements no tools at all; answer without tool calls."
                : "The tools you can call are the host's own, under their "
                        + OpenCodeZenClientProfile.CLIENT_TOOL_PREFIX + " names: "
                        + String.join(", ", names) + ".";
        return String.join("\n",
                "<system-reminder>",
                "Code Mode is not active in this session: this host is not OpenCode, and the built-in "
                        + "tools described above - `execute` and every entry of the catalog - are not "
                        + "implemented here. A call to one is answered with an unknown-tool error and "
                        + "nothing else.",
                callable + " Every other entry in the tools array is an OpenCode built-in nothing here "
                        + "runs: read that array as the host's own list, not as OpenCode's.",
                "</system-reminder>");
    }

    private static String toolName(Map<?, ?> tool) {
        Object function = tool.get("function");
        Object name = function instanceof Map<?, ?> nested
                ? nested.get("name") : tool.get("name");
        return name instanceof String text ? text : null;
    }

    /** Put the official prompt in front of a shaped body. The protocol decides
     * where its system text lives: Chat folds system and developer messages
     * into one leading system message, Anthropic uses the top-level system
     * value, and Responses uses instructions. A body that already carries the
     * marker is left untouched and answers false. The prompt is followed by
     * the tool availability note, so the model meets the correction before it
     * meets the host's own instructions. */
    static boolean inject(Map<String, Object> body, ZenModelProtocol protocol, String model) {
        String prompt = OpenCodeZenClientProfile.officialSystemPrompt(model, null, null,
                true, null, null) + "\n\n" + toolAvailabilityNote(body);
        return switch (protocol) {
            case CHAT -> injectChatPrompt(body, prompt);
            case ANTHROPIC -> injectAnthropicPrompt(body, prompt);
            case RESPONSES -> injectResponsesPrompt(body, prompt);
            default -> throw new IllegalArgumentException("Unsupported inference protocol: " + protocol);
        };
    }

    private static boolean injectChatPrompt(Map<String, Object> body, String prompt) {
        if (!(body.get("messages") instanceof List<?> messages)) {
            return false;
        }
        List<Object> kept = new ArrayList<>();
        List<String> instructions = new ArrayList<>();
        for (Object message : messages) {
            if (!(message instanceof Map<?, ?> record)
                    || (!"system".equals(record.get("role")) && !"developer".equals(record.get("role")))) {
                kept.add(message);
                continue;
            }
            if (carriesOfficialPrompt(record.get("content"))) {
                return false;
            }
            for (String part : textFragments(record.get("content"))) {
                String text = part.strip();
                if (!text.isEmpty()) {
                    instructions.add(text);
                }
            }
        }
        String system = instructions.isEmpty()
                ? prompt : prompt + "\n\n" + String.join("\n", instructions);
        List<Object> out = new ArrayList<>();
        out.add(Map.of("role", "system", "content", system));
        out.addAll(kept);
        body.put("messages", out);
        return true;
    }

    private static boolean injectAnthropicPrompt(Map<String, Object> body, String prompt) {
        Object system = body.get("system");
        if (system != null) {
            if (carriesOfficialPrompt(system)) {
                return false;
            }
            if (system instanceof String text) {
                body.put("system", text.strip().isEmpty() ? prompt : prompt + "\n\n" + text);
                return true;
            }
            if (system instanceof List<?> list) {
                List<Object> out = new ArrayList<>();
                out.add(Map.of("type", "text", "text", prompt));
                out.addAll(list);
                body.put("system", out);
                return true;
            }
        }
        body.put("system", prompt);
        return true;
    }

    private static boolean injectResponsesPrompt(Map<String, Object> body, String prompt) {
        Object instructions = body.get("instructions");
        if (instructions != null) {
            if (carriesOfficialPrompt(instructions)) {
                return false;
            }
            if (instructions instanceof String text) {
                body.put("instructions", text.strip().isEmpty() ? prompt : prompt + "\n\n" + text);
                return true;
            }
        }
        body.put("instructions", prompt);
        return true;
    }
}
