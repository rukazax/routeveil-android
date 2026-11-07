package io.github.rukazax.routeveil.probe;

import java.util.Locale;

/** Pure Java response policy; never resolves an IP or accepts a caller URL. */
final class ProbePolicy {
    static final String HOST_PACKAGE = "io.github.rukazax.routeveil";
    static final String PERMISSION = HOST_PACKAGE + ".permission.PROBE";
    static final String CHECK_URL = "https://check.torproject.org/api/ip";
    static final String CHALLENGE_URL = "https://check.torproject.org/";
    static final String DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=65536";
    static final int CHECK_LIMIT = 16 * 1024;
    static final int DOWNLOAD_BYTES = 65536;
    static final long DEADLINE_MS = 60000;
    static final int IO_TIMEOUT_MS = 15000;

    private ProbePolicy() {}

    static boolean validRequestId(String id) {
        return id != null && !id.isEmpty() && id.length() <= 128;
    }

    static boolean isChallenge(String contentType, String body) {
        String text = body.toLowerCase(Locale.ROOT);
        boolean html = text.contains("<html") || text.contains("<!doctype html")
                || (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/html")
                    && (text.contains("<form") || text.contains("<script") || text.contains("<div")));
        return html && (text.contains("g-recaptcha") || text.contains("h-captcha")
                || text.contains("hcaptcha") || text.contains("cf-chl-")
                || text.contains("cf-turnstile") || text.contains("captcha")
                || text.contains("verify you are human")
                || text.contains("checking your browser")
                || text.contains("id=\"challenge-form\"")
                || text.contains("id='challenge-form'"));
    }

    static boolean isIpLiteral(String ip) {
        if (ip == null || ip.length() > 45 || ip.isEmpty()) return false;
        if (!ip.contains(":")) return ipv4(ip);
        if (!ip.matches("[0-9a-fA-F:.]+")) return false;
        int compressed = ip.indexOf("::");
        if (compressed >= 0 && ip.indexOf("::", compressed + 2) >= 0) return false;
        if ((ip.startsWith(":") && !ip.startsWith("::"))
                || (ip.endsWith(":") && !ip.endsWith("::"))) return false;
        String[] groups = ip.split(":", -1);
        int count = 0;
        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            if (group.isEmpty()) continue;
            if (group.contains(".")) {
                if (i != groups.length - 1 || !ipv4(group)) return false;
                count += 2;
            } else {
                if (group.length() > 4 || !group.matches("[0-9a-fA-F]+")) return false;
                count++;
            }
        }
        // Triple-colon is not valid compression, including at either edge.
        if (ip.contains(":::")) return false;
        return compressed >= 0 ? count < 8 : count == 8;
    }

    private static boolean ipv4(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}") || (part.length() > 1 && part.startsWith("0"))) return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }
}
