package spirex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every command-line flag the crawler accepts. Fields are grouped to match the
 * sections shown in -h. Flags that cannot be honoured in a pure-JDK build
 * (headless Chromium, JA3 TLS, captcha, pprof, self-update) are still parsed and
 * stored here for completeness; the engine reports them as unsupported at runtime.
 */
public class Options {

    // ---- INPUT ----
    public final List<String> seeds = new ArrayList<>();        // -u, -list
    public String resume = null;                                // -resume
    public final List<String> exclude = new ArrayList<>();      // -e, -exclude

    // ---- CONFIGURATION ----
    public final List<String> resolvers = new ArrayList<>();    // -r, -resolvers
    public int depth = 3;                                       // -d, -depth
    public boolean jsCrawl = false;                             // -jc, -js-crawl
    public boolean jsluice = false;                             // -jsl, -jsluice
    public String crawlDuration = null;                         // -ct, -crawl-duration (e.g. 30s, 5m)
    public String knownFiles = null;                            // -kf, -known-files (all|robotstxt|sitemapxml)
    public int maxResponseSize = 4 * 1024 * 1024;              // -mrs, -max-response-size
    public int timeoutSeconds = 10;                             // -timeout
    public int timeStable = 1;                                  // -time-stable (headless)
    public boolean automaticFormFill = false;                   // -aff, -automatic-form-fill
    public boolean formExtraction = false;                      // -fx, -form-extraction
    public int retries = 1;                                     // -retry
    public String proxy = null;                                 // -proxy
    public boolean techDetect = false;                          // -td, -tech-detect
    public final Map<String, String> headers = new LinkedHashMap<>(); // -H, -headers
    public String configFile = null;                            // -config
    public String formConfig = null;                            // -fc, -form-config
    public String fieldConfig = null;                           // -flc, -field-config
    public String strategy = "depth-first";                     // -s, -strategy
    public boolean ignoreQueryParams = false;                   // -iqp, -ignore-query-params
    public boolean filterSimilar = false;                       // -fsu, -filter-similar
    public int filterSimilarThreshold = 10;                     // -fst, -filter-similar-threshold
    public boolean tlsImpersonate = false;                      // -tlsi, -tls-impersonate
    public boolean disableRedirects = false;                    // -dr, -disable-redirects
    public boolean pathClimb = false;                           // -pc, -path-climb
    public boolean knowledgeBase = false;                       // -kb, -knowledge-base

    // ---- DEBUG ----
    public boolean healthCheck = false;                         // -hc, -health-check
    public String errorLog = null;                              // -elog, -error-log
    public boolean pprofServer = false;                         // -pprof-server

    // ---- HEADLESS (accepted, not supported in pure JDK) ----
    public boolean headless = false;                            // -hl, -headless
    public boolean hybrid = false;                              // -hh, -hybrid
    public boolean systemChrome = false;                        // -sc, -system-chrome
    public boolean showBrowser = false;                         // -sb, -show-browser
    public final List<String> headlessOptions = new ArrayList<>(); // -ho, -headless-options
    public boolean noSandbox = false;                           // -nos, -no-sandbox
    public String chromeDataDir = null;                         // -cdd, -chrome-data-dir
    public String systemChromePath = null;                      // -scp, -system-chrome-path
    public boolean noIncognito = false;                         // -noi, -no-incognito
    public String chromeWsUrl = null;                           // -cwu, -chrome-ws-url
    public boolean xhrExtraction = false;                       // -xhr, -xhr-extraction
    public int maxFailureCount = 10;                            // -mfc, -max-failure-count
    public boolean enableDiagnostics = false;                   // -ed, -enable-diagnostics
    public String captchaSolverProvider = null;                 // -csp, -captcha-solver-provider
    public String captchaSolverKey = null;                      // -csk, -captcha-solver-key

    // ---- SCOPE ----
    public final List<String> crawlScope = new ArrayList<>();   // -cs, -crawl-scope
    public final List<String> crawlOutScope = new ArrayList<>();// -cos, -crawl-out-scope
    public String fieldScope = "rdn";                           // -fs, -field-scope (dn|rdn|fqdn|regex)
    public boolean noScope = false;                             // -ns, -no-scope
    public boolean displayOutScope = false;                     // -do, -display-out-scope
    public boolean crawlLogout = false;                         // --allow-logout (default: skip logout/sign-out)

    // ---- FILTER ----
    public final List<String> matchRegex = new ArrayList<>();   // -mr, -match-regex
    public final List<String> filterRegex = new ArrayList<>();  // -fr, -filter-regex
    public String field = null;                                 // -f, -field
    public String storeField = null;                            // -sf, -store-field
    public final List<String> extensionMatch = new ArrayList<>();  // -em, -extension-match
    public final List<String> extensionFilter = new ArrayList<>(); // -ef, -extension-filter
    public boolean noDefaultExtFilter = false;                  // -ndef, -no-default-ext-filter
    public String matchCondition = null;                        // -mdc, -match-condition
    public String filterCondition = null;                       // -fdc, -filter-condition
    public boolean disableUniqueFilter = false;                 // -duf, -disable-unique-filter
    public final List<String> filterPageType = new ArrayList<>();  // -fpt, -filter-page-type

    // ---- RATE-LIMIT ----
    public int concurrency = 10;                                // -c, -concurrency
    public int parallelism = 10;                                // -p, -parallelism
    public int delaySeconds = 0;                                // -rd, -delay
    public int rateLimit = 150;                                 // -rl, -rate-limit
    public int rateLimitMinute = 0;                             // -rlm, -rate-limit-minute

    // ---- UPDATE ----
    public boolean update = false;                              // -up, -update
    public boolean disableUpdateCheck = false;                  // -duc, -disable-update-check

    // ---- OUTPUT ----
    public String output = null;                                // -o, -output
    public String outputTemplate = null;                        // -ot, -output-template
    public boolean storeResponse = false;                       // -sr, -store-response
    public String storeResponseDir = "spirex_response";        // -srd, -store-response-dir
    public boolean noClobber = false;                           // -ncb, -no-clobber
    public String storeFieldDir = null;                         // -sfd, -store-field-dir
    public boolean omitRaw = false;                             // -or, -omit-raw
    public boolean omitBody = false;                            // -ob, -omit-body
    public boolean listOutputFields = false;                    // -lof, -list-output-fields
    public final List<String> excludeOutputFields = new ArrayList<>(); // -eof, -exclude-output-fields
    public boolean jsonl = false;                               // -j, -jsonl
    public boolean noColor = false;                             // -nc, -no-color
    public boolean silent = false;                              // -silent
    public boolean verbose = false;                             // -v, -verbose
    public boolean debug = false;                               // -debug
    public boolean version = false;                             // -version
}
