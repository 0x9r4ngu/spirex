package spirex;

import java.net.InetAddress;

/**
 * Small IPv4 helpers for the -e exclude filters (ip / cidr / private-ips).
 * Kept deliberately simple — IPv4 only, which covers the common case.
 */
public final class IpUtils {

    private IpUtils() {
    }

    public static boolean isIp(String s) {
        String[] parts = s.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            try {
                int v = Integer.parseInt(p);
                if (v < 0 || v > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    public static boolean isCidr(String s) {
        int slash = s.indexOf('/');
        if (slash < 0) {
            return false;
        }
        String ip = s.substring(0, slash);
        try {
            int bits = Integer.parseInt(s.substring(slash + 1));
            return isIp(ip) && bits >= 0 && bits <= 32;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static boolean isPrivate(InetAddress addr) {
        return addr.isSiteLocalAddress() || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress() || addr.isAnyLocalAddress();
    }

    public static Cidr parseCidr(String s) {
        int slash = s.indexOf('/');
        String ip = s.substring(0, slash);
        int bits = Integer.parseInt(s.substring(slash + 1));
        return new Cidr(toInt(ip), bits);
    }

    private static int toInt(String ip) {
        String[] parts = ip.split("\\.");
        int v = 0;
        for (String p : parts) {
            v = (v << 8) | (Integer.parseInt(p) & 0xff);
        }
        return v;
    }

    /** An IPv4 CIDR range. */
    public record Cidr(int network, int bits) {
        public boolean contains(InetAddress addr) {
            byte[] b = addr.getAddress();
            if (b.length != 4) {
                return false; // IPv6 not handled
            }
            int ip = ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16)
                    | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
            if (bits == 0) {
                return true;
            }
            int mask = (int) (0xFFFFFFFFL << (32 - bits));
            return (ip & mask) == (network & mask);
        }
    }
}
