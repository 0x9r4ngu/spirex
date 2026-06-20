package spirex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line entry point. spirex uses GNU-style flags: single-letter shorts
 * (-d) and double-dash longs (--depth), with optional --key=value form. Parses
 * the command line into {@link Options} and launches the {@link Crawler}.
 */
public class Main {

    public static void main(String[] args) {
        Options opts = new Options();
        try {
            parse(expand(args), opts);
        } catch (IllegalArgumentException e) {
            System.err.println("spirex: " + e.getMessage());
            System.err.println("try 'spirex --help'");
            System.exit(2);
        }

        if (opts.version) {
            System.out.println("spirex " + OutputWriter.VERSION);
            return;
        }
        if (opts.healthCheck) {
            healthCheck();
            return;
        }
        if (opts.update) {
            Updater.runSelfUpdate(opts);
            return;
        }
        if (opts.seeds.isEmpty()) {
            usage();
            System.exit(1);
        }

        Updater.notifyIfOutdated(opts);
        opts.seeds.replaceAll(Main::ensureScheme);
        new Crawler(opts).start();
    }

    /** Expand --key=value into two tokens so the main loop stays simple. */
    private static String[] expand(String[] args) {
        List<String> out = new ArrayList<>(args.length);
        for (String a : args) {
            int eq = a.indexOf('=');
            if (a.startsWith("--") && eq > 2) {
                out.add(a.substring(0, eq));
                out.add(a.substring(eq + 1));
            } else {
                out.add(a);
            }
        }
        return out.toArray(new String[0]);
    }

    private static void parse(String[] args, Options opts) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                // ---- target ----
                case "-u", "--url" -> addList(opts.seeds, val(args, ++i, a));
                case "-l", "--list" -> opts.seeds.addAll(readLines(val(args, ++i, a)));

                // ---- crawl ----
                case "-d", "--depth" -> opts.depth = intVal(args, ++i, a);
                case "-t", "--threads" -> opts.concurrency = intVal(args, ++i, a);
                case "-b", "--bfs" -> opts.strategy = "breadth-first";
                case "--scripts" -> opts.jsCrawl = true;
                case "--known-files" -> opts.knownFiles = val(args, ++i, a);
                case "--climb" -> opts.pathClimb = true;
                case "--max-time" -> opts.crawlDuration = val(args, ++i, a);
                case "--no-query" -> opts.ignoreQueryParams = true;
                case "--fold-similar" -> opts.filterSimilar = true;
                case "--no-redirect" -> opts.disableRedirects = true;

                // ---- scope ----
                case "--scope" -> applyScope(opts, val(args, ++i, a));
                case "--in-scope" -> opts.crawlScope.add(val(args, ++i, a));
                case "--out-scope" -> opts.crawlOutScope.add(val(args, ++i, a));
                case "--skip" -> addList(opts.exclude, val(args, ++i, a));
                case "--allow-logout" -> opts.crawlLogout = true;
                case "--show-external" -> opts.displayOutScope = true;

                // ---- network ----
                case "--timeout" -> opts.timeoutSeconds = intVal(args, ++i, a);
                case "--retries" -> opts.retries = intVal(args, ++i, a);
                case "--rate" -> opts.rateLimit = intVal(args, ++i, a);
                case "--delay" -> opts.delaySeconds = intVal(args, ++i, a);
                case "--max-size" -> opts.maxResponseSize = intVal(args, ++i, a);
                case "-H", "--header" -> addHeader(opts, val(args, ++i, a));
                case "-A", "--agent" -> opts.headers.put("User-Agent", val(args, ++i, a));
                case "-x", "--proxy" -> opts.proxy = val(args, ++i, a);

                // ---- filter ----
                case "-m", "--match" -> opts.matchRegex.add(val(args, ++i, a));
                case "-f", "--filter" -> opts.filterRegex.add(val(args, ++i, a));
                case "--ext-only" -> addList(opts.extensionMatch, val(args, ++i, a));
                case "--ext-skip" -> addList(opts.extensionFilter, val(args, ++i, a));
                case "--keep-dupes" -> opts.disableUniqueFilter = true;

                // ---- output ----
                case "-o", "--output" -> opts.output = val(args, ++i, a);
                case "--field" -> opts.field = val(args, ++i, a);
                case "--template" -> opts.outputTemplate = val(args, ++i, a);
                case "--forms" -> opts.formExtraction = true;
                case "--tech" -> opts.techDetect = true;
                case "--json" -> opts.jsonl = true;
                case "--store" -> opts.storeResponse = true;
                case "--store-dir" -> {
                    opts.storeResponse = true;
                    opts.storeResponseDir = val(args, ++i, a);
                }
                case "-v", "--verbose" -> opts.verbose = true;
                case "-q", "--quiet" -> opts.silent = true;
                case "--no-color" -> opts.noColor = true;

                // ---- ai ----
                case "--ai" -> opts.aiKey = val(args, ++i, a);
                case "--ai-model" -> opts.aiModel = val(args, ++i, a);

                // ---- update ----
                case "-up", "--update" -> opts.update = true;
                case "-duc", "--disable-update-check" -> opts.disableUpdateCheck = true;

                // ---- misc ----
                case "--health" -> opts.healthCheck = true;
                case "-V", "--version" -> opts.version = true;
                case "-h", "--help" -> {
                    usage();
                    System.exit(0);
                }

                default -> {
                    if (a.startsWith("-")) {
                        throw new IllegalArgumentException("unknown flag '" + a + "'");
                    }
                    addList(opts.seeds, a); // bare positional = a target URL
                }
            }
        }
    }

    private static void applyScope(Options o, String mode) {
        switch (mode.toLowerCase()) {
            case "host" -> o.fieldScope = "fqdn";
            case "domain" -> o.fieldScope = "rdn";
            case "any" -> o.noScope = true;
            default -> throw new IllegalArgumentException("--scope must be host|domain|any");
        }
    }

    private static void addList(List<String> target, String value) {
        if (value.startsWith("@")) {
            target.addAll(readLines(value.substring(1)));
            return;
        }
        Path p = Path.of(value);
        if (Files.isRegularFile(p)) {
            target.addAll(readLines(value));
            return;
        }
        for (String s : value.split(",")) {
            s = s.trim();
            if (!s.isEmpty()) {
                target.add(s);
            }
        }
    }

    private static void addHeader(Options opts, String value) {
        int idx = value.indexOf(':');
        if (idx < 0) {
            throw new IllegalArgumentException("header must be 'Name: value', got '" + value + "'");
        }
        opts.headers.put(value.substring(0, idx).trim(), value.substring(idx + 1).trim());
    }

    private static String ensureScheme(String s) {
        return (s.startsWith("http://") || s.startsWith("https://")) ? s : "https://" + s;
    }

    private static List<String> readLines(String path) {
        try {
            return Files.readAllLines(Path.of(path)).stream()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot read file '" + path + "'");
        }
    }

    private static String val(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException("flag '" + flag + "' needs a value");
        }
        return args[i];
    }

    private static int intVal(String[] args, int i, String flag) {
        try {
            return Integer.parseInt(val(args, i, flag));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("flag '" + flag + "' needs a number");
        }
    }

    private static void healthCheck() {
        System.out.println("spirex self-check");
        System.out.println("  java       : " + System.getProperty("java.version"));
        System.out.println("  os         : " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch"));
        System.out.println("  cpus       : " + Runtime.getRuntime().availableProcessors());
        System.out.println("  max heap   : " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB");
        try {
            java.net.InetAddress.getByName("github.com");
            System.out.println("  dns        : ok");
        } catch (Exception e) {
            System.out.println("  dns        : FAILED (" + e.getMessage() + ")");
        }
        System.out.println("  status     : ready");
    }

    private static void usage() {
        boolean color = System.console() != null;
        System.out.println();
        System.out.print(OutputWriter.logoBlock(color));
        System.out.println("  spirex v" + OutputWriter.VERSION
                + "  -  multithreaded web crawler  -  https://github.com/0x9r4ngu/spirex");
        System.out.println();
        System.out.print("""
                USAGE
                  spirex -u <url> [options]
                  spirex <url>                  (https:// is assumed)

                TARGET
                  -u, --url <url,...>     seed URL(s): comma-separated, or @file
                  -l, --list <file>       read seed URLs from a file

                CRAWL
                  -d, --depth <n>         how deep to crawl              (default 3)
                  -t, --threads <n>       worker threads                 (default 10)
                  -b, --bfs               breadth-first (default: depth-first)
                      --scripts           mine endpoints from JavaScript
                      --known-files <s>   also crawl robots.txt / sitemap.xml (all|robots|sitemap)
                      --climb             also crawl parent directories
                      --max-time <dur>    stop after a budget            (e.g. 30s, 5m, 1h)
                      --no-query          treat URLs differing only by query as one
                      --fold-similar      fold look-alike URLs (/p/1, /p/2 -> one)
                      --no-redirect       do not follow redirects

                SCOPE
                      --scope <mode>      host | domain | any            (default domain)
                      --in-scope <re>     only crawl URLs matching this regex
                      --out-scope <re>    never crawl URLs matching this regex
                      --skip <filter>     skip hosts: cdn | private | <ip> | <cidr> | <regex>
                      --allow-logout      follow logout/sign-out links (default: skip, keeps session)
                      --show-external     also list external links (without crawling them)

                NETWORK
                      --timeout <s>       per-request timeout            (default 10)
                      --retries <n>       retries on failure             (default 1)
                      --rate <n>          max requests per second        (default 150)
                      --delay <s>         delay before each request
                      --max-size <bytes>  cap response body size         (default 4194304)
                  -H, --header <k:v>      add a request header (repeatable)
                  -A, --agent <ua>        set the User-Agent
                  -x, --proxy <host:port> route through an HTTP proxy

                FILTER
                  -m, --match <re>        only output URLs matching this regex
                  -f, --filter <re>       drop output URLs matching this regex
                      --ext-only <list>   only output these extensions   (e.g. php,html,js)
                      --ext-skip <list>   drop these extensions          (e.g. png,css)
                      --keep-dupes        keep pages with duplicate content

                OUTPUT
                  -o, --output <file>     also write results to a file
                      --field <name>      print one field: url,path,fqdn,rdn,dir,file,key,value,kv,...
                      --template <str>    custom line: {url} {status} {depth} {source} {tech}
                      --forms             extract forms (action/method/inputs) as JSON
                      --tech              fingerprint server/framework technologies
                      --json              JSON Lines output
                      --store             save raw responses to disk
                      --store-dir <dir>   directory for --store           (default spirex_response)
                  -v, --verbose           show status codes and errors
                  -q, --quiet             results only - no banner or summary
                      --no-color          disable colour

                AI
                      --ai <gemini-key>   after crawling, ask Google Gemini which
                                          vulnerabilities to test on the discovered endpoints
                      --ai-model <name>   Gemini model (default gemini-flash-lite-latest); one of:
                                          gemini-flash-lite-latest, gemini-flash-latest,
                                          gemini-pro-latest, gemini-3.5-flash,
                                          gemini-3.1-pro-preview, gemini-3.1-flash-lite,
                                          gemini-3-pro-preview, gemini-3-flash-preview,
                                          gemini-2.5-pro, gemini-2.5-flash, gemini-2.5-flash-lite,
                                          gemini-2.0-flash, gemini-2.0-flash-lite

                UPDATE
                      --update            update spirex to the latest release
                      --disable-update-check  do not check for a new version on startup

                MISC
                      --health            run a quick self-diagnostic
                  -V, --version           print version
                  -h, --help              show this help

                EXAMPLES
                  spirex -u https://example.com -d 2 -t 20
                  spirex example.com --scripts --known-files all -m "/api/" -o out.txt
                  spirex -l hosts.txt -b --json -q --ext-skip png,css,svg
                  spirex example.com --scope host --skip private,cdn --tech
                  spirex http://testaspnet.vulnweb.com/ --ai <gemini-api-key>
                """);
    }
}
