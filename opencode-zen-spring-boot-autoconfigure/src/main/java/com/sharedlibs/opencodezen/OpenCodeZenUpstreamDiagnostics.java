package com.sharedlibs.opencodezen;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * Bounded, credential-free categorization of an upstream response.
 * Classifications describe the server response; they do not change access
 * permissions and never trigger proxy switching or retry attempts.
 */
public final class OpenCodeZenUpstreamDiagnostics {
    public enum Outcome {
        SUCCESS, ABUSE_REJECTED, FREE_TIER_REJECTED,
        FORBIDDEN, RATE_LIMITED, OTHER_HTTP_ERROR
    }

    private OpenCodeZenUpstreamDiagnostics() {}

    /**
     * Mirrors Python V2.4's _gateway_rejection. No response body is retained,
     * logged or exposed. This method never inspects user prompts or API keys.
     */
    public static Outcome classify(int statusCode, byte[] responseBody) {
        if (statusCode >= 200 && statusCode <= 299) {
            return Outcome.SUCCESS;
        }
        if (statusCode == 429) {
            return Outcome.RATE_LIMITED;
        }
        if (statusCode == 403) {
            byte[] bytes = responseBody == null ? new byte[0] : responseBody;
            int length = Math.min(bytes.length, 65536);
            String message = new String(bytes, 0, length, StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            if (message.contains("detect abuse traffic")
                    || message.contains("abuse traffic")
                    || message.contains("abusive traffic")) {
                return Outcome.ABUSE_REJECTED;
            }
            if (message.contains("freetiererror") || message.contains("free tier")) {
                return Outcome.FREE_TIER_REJECTED;
            }
            return Outcome.FORBIDDEN;
        }
        return Outcome.OTHER_HTTP_ERROR;
    }

    /**
     * In particular, 403/429 are never automatically retried.
     * A caller may handle unrelated connectivity problems separately.
     */
    public static boolean shouldRetryAutomatically(Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return false;
    }
}
