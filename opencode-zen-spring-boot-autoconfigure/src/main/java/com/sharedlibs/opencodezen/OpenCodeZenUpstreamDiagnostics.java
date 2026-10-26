package com.sharedlibs.opencodezen;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * Bounded, credential-free categorization of an upstream response.
 * Classifications describe the server response; they do not change access
 * permissions and never trigger proxy switching.
 */
public final class OpenCodeZenUpstreamDiagnostics {
    public enum Outcome {
        SUCCESS, ABUSE_REJECTED, FREE_TIER_REJECTED,
        FORBIDDEN, RATE_LIMITED, OTHER_HTTP_ERROR
    }

    private OpenCodeZenUpstreamDiagnostics() {}

    /**
     * Mirrors Python V2.4's _gateway_rejection, with the free tier's two
     * block wordings from OpenCode-Zen-FreeX v0.3.1. No response body is
     * retained, logged or exposed. This method never inspects user prompts or
     * API keys.
     *
     * <p>A 429 whose body names abuse of the egress is the IP block, not a
     * rate limit — no id fixes an IP, so it is ABUSE_REJECTED and never
     * retried (upstream v0.5.10). A count-based limit may answer with a
     * minimal body or none at all; the status is what carries that meaning.
     */
    public static Outcome classify(int statusCode, byte[] responseBody) {
        if (statusCode >= 200 && statusCode <= 299) {
            return Outcome.SUCCESS;
        }
        if (statusCode == 429) {
            return isIpAbuseWording(responseBody)
                    ? Outcome.ABUSE_REJECTED : Outcome.RATE_LIMITED;
        }
        if (statusCode == 403) {
            return isIpAbuseWording(responseBody)
                    ? Outcome.ABUSE_REJECTED
                    : isFreeTierWording(responseBody)
                        ? Outcome.FREE_TIER_REJECTED : Outcome.FORBIDDEN;
        }
        return Outcome.OTHER_HTTP_ERROR;
    }

    /** The free tier's wording for a network it has blocked. This is a fact
     * about the egress IP, not about the request: the wording names traffic it
     * considers abusive, or says its free models are not available from the
     * caller's current network. Both forms are read by the same rule, and
     * only the full pair is — "free models aren't available" alone is a
     * retirement notice, not a block. */
    private static boolean isIpAbuseWording(byte[] responseBody) {
        String message = messageOf(responseBody);
        return message.contains("abusive traffic")
                || message.contains("abuse traffic")
                || message.contains("abuse of traffic")
                || (message.contains("free models aren't available")
                    && message.contains("from your current network"));
    }

    private static boolean isFreeTierWording(byte[] responseBody) {
        String message = messageOf(responseBody);
        return message.contains("freetiererror") || message.contains("free tier");
    }

    private static String messageOf(byte[] responseBody) {
        byte[] bytes = responseBody == null ? new byte[0] : responseBody;
        int length = Math.min(bytes.length, 65536);
        return new String(bytes, 0, length, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a failure is worth asking again about, and they are two
     * different questions (upstream OpenCode-Zen-FreeX v0.5.10/v0.5.11). A
     * {@code RATE_LIMITED} 429 may be one spent bucket rather than a spent
     * exit — the tier ledgers its free quota per project id and egress
     * together, so a retry under a fresh ledger (fresh session and project
     * identifiers, which every {@code OpenCodeZenRequestFactory} call mints)
     * is a ledger nothing has drawn on. {@code ABUSE_REJECTED} is a block,
     * and no id fixes an IP. A 403 about the fingerprint, a 426 about the
     * version and a 5xx from the endpoint failing are answered no differently
     * by a fresh project id. A request that never got an answer is judged by
     * {@link OpenCodeZenRetryPolicy#looksLikeTransportFailure}, not here —
     * there is no Outcome for an exception that never produced a response.
     */
    public static boolean shouldRetryAutomatically(Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return outcome == Outcome.RATE_LIMITED;
    }
}
