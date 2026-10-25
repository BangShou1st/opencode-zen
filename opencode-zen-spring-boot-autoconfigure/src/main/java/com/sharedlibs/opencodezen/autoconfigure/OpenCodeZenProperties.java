package com.sharedlibs.opencodezen.autoconfigure;

import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the shared OpenCode Zen request support. Credentials stay application-owned. */
@ConfigurationProperties(prefix = "opencode.zen")
public class OpenCodeZenProperties {

    /** Defaults to the verified OpenCode CLI-compatible User-Agent. */
    private String userAgent = OpenCodeZenProtocolPolicy.USER_AGENT;

    /**
     * Session affinity: when true, one conversation key keeps ONE sticky upstream
     * session; when false (the default), every request leaves as its own session —
     * the tier keeps what it thinks of traffic against the session, and a session
     * that has been refused once is a session the next request answers for.
     */
    private boolean sessionAffinity = false;

    /**
     * How the requests leave for the upstream. Blank or {@code auto} (the
     * default): an already-running local Bun relay wins, then the bundled
     * relay script is started with the local Bun, then — with no Bun — the
     * requests leave directly from this JVM, which the free tier currently
     * refuses (it admits only Bun-shaped traffic). {@code none}/{@code
     * direct}/{@code off} always go direct; anything else is an explicit
     * relay URL (for example {@code http://127.0.0.1:18992}).
     */
    private String relayUrl = "";

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public boolean isSessionAffinity() {
        return sessionAffinity;
    }

    public void setSessionAffinity(boolean sessionAffinity) {
        this.sessionAffinity = sessionAffinity;
    }

    public String getRelayUrl() {
        return relayUrl;
    }

    public void setRelayUrl(String relayUrl) {
        this.relayUrl = relayUrl;
    }
}
