package com.sharedlibs.opencodezen;

import java.util.Locale;
import java.util.Set;

/**
 * The single shared source of truth for the reviewed OpenCode Zen free-model admission policy.
 *
 * <p>Backend and Gateway are separate deployables and each must execute the check before it lets a
 * built-in {@code OPENCODE_ZEN} model reach Provider HTTP I/O (probe/promotion on the Backend,
 * dispatch barrier on the Gateway). Keeping two independent barriers only works if they agree:
 * duplicating the allow-list per deployable risks two divergent "free" rules. This class centralises
 * the reviewed set and predicate so both barriers apply exactly the same rule.
 *
 * <p>The set is closed under "served by V1 Zen at the reviewed revision <em>and</em> listed
 * {@code Free} on the official V1 Zen pricing table". Any paid, unknown, unsupported, delisted or
 * Console-only id fails closed.
 *
 * <p><b>Reviewed 2026-10-21 against the live V1 Zen listing</b>
 * ({@code GET https://opencode.ai/inference/v1/models}, 86 ids) and the public pricing table
 * ({@code https://models.dev/api.json}). The previous revision (2026-10-16) listed five ids and was
 * wrong in both directions: five models Zen was serving were missing from it, and one it listed was
 * no longer served at all. A closed list that drifts from the live listing hides models that work
 * and keeps models that do not — the same failure
 * {@link OpenCodeZenFreeModelPolicy#isFreeInLiveListing} avoids by letting the live listing decide.
 *
 * <p>{@code jev-1.13-free} is deliberately absent: it is a decision endpoint (SystemOne), not a
 * text-generation model, and a chat-completions request for it is refused upstream.
 */
public final class OpenCodeZenFreeAdmissionPolicy {

    /** Reviewed revision of the free admission policy; must advance with the evidence. */
    public static final String POLICY_VERSION = "2026-10-21-v2";

    /**
     * OpenCode Zen Chat Completions models that carry a currently valid free attestation: served by
     * the live V1 Zen listing, listed {@code Free} on the official V1 Zen pricing table, and a Chat
     * Completions route.
     */
    public static final Set<String> ADMITTED_FREE_CHAT_MODELS = Set.of(
            "beam-free",
            "big-pickle",
            "deepseek-v4.1-flash-free",
            "glm-5.3-flash-free",
            "longcat-2.5-preview-free",
            "mimo-v2.6-flash-free",
            "minimax-m3.1-flash-free",
            "nemotron-3.5-lightning-free",
            "nemotron-3.5-super-vl-free");

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
