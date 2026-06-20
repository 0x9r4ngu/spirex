package spirex;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders results to stdout and an optional file. Thread-safe via synchronisation
 * so concurrent worker output never interleaves. Honours the output-related
 * flags: -f/-field, -ot/-output-template, -em/-ef plus the default extension
 * filter, -mr/-fr, -j/-jsonl, -nc, -ncb/-no-clobber, -do/-display-out-scope, -fx.
 */
public class OutputWriter {

    private static final String RESET = "\u001b[0m";
    private static final String BOLD = "\u001b[1m";
    private static final String DIM = "\u001b[2m";
    private static final String GREEN = "\u001b[32m";
    private static final String CYAN = "\u001b[36m";
    private static final String YELLOW = "\u001b[33m";
    private static final String RED = "\u001b[31m";
    private static final String GREY = "\u001b[90m";
    /** Brand red (#cc0000) from 0x9r4ngu.github.io, as a 24-bit truecolor escape. */
    private static final String BRAND = "\u001b[38;2;204;0;0m";

    public static final String VERSION = "1.0.6";

    /**
     * Default static-asset extensions. Default output filtering is left OFF so
     * that linked stylesheets and scripts — which frequently reveal further
     * endpoints — still appear in the results. The -ndef flag is accepted and
     * the list can be activated by flipping DEFAULT_FILTER_ON.
     */
    private static final boolean DEFAULT_FILTER_ON = false;
    private static final Set<String> DEFAULT_EXT_FILTER = Set.of(
            "bmp", "css", "eot", "gif", "ico", "jpeg", "jpg", "mp4", "otf",
            "png", "svg", "ttf", "webp", "woff", "woff2");

    private final Options opts;
    private final List<Pattern> matchPats;
    private final List<Pattern> filterPats;
    private final Set<String> extMatch;
    private final Set<String> extFilter;
    private BufferedWriter file;

    public OutputWriter(Options opts) {
        this.opts = opts;
        this.matchPats = opts.matchRegex.stream().map(Pattern::compile).toList();
        this.filterPats = opts.filterRegex.stream().map(Pattern::compile).toList();
        this.extMatch = lower(opts.extensionMatch);
        this.extFilter = lower(opts.extensionFilter);

        if (opts.output != null) {
            try {
                if (opts.noClobber && Files.exists(Path.of(opts.output))) {
                    System.err.println("[!] -no-clobber: " + opts.output
                            + " exists; not writing to file");
                } else {
                    this.file = new BufferedWriter(new FileWriter(opts.output));
                }
            } catch (IOException e) {
                System.err.println("[!] cannot open output file: " + e.getMessage());
            }
        }
    }

    private static Set<String> lower(List<String> in) {
        Set<String> s = new LinkedHashSet<>();
        for (String e : in) {
            e = e.trim().toLowerCase();
            if (e.startsWith(".")) {
                e = e.substring(1);
            }
            if (!e.isEmpty()) {
                s.add(e);
            }
        }
        return s;
    }

    public synchronized void write(Result r) {
        if (!passesFilters(r.url(), r.outOfScope())) {
            return;
        }
        String line = opts.jsonl ? jsonLine(r) : plainLine(r);
        System.out.println(line);
        writeFile(opts.jsonl ? line : fieldValue(r.url(), opts.field));
    }

    /** Emit a discovered form as a JSONL object (-form-extraction). */
    public synchronized void writeForm(LinkExtractor.Form form, String source) {
        if (!opts.jsonl) {
            return;
        }
        StringBuilder in = new StringBuilder("[");
        for (int i = 0; i < form.inputs().size(); i++) {
            if (i > 0) {
                in.append(',');
            }
            in.append('"').append(esc(form.inputs().get(i))).append('"');
        }
        in.append(']');
        String line = "{\"timestamp\":\"" + Instant.now() + "\",\"type\":\"form\","
                + "\"source\":\"" + esc(source) + "\","
                + "\"action\":\"" + esc(form.action()) + "\","
                + "\"method\":\"" + esc(form.method()) + "\","
                + "\"inputs\":" + in + "}";
        System.out.println(line);
        writeFile(line);
    }

    private void writeFile(String s) {
        if (file != null && s != null) {
            try {
                file.write(s);
                file.newLine();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /** True when results are also being written to a file (-o). */
    public synchronized boolean hasFile() {
        return file != null;
    }

    /** Append a raw, already-formatted block to the output file (used by --ai). */
    public synchronized void appendRaw(String s) {
        if (file != null && s != null) {
            try {
                file.write(s);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    public synchronized void error(String url, Throwable t) {
        if ((opts.verbose || opts.debug) && !opts.silent) {
            System.err.println("[err] " + url + " -> " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    public synchronized void info(String msg) {
        if (!opts.silent) {
            System.err.println(msg);
        }
    }

    /** ASCII wordmark — pure ASCII so it renders on any terminal/font. */
    private static final String[] LOGO = {
        "           _               ",
        " ___ _ __ (_)_ __ _____  __",
        "/ __| '_ \\| | '__/ _ \\ \\/ /",
        "\\__ \\ |_) | | | |  __/>  < ",
        "|___/ .__/|_|_|  \\___/_/\\_\\",
        "    |_|",
    };

    /** The ASCII logo as a printable block, used by both the banner and --help. */
    public static String logoBlock(boolean color) {
        String c = color ? BOLD + BRAND : "";
        String r = color ? RESET : "";
        StringBuilder sb = new StringBuilder();
        for (String line : LOGO) {
            sb.append("  ").append(c).append(line).append(r).append('\n');
        }
        return sb.toString();
    }

    /** Startup banner (stderr): ASCII logo, version/tagline, run config. */
    public synchronized void banner(String configLine) {
        if (opts.silent) {
            return;
        }
        String c = opts.noColor ? "" : BOLD + BRAND;
        String d = opts.noColor ? "" : DIM;
        String r = opts.noColor ? "" : RESET;
        System.err.println();
        for (String line : LOGO) {
            System.err.println("  " + c + line + r);
        }
        System.err.println("  " + d + "v" + VERSION + "  -  multithreaded web crawler"
                + "  -  https://github.com/0x9r4ngu/spirex" + r);
        System.err.println();
        System.err.println("  " + d + configLine + r);
        System.err.println();
    }

    /** End-of-run summary with a status-code breakdown (stderr). */
    public synchronized void summary(long elapsedMs, int pages, int urls, int hosts,
                                     int s2xx, int s3xx, int s4xx, int s5xx, int errors) {
        if (opts.silent) {
            return;
        }
        String d = opts.noColor ? "" : DIM;
        String b = opts.noColor ? "" : BOLD;
        String r = opts.noColor ? "" : RESET;
        String secs = String.format("%.1fs", elapsedMs / 1000.0);
        System.err.println();
        System.err.println("   " + b + "[done]" + r + " " + secs
                + d + "   pages " + r + b + pages + r
                + d + "   urls " + r + b + urls + r
                + d + "   hosts " + r + b + hosts + r);
        System.err.println("   " + d + "status" + r
                + "  " + color(GREEN, "2xx " + s2xx)
                + "  " + color(CYAN, "3xx " + s3xx)
                + "  " + color(YELLOW, "4xx " + s4xx)
                + "  " + color(RED, "5xx " + s5xx)
                + "  " + color(GREY, "err " + errors));
    }

    public synchronized void close() {
        if (file != null) {
            try {
                file.flush();
                file.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    private boolean passesFilters(String url, boolean outOfScope) {
        for (Pattern p : matchPats) {
            if (!p.matcher(url).find()) {
                return false;
            }
        }
        for (Pattern p : filterPats) {
            if (p.matcher(url).find()) {
                return false;
            }
        }
        String ext = extensionOf(url);
        if (!extMatch.isEmpty() && !extMatch.contains(ext)) {
            return false;
        }
        if (!extFilter.isEmpty() && extFilter.contains(ext)) {
            return false;
        }
        // Default extension filter — off by default so linked assets still surface.
        if (DEFAULT_FILTER_ON && !opts.noDefaultExtFilter && extMatch.isEmpty()
                && DEFAULT_EXT_FILTER.contains(ext)) {
            return false;
        }
        return true;
    }

    private String plainLine(Result r) {
        if (opts.outputTemplate != null) {
            return applyTemplate(opts.outputTemplate, r);
        }
        String shown = fieldValue(r.url(), opts.field);
        StringBuilder sb = new StringBuilder();
        if (opts.verbose) {
            sb.append('[').append(colorStatus(r.status())).append("] ");
        }
        if (r.outOfScope()) {
            sb.append(color(GREY, "[out] "));
        }
        sb.append(shown);
        if (opts.techDetect && r.tech() != null && !r.tech().isBlank()) {
            sb.append(' ').append(color(GREY, "[" + r.tech() + "]"));
        }
        return sb.toString();
    }

    private String applyTemplate(String tpl, Result r) {
        return tpl
                .replace("{url}", n(r.url()))
                .replace("{status}", Integer.toString(r.status()))
                .replace("{depth}", Integer.toString(r.depth()))
                .replace("{source}", n(r.source()))
                .replace("{tech}", n(r.tech()))
                .replace("{content_type}", n(r.contentType()));
    }

    private String colorStatus(int status) {
        String s = Integer.toString(status);
        if (opts.noColor) {
            return s;
        }
        String c;
        if (status >= 200 && status < 300) {
            c = GREEN;
        } else if (status >= 300 && status < 400) {
            c = CYAN;
        } else if (status >= 400 && status < 500) {
            c = YELLOW;
        } else {
            c = RED;
        }
        return c + s + RESET;
    }

    private String color(String code, String s) {
        return opts.noColor ? s : code + s + RESET;
    }

    private String jsonLine(Result r) {
        StringBuilder sb = new StringBuilder("{");
        Set<String> excl = new LinkedHashSet<>(opts.excludeOutputFields);
        field(sb, excl, "timestamp", Instant.now().toString(), true);
        field(sb, excl, "url", r.url(), true);
        field(sb, excl, "source", r.source(), true);
        field(sb, excl, "content_type", r.contentType(), true);
        rawField(sb, excl, "depth", Integer.toString(r.depth()));
        rawField(sb, excl, "status_code", Integer.toString(r.status()));
        if (opts.techDetect && r.tech() != null && !r.tech().isBlank()) {
            field(sb, excl, "tech", r.tech(), true);
        }
        if (r.outOfScope()) {
            rawField(sb, excl, "out_of_scope", "true");
        }
        if (sb.charAt(sb.length() - 1) == ',') {
            sb.setLength(sb.length() - 1);
        }
        sb.append('}');
        return sb.toString();
    }

    private void field(StringBuilder sb, Set<String> excl, String k, String v, boolean str) {
        if (excl.contains(k)) {
            return;
        }
        sb.append('"').append(k).append("\":");
        if (str) {
            sb.append('"').append(esc(v)).append('"');
        } else {
            sb.append(n(v));
        }
        sb.append(',');
    }

    private void rawField(StringBuilder sb, Set<String> excl, String k, String v) {
        if (excl.contains(k)) {
            return;
        }
        sb.append('"').append(k).append("\":").append(v).append(',');
    }

    /** Implements the -field projections (url, path, fqdn, rdn, ...). */
    static String fieldValue(String url, String field) {
        if (field == null || field.isBlank() || field.equals("url")) {
            return url;
        }
        URI u;
        try {
            u = URI.create(url);
        } catch (Exception e) {
            return url;
        }
        String host = u.getHost() == null ? "" : u.getHost();
        String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        String query = u.getRawQuery();
        String scheme = u.getScheme();
        int port = u.getPort();
        String rurl = scheme + "://" + host + (port == -1 ? "" : ":" + port);
        String file = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "/";

        return switch (field) {
            case "path" -> path;
            case "fqdn" -> host;
            case "rdn" -> Scope.rootDomain(host);
            case "rurl" -> rurl;
            case "qurl" -> query == null ? url : url;
            case "qpath" -> query == null ? path : path + "?" + query;
            case "file" -> file;
            case "ufile" -> rurl + path;
            case "dir" -> dir;
            case "udir" -> rurl + dir;
            case "key" -> query == null ? "" : queryKeys(query);
            case "value" -> query == null ? "" : queryValues(query);
            case "kv" -> query == null ? "" : query;
            default -> url;
        };
    }

    private static String queryKeys(String q) {
        StringBuilder sb = new StringBuilder();
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(eq < 0 ? pair : pair.substring(0, eq));
        }
        return sb.toString();
    }

    private static String queryValues(String q) {
        StringBuilder sb = new StringBuilder();
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(pair.substring(eq + 1));
        }
        return sb.toString();
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    static String extensionOf(String url) {
        String p = url;
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        int slash = p.lastIndexOf('/');
        String last = slash >= 0 ? p.substring(slash + 1) : p;
        int dot = last.lastIndexOf('.');
        return dot < 0 ? "" : last.substring(dot + 1).toLowerCase();
    }
}
