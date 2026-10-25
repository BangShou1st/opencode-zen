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
     * The local Bun relay the requests leave through (for example
     * {@code http://127.0.0.1:18992}, started from
     * {@code bun bun-relay.mjs}). Blank (the default) addresses the endpoint
     * directly from this JVM — which the free tier currently refuses, because
     * it admits only Bun-shaped traffic.
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
