package com.sharedlibs.opencodezen.autoconfigure;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Explicit Java transport selection. Building an HttpRequest alone does not
 * configure the proxy used by the sender. No retries on 403/429 are performed.
 */
public final class OpenCodeZenHttpClients {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);

    private OpenCodeZenHttpClients() { }

    /** Opt into a direct, proxy-free connection. */
    public static HttpClient direct() {
        ProxySelector none = new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                return List.of(Proxy.NO_PROXY);
            }
            @Override public void connectFailed(URI uri, java.net.SocketAddress sa,
                                                java.io.IOException error) { }
        };
        return HttpClient.newBuilder().proxy(none)
                .connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** Use the JVM's configured proxy selector; unlike Python urllib,
     * this does not promise that HTTPS_PROXY/HTTP_PROXY are automatically read.
     */
    public static HttpClient system() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT);
        ProxySelector selector = ProxySelector.getDefault();
        if (selector != null) {
            builder.proxy(selector);
        }
        return builder.build();
    }

    /** Explicit HTTP proxy for comparison with an authorized Python proxy route. */
    public static HttpClient explicitHttpProxy(String host, int port) {
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Valid proxy host and port required");
        }
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(ProxySelector.of(new InetSocketAddress(host, port))).build();
    }
}
