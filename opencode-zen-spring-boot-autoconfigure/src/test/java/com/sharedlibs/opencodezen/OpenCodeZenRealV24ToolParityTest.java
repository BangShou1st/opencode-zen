package com.sharedlibs.opencodezen;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reference: OpenCode-Zen-Proxy-V2.4.zip, Python _names_for_tools and its 63 tests.
 * These are offline wire-format tests; they are not an upstream access guarantee.
 */
class OpenCodeZenRealV24ToolParityTest {

    private static Map<String, Object> chat(Map<String, Object> additional) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "big-pickle");
        body.putAll(additional);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entry(Map<String, Object> body, int index) {
        return (Map<String, Object>) ((List<?>) body.get("tools")).get(index);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> chatFunction(Map<String, Object> body, int index) {
        return (Map<String, Object>) entry(body, index).get("function");
    }

    @Test
    void chatExistingShellKeepsSchemaButReceivesCapturedDescription() {
        Map<String, Object> schema = Map.of(
                "type", "object", "properties", Map.of("command", Map.of("type", "string")),
                "required", List.of("command"));
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "shell");
        function.put("description", "User wording");
        function.put("parameters", schema);
        Map<String, Object> tool = Map.of("type", "function", "function", function);
        Map<String, Object> body = chat(Map.of("tools", List.of(tool)));
        assertThat(OpenCodeZenProtocolPolicy.callerDeclaredToolNames(body, ZenModelProtocol.CHAT))
                .isEqualTo(Set.of("shell"));
        OpenCodeZenProtocolPolicy.applyRequiredEnvelope(body, ZenModelProtocol.CHAT, true);
        assertThat((List<?>) body.get("tools")).hasSize(2);
        assertThat(chatFunction(body, 0)).containsEntry("name", "shell")
                .containsEntry("parameters", schema)
                .containsEntry("description", OpenCodeZenClientProfile.capturedDescription("shell"));
        assertThat(chatFunction(body, 1).get("description").toString()).contains("placeholder");
        assertThat(function.get("description")).isEqualTo("User wording");
    }

    @Test
    void existingNativeReadPreservesSchemaWithCapturedDescription() {
        for (ZenModelProtocol protocol : List.of(ZenModelProtocol.RESPONSES, ZenModelProtocol.ANTHROPIC)) {
            Map<String, Object> schema = Map.of("type", "object",
                    "properties", Map.of("path", Map.of("type", "string")));
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", "read");
            tool.put("description", "custom");
            if (protocol == ZenModelProtocol.RESPONSES) {
                tool.put("type", "function");
                tool.put("parameters", schema);
            } else {
                tool.put("input_schema", schema);
            }
            Map<String, Object> body = chat(Map.of("tools", List.of(tool)));
            OpenCodeZenProtocolPolicy.applyRequiredEnvelope(body, protocol, false);
            assertThat((List<?>) body.get("tools")).hasSize(2);
            assertThat(entry(body, 0).get("description"))
                    .isEqualTo(OpenCodeZenClientProfile.capturedDescription("read"));
            assertThat(entry(body, 0).get(protocol == ZenModelProtocol.RESPONSES
                    ? "parameters" : "input_schema")).isEqualTo(schema);
            assertThat(entry(body, 1).get("name")).isEqualTo("shell");
            assertThat(tool.get("description")).isEqualTo("custom");
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void legacyChatFunctionIsNotDuplicatedAsSyntheticTool() {
        Map<String, Object> legacy = Map.of("name", "shell",
                "description", "old desc", "parameters", Map.of("type", "object"));
        Map<String, Object> body = chat(Map.of("functions", List.of(legacy)));
        assertThat(OpenCodeZenProtocolPolicy.callerDeclaredToolNames(body, ZenModelProtocol.CHAT))
                .isEqualTo(Set.of("shell"));
        OpenCodeZenProtocolPolicy.applyRequiredEnvelope(body, ZenModelProtocol.CHAT, true);
        assertThat((List<?>) body.get("tools")).hasSize(1);
        assertThat(chatFunction(body, 0).get("name")).isEqualTo("read");
        List<Map<String, Object>> normalized = (List<Map<String, Object>>) body.get("functions");
        assertThat(normalized.get(0)).containsEntry(
                "description", OpenCodeZenClientProfile.capturedDescription("shell"));
        assertThat(legacy.get("description")).isEqualTo("old desc");
    }

    @Test
    void unknownToolsGainDefaultDescriptionAndCallerOrderIsPreserved() {
        Map<String, Object> unknown = Map.of("type", "function",
                "function", Map.of("name", "Bash", "parameters", Map.of("type", "object")));
        Map<String, Object> body = chat(Map.of("tools", List.of(unknown)));
        OpenCodeZenProtocolPolicy.applyRequiredEnvelope(body, ZenModelProtocol.CHAT, true);
        assertThat((List<?>) body.get("tools")).hasSize(3);
        assertThat(chatFunction(body, 0)).containsEntry("name", "Bash")
                .containsEntry("description", "Tool function");
        assertThat(chatFunction(body, 1).get("name")).isEqualTo("shell");
        assertThat(chatFunction(body, 2).get("name")).isEqualTo("read");
    }

    @Test
    void duplicatesAreHandledLikePythonV24NotRejected() {
        Map<String, Object> one = Map.of("type", "function",
                "function", Map.of("name", "read"));
        Map<String, Object> body = chat(Map.of("tools", List.of(one, one)));
        OpenCodeZenProtocolPolicy.applyRequiredEnvelope(body, ZenModelProtocol.CHAT, true);
        assertThat((List<?>) body.get("tools")).hasSize(3);
        assertThat(chatFunction(body, 0).get("description"))
                .isEqualTo(OpenCodeZenClientProfile.capturedDescription("read"));
        assertThat(chatFunction(body, 1).get("description")).isEqualTo("Tool function");
    }

    @Test
    void invalidToolsAndLegacyFieldsRejectLocally() {
        Map<String, Object> bad = chat(Map.of("tools", "wrong"));
        assertThatThrownBy(() -> OpenCodeZenProtocolPolicy.applyRequiredEnvelope(
                bad, ZenModelProtocol.CHAT, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("array");
        Map<String, Object> badFunction = chat(Map.of("tools", List.of(
                Map.of("type", "function", "function", "wrong"))));
        assertThatThrownBy(() -> OpenCodeZenProtocolPolicy.applyRequiredEnvelope(
                badFunction, ZenModelProtocol.CHAT, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("function");
        Map<String, Object> badLegacy = chat(Map.of("functions", "wrong"));
        assertThatThrownBy(() -> OpenCodeZenProtocolPolicy.applyRequiredEnvelope(
                badLegacy, ZenModelProtocol.CHAT, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("functions");
    }

    @Test
    void callerDeclaredSetExcludesSyntheticPlaceholders() {
        Map<String, Object> input = chat(Map.of("messages", List.of(
                Map.of("role", "user", "content", "hello"))));
        Set<String> before = OpenCodeZenProtocolPolicy.callerDeclaredToolNames(
                input, ZenModelProtocol.CHAT);
        assertThat(before).isEmpty();
        OpenCodeZenProtocolPolicy.applyRequiredEnvelope(input, ZenModelProtocol.CHAT, true);
        assertThat((List<?>) input.get("tools")).hasSize(2);
        assertThat(before).isEmpty();
    }
}
