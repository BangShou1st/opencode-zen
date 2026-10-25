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
}
