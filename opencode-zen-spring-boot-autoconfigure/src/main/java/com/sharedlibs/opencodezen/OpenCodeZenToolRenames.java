package com.sharedlibs.opencodezen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The renames of one shaped request, in both directions.
 *
 * <p>A shaped request renames every client tool under the {@code clint_}
 * prefix (see {@link OpenCodeZenV24ToolNormalizer}); the reply names the wire
 * spelling, so the restore is what hands the host back the names it declared —
 * the host declared {@code read}, not {@code clint_read}, and handing it the
 * latter is a tool it does not have.
 *
 * <p>A pair is recorded once; a duplicate client name reuses its first wire
 * name, so every reference to it points at the one tool that was sent.
 */
public final class OpenCodeZenToolRenames {

    private final Map<String, String> clientToWire = new LinkedHashMap<>();
    private final Map<String, String> wireToClient = new LinkedHashMap<>();

    /** Records one pair. A null, empty, self-mapping or colliding pair is ignored. */
    public void add(String client, String wire) {
        if (client == null || client.isEmpty() || wire == null || wire.isEmpty()
                || wire.equals(client)) {
            return;
        }
        if (clientToWire.containsKey(client) || wireToClient.containsKey(wire)) {
            return;
        }
        clientToWire.put(client, wire);
        wireToClient.put(wire, client);
    }

    /** How many client tools this request renamed. */
    public int size() {
        return clientToWire.size();
    }

    /** True when no client tool was renamed (no restore is needed). */
    public boolean isEmpty() {
        return clientToWire.isEmpty();
    }

    /** The wire name for a client name, or null when it was not renamed. */
    public String wire(String client) {
        return client == null ? null : clientToWire.get(client);
    }

    /**
     * The name the client declared for a wire name. An exact mapping is the
     * rule; a prefix-only name is recovered by stripping it, which is what a
     * stream that outlived a reconfiguration needs. A name without the prefix
     * is returned as it came.
     */
    public String restore(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        String client = wireToClient.get(name);
        if (client != null) {
            return client;
        }
        if (name.startsWith(OpenCodeZenClientProfile.CLIENT_TOOL_PREFIX)) {
            String rest = name.substring(OpenCodeZenClientProfile.CLIENT_TOOL_PREFIX.length());
            if (!rest.isEmpty()) {
                return rest;
            }
        }
        return name;
    }
}
