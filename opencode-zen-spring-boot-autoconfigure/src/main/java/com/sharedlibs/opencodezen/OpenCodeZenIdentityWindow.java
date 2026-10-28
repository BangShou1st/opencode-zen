package com.sharedlibs.opencodezen;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One (session, project) pair per conversation, held for a window instead of
 * minted per request.
 *
 * <p>The tier ledgers its free quota per project id and egress together, and it
 * files what it thinks of a conversation against the session, so minting both
 * per request is the most ledgers per unit of traffic — and the least like a
 * client. That last part is what it costs: identity hops in rapid bursts are
 * blanket-refused where the same requests spaced about half a second apart pass,
 * which is a gate reading the hops rather than the bytes. A real client does not
 * do that — it opens a conversation, uses it for a while, and opens another. So
 * the pair is held for a window instead, 60 seconds by default.
 *
 * <p>{@code OPENCODE_ZEN_FREE_IDENTITY_TTL_MS} sets the window, and {@code 0}
 * restores the per-request minting this library shipped with. The knob is read
 * per request, like every knob on this path.
 *
 * <p>In memory only: a restart is a new identity for the same reason a new
 * window is, and a library whose job is to look like the client beside it does
 * not write its bookkeeping to disk. One entry per conversation — the slot a
 * request is filed under is the caller's conversation key — and requests that
 * name nothing share one unnamed slot, which is exactly the single pair this
 * library used before the window existed.
 */
public final class OpenCodeZenIdentityWindow {

    /** Environment variable setting how long one pair is kept, in milliseconds. */
    public static final String IDENTITY_TTL_ENV = "OPENCODE_ZEN_FREE_IDENTITY_TTL_MS";

    public static final long IDENTITY_TTL_DEFAULT_MS = 60_000;
    /** A day is longer than any window this tier is known to use, and short
     * enough that a typo cannot pin one identity for a year. */
    public static final long IDENTITY_TTL_MAX_MS = 24 * 60 * 60 * 1000L;

    private static final int MAX_ENTRIES = 8192;
    private static final String UNNAMED = "";

    /** One conversation's identity, minted in one instant: the session and the
     * project id are what the tier ledgers against, so they are retired together
     * and re-made together. */
    public static final class Pair {
        private final String session;
        private final String project;
        private final long at;

        Pair(String session, String project, long at) {
            this.session = session;
            this.project = project;
            this.at = at;
        }

        public String session() {
            return session;
        }

        public String project() {
            return project;
        }

        long at() {
            return at;
        }
    }

    private final LinkedHashMap<String, Pair> ledgers = new LinkedHashMap<>();

    /** How long one pair is kept before it is replaced, read from the
     * environment. */
    public static long identityTtl() {
        return identityTtl(System.getenv(IDENTITY_TTL_ENV));
    }

    /** The same rule for a raw value the caller already holds. */
    public static long identityTtl(String raw) {
        return setting(raw, IDENTITY_TTL_DEFAULT_MS);
    }

    /** Both halves of a replacement ledger, minted in one instant. A retry is
     * one request's worth of quota replaced wholesale, and a pair minted from
     * two separate reads of the clock is not one action: two ids that disagree
     * about when their request happened are exactly the shape a gate that reads
     * a session and a project as "one conversation, made now" would look at
     * twice. */
    public static Pair mintIdentity(long now) {
        return new Pair(OpenCodeZenIdGenerator.newSessionId(),
                OpenCodeZenProtocolPolicy.randomProjectId(), now);
    }

    /** The pair this request leaves with: the one its conversation is using
     * while it is younger than the window, a fresh one once it is not. A window
     * of zero makes every call a new pair, which is the per-request minting this
     * library shipped with. A clock that went backwards is treated as an expired
     * window rather than as a pair with time left: an id stamped in the future
     * is one the tier reads as a lie. A {@code null} key names no conversation;
     * those requests share the unnamed slot. */
    public synchronized Pair current(String key, long now) {
        return current(key, now, identityTtl());
    }

    /** The same read with the window the caller already resolved: a test
     * drives a zero window or an expired one through this overload instead of
     * the environment. */
    public synchronized Pair current(String key, long now, long ttlMillis) {
        Pair held = ledgers.get(slot(key));
        if (held != null && now >= held.at() && now - held.at() < ttlMillis) {
            return held;
        }
        return mintAndReplace(key, now);
    }

    /** Replace one conversation's pair now, whatever the window says. A 429 is
     * the tier saying this pair's bucket is spent, so the retry that answers it
     * asks under a new one — and the pair that just answered is the one the next
     * request of that conversation uses, because a ledger proven to answer is
     * worth more than the window it replaces. Only that conversation's slot
     * moves: another conversation's pair is not this refusal's business. */
    public synchronized Pair rotate(String key, long now) {
        return mintAndReplace(key, now);
    }

    private Pair mintAndReplace(String key, long now) {
        String slot = slot(key);
        Pair fresh = mintIdentity(now);
        ledgers.remove(slot);
        ledgers.put(slot, fresh);
        while (ledgers.size() > MAX_ENTRIES) {
            Iterator<Map.Entry<String, Pair>> oldest = ledgers.entrySet().iterator();
            if (!oldest.hasNext()) {
                break;
            }
            oldest.next();
            oldest.remove();
        }
        return fresh;
    }

    /** Forget every pair, as a process that has sent nothing would. A test that
     * drives two requests and expects two ledgers needs this; nothing else
     * does. */
    public synchronized void forget() {
        ledgers.clear();
    }

    private static String slot(String key) {
        return key == null ? UNNAMED : key;
    }

    /** A whole number inside the range, or the fallback. The same rule the
     * retry knobs are read under. */
    private static long setting(String raw, long fallback) {
        Objects.requireNonNull(fallback);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value >= 0 && value <= IDENTITY_TTL_MAX_MS ? value : fallback;
        } catch (NumberFormatException broken) {
            return fallback;
        }
    }
}
