// Bun relay — the local transport the proxy sends its upstream requests
// through.
//
// Zen's free tier accepts only Bun-shaped traffic: the same shaped request
// that Bun's fetch answers with 200 is answered 429 FreeUsageLimitError from
// every other client stack measured on this machine (python urllib, Windows
// curl, httpx over HTTP/1.1 and HTTP/2, curl_cffi impersonating Chrome,
// Safari and Firefox, node 24 fetch). magpie — a Bun-compiled executable
// running the upstream OpenCode-Zen-Freex package — answers 200 with the
// public credential where all of those are refused. The fingerprint the tier
// reads is the client's TLS/transport stack, so the request has to LEAVE
// from Bun, whatever shapes it on this side.
//
// This file is a dumb transparent relay: it takes the target URL from the
// `x-relay-target` header, forwards the request with every other header and
// the body untouched, and streams the upstream's answer back. It knows
// nothing about the Zen protocol; the proxy owns the shaping, the relay owns
// the wire. Start it with Bun (bun bun-relay.mjs); it listens on
// 127.0.0.1:RELAY_PORT (default 18992) and answers nothing else.
//
// The proxy falls back to its direct egress when the relay is down, so the
// relay is an optimisation over a refusal, not a dependency of startup.

const PORT = Number(process.env.RELAY_PORT || 18992);
const STALE_HEADERS = new Set([
  "content-length", "content-encoding", "transfer-encoding",
  "connection", "keep-alive", "te", "trailer", "upgrade",
  "x-relay-target",
  // Framing the CALLER's stack chose. `host` belongs to the relay's own
  // connection, and an `accept-encoding` that says "identity" (urllib's
  // default) is a non-Bun fingerprint Cloudflare refuses outright: the
  // relay's fetch states its own, which is the one the tier accepts.
  "host", "accept-encoding",
]);

Bun.serve({
  hostname: "127.0.0.1",
  port: PORT,
  idleTimeout: 255,
  async fetch(request) {
    const target = request.headers.get("x-relay-target");
    if (!target) {
      return new Response(JSON.stringify({ error: "x-relay-target header required" }),
        { status: 400, headers: { "content-type": "application/json" } });
    }
    const headers = new Headers(request.headers);
    for (const name of STALE_HEADERS) headers.delete(name);
    let upstream;
    try {
      upstream = await fetch(target, {
        method: request.method,
        headers,
        body: request.body,
        redirect: "manual",
        // Bun aborts the whole relay request when the proxy's caller goes
        // away; the deadline is the proxy's business, and it is generous
        // enough for the longest streaming reply.
        signal: request.signal,
      });
    } catch (error) {
      return new Response(JSON.stringify({ error: String(error) }),
        { status: 502, headers: { "content-type": "application/json" } });
    }
    const responseHeaders = new Headers(upstream.headers);
    for (const name of STALE_HEADERS) {
      if (name !== "x-relay-target") responseHeaders.delete(name);
    }
    return new Response(upstream.body, {
      status: upstream.status,
      headers: responseHeaders,
    });
  },
});

console.log(`[bun-relay] listening on http://127.0.0.1:${PORT}`);
