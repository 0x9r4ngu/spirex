package spirex;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Crawl orchestrator. A pool of worker threads pulls from a shared
 * {@link Frontier} whose ordering implements the -strategy option
 * (depth-first = LIFO, breadth-first = FIFO). Termination is detected when the
 * frontier is empty and no worker is busy.
 */
public class Crawler {

    final Options opts;
    final HttpFetcher fetcher;
    final LinkExtractor extractor = new LinkExtractor();
    final OutputWriter output;
    final Scope scope;
    final RateLimiter rateLimiter;

    private final Set<String> visited = ConcurrentHashMap.newKeySet();
    private final Set<Integer> seenBodies = ConcurrentHashMap.newKeySet();
    private final Set<String> hosts = ConcurrentHashMap.newKeySet();
    // Distinct in-scope URLs retained for the optional post-crawl --ai analysis.
    private final Set<String> aiUrls = ConcurrentHashMap.newKeySet();
    final AtomicInteger crawledCount = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);
    // Status-code class tallies: index 1..5 = 1xx..5xx.
    private final java.util.concurrent.atomic.AtomicIntegerArray statusClass =
            new java.util.concurrent.atomic.AtomicIntegerArray(6);
    private final Frontier frontier;
    private final long deadlineNanos;
    private BufferedWriter errorLog;

    private static final Pattern SIMILAR_SEG =
            Pattern.compile("^(\\d+|[0-9a-fA-F]{8,}|[A-Za-z0-9_-]{20,})$");

    public Crawler(Options opts) {
        this.opts = opts;
        this.fetcher = new HttpFetcher(opts);
        this.output = new OutputWriter(opts);
        this.scope = new Scope(opts, firstSeedHost(opts));
        this.rateLimiter = new RateLimiter(opts.rateLimit, opts.rateLimitMinute, opts.delaySeconds);
        boolean bfs = opts.strategy != null && opts.strategy.toLowerCase().startsWith("breadth");
        this.frontier = new Frontier(bfs);
        this.deadlineNanos = parseDuration(opts.crawlDuration);
        if (opts.errorLog != null) {
            try {
                this.errorLog = new BufferedWriter(new FileWriter(opts.errorLog));
            } catch (Exception e) {
                System.err.println("[!] cannot open error log: " + e.getMessage());
            }
        }
    }

    public void start() {
        long t0 = System.currentTimeMillis();
        output.banner("target " + (opts.seeds.isEmpty() ? "-" : opts.seeds.get(0))
                + "  depth " + opts.depth + "  threads " + opts.concurrency
                + "  strategy " + opts.strategy
                + "  scope " + (opts.noScope ? "none" : opts.fieldScope));
        warnUnsupported();

        if (opts.knownFiles != null) {
            seedKnownFiles();
        }
        for (String seed : opts.seeds) {
            enqueue(seed, 1, "seed");
        }

        // Optional crawl-duration watchdog.
        if (deadlineNanos > 0) {
            Thread watchdog = new Thread(() -> {
                while (System.nanoTime() < deadlineNanos) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                output.info("[*] crawl-duration reached; stopping");
                frontier.stop();
            }, "spirex-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }

        int workers = Math.max(1, opts.concurrency);
        Thread[] pool = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            pool[i] = new Thread(this::workerLoop, "spirex-worker-" + i);
            pool[i].start();
        }
        for (Thread t : pool) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        closeErrorLog();
        output.summary(System.currentTimeMillis() - t0, crawledCount.get(), visited.size(),
                hosts.size(), statusClass.get(2), statusClass.get(3), statusClass.get(4),
                statusClass.get(5), errorCount.get());

        // AI analysis runs before the output file is closed so it can be appended to -o.
        if (opts.aiKey != null) {
            AiAdvisor.run(aiUrls, opts,
                    opts.seeds.isEmpty() ? "the target" : opts.seeds.get(0), output);
        }
        output.close();
    }

    private void workerLoop() {
        Node n;
        while ((n = frontier.next()) != null) {
            try {
                process(n);
            } catch (Throwable t) {
                errorCount.incrementAndGet();
                output.error(n.url(), t);
                logError(n.url(), t);
            } finally {
                frontier.complete();
            }
        }
    }

    private void process(Node node) throws Exception {
        rateLimiter.acquire();
        URI uri = URI.create(node.url());
        HttpFetcher.Response resp = fetcher.fetch(uri);
        crawledCount.incrementAndGet();
        int cls = resp.status() / 100;
        if (cls >= 1 && cls <= 5) {
            statusClass.incrementAndGet(cls);
        }
        if (uri.getHost() != null) {
            hosts.add(uri.getHost());
        }

        if (opts.storeResponse) {
            storeResponse(node.url(), resp);
        }

        // Unique-content filter (-duf disables it): skip output for duplicate bodies.
        boolean duplicate = false;
        if (!opts.disableUniqueFilter && resp.body() != null) {
            duplicate = !seenBodies.add(resp.body().hashCode());
        }

        String tech = opts.techDetect
                ? LinkExtractor.techDetect(resp.headers(), resp.body()) : null;

        if (!duplicate) {
            output.write(new Result(node.url(), node.source(), node.depth(),
                    resp.status(), resp.contentType(), tech, false));
            if (opts.aiKey != null) {
                aiUrls.add(node.url());
            }
        }

        // Path-climb: surface parent directories as crawlable endpoints.
        if (opts.pathClimb) {
            for (String parent : parents(uri)) {
                enqueue(parent, node.depth() + 1, "path-climb");
            }
        }

        boolean parseable = isParseable(resp.contentType(), node.url());

        // Form extraction runs on every fetched page, independent of depth.
        if (opts.formExtraction && parseable) {
            for (LinkExtractor.Form form : extractor.extractForms(resp.body(), resp.finalUri())) {
                output.writeForm(form, node.url());
            }
        }

        // Link extraction only when we are allowed to go deeper.
        if (node.depth() < opts.depth && parseable) {
            boolean js = opts.jsCrawl || opts.jsluice;
            Set<String> links = extractor.extract(resp.body(), resp.finalUri(),
                    resp.contentType(), js);
            for (String link : links) {
                enqueue(link, node.depth() + 1, node.url());
            }
        }
    }

    /** Admit a URL for crawling (thread-safe). Applies dedup, scope, and excludes. */
    void enqueue(String rawUrl, int depth, String source) {
        if (rawUrl == null || depth > opts.depth) {
            return;
        }
        String norm = normalize(rawUrl);
        if (norm == null) {
            return;
        }
        if (!visited.add(dedupKey(norm))) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(norm);
        } catch (Exception e) {
            return;
        }
        if (scope.isSessionDestroying(uri)) {
            if (opts.verbose) {
                output.info("[*] skipping session-destroying endpoint: " + norm);
            }
            return;
        }
        if (scope.isExcluded(uri)) {
            return;
        }
        if (!scope.inScope(uri)) {
            if (opts.displayOutScope) {
                output.write(new Result(norm, source, depth, 0, "", null, true));
            }
            return;
        }
        frontier.add(new Node(norm, depth, source));
    }

    private String dedupKey(String norm) {
        String key = norm;
        if (opts.ignoreQueryParams) {
            int q = key.indexOf('?');
            if (q >= 0) {
                key = key.substring(0, q);
            }
        }
        if (opts.filterSimilar) {
            int q = key.indexOf('?');
            String base = q >= 0 ? key.substring(0, q) : key;
            String[] parts = base.split("/");
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                if (sb.length() > 0) {
                    sb.append('/');
                }
                sb.append(SIMILAR_SEG.matcher(p).matches() ? "{}" : p);
            }
            key = sb.toString();
        }
        return key;
    }

    private void seedKnownFiles() {
        String kf = opts.knownFiles.toLowerCase();
        boolean robots = kf.equals("all") || kf.contains("robots");
        boolean sitemap = kf.equals("all") || kf.contains("sitemap");
        for (String seed : opts.seeds) {
            try {
                URI u = URI.create(seed.startsWith("http") ? seed : "https://" + seed);
                String origin = u.getScheme() + "://" + u.getHost()
                        + (u.getPort() == -1 ? "" : ":" + u.getPort());
                if (robots) {
                    enqueue(origin + "/robots.txt", 1, "known-files");
                }
                if (sitemap) {
                    enqueue(origin + "/sitemap.xml", 1, "known-files");
                }
            } catch (Exception ignored) {
                // skip
            }
        }
    }

    private void storeResponse(String url, HttpFetcher.Response resp) {
        try {
            URI u = URI.create(url);
            Path dir = Path.of(opts.storeResponseDir, u.getHost() == null ? "_" : u.getHost());
            Files.createDirectories(dir);
            Path f = dir.resolve(Integer.toHexString(url.hashCode()) + ".txt");
            StringBuilder sb = new StringBuilder();
            if (!opts.omitRaw) {
                sb.append("GET ").append(url).append("\n\n");
                sb.append("HTTP ").append(resp.status()).append('\n');
                resp.headers().map().forEach((k, v) ->
                        sb.append(k).append(": ").append(String.join(",", v)).append('\n'));
                sb.append('\n');
            }
            if (!opts.omitBody && resp.body() != null) {
                sb.append(resp.body());
            }
            Files.writeString(f, sb.toString());
        } catch (Exception e) {
            output.error(url, e);
        }
    }

    private List<String> parents(URI uri) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        String path = uri.getRawPath();
        if (path == null || path.equals("/")) {
            return out;
        }
        String origin = uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        String p = path;
        if (!p.endsWith("/")) {
            p = p.substring(0, p.lastIndexOf('/') + 1);
        }
        while (p.length() > 1) {
            out.add(origin + p);
            p = p.substring(0, p.length() - 1);
            int slash = p.lastIndexOf('/');
            p = slash >= 0 ? p.substring(0, slash + 1) : "/";
            if (p.equals("/")) {
                break;
            }
        }
        return out;
    }

    private void warnUnsupported() {
        if (opts.silent) {
            return;
        }
        if (opts.headless || opts.hybrid) {
            System.err.println("[!] headless/hybrid crawling requires Chromium; "
                    + "not supported in the pure-JDK build (continuing in standard mode)");
        }
        if (opts.tlsImpersonate) {
            System.err.println("[!] -tls-impersonate (JA3) not supported in the pure-JDK build");
        }
        if (opts.captchaSolverProvider != null) {
            System.err.println("[!] captcha solver integration not supported in this build");
        }
        if (opts.pprofServer) {
            System.err.println("[!] -pprof-server not supported in this build");
        }
        if (opts.jsluice) {
            System.err.println("[*] -jsluice approximated by -js-crawl regex extraction");
        }
        if (opts.proxy != null && opts.proxy.startsWith("socks")) {
            System.err.println("[!] SOCKS proxy unsupported by JDK HTTP client; ignored");
        }
    }

    private void logError(String url, Throwable t) {
        if (errorLog != null) {
            try {
                errorLog.write(url + "\t" + t + "\n");
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private void closeErrorLog() {
        if (errorLog != null) {
            try {
                errorLog.flush();
                errorLog.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private static boolean isParseable(String contentType, String url) {
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if (ct.contains("text/html") || ct.contains("xml") || ct.contains("javascript")
                || ct.contains("text/plain") || ct.contains("json")) {
            return true;
        }
        if (ct.isEmpty()) {
            String ext = OutputWriter.extensionOf(url);
            return ext.isEmpty() || ext.equals("html") || ext.equals("htm")
                    || ext.equals("js") || ext.equals("xml") || ext.equals("txt");
        }
        return false;
    }

    private static long parseDuration(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        s = s.trim().toLowerCase();
        char unit = s.charAt(s.length() - 1);
        long mult;
        String num = s;
        switch (unit) {
            case 's' -> { mult = 1; num = s.substring(0, s.length() - 1); }
            case 'm' -> { mult = 60; num = s.substring(0, s.length() - 1); }
            case 'h' -> { mult = 3600; num = s.substring(0, s.length() - 1); }
            case 'd' -> { mult = 86400; num = s.substring(0, s.length() - 1); }
            default -> mult = 1; // bare number = seconds
        }
        try {
            long secs = Long.parseLong(num.trim());
            return System.nanoTime() + secs * 1_000_000_000L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String firstSeedHost(Options opts) {
        for (String s : opts.seeds) {
            try {
                String h = URI.create(s.startsWith("http") ? s : "https://" + s).getHost();
                if (h != null) {
                    return h;
                }
            } catch (Exception ignored) {
                // try next
            }
        }
        return "";
    }

    static String normalize(String raw) {
        try {
            URI u = new URI(raw);
            String scheme = u.getScheme();
            if (scheme == null) {
                return null;
            }
            scheme = scheme.toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            String host = u.getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase();
            int port = u.getPort();
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
                port = -1;
            }
            String path = u.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            String query = u.getRawQuery();
            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://").append(host);
            if (port != -1) {
                sb.append(':').append(port);
            }
            sb.append(path);
            if (query != null) {
                sb.append('?').append(query);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** A queued crawl target. */
    private record Node(String url, int depth, String source) {
    }

    /**
     * Shared work queue with strategy-aware ordering and built-in termination
     * detection. DFS pushes/pops the front (LIFO); BFS appends to the back and
     * pops the front (FIFO).
     */
    private static final class Frontier {
        private final ArrayDeque<Node> dq = new ArrayDeque<>();
        private final boolean bfs;
        private int busy = 0;
        private boolean done = false;

        Frontier(boolean bfs) {
            this.bfs = bfs;
        }

        synchronized void add(Node n) {
            if (done) {
                return;
            }
            if (bfs) {
                dq.addLast(n);
            } else {
                dq.addFirst(n);
            }
            notifyAll();
        }

        synchronized Node next() {
            while (true) {
                if (done) {
                    return null;
                }
                Node n = dq.pollFirst();
                if (n != null) {
                    busy++;
                    return n;
                }
                if (busy == 0) {
                    done = true;
                    notifyAll();
                    return null;
                }
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        synchronized void complete() {
            busy--;
            if (busy == 0 && dq.isEmpty()) {
                done = true;
                notifyAll();
            }
        }

        synchronized void stop() {
            done = true;
            notifyAll();
        }
    }
}
