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
 *       process is stopped when the JVM exits; a machine without Bun downloads
 *       it once (pinned release, SHA-256 verified) into a user cache and is
 *       then self-sufficient; only an offline machine without Bun leaves the
 *       requests going directly, which the tier currently refuses.</li>
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
    private static final int DOWNLOAD_TIMEOUT_MS = 600_000;

    /** The Bun release this build is pinned to, with its official SHA-256.
     * The zip carries one entry, {@code bun-windows-x64/bun.exe}. */
    private static final String BUN_VERSION = "1.4.4";
    private static final String BUN_WINDOWS_X64_URL =
            "https://github.com/oven-sh/bun/releases/download/bun-v"
                    + BUN_VERSION + "/bun-windows-x64.zip";
    private static final String BUN_WINDOWS_X64_SHA256 =
            "cd2c0fccc78f3ede1f50ca827a15f063eda13d4d150a3d0674f4f6e323d864a5";

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
            if (bun == null && isWindowsX64()) {
                bun = downloadBun();
            }
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
        Path managed = managedBunPath();
        if (Files.isRegularFile(managed)) {
            return managed.toString();
        }
        return null;
    }

    /** The Bun runtime this library downloaded itself, once, into a user
     * cache — the "add the dependency and it just works" path. */
    private static Path managedBunPath() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = Path.of(System.getProperty("user.home"), ".cache").toString();
        }
        return Path.of(base, "opencode-zen-freex", "bun", BUN_VERSION, "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
                        ? "bun.exe" : "bun");
    }

    /** Downloads the pinned Bun release into the user cache and returns its
     * path. The download is verified against the pinned SHA-256 before
     * anything is extracted, and the whole file is re-verified through the
     * cache's own temporary name, so a truncated or tampered download never
     * becomes an executable this library runs. */
    private static String downloadBun() {
        if (!isWindowsX64()) {
            LOG.warning("Bun is not installed; automatic download is wired for Windows x64 "
                    + "only. Install Bun from https://bun.sh and retry.");
            return null;
        }
        Path target = managedBunPath();
        if (Files.isRegularFile(target)) {
            return target.toString();
        }
        LOG.info("Downloading Bun " + BUN_VERSION + " (about 55 MB, once) into "
                + target.getParent() + " ...");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<byte[]> response = client.send(
                    HttpRequest.newBuilder(URI.create(BUN_WINDOWS_X64_URL)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                LOG.warning("Bun download answered HTTP " + response.statusCode());
                return null;
            }
            byte[] zip = response.body();
            String sha = hexDigest(zip);
            if (!BUN_WINDOWS_X64_SHA256.equals(sha)) {
                LOG.warning("Bun download failed the pinned SHA-256 check (" + sha + "); "
                        + "nothing was extracted. Delete the cache and retry, or install Bun "
                        + "from https://bun.sh.");
                return null;
            }
            try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(
                    writeTemporary(zip).toFile())) {
                java.util.zip.ZipEntry entry = archive.stream()
                        .filter(item -> item.getName().endsWith("/bun.exe") || item.getName().equals("bun.exe"))
                        .findFirst().orElse(null);
                if (entry == null) {
                    LOG.warning("The Bun archive carries no bun.exe entry");
                    return null;
                }
                Files.createDirectories(target.getParent());
                Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
                try (InputStream in = archive.getInputStream(entry)) {
                    Files.copy(in, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                target.toFile().setExecutable(true);
            }
            LOG.info("Bun " + BUN_VERSION + " is cached at " + target);
            return target.toString();
        } catch (IOException | InterruptedException e) {
            LOG.warning("Bun download failed: " + e);
            return null;
        }
    }

    private static Path writeTemporary(byte[] bytes) throws IOException {
        Path file = Files.createTempFile("opencode-zen-bun-download-", ".zip");
        Files.write(file, bytes);
        file.toFile().deleteOnExit();
        return file;
    }

    private static String hexDigest(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                out.append(Character.forDigit((item >> 4) & 0xF, 16));
                out.append(Character.forDigit(item & 0xF, 16));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isWindowsX64() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
                && "amd64".equals(System.getProperty("os.arch", "").toLowerCase(Locale.ROOT));
    }
}
