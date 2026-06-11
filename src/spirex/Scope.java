package spirex;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Decides whether a discovered URL may be crawled. Implements the full scope
 * model: field-scope (dn/rdn/fqdn/custom regex), -no-scope, crawl-scope /
 * crawl-out-scope regex lists, and the -e exclude filters
 * (cdn, private-ips, cidr, ip, regex).
 */
public class Scope {

    private enum Mode { STRICT, SUBS, ALL }

    private static final Set<String> SECOND_LEVEL = Set.of(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk",
            "com.au", "net.au", "org.au", "gov.au",
            "co.in", "net.in", "org.in", "co.jp", "co.nz",
            "com.br", "com.cn", "com.mx", "com.sg", "co.za");

    /** A few common CDN suffixes for the -e cdn filter. */
    private static final Set<String> CDN_SUFFIXES = Set.of(
            "cloudfront.net", "akamai.net", "akamaiedge.net", "akamaihd.net",
            "fastly.net", "cloudflare.net", "cdn.cloudflare.net", "edgekey.net",
            "edgesuite.net", "azureedge.net", "stackpathcdn.com", "cdn77.org",
            "googleusercontent.com", "gstatic.com", "jsdelivr.net", "unpkg.com");

    private final Mode mode;
    private final String seedHost;
    private final String seedRoot;
    private final List<Pattern> include = new ArrayList<>();
    private final List<Pattern> exclude = new ArrayList<>();
    private final List<Exclusion> excludeFilters = new ArrayList<>();
    private final java.util.Map<String, InetAddress> dnsCache = new ConcurrentHashMap<>();

    public Scope(Options opts, String seedHost) {
        this.seedHost = seedHost == null ? "" : seedHost.toLowerCase();
        this.seedRoot = rootDomain(this.seedHost);

        // crawl-scope / crawl-out-scope regex lists
        for (String r : opts.crawlScope) {
            include.add(Pattern.compile(r));
        }
        for (String r : opts.crawlOutScope) {
            exclude.add(Pattern.compile(r));
        }

        // field-scope: keyword or custom regex
        String fs = opts.fieldScope == null ? "rdn" : opts.fieldScope.trim();
        if (opts.noScope) {
            this.mode = Mode.ALL;
        } else {
            switch (fs.toLowerCase()) {
                case "rdn" -> this.mode = Mode.SUBS;
                case "dn", "fqdn" -> this.mode = Mode.STRICT;
                default -> {
                    // treat as a custom scope regex
                    this.mode = Mode.ALL;
                    include.add(Pattern.compile(fs));
                }
            }
        }

        // -e exclude filters
        for (String e : opts.exclude) {
            excludeFilters.add(Exclusion.parse(e.trim()));
        }
    }

    public static String rootDomain(String host) {
        if (host == null || host.isEmpty()) {
            return host;
        }
        host = host.toLowerCase();
        String[] parts = host.split("\\.");
        if (parts.length <= 2) {
            return host;
        }
        String lastTwo = parts[parts.length - 2] + "." + parts[parts.length - 1];
        if (SECOND_LEVEL.contains(lastTwo) && parts.length >= 3) {
            return parts[parts.length - 3] + "." + lastTwo;
        }
        return lastTwo;
    }

    /** Host-and-regex scope, ignoring the -e exclude filters. */
    public boolean inScope(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase();
        String full = uri.toString();

        for (Pattern p : exclude) {
            if (p.matcher(full).find()) {
                return false;
            }
        }
        if (!include.isEmpty()) {
            boolean matched = false;
            for (Pattern p : include) {
                if (p.matcher(full).find()) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
            // include regex satisfied; still apply host mode unless ALL
        }

        return switch (mode) {
            case ALL -> true;
            case STRICT -> host.equals(seedHost);
            case SUBS -> host.equals(seedRoot) || host.endsWith("." + seedRoot);
        };
    }

    /** True if the host matches any -e exclude filter (cdn/private-ips/cidr/ip/regex). */
    public boolean isExcluded(URI uri) {
        if (excludeFilters.isEmpty()) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase();
        String full = uri.toString();
        // Resolve DNS lazily — only the private/cidr filters need an address.
        InetAddress addr = null;
        boolean resolved = false;
        for (Exclusion ex : excludeFilters) {
            if (ex.needsAddr() && !resolved) {
                addr = resolve(host);
                resolved = true;
            }
            if (ex.matches(host, addr, full)) {
                return true;
            }
        }
        return false;
    }

    private InetAddress resolve(String host) {
        return dnsCache.computeIfAbsent(host, h -> {
            try {
                return InetAddress.getByName(h);
            } catch (Exception e) {
                return null;
            }
        });
    }

    // ---- exclude filter model ----

    private record Exclusion(String type, String raw, Pattern regex, IpUtils.Cidr cidr) {

        static Exclusion parse(String s) {
            if (s.equalsIgnoreCase("cdn")) {
                return new Exclusion("cdn", s, null, null);
            }
            if (s.equalsIgnoreCase("private-ips") || s.equalsIgnoreCase("private-ip")) {
                return new Exclusion("private", s, null, null);
            }
            if (s.contains("/") && IpUtils.isCidr(s)) {
                return new Exclusion("cidr", s, null, IpUtils.parseCidr(s));
            }
            if (IpUtils.isIp(s)) {
                return new Exclusion("ip", s, null, null);
            }
            return new Exclusion("regex", s, Pattern.compile(s), null);
        }

        boolean needsAddr() {
            return type.equals("private") || type.equals("cidr");
        }

        boolean matches(String host, InetAddress addr, String url) {
            return switch (type) {
                case "cdn" -> {
                    for (String suf : CDN_SUFFIXES) {
                        if (host.endsWith(suf)) {
                            yield true;
                        }
                    }
                    yield false;
                }
                case "private" -> addr != null && IpUtils.isPrivate(addr);
                case "ip" -> host.equals(raw) || (addr != null && addr.getHostAddress().equals(raw));
                case "cidr" -> addr != null && cidr != null && cidr.contains(addr);
                case "regex" -> regex.matcher(url).find() || regex.matcher(host).find();
                default -> false;
            };
        }
    }
}
