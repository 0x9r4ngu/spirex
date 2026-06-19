package spirex;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version checking and self-update:
 *
 *   - On every normal run spirex compares its baked-in {@link OutputWriter#VERSION}
 *     against the latest GitHub release and prints an "update available" notice to
 *     stderr (suppressed by -q/--quiet or --disable-update-check). The result is
 *     cached under $XDG_CONFIG_HOME/spirex/version-check for 6h so repeated runs
 *     stay instant and never hammer the API.
 *   - {@code spirex --update} downloads the latest release's spirex.jar asset and
 *     atomically replaces the running jar (keeping a .bak), so the very next run is
 *     on the new version.
 *
 * Everything here fails soft: no network, no releases, or a parse error simply
 * means "no notice / nothing to do" — it never breaks a crawl.
 */
public final class Updater {

    private static final String REPO = "0x9r4ngu/spirex";
    private static final String LATEST_API =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final Duration NET_TIMEOUT = Duration.ofSeconds(4);
    private static final long CACHE_TTL_SECONDS = 6 * 3600;

    private static final String RESET = "[0m";
    private static final String BOLD = "[1m";
    private static final String RED = "[31m";
    private static final String GREEN = "[32m";
    private static final String YELLOW = "[33m";
    private static final String CYAN = "[36m";

    private Updater() {
    }

    // ---------------------------------------------------------------- notice

    /** Print an "update available" notice if a newer release exists. Non-fatal. */
    public static void notifyIfOutdated(Options opts) {
        if (opts.disableUpdateCheck || opts.silent) {
            return;
        }
        String latest = latestVersionCached();
        if (latest != null && isNewer(latest, OutputWriter.VERSION)) {
            boolean color = useColor(opts);
            String wrn = c(color, BOLD + YELLOW, "[WRN]");
            System.err.println(wrn + " A new spirex release is available: "
                    + c(color, GREEN, "v" + strip(latest))
                    + " (you have v" + OutputWriter.VERSION + ")");
            System.err.println(wrn + " Run " + c(color, CYAN, "spirex --update")
                    + " to upgrade.");
        }
    }

    /** Latest version from cache when fresh; otherwise refresh from the network. */
    private static String latestVersionCached() {
        long now = System.currentTimeMillis() / 1000L;
        Cached cached = readCache();
        if (cached != null && (now - cached.checkedAt) < CACHE_TTL_SECONDS) {
            return cached.latest;
        }
        Http resp = httpGet(LATEST_API);
        String fetched = resp.status() / 100 == 2 ? fetchLatestTag(resp.body()) : null;
        if (fetched != null) {
            writeCache(now, fetched);
            return fetched;
        }
        // no release / network failure — fall back to whatever (stale) value we last knew
        return cached != null ? cached.latest : null;
    }

    // ---------------------------------------------------------------- update

    /** Implements {@code spirex --update}: download and replace the running jar. */
    public static void runSelfUpdate(Options opts) {
        boolean color = useColor(opts);
        System.out.println(c(color, CYAN, "[*]") + " spirex: checking for updates ...");

        Http resp = httpGet(LATEST_API);
        if (resp.status() == 0) {
            fail(color, "could not reach the update server (api.github.com) — check connectivity");
            return;
        }
        if (resp.status() == 404) {
            fail(color, "no published releases found for " + REPO + " yet");
            return;
        }
        if (resp.status() / 100 != 2) {
            fail(color, "update server returned HTTP " + resp.status());
            return;
        }
        String body = resp.body();
        String latest = fetchLatestTag(body);
        if (latest == null) {
            fail(color, "could not parse the latest release for " + REPO);
            return;
        }
        if (!isNewer(latest, OutputWriter.VERSION)) {
            System.out.println(c(color, GREEN, "[ok]")
                    + " already on the latest version (v" + OutputWriter.VERSION + ")");
            return;
        }

        Path self = currentJarPath();
        if (self == null || !self.toString().endsWith(".jar")) {
            System.out.println(c(color, YELLOW, "[!]")
                    + " a new version is available: v" + strip(latest));
            System.out.println("    you are running from a source build, not an installed jar.");
            System.out.println("    update with:  git pull && ./build.sh && ./install.sh");
            return;
        }

        String asset = match(body, "\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.jar)\"");
        if (asset == null) {
            fail(color, "release v" + strip(latest) + " has no downloadable .jar asset");
            return;
        }

        try {
            System.out.println(c(color, CYAN, "[*]") + " downloading v" + strip(latest) + " ...");
            Path tmp = Files.createTempFile(self.toAbsolutePath().getParent(), "spirex-", ".jar.new");
            download(asset, tmp);
            if (Files.size(tmp) < 1024) {
                Files.deleteIfExists(tmp);
                throw new IOException("downloaded file looks truncated");
            }
            Path backup = self.resolveSibling("spirex.jar.bak");
            try {
                Files.copy(self, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignore) {
                // a missing backup is not fatal
            }
            Files.move(tmp, self, StandardCopyOption.REPLACE_EXISTING);
            System.out.println(c(color, GREEN, "[ok]") + " updated spirex v"
                    + OutputWriter.VERSION + " -> v" + strip(latest));
            System.out.println("    previous jar saved as " + backup.getFileName());
        } catch (Exception e) {
            fail(color, "update failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- http

    /** HTTP GET result: status 0 means the request never completed (no network). */
    private record Http(int status, String body) {
    }

    private static Http httpGet(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(NET_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(NET_TIMEOUT)
                    .header("User-Agent", "spirex/" + OutputWriter.VERSION)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return new Http(resp.statusCode(), resp.body());
        } catch (Exception e) {
            return new Http(0, null);
        }
    }

    private static void download(String url, Path dest) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(180))
                .header("User-Agent", "spirex/" + OutputWriter.VERSION)
                .GET()
                .build();
        HttpResponse<Path> resp = client.send(req,
                HttpResponse.BodyHandlers.ofFile(dest, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING));
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + " fetching release asset");
        }
    }

    // ---------------------------------------------------------------- parsing

    private static String fetchLatestTag(String body) {
        return body == null ? null : match(body, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    }

    private static String match(String body, String regex) {
        Matcher m = Pattern.compile(regex).matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** True when {@code latest} is a strictly higher semantic version than {@code current}. */
    static boolean isNewer(String latest, String current) {
        int[] a = parseVersion(latest);
        int[] b = parseVersion(current);
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int[] parseVersion(String v) {
        String s = strip(v);
        int dash = s.indexOf('-'); // drop pre-release / build suffix
        if (dash >= 0) {
            s = s.substring(0, dash);
        }
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static String strip(String v) {
        String s = v == null ? "" : v.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        return s;
    }

    // ---------------------------------------------------------------- cache

    private record Cached(long checkedAt, String latest) {
    }

    private static Path cacheFile() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank())
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home", "."), ".config");
        return base.resolve("spirex").resolve("version-check");
    }

    private static Cached readCache() {
        try {
            Path f = cacheFile();
            if (!Files.exists(f)) {
                return null;
            }
            List<String> lines = Files.readAllLines(f);
            if (lines.isEmpty()) {
                return null;
            }
            String[] p = lines.get(0).trim().split("\\s+");
            if (p.length < 2) {
                return null;
            }
            return new Cached(Long.parseLong(p[0]), p[1]);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(long ts, String latest) {
        try {
            Path f = cacheFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, ts + " " + latest + "\n");
        } catch (Exception ignore) {
            // a non-writable cache only costs us a network call next time
        }
    }

    // ---------------------------------------------------------------- misc

    private static Path currentJarPath() {
        try {
            return Path.of(Updater.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean useColor(Options opts) {
        return !opts.noColor && System.console() != null;
    }

    private static String c(boolean color, String code, String text) {
        return color ? code + text + RESET : text;
    }

    private static void fail(boolean color, String msg) {
        System.err.println(c(color, RED, "[!]") + " " + msg);
        System.exit(1);
    }
}
