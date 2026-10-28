package com.sharedlibs.opencodezen;

import java.net.ConnectException;
import java.net.HttpRetryException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * The retry, ported from upstream OpenCode-Zen-FreeX v0.5.10/v0.5.11: a 429
 * asks again under a new ledger before the turn is lost, and a request that
 * never got an answer is asked again rather than reported as quota.
 *
 * <p><b>Why a 429 is worth asking again.</b> The tier's free quota is ledged
 * per project id and egress together, so a spent bucket is not a spent exit:
 * measured 2026-10-26, the same exit answered 429 to one project id and 200
 * to a fresh random id, twice. A retry is therefore a new ledger for the same
 * request, not the same request repeated — which is why this is worth doing
 * rather than handing the 429 on. Two things it is not: a 429 whose body
 * names abuse of the egress is a block, and no id fixes an IP (that is
 * {@link OpenCodeZenUpstreamDiagnostics.Outcome#ABUSE_REJECTED ABUSE_REJECTED}'s
 * case); and nothing here is a promise — the 429 the caller finally receives
 * is the route's own answer, unchanged, so a genuinely exhausted account
 * still reads as one.
 *
 * <p><b>Why a request that never got an answer is worth asking again.</b> A
 * connection that never came up — the egress is being switched, the relay is
 * momentarily down, DNS is not up yet — says nothing about the request at
 * all, and the same bytes are answered once the path settles. A host reports
 * such a failure as a quota error (measured 2026-10-27: {@code dial tcp
 * 127.0.0.1:1: connection refused} arrived at a consumer as {@code 429 …
 * QUOTA}), and the turn ends for a reason that had nothing to do with the
 * request.
 *
 * <p><b>How a caller uses this.</b> The library never sends a request, so the
 * loop is the consumer's. On a retryable failure, build the request again
 * through {@code OpenCodeZenRequestFactory#newConversationRetryRequest}: the
 * pair the tier ledgers against — the session and the project id — is replaced
 * wholesale, minted in one instant, and becomes the pair the conversation's
 * window holds afterwards; a project the host named itself rides in through
 * the incoming headers and is re-applied the same way. Wait {@link
 * #backoffMillis(int) backoffMillis} between attempts, and hand the caller the
 * failure of the last attempt unchanged when the retries are spent. Everything
 * else — a 403 about the fingerprint or the IP, a 426 about the version, a 5xx
 * from the endpoint failing — is not answered differently by a fresh project
 * id and must not be retried.
 *
 * <p>{@code OPENCODE_ZEN_FREE_RETRY_429=0} turns the retry off, and the wait
 * is bounded in both directions so a misconfigured value cannot park a caller
 * for an hour. The defaults are deliberately short — most hosts have nothing
 * behind a 429 that a longer wait would reach — and a deployment that DOES
 * have something behind one sets the ceiling itself. All knobs are read per
 * call, so an operator's change takes effect on the next request — a quota
 * problem is exactly when someone reaches for the variable.
 */
public final class OpenCodeZenRetryPolicy {

    /** Environment variable counting how many times a retryable failure is retried. */
    public static final String RETRY_429_ENV = "OPENCODE_ZEN_FREE_RETRY_429";
    /** Environment variable setting the wait before the first retry, in milliseconds. */
    public static final String RETRY_WAIT_ENV = "OPENCODE_ZEN_FREE_RETRY_WAIT_MS";
    /** Environment variable setting the ceiling the doubling stops at, in milliseconds. */
    public static final String RETRY_WAIT_MAX_ENV = "OPENCODE_ZEN_FREE_RETRY_WAIT_MAX_MS";
    /** Environment variable letting a 429's own retry-after window set one wait. */
    public static final String RETRY_AFTER_ENV = "OPENCODE_ZEN_FREE_RETRY_AFTER";
    /** Environment variable setting the smallest gap between a lane's consecutive requests. */
    public static final String PACING_ENV = "OPENCODE_ZEN_FREE_PACING_MS";

    public static final int RETRY_429_DEFAULT = 2;
    public static final long RETRY_WAIT_DEFAULT_MS = 1200;
    public static final long RETRY_WAIT_MIN_MS = 100;
    public static final long RETRY_WAIT_MAX_DEFAULT_MS = 5000;
    /** The ceiling a deployment may set. High enough to cover the long waits a
     * deployment may genuinely need (tens of seconds), but not so high that a
     * typo parks a caller for an hour. */
    public static final long RETRY_WAIT_MAX_LIMIT_MS = 120_000;
    public static final int RETRY_429_MAX = 5;
    /** Off by default: a delay imposed on every request needs a reason measured
     * in refusals, not in plausibility. */
    public static final long PACING_MAX_MS = 30_000;

    private static final int MAX_CAUSE_HOPS = 5;

    private OpenCodeZenRetryPolicy() {
    }

    /**
     * How many times a retryable failure is retried, read from the
     * environment. {@code 0} is the documented off switch and is not a
     * fallback: a value that parsed to zero means "do not retry", which is a
     * decision, not a typo.
     */
    public static int retryCount() {
        return retryCount(System.getenv(RETRY_429_ENV));
    }

    /** The same rule for a raw value the caller already holds. */
    public static int retryCount(String raw) {
        if (raw != null && raw.trim().equals("0")) {
            return 0;
        }
        return (int) setting(raw, 1, RETRY_429_MAX, RETRY_429_DEFAULT);
    }

    /** How long to wait before the first retry, in milliseconds. */
    public static long waitMillis() {
        return waitMillis(System.getenv(RETRY_WAIT_ENV));
    }

    /** The same rule for a raw value the caller already holds. */
    public static long waitMillis(String raw) {
        return setting(raw, (int) RETRY_WAIT_MIN_MS, (int) RETRY_WAIT_MAX_LIMIT_MS, RETRY_WAIT_DEFAULT_MS);
    }

    /** The ceiling the doubling stops at. A deployment whose 429 clears on its
     * own clock sets this above that clock, so a later attempt lands after it
     * does; everything else keeps the short default. It is floored at the first
     * wait, so a ceiling below the starting delay cannot make the second wait
     * shorter than the first. */
    public static long waitCeilingMillis() {
        return waitCeilingMillis(System.getenv(RETRY_WAIT_MAX_ENV));
    }

    /** The same rule for a raw value the caller already holds. */
    public static long waitCeilingMillis(String raw) {
        long floor = waitMillis();
        long ceiling = setting(raw, (int) RETRY_WAIT_MIN_MS, (int) RETRY_WAIT_MAX_LIMIT_MS,
                RETRY_WAIT_MAX_DEFAULT_MS);
        return Math.max(ceiling, floor);
    }

    /** Whether a 429's own window may set the wait. The doubling schedule is
     * this library's opinion of how long to hold a turn, and its defaults are
     * deliberately short; {@code OPENCODE_ZEN_FREE_RETRY_AFTER=1} hands that
     * decision to the route whenever it makes one: a refusal carrying
     * {@code retry-after} is waited for the window it names, bounded by {@link
     * #RETRY_WAIT_MAX_LIMIT_MS}, and a refusal without one keeps the schedule.
     * Read per call, like every knob on this path. */
    public static boolean retryAfterHonored() {
        return retryAfterHonored(System.getenv(RETRY_AFTER_ENV));
    }

    /** The same rule for a raw value the caller already holds. */
    public static boolean retryAfterHonored(String raw) {
        return "1".equals(raw) || "true".equals(raw);
    }

    /**
     * A route's window, as a number of milliseconds. Two spellings are the ones
     * HTTP names: delta-seconds (an integer; {@code retry-after: 30}) and an
     * HTTP date. Anything else — a float, a word, an empty header — is no window
     * at all and answers {@code null}, which the caller reads as "no route
     * opinion; the schedule stands". The date spelling is recognised by the day
     * name that starts every RFC 1123 date, because a parser this lenient would
     * otherwise read "1.5" as January 5th and answer a window for it. A date in
     * the past is a window already over: the answer is zero, not {@code null},
     * because the route has said something — it said "go" — and one answer
     * {@code null} would start the schedule instead. The number is returned
     * uncapped: the cap is the caller's, and the caller's bound is shared with
     * the deployment ceilings above.
     *
     * @param value the raw {@code Retry-After} header value, or null
     * @param nowMillis the current epoch millisecond
     */
    public static Long retryAfterHeaderMs(String value, long nowMillis) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.matches("\\d+")) {
            return Long.parseLong(trimmed) * 1000L;
        }
        if (!trimmed.matches("[A-Za-z]{3},.*")) {
            return null;
        }
        try {
            java.time.ZonedDateTime at = java.time.ZonedDateTime.parse(
                    trimmed, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
            return Math.max(0L, at.toInstant().toEpochMilli() - nowMillis);
        } catch (java.time.DateTimeException broken) {
            return null;
        }
    }

    /** The smallest gap between two requests on one lane, when one is wanted.
     * Identity hops in rapid bursts are blanket-refused where the same requests
     * spaced about half a second apart pass, which makes the hop the retry does
     * a pattern the gate already has an opinion about. The wait between retries
     * is already that spacer and need not be touched; the gap this knob owns is
     * between the host's own consecutive requests, whose bursts are the host's
     * shape and not this library's. The default is 0 — off — and the gap is
     * read per request, so a change takes effect on the next one. */
    public static long pacingMillis() {
        return pacingMillis(System.getenv(PACING_ENV));
    }

    /** The same rule for a raw value the caller already holds. */
    public static long pacingMillis(String raw) {
        return setting(raw, 0, (int) PACING_MAX_MS, 0);
    }

    /**
     * The wait before attempt {@code attempt} (1 = the first retry). One
     * wait, doubled per attempt — so a route that is counting rather than
     * rate limiting is not hammered by the retry that exists to avoid a dead
     * turn — capped at {@link #waitCeilingMillis()}.
     */
    public static long backoffMillis(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt starts at 1");
        }
        long wait = waitMillis();
        for (int doubled = 1; doubled < attempt; doubled++) {
            wait = Math.min(wait * 2, waitCeilingMillis());
        }
        return Math.min(wait, waitCeilingMillis());
    }

    /**
     * Whether a request that never got an answer is worth asking again. The
     * test is on the error, not on a list of messages: a transport failure is
     * the runtime's own judgement (the JDK's own exception types for the
     * socket underneath), and guessing from prose is how a permanent failure
     * gets retried forever. Anything this cannot confidently call transient
     * is not retried.
     *
     * <p>The caller's own cancellation is not one of these: an {@link
     * InterruptedException} reaches the send loop as the thread's interrupted
     * status and must travel to the caller untouched.
     */
    public static boolean looksLikeTransportFailure(Throwable error) {
        Throwable cursor = error;
        for (int hops = 0; cursor != null && hops < MAX_CAUSE_HOPS; hops += 1) {
            if (cursor instanceof ConnectException
                    || cursor instanceof SocketTimeoutException
                    || cursor instanceof HttpConnectTimeoutException
                    || cursor instanceof HttpTimeoutException
                    || cursor instanceof UnknownHostException
                    || cursor instanceof SocketException
                    || cursor instanceof NoRouteToHostException
                    || cursor instanceof PortUnreachableException
                    || cursor instanceof HttpRetryException) {
                return true;
            }
            // The wording a proxy failure arrives in when the runtime has no
            // type for it: measured upstream, the message was "proxyconnect
            // tcp: … connection refused".
            if (cursor instanceof Exception
                    && cursor.getMessage() != null
                    && cursor.getMessage().toLowerCase(java.util.Locale.ROOT)
                            .matches(".*(proxyconnect|connect)\\b.*")) {
                return true;
            }
            cursor = cursor.getCause() == cursor ? null : cursor.getCause();
        }
        return false;
    }

    /** A whole number inside a range, or the fallback. */
    private static long setting(String raw, int min, int max, long fallback) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value >= min && value <= max ? value : fallback;
        } catch (NumberFormatException broken) {
            return fallback;
        }
    }
}
