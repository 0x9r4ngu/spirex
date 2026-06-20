package spirex;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Dependency-free test suite (no JUnit) to honour the project's pure-JDK
 * constraint. Runs unit tests over the pure-logic classes plus a full
 * end-to-end crawl against an embedded {@link HttpServer} fake site, so the
 * multithreaded engine is exercised offline and deterministically.
 *
 * Run via ./test.sh — exits non-zero if any check fails.
 */
public class Tests {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        System.out.println("== spirex test suite ==");

        testNormalize();
        testIpUtils();
        testScope();
        testLinkExtractor();
        testForms();
        testTechDetect();
        testOutputFields();
        testRateLimiter();
        testAiAdvisor();
        testIntegrationCrawl();
        testDepthLimit();
        testStrategiesSameSet();

        System.out.println();
        System.out.println("passed: " + passed + " | failed: " + failures.size());
        if (!failures.isEmpty()) {
            System.out.println("FAILURES:");
            failures.forEach(f -> System.out.println("  - " + f));
            System.exit(1);
        }
        System.out.println("ALL GREEN");
    }

    // ---------- unit tests ----------

    private static void testNormalize() {
        section("Crawler.normalize");
        eq("lowercase + add path", Crawler.normalize("HTTP://Example.COM"), "http://example.com/");
        eq("drop default port", Crawler.normalize("https://example.com:443/x"), "https://example.com/x");
        eq("keep non-default port", Crawler.normalize("http://example.com:8080/"), "http://example.com:8080/");
        eq("drop fragment, keep query", Crawler.normalize("https://e.com/a?b=1#frag"), "https://e.com/a?b=1");
        check("reject mailto", Crawler.normalize("mailto:x@y.com") == null);
        check("reject relative", Crawler.normalize("/just/a/path") == null);
        check("reject ftp", Crawler.normalize("ftp://e.com/") == null);
    }

    private static void testIpUtils() {
        section("IpUtils");
        check("isIp valid", IpUtils.isIp("10.0.0.1"));
        check("isIp reject", !IpUtils.isIp("999.1.1.1") && !IpUtils.isIp("example.com"));
        check("isCidr valid", IpUtils.isCidr("10.0.0.0/8"));
        check("isCidr reject", !IpUtils.isCidr("10.0.0.0/40"));
        IpUtils.Cidr c = IpUtils.parseCidr("10.0.0.0/8");
        try {
            check("cidr contains", c.contains(java.net.InetAddress.getByName("10.5.6.7")));
            check("cidr excludes", !c.contains(java.net.InetAddress.getByName("11.0.0.1")));
        } catch (Exception e) {
            check("cidr resolve", false);
        }
    }

    private static void testScope() {
        section("Scope");

        // rdn (default): root domain + subdomains
        Scope rdn = new Scope(opts(o -> o.fieldScope = "rdn"), "example.com");
        check("rdn same host", rdn.inScope(URI.create("https://example.com/x")));
        check("rdn subdomain", rdn.inScope(URI.create("https://api.example.com/")));
        check("rdn other domain out", !rdn.inScope(URI.create("https://evil.com/")));

        // fqdn / dn: strict host
        Scope strict = new Scope(opts(o -> o.fieldScope = "fqdn"), "www.example.com");
        check("strict exact host", strict.inScope(URI.create("https://www.example.com/")));
        check("strict subdomain out", !strict.inScope(URI.create("https://api.example.com/")));
        check("strict apex out", !strict.inScope(URI.create("https://example.com/")));

        // no-scope: everything in
        Scope ns = new Scope(opts(o -> o.noScope = true), "example.com");
        check("no-scope allows any", ns.inScope(URI.create("https://anything.io/")));

        // crawl-out-scope regex
        Scope cos = new Scope(opts(o -> { o.fieldScope = "rdn"; o.crawlOutScope.add("/admin"); }), "example.com");
        check("out-scope regex blocks", !cos.inScope(URI.create("https://example.com/admin/x")));
        check("out-scope regex allows", cos.inScope(URI.create("https://example.com/public")));

        // custom field-scope regex
        Scope custom = new Scope(opts(o -> o.fieldScope = "(foo\\.com|bar\\.com)"), "foo.com");
        check("custom regex match", custom.inScope(URI.create("https://foo.com/")));
        check("custom regex non-match", !custom.inScope(URI.create("https://baz.com/")));

        // -e exclude filters
        Scope exIp = new Scope(opts(o -> o.exclude.add("1.2.3.4")), "x");
        check("exclude ip", exIp.isExcluded(URI.create("http://1.2.3.4/")));
        Scope exCidr = new Scope(opts(o -> o.exclude.add("10.0.0.0/8")), "x");
        check("exclude cidr", exCidr.isExcluded(URI.create("http://10.9.9.9/")));
        check("exclude cidr miss", !exCidr.isExcluded(URI.create("http://11.0.0.1/")));
        Scope exPriv = new Scope(opts(o -> o.exclude.add("private-ips")), "x");
        check("exclude private", exPriv.isExcluded(URI.create("http://127.0.0.1/")));
        check("exclude private miss", !exPriv.isExcluded(URI.create("http://8.8.8.8/")));
        Scope exCdn = new Scope(opts(o -> o.exclude.add("cdn")), "x");
        check("exclude cdn", exCdn.isExcluded(URI.create("https://foo.cloudfront.net/")));
        Scope exRe = new Scope(opts(o -> o.exclude.add("tracker")), "x");
        check("exclude regex", exRe.isExcluded(URI.create("https://tracker.example.com/")));

        // session-destroying (logout) endpoints: skipped by default, kept with --allow-logout
        Scope sd = new Scope(opts(o -> o.fieldScope = "rdn"), "example.com");
        check("skip /logout/", sd.isSessionDestroying(URI.create("https://example.com/logout/")));
        check("skip /accounts/logout", sd.isSessionDestroying(URI.create("https://example.com/accounts/logout")));
        check("skip ?do=sign-out", sd.isSessionDestroying(URI.create("https://example.com/auth?do=sign-out")));
        check("skip /logoff", sd.isSessionDestroying(URI.create("https://example.com/user/logoff")));
        check("keep content url", !sd.isSessionDestroying(URI.create("https://example.com/cost/rate-cards/359/")));
        check("keep logout-in-slug", !sd.isSessionDestroying(URI.create("https://example.com/logouts-report/")));
        Scope sdAllow = new Scope(opts(o -> o.crawlLogout = true), "example.com");
        check("allow-logout opts back in", !sdAllow.isSessionDestroying(URI.create("https://example.com/logout/")));
    }

    private static void testLinkExtractor() {
        section("LinkExtractor.extract");
        LinkExtractor le = new LinkExtractor();
        URI base = URI.create("https://e.com/dir/page");
        String html = """
                <html><head><base href="https://e.com/"></head>
                <a href="/a">a</a>
                <a href='sub/b'>b</a>
                <a href="https://other.com/x">ext</a>
                <a href="mailto:x@y.com">mail</a>
                <a href="#frag">frag</a>
                <a href="javascript:void(0)">js</a>
                <img src="/img.png"><form action="/post"></form>
                </html>""";
        Set<String> links = le.extract(html, base, "text/html", false);
        check("resolves root-relative", links.contains("https://e.com/a"));
        check("resolves base-relative", links.contains("https://e.com/sub/b"));
        check("keeps external (scope filters later)", links.contains("https://other.com/x"));
        check("includes img src", links.contains("https://e.com/img.png"));
        check("skips mailto", links.stream().noneMatch(s -> s.contains("mailto")));
        check("skips javascript", links.stream().noneMatch(s -> s.contains("javascript")));
        check("skips pure fragment", !links.contains("https://e.com/dir/page"));

        // sitemap
        Set<String> sm = le.extract("<urlset><url><loc>https://e.com/s1</loc></url></urlset>",
                URI.create("https://e.com/sitemap.xml"), "application/xml", false);
        check("sitemap loc", sm.contains("https://e.com/s1"));

        // robots
        Set<String> rb = le.extract("User-agent: *\nDisallow: /private\nSitemap: https://e.com/sm.xml",
                URI.create("https://e.com/robots.txt"), "text/plain", false);
        check("robots disallow path", rb.contains("https://e.com/private"));
        check("robots sitemap url", rb.contains("https://e.com/sm.xml"));

        // js endpoints
        String js = "var u='/api/v1/users'; fetch(\"https://e.com/api/data\");";
        Set<String> jl = le.extract(js, URI.create("https://e.com/app.js"), "application/javascript", true);
        check("js relative endpoint", jl.contains("https://e.com/api/v1/users"));
        check("js absolute endpoint", jl.contains("https://e.com/api/data"));
        Set<String> jOff = le.extract(js, URI.create("https://e.com/app.js"), "application/javascript", false);
        check("js extraction off when -jc absent", jOff.isEmpty());

        // HTML entity decoding in URLs (&amp; must become &)
        Set<String> ent = le.extract("<a href=\"/s?hl=ne&amp;a=1&amp;b=2\">x</a>",
                URI.create("https://e.com/"), "text/html", false);
        check("decodes &amp; in query", ent.contains("https://e.com/s?hl=ne&a=1&b=2"));
        check("no literal &amp; remains", ent.stream().noneMatch(s -> s.contains("&amp;")));
        eq("decodeEntities numeric", LinkExtractor.decodeEntities("a&#x2F;b&#47;c"), "a/b/c");
    }

    private static void testForms() {
        section("LinkExtractor.extractForms");
        LinkExtractor le = new LinkExtractor();
        String html = "<form action=\"/login\" method=\"post\">"
                + "<input name=\"user\"><input name='pass'><textarea name=\"bio\"></textarea>"
                + "<select name=\"role\"></select></form>";
        List<LinkExtractor.Form> forms = le.extractForms(html, URI.create("https://e.com/"));
        check("one form found", forms.size() == 1);
        if (!forms.isEmpty()) {
            LinkExtractor.Form f = forms.get(0);
            eq("form action resolved", f.action(), "https://e.com/login");
            eq("form method", f.method(), "POST");
            check("inputs captured", f.inputs().containsAll(List.of("user", "pass", "bio", "role")));
        }
    }

    private static void testTechDetect() {
        section("LinkExtractor.techDetect");
        HttpHeaders h = HttpHeaders.of(
                Map.of("server", List.of("nginx"), "x-powered-by", List.of("PHP/8.1")),
                (a, b) -> true);
        String tech = LinkExtractor.techDetect(h,
                "<meta name=\"generator\" content=\"WordPress 6.4\">");
        check("server header", tech.contains("nginx"));
        check("x-powered-by", tech.contains("PHP/8.1"));
        check("meta generator", tech.contains("WordPress 6.4"));
    }

    private static void testOutputFields() {
        section("OutputWriter fields");
        String u = "https://sub.example.com:8080/a/b/file.php?x=1&y=2";
        eq("url", OutputWriter.fieldValue(u, "url"), u);
        eq("path", OutputWriter.fieldValue(u, "path"), "/a/b/file.php");
        eq("fqdn", OutputWriter.fieldValue(u, "fqdn"), "sub.example.com");
        eq("rdn", OutputWriter.fieldValue(u, "rdn"), "example.com");
        eq("rurl", OutputWriter.fieldValue(u, "rurl"), "https://sub.example.com:8080");
        eq("file", OutputWriter.fieldValue(u, "file"), "file.php");
        eq("dir", OutputWriter.fieldValue(u, "dir"), "/a/b/");
        eq("kv", OutputWriter.fieldValue(u, "kv"), "x=1&y=2");
        eq("key", OutputWriter.fieldValue(u, "key"), "x\ny");
        eq("value", OutputWriter.fieldValue(u, "value"), "1\n2");
        eq("extensionOf", OutputWriter.extensionOf(u), "php");
        eq("extensionOf none", OutputWriter.extensionOf("https://e.com/a/b"), "");
    }

    private static void testRateLimiter() {
        section("RateLimiter");
        RateLimiter rl = new RateLimiter(10, 0, 0); // 10/sec => 100ms interval
        long t0 = System.nanoTime();
        rl.acquire();
        rl.acquire();
        rl.acquire(); // 2 intervals after the first ~= >=200ms
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        check("rate limiter spaces requests (>=150ms, got " + ms + ")", ms >= 150);
    }

    // ---------- integration tests (embedded HttpServer) ----------

    private static HttpServer startFakeSite() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", Tests::route);
        server.start();
        return server;
    }

    private static void route(HttpExchange ex) throws java.io.IOException {
        String path = ex.getRequestURI().getPath();
        String body = switch (path) {
            case "/" -> "<a href=\"/a\">a</a><a href=\"/b\">b</a>"
                    + "<a href=\"/external\">x</a><a href=\"mailto:z@z.com\">m</a><a href=\"/a\">dup</a>";
            case "/a" -> "<a href=\"/a/deep\">deep</a>";
            case "/a/deep" -> "<a href=\"/a\">cycle back</a>"; // tests dedup/termination
            case "/b" -> "no links here";
            case "/external" -> "<a href=\"/secret\">should never be reached</a>";
            default -> "404";
        };
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html");
        ex.sendResponseHeaders(path.equals("/secret") ? 404 : 200, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private static Set<String> runCrawl(Options opts) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
        try {
            new Crawler(opts).start();
        } finally {
            System.out.flush();
            System.setOut(original);
        }
        Set<String> urls = new LinkedHashSet<>();
        for (String line : baos.toString(StandardCharsets.UTF_8).split("\\R")) {
            line = line.trim();
            if (line.startsWith("http")) {
                urls.add(line.endsWith("/") && line.length() > "http://x".length()
                        ? line.substring(0, line.length() - 1) : line);
            }
        }
        return urls;
    }

    private static Options baseOpts(int port) {
        Options o = new Options();
        o.seeds.add("http://127.0.0.1:" + port + "/");
        o.noScope = true;                 // scope tested separately; isolate crawl logic
        o.crawlOutScope.add("/external"); // exclude one branch
        o.concurrency = 4;
        o.silent = true;
        o.noColor = true;
        o.rateLimit = 0;
        return o;
    }

    private static void testIntegrationCrawl() throws Exception {
        section("integration: full multithreaded crawl");
        HttpServer s = startFakeSite();
        int port = s.getAddress().getPort();
        try {
            Options o = baseOpts(port);
            o.depth = 5;
            Set<String> got = runCrawl(o);
            String b = "http://127.0.0.1:" + port;
            check("crawled root", got.contains(b));
            check("crawled /a", got.contains(b + "/a"));
            check("crawled /b", got.contains(b + "/b"));
            check("crawled /a/deep", got.contains(b + "/a/deep"));
            check("excluded /external", got.stream().noneMatch(u -> u.endsWith("/external")));
            check("never reached /secret", got.stream().noneMatch(u -> u.endsWith("/secret")));
            check("terminated with exactly 4 urls (got " + got.size() + ")", got.size() == 4);
        } finally {
            s.stop(0);
        }
    }

    private static void testDepthLimit() throws Exception {
        section("integration: depth limit");
        HttpServer s = startFakeSite();
        int port = s.getAddress().getPort();
        try {
            Options o = baseOpts(port);
            o.depth = 1; // only the seed should be fetched
            Set<String> got = runCrawl(o);
            check("depth 1 => only seed (got " + got.size() + ")", got.size() == 1);

            Options o2 = baseOpts(port);
            o2.depth = 2; // seed + its direct links (a, b), not a/deep
            Set<String> got2 = runCrawl(o2);
            String b = "http://127.0.0.1:" + port;
            check("depth 2 reaches /a", got2.contains(b + "/a"));
            check("depth 2 stops before /a/deep", !got2.contains(b + "/a/deep"));
        } finally {
            s.stop(0);
        }
    }

    private static void testStrategiesSameSet() throws Exception {
        section("integration: DFS and BFS reach same set");
        HttpServer s = startFakeSite();
        int port = s.getAddress().getPort();
        try {
            Options dfs = baseOpts(port);
            dfs.depth = 5;
            dfs.strategy = "depth-first";
            Options bfs = baseOpts(port);
            bfs.depth = 5;
            bfs.strategy = "breadth-first";
            Set<String> a = runCrawl(dfs);
            Set<String> b = runCrawl(bfs);
            check("DFS terminated", !a.isEmpty());
            check("BFS terminated", !b.isEmpty());
            check("same URL set regardless of strategy", a.equals(b));
        } finally {
            s.stop(0);
        }
    }

    private static void testAiAdvisor() {
        section("AiAdvisor endpoint selection + response parsing");
        List<String> urls = List.of(
                "https://e.com/",
                "https://e.com/about",
                "https://e.com/user/1",
                "https://e.com/user/2",                 // folds with /user/1
                "https://e.com/product.php?id=5",
                "https://e.com/product.php?id=9",        // folds with id=5
                "https://e.com/search?q=hi",
                "https://e.com/img/logo.png");

        List<String> sel = AiAdvisor.selectEndpoints(urls, 60);
        check("folds look-alike id paths", sel.stream()
                .filter(u -> u.contains("/user/")).count() == 1);
        check("folds same param signature", sel.stream()
                .filter(u -> u.contains("product.php")).count() == 1);
        check("parameterised endpoint kept", sel.stream().anyMatch(u -> u.contains("?")));
        check("interesting param URL ranked before static page",
                indexOf(sel, "search?q=") < indexOf(sel, "/about"));

        List<String> capped = AiAdvisor.selectEndpoints(urls, 2);
        check("respects max cap", capped.size() == 2);

        // Response parsing: pull only "text" parts, decode escapes, skip siblings.
        String json = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"line1\\n"
                + "line2 \\u0026 more\",\"thoughtSignature\":\"IGNORE\"}]}}]}";
        eq("extractText decodes parts", AiAdvisor.extractText(json), "line1\nline2 & more");
        String err = "{\"error\":{\"code\":400,\"message\":\"API key not valid\"}}";
        eq("extractError reads message", AiAdvisor.extractError(err), "API key not valid");
    }

    private static int indexOf(List<String> list, String needle) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).contains(needle)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    // ---------- helpers ----------

    private interface OptTweak {
        void apply(Options o);
    }

    private static Options opts(OptTweak t) {
        Options o = new Options();
        t.apply(o);
        return o;
    }

    private static void section(String name) {
        System.out.println("[" + name + "]");
    }

    private static void check(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failures.add(name);
            System.out.println("  FAIL " + name);
        }
    }

    private static void eq(String name, Object got, Object exp) {
        check(name + " (exp=" + exp + " got=" + got + ")", Objects.equals(got, exp));
    }
}
