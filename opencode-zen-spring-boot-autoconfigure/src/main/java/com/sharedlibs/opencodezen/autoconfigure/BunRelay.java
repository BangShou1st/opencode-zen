package com.sharedlibs.opencodezen.autoconfigure;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Where the upstream requests leave from, and who owns the Bun relay.
 *
 * <p>Zen's free tier reads the client's transport fingerprint and refuses
 * everything that is not Bun's with {@code 429 FreeUsageLimitError} (measured
 * 2026-10-26: the same shaped request that Bun's fetch answers with 200 is
 * refused from python, curl, httpx, node and Java HttpClient alike). So a
 * conforming request has to LEAVE from Bun's network stack, and this resolver
 * is what makes that happen without configuration:
 *
 * <ul>
 *   <li>blank / {@code auto} (the default) — an already-running relay on the
 *       default port wins; otherwise the bundled {@code bun-relay.mjs} is
 *       extracted from this jar and started with the local Bun, and its
 *       process is stopped when the JVM exits; otherwise (no Bun) the
 *       requests leave directly, which the tier currently refuses.</li>
 *   <li>{@code none} / {@code direct} / {@code off} — always direct.</li>
 *   <li>anything else — an explicit relay URL, used as it stands.</li>
 * </ul>
 */
final class BunRelay {

    private static final Logger LOG = Logger.getLogger(BunRelay.class.getName());

    /** The default relay address; the bundled script listens here. */
    static final String DEFAULT_RELAY_URL = "http://127.0.0.1:18992";
    private static final long STARTUP_WAIT_MS = 10_000;
    private static final int PROBE_TIMEOUT_MS = 1_000;

    private static Process startedRelay;

    private BunRelay() { }

    /** The relay URL the factory should address, or null for the direct egress. */
    static String resolve(String configured) {
        String value = configured == null || configured.isBlank()
                ? "auto" : configured.strip().toLowerCase(Locale.ROOT);
        if (value.equals("none") || value.equals("direct") || value.equals("off")) {
            return null;
        }
        if (!value.equals("auto")) {
            return configured.strip().replaceAll("/+$", "");
        }
        if (isReachable(DEFAULT_RELAY_URL)) {
            LOG.info("Using the Bun relay already listening at " + DEFAULT_RELAY_URL);
            return DEFAULT_RELAY_URL;
        }
        synchronized (BunRelay.class) {
            if (startedRelay != null && startedRelay.isAlive() && isReachable(DEFAULT_RELAY_URL)) {
                return DEFAULT_RELAY_URL;
            }
            String bun = findBun();
            if (bun == null) {
                LOG.warning("No Bun relay is running and Bun was not found; requests leave "
                        + "from this JVM directly and the free tier currently refuses them with "
                        + "429 FreeUsageLimitError. Install Bun (powershell -c \"irm bun.sh/install.ps1 | iex\") "
                        + "or start the relay (bun bun-relay.mjs), or set opencode.zen.relay-url explicitly.");
                return null;
            }
            return startRelay(bun);
        }
    }

    /** The bundled relay script, started detached and stopped at JVM exit. */
    private static String startRelay(String bun) {
        try {
            Path script = extractRelayScript();
            Path log = Files.createTempFile("opencode-zen-bun-relay-", ".log");
            ProcessBuilder builder = new ProcessBuilder(bun, script.toString());
            builder.redirectOutput(log.toFile()).redirectErrorStream(true);
            startedRelay = builder.start();
            Runtime.getRuntime().addShutdownHook(new Thread(startedRelay::destroy));
            long deadline = System.currentTimeMillis() + STARTUP_WAIT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (isReachable(DEFAULT_RELAY_URL)) {
                    LOG.info("Started the Bun relay at " + DEFAULT_RELAY_URL + " (bun " + bun
                            + ", log " + log + "); upstream requests leave from Bun");
                    return DEFAULT_RELAY_URL;
                }
                if (!startedRelay.isAlive()) {
                    LOG.warning("The Bun relay process exited immediately (log " + log + ")");
                    return null;
                }
                Thread.sleep(200);
            }
            LOG.warning("The Bun relay did not answer within " + STARTUP_WAIT_MS
                    + "ms (log " + log + "); requests leave from this JVM directly");
            return null;
        } catch (IOException | InterruptedException e) {
            LOG.warning("Could not start the Bun relay: " + e);
            return null;
        }
    }

    private static Path extractRelayScript() throws IOException {
        Path script = Files.createTempFile("opencode-zen-bun-relay-", ".mjs");
        try (InputStream in = BunRelay.class.getResourceAsStream("/bun-relay.mjs")) {
            if (in == null) {
                throw new IOException("Missing bun-relay.mjs resource");
            }
            Files.copy(in, script, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        script.toFile().deleteOnExit();
        return script;
    }

    /** Whether anything answers HTTP at the URL — a 400 from the relay's own
     * "no target" guard counts, because it says the tunnel is up. */
    private static boolean isReachable(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(PROBE_TIMEOUT_MS)).build();
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 100;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    static String findBun() {
        String named = System.getenv("ZEN_BUN");
        if (named != null && !named.isBlank() && Files.isRegularFile(Path.of(named))) {
            return named;
        }
        String exe = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
                ? "bun.exe" : "bun";
        Path home = Path.of(System.getProperty("user.home"), ".bun", "bin", exe);
        if (Files.isRegularFile(home)) {
            return home.toString();
        }
        for (String dir : System.getenv("PATH").split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir, exe);
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }
}
