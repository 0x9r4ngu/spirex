package spirex;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls links out of a response body using regular expressions. A real browser
 * (or a library like jsoup) would build a DOM, but this build is pure-JDK, so
 * we extract the things a crawler cares about directly from the response text:
 *
 * <ul>
 *   <li>link-bearing HTML attributes: href / src / action / formaction / data-*</li>
 *   <li>{@code <base href>} so relative URLs resolve against the right base</li>
 *   <li>{@code <loc>} entries inside sitemap.xml</li>
 *   <li>Disallow / Allow / Sitemap lines inside robots.txt</li>
 *   <li>endpoint-looking strings inside JavaScript (when -jc is set)</li>
 * </ul>
 *
 * Every pattern uses bounded character classes to avoid catastrophic regex
 * backtracking on hostile pages.
 */
public class LinkExtractor {

    // group 1/2/3 = double-quoted / single-quoted / bare attribute value
    private static final Pattern ATTR = Pattern.compile(
            "(?is)\\b(?:href|src|action|formaction|data-url|data-href|poster)\\s*=\\s*"
                    + "(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");

    private static final Pattern BASE = Pattern.compile(
            "(?is)<base\\b[^>]*?\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");

    private static final Pattern SITEMAP_LOC = Pattern.compile(
            "(?is)<loc>\\s*([^<\\s]+)\\s*</loc>");

    // Quoted absolute or root-relative paths inside JS. Conservative on purpose.
    private static final Pattern JS_ENDPOINT = Pattern.compile(
            "[\"']((?:https?:)?//[A-Za-z0-9_.~:/?#\\[\\]@!$&'()*+,;=%-]{3,}"
                    + "|/[A-Za-z0-9_.~:/?#\\[\\]@!$&'()*+,;=%-]{2,})[\"']");

    /**
     * Extract absolute, normalised, http(s) links from a body.
     *
     * @param body        response text
     * @param base        base URI to resolve relative links against
     * @param contentType server content-type (lower cased by caller is fine too)
     * @param jsEnabled   whether JS endpoint extraction is requested (-jc)
     */
    public Set<String> extract(String body, URI base, String contentType, boolean jsEnabled) {
        Set<String> out = new LinkedHashSet<>();
        if (body == null || body.isEmpty()) {
            return out;
        }
        String ct = contentType == null ? "" : contentType.toLowerCase();

        // Honour <base href> if present so relative links resolve correctly.
        URI effectiveBase = base;
        Matcher bm = BASE.matcher(body);
        if (bm.find()) {
            String v = firstGroup(bm);
            if (v != null && !v.isBlank()) {
                try {
                    effectiveBase = base.resolve(sanitize(v));
                } catch (Exception ignored) {
                    // keep original base
                }
            }
        }

        // Standard HTML link attributes.
        Matcher m = ATTR.matcher(body);
        while (m.find()) {
            add(out, effectiveBase, firstGroup(m));
        }

        // sitemap.xml <loc> entries.
        if (ct.contains("xml") || base.getPath().endsWith(".xml")) {
            Matcher sm = SITEMAP_LOC.matcher(body);
            while (sm.find()) {
                add(out, effectiveBase, sm.group(1));
            }
        }

        // robots.txt directives.
        if (ct.contains("text/plain") || base.getPath().endsWith("robots.txt")) {
            for (String line : body.split("\\r?\\n")) {
                line = line.trim();
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = line.substring(0, colon).trim().toLowerCase();
                String val = line.substring(colon + 1).trim();
                if (val.isEmpty()) {
                    continue;
                }
                if (key.equals("disallow") || key.equals("allow")) {
                    add(out, effectiveBase, val);
                } else if (key.equals("sitemap")) {
                    add(out, effectiveBase, val);
                }
            }
        }

        // JavaScript endpoints.
        boolean js = jsEnabled && (ct.contains("javascript") || ct.contains("html") || ct.isEmpty()
                || base.getPath().endsWith(".js"));
        if (js) {
            Matcher jm = JS_ENDPOINT.matcher(body);
            while (jm.find()) {
                add(out, effectiveBase, jm.group(1));
            }
        }

        return out;
    }

    // ---- form extraction (-fx) ----

    private static final Pattern FORM = Pattern.compile("(?is)<form\\b[^>]*>(.*?)</form>");
    private static final Pattern FORM_ACTION = Pattern.compile(
            "(?is)\\baction\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern FORM_METHOD = Pattern.compile(
            "(?is)\\bmethod\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern INPUT_NAME = Pattern.compile(
            "(?is)<(?:input|textarea|select)\\b[^>]*?\\bname\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");

    /** A discovered HTML form. */
    public record Form(String action, String method, java.util.List<String> inputs) {
    }

    /** Extract forms (action, method, input names) for -form-extraction. */
    public java.util.List<Form> extractForms(String body, URI base) {
        java.util.List<Form> forms = new java.util.ArrayList<>();
        if (body == null) {
            return forms;
        }
        Matcher fm = FORM.matcher(body);
        while (fm.find()) {
            String tag = body.substring(fm.start(), Math.min(body.length(), fm.start() + 400));
            String inner = fm.group(1);

            String action = base.toString();
            Matcher am = FORM_ACTION.matcher(tag);
            if (am.find()) {
                String v = firstGroup(am);
                if (v != null && !v.isBlank()) {
                    try {
                        action = base.resolve(sanitize(v)).toString();
                    } catch (Exception ignored) {
                        // keep base
                    }
                }
            }
            String method = "GET";
            Matcher mm = FORM_METHOD.matcher(tag);
            if (mm.find()) {
                String v = firstGroup(mm);
                if (v != null) {
                    method = v.toUpperCase();
                }
            }
            java.util.List<String> inputs = new java.util.ArrayList<>();
            Matcher im = INPUT_NAME.matcher(inner);
            while (im.find()) {
                inputs.add(firstGroup(im));
            }
            forms.add(new Form(action, method, inputs));
        }
        return forms;
    }

    /** Best-effort technology fingerprint from headers and HTML meta (-tech-detect). */
    public static String techDetect(java.net.http.HttpHeaders headers, String body) {
        java.util.LinkedHashSet<String> tech = new java.util.LinkedHashSet<>();
        headers.firstValue("server").ifPresent(tech::add);
        headers.firstValue("x-powered-by").ifPresent(tech::add);
        headers.firstValue("x-generator").ifPresent(tech::add);
        if (body != null) {
            Matcher gm = Pattern.compile(
                    "(?is)<meta\\b[^>]*name\\s*=\\s*[\"']generator[\"'][^>]*content\\s*=\\s*"
                            + "(?:\"([^\"]*)\"|'([^']*)')").matcher(body);
            if (gm.find()) {
                String v = gm.group(1) != null ? gm.group(1) : gm.group(2);
                if (v != null && !v.isBlank()) {
                    tech.add(v.trim());
                }
            }
        }
        return String.join(", ", tech);
    }

    private static String firstGroup(Matcher m) {
        for (int i = 1; i <= m.groupCount(); i++) {
            if (m.group(i) != null) {
                return m.group(i);
            }
        }
        return null;
    }

    private static void add(Set<String> out, URI base, String value) {
        if (value == null) {
            return;
        }
        value = decodeEntities(value.trim());
        if (value.isEmpty()) {
            return;
        }
        String lower = value.toLowerCase();
        if (lower.startsWith("javascript:") || lower.startsWith("mailto:")
                || lower.startsWith("tel:") || lower.startsWith("data:")
                || value.startsWith("#")) {
            return;
        }
        try {
            URI resolved = base.resolve(sanitize(value));
            String norm = Crawler.normalize(resolved.toString());
            if (norm != null) {
                out.add(norm);
            }
        } catch (Exception ignored) {
            // malformed link — skip it
        }
    }

    /**
     * Decode the HTML entities that commonly appear inside attribute values,
     * most importantly {@code &amp;} which otherwise corrupts query strings
     * (e.g. {@code ?a=1&amp;b=2}). Handles named and numeric (decimal/hex)
     * entities; leaves anything unrecognised untouched.
     */
    static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i + 1);
                if (semi > i && semi - i <= 12) {
                    String rep = entity(s.substring(i + 1, semi));
                    if (rep != null) {
                        out.append(rep);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String entity(String e) {
        switch (e) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return " ";
            default: break;
        }
        try {
            if (e.startsWith("#x") || e.startsWith("#X")) {
                return String.valueOf((char) Integer.parseInt(e.substring(2), 16));
            }
            if (e.startsWith("#")) {
                return String.valueOf((char) Integer.parseInt(e.substring(1)));
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    /** Make a raw attribute value safe to hand to URI.resolve. */
    private static String sanitize(String v) {
        v = v.trim();
        // srcset values can carry a "url 2x, url 1x" list — take the first token.
        int comma = v.indexOf(',');
        if (comma > 0) {
            v = v.substring(0, comma).trim();
        }
        int space = v.indexOf(' ');
        if (space > 0) {
            v = v.substring(0, space).trim();
        }
        return v.replace(" ", "%20");
    }
}
