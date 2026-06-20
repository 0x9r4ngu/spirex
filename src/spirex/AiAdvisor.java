package spirex;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Optional post-crawl helper (--ai). Sends the most representative crawled
 * endpoints to Google's Gemini API and prints, in plain text, which
 * vulnerability is worth testing on each — a quick "where to start" for manual
 * testing. Pure JDK: builds the request JSON and parses the response by hand.
 */
public class AiAdvisor {

    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    /** Hard cap on endpoints sent to the model — keeps the prompt small and cheap. */
    private static final int MAX_ENDPOINTS = 60;

    /** Path segments that are almost certainly IDs; folded so /user/1 == /user/2. */
    private static final Pattern ID_SEG =
            Pattern.compile("^(\\d+|[0-9a-fA-F]{8,}|[A-Za-z0-9_-]{20,})$");

    /** Endpoints that tend to be interesting are surfaced first. */
    private static final Pattern INTERESTING = Pattern.compile(
            "(?i)(login|signin|sign-in|logout|register|signup|auth|oauth|sso|token|"
            + "search|query|q=|file|download|upload|import|export|redirect|return|url=|"
            + "next=|admin|account|profile|user|api|graphql|id=|reset|password|email|"
            + "cmd|exec|debug|preview|page=|path=|dir=|include|template|callback)");

    private AiAdvisor() {
    }

    // ---- terminal styling (suppressed under --no-color) ----
    private static final String RESET = "[0m";
    private static final String BOLD = "[1m";
    private static final String DIM = "[2m";
    private static final String CYAN = "[36m";
    private static final String YELLOW = "[33m";
    /** Brand red (#cc0000), matching the spirex banner. */
    private static final String BRAND = "[38;2;204;0;0m";

    /** Bug-class tags inferred from the advice text, for an at-a-glance label. */
    private static final Map<String, Pattern> TAGS = new LinkedHashMap<>();
    static {
        TAGS.put("SQLi", Pattern.compile("(?i)sql injection|sqli"));
        TAGS.put("XSS", Pattern.compile("(?i)\\bxss\\b|cross.site scripting"));
        TAGS.put("IDOR", Pattern.compile("(?i)\\bidor\\b|insecure direct object|broken object"));
        TAGS.put("SSRF", Pattern.compile("(?i)\\bssrf\\b|server.side request"));
        TAGS.put("LFI/Path", Pattern.compile("(?i)\\blfi\\b|local file inclusion|path traversal|directory traversal"));
        TAGS.put("RCE", Pattern.compile("(?i)\\brce\\b|remote code|command injection"));
        TAGS.put("Open-Redirect", Pattern.compile("(?i)open redirect"));
        TAGS.put("CSRF", Pattern.compile("(?i)\\bcsrf\\b|cross.site request"));
        TAGS.put("Auth-Bypass", Pattern.compile("(?i)auth(entication)? bypass|credential stuffing"));
        TAGS.put("SSTI", Pattern.compile("(?i)\\bssti\\b|template injection"));
        TAGS.put("Upload", Pattern.compile("(?i)file upload|unrestricted upload"));
        TAGS.put("Enum", Pattern.compile("(?i)enumeration|user enumeration"));
    }

    /**
     * Build the endpoint shortlist, query Gemini, and print the advice to stderr
     * (advisory meta-output, kept off stdout so piped URL output stays clean).
     */
    public static void run(Collection<String> crawledUrls, Options opts, String target,
                           OutputWriter out) {
        List<String> endpoints = selectEndpoints(crawledUrls, MAX_ENDPOINTS);
        boolean color = !opts.noColor;
        if (endpoints.isEmpty()) {
            System.err.println(c(color, DIM, "  [ai] no endpoints to analyse"));
            return;
        }
        System.err.println();
        System.err.println("  " + c(color, BOLD + BRAND, "spirex ai")
                + "  " + c(color, DIM, "thinking with " + opts.aiModel + "..."));
        try {
            String advice = ask(buildPrompt(target, endpoints), opts);
            // Build once, in colour, then print to stderr and write a plain copy to -o.
            String block = render(advice, opts.aiModel, endpoints.size(), color);
            System.err.print(block);
            if (out != null && out.hasFile()) {
                if (opts.jsonl) {
                    // Keep JSONL valid: one structured record instead of the pretty block.
                    out.appendRaw("{\"type\":\"ai_analysis\",\"model\":\""
                            + jsonEscape(opts.aiModel) + "\",\"text\":\""
                            + jsonEscape(advice.strip()) + "\"}\n");
                } else {
                    out.appendRaw("\n" + (color ? stripAnsi(block) : block));
                }
            }
        } catch (Exception e) {
            System.err.println("  " + c(color, YELLOW, "[ai] request failed: ")
                    + e.getMessage());
        }
    }

    /** Render the model's "URL - advice" blocks as a tagged, wrapped list. */
    private static String render(String advice, String model, int count, boolean color) {
        List<String[]> findings = parseFindings(advice);
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        sb.append("  ").append(c(color, BOLD + BRAND, "spirex ai"))
                .append("  ").append(c(color, DIM, model + " · " + count + " endpoints · "
                        + findings.size() + " worth a look")).append('\n');
        sb.append("  ").append(c(color, DIM, "─".repeat(58))).append('\n');

        if (findings.isEmpty()) {
            // Model didn't follow the format — show its text rather than nothing.
            for (String line : advice.strip().split("\n")) {
                sb.append("    ").append(line).append('\n');
            }
            return sb.toString();
        }

        int width = wrapWidth();
        int n = 1;
        for (String[] f : findings) {
            String tags = tagsFor(f[1]);
            sb.append('\n');
            sb.append("  ").append(c(color, BRAND + BOLD, String.format("%2d", n++)))
                    .append("  ").append(c(color, CYAN, f[0]));
            if (!tags.isEmpty()) {
                sb.append("  ").append(c(color, YELLOW, tags));
            }
            sb.append('\n');
            for (String line : wrap(f[1], width)) {
                sb.append("      ").append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\\[[0-9;]*m", "");
    }

    /** Split the response into (url, advice) pairs on the first " - " of each block. */
    static List<String[]> parseFindings(String advice) {
        List<String[]> out = new ArrayList<>();
        for (String block : advice.strip().split("\\n\\s*\\n")) {
            String b = block.replaceAll("\\s+", " ").strip();
            if (b.isEmpty()) {
                continue;
            }
            int sep = firstSeparator(b);
            if (sep < 0) {
                continue;
            }
            String url = b.substring(0, sep).strip().replaceFirst("^[-*\\d.\\s]+", "");
            String text = b.substring(sep).replaceFirst("^\\s*[-–—]\\s*", "").strip();
            if (!url.isEmpty() && !text.isEmpty()) {
                out.add(new String[]{url, text});
            }
        }
        return out;
    }

    /** Index of the " - "/" – "/" — " that separates the URL from the advice. */
    private static int firstSeparator(String s) {
        for (int i = 1; i < s.length() - 1; i++) {
            char d = s.charAt(i);
            if ((d == '-' || d == '–' || d == '—')
                    && s.charAt(i - 1) == ' ' && s.charAt(i + 1) == ' ') {
                return i;
            }
        }
        return -1;
    }

    private static String tagsFor(String text) {
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, Pattern> e : TAGS.entrySet()) {
            if (e.getValue().matcher(text).find()) {
                hits.add(e.getKey());
            }
            if (hits.size() == 3) {
                break;
            }
        }
        return hits.isEmpty() ? "" : "[" + String.join(" · ", hits) + "]";
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static int wrapWidth() {
        String cols = System.getenv("COLUMNS");
        if (cols != null) {
            try {
                int w = Integer.parseInt(cols.trim()) - 8;
                return Math.max(48, Math.min(w, 100));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 84;
    }

    private static String c(boolean color, String code, String s) {
        return color ? code + s + RESET : s;
    }

    /**
     * Pick the most representative endpoints: every distinct parameterised URL
     * first (those carry the testable surface), then a sample of other paths,
     * with look-alike paths (numeric/hash IDs) folded to one. Pure logic so it
     * can be unit-tested without a network.
     */
    static List<String> selectEndpoints(Collection<String> urls, int max) {
        // Keep one representative per (folded-path + sorted query-keys) signature,
        // preferring parameterised URLs.
        Map<String, String> withParams = new LinkedHashMap<>();
        Map<String, String> noParams = new LinkedHashMap<>();
        for (String u : urls) {
            if (u == null || u.isBlank()) {
                continue;
            }
            int q = u.indexOf('?');
            String sig = signature(u);
            if (q >= 0 && q < u.length() - 1) {
                withParams.putIfAbsent(sig, u);
            } else {
                noParams.putIfAbsent(sig, u);
            }
        }
        List<String> ordered = new ArrayList<>();
        // Parameterised endpoints first, interesting ones ahead of the rest.
        addSorted(ordered, withParams.values());
        addSorted(ordered, noParams.values());
        if (ordered.size() > max) {
            return new ArrayList<>(ordered.subList(0, max));
        }
        return ordered;
    }

    private static void addSorted(List<String> out, Collection<String> in) {
        List<String> interesting = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String u : in) {
            (INTERESTING.matcher(u).find() ? interesting : rest).add(u);
        }
        out.addAll(interesting);
        out.addAll(rest);
    }

    /** Path with ID-like segments folded, plus sorted query-parameter names. */
    private static String signature(String url) {
        try {
            URI u = URI.create(url);
            String path = u.getRawPath() == null ? "/" : u.getRawPath();
            StringBuilder sb = new StringBuilder();
            for (String seg : path.split("/")) {
                if (seg.isEmpty()) {
                    continue;
                }
                sb.append('/').append(ID_SEG.matcher(seg).matches() ? "{}" : seg);
            }
            if (sb.length() == 0) {
                sb.append('/');
            }
            String query = u.getRawQuery();
            if (query != null && !query.isEmpty()) {
                Set<String> keys = new java.util.TreeSet<>();
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    keys.add(eq < 0 ? pair : pair.substring(0, eq));
                }
                sb.append('?').append(String.join("&", keys));
            }
            return sb.toString();
        } catch (Exception e) {
            return url;
        }
    }

    private static String buildPrompt(String target, List<String> endpoints) {
        StringBuilder list = new StringBuilder();
        for (String e : endpoints) {
            list.append(e).append('\n');
        }
        return "You are a web application penetration testing assistant. These URLs were "
                + "discovered by crawling " + target + ".\n\n"
                + "Pick only the most common and most interesting endpoints (especially ones "
                + "with query parameters, or that look like login, search, upload, file, "
                + "redirect, account, admin or API endpoints). Do NOT list every URL — focus "
                + "on the most representative ones.\n\n"
                + "For each endpoint you pick, in no more than 2 sentences, say which "
                + "vulnerability is worth testing and why. If an endpoint takes a parameter, "
                + "name the parameter and the bug class it invites (for example SQLi, XSS, "
                + "IDOR, SSRF, open redirect, LFI/path traversal).\n\n"
                + "Output PLAIN TEXT only. Do NOT use markdown: no asterisks, backticks, "
                + "hashes, tables or bullet characters. Write one endpoint per block as: "
                + "URL then a dash then the advice.\n\n"
                + "URLs:\n" + list;
    }

    private static String ask(String prompt, Options opts) throws Exception {
        String body = "{\"contents\":[{\"parts\":[{\"text\":\"" + jsonEscape(prompt)
                + "\"}]}]}";
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(String.format(ENDPOINT, opts.aiModel)))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("X-goog-api-key", opts.aiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            String msg = extractError(resp.body());
            throw new IllegalStateException("HTTP " + resp.statusCode()
                    + (msg.isEmpty() ? "" : " - " + msg));
        }
        String text = extractText(resp.body());
        if (text.isEmpty()) {
            throw new IllegalStateException("empty response from model");
        }
        return text;
    }

    /** Concatenate every "text" string under candidates[].content.parts[]. */
    static String extractText(String json) {
        StringBuilder out = new StringBuilder();
        Pattern p = Pattern.compile("\"text\"\\s*:\\s*\"");
        var m = p.matcher(json);
        while (m.find()) {
            String s = readJsonString(json, m.end());
            if (s != null) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(s);
            }
        }
        return out.toString();
    }

    /** Pull error.message out of a Gemini error payload, best-effort. */
    static String extractError(String json) {
        Pattern p = Pattern.compile("\"message\"\\s*:\\s*\"");
        var m = p.matcher(json);
        if (m.find()) {
            String s = readJsonString(json, m.end());
            if (s != null) {
                return s;
            }
        }
        return "";
    }

    /** Read a JSON string body starting just after the opening quote, unescaping. */
    private static String readJsonString(String s, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (++i >= s.length()) {
                    break;
                }
                char n = s.charAt(i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(
                                        s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {
                                sb.append(n);
                            }
                        }
                    }
                    default -> sb.append(n);
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
