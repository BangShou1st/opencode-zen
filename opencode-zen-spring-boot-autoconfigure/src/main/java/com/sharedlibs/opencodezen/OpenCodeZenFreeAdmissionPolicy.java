package com.sharedlibs.opencodezen;

import java.util.Locale;
import java.util.Set;

/**
 * The single shared source of truth for the reviewed V1 OpenCode Zen free-model admission policy.
 *
 * <p>Backend and Gateway are separate deployables and each must execute the check before it lets a
 * built-in {@code OPENCODE_ZEN} model reach Provider HTTP I/O (probe/promotion on the Backend,
 * dispatch barrier on the Gateway). Keeping two independent barriers only works if they agree:
 * duplicating the allow-list per deployable risks two divergent "free" rules. This class centralises
 * the reviewed set and predicate so both barriers apply exactly the same rule.
 *
 * <p>The set is closed under "officially served by V1 Zen at the reviewed revision <em>and</em>
 * listed {@code Free} on the official V1 Zen pricing table". A {@code -free} name suffix is never
 * evidence, and any paid, unknown, unsupported, delisted or Console-only id fails closed.
 */
public final class OpenCodeZenFreeAdmissionPolicy {

    /** Reviewed revision of the free admission policy; must advance with the evidence. */
    public static final String POLICY_VERSION = "2026-10-16-v1";

    /**
     * OpenCode Zen Chat Completions models that carry a currently valid free attestation: an official
     * {@code /zen/v1/chat/completions} endpoint row, an official V1 Zen pricing row of {@code Free},
     * and continued service in the live V1 Zen catalog as of 2026-10-16.
     */
    public static final Set<String> ADMITTED_FREE_CHAT_MODELS = Set.of(
            "big-pickle",
            "longcat-2.5-preview-free",
            "mimo-v2.6-flash-free",
            "nemotron-3.5-lightning-free",
            "space-bunny-free");

    private OpenCodeZenFreeAdmissionPolicy() {
    }

    /**
     * True only for an exact, reviewed free Chat model id. Every other id — paid, unknown,
     * unsupported, delisted, Console-only or suffix-invented — fails closed.
     */
    public static boolean admitsFreeChatModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        return ADMITTED_FREE_CHAT_MODELS.contains(modelId.strip().toLowerCase(Locale.ROOT));
    }
}
