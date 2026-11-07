package io.github.rukazax.routeveil.probe;

/** Dependency-free policy checks: run this main with the two pure Java sources. */
public final class ProbePolicySelfTest {
    public static void main(String[] args) {
        check(ProbePolicy.validRequestId("probe-1"), "request id");
        check(!ProbePolicy.validRequestId(null), "missing id");
        check(!ProbePolicy.validRequestId(""), "empty id");
        check(!ProbePolicy.validRequestId("x".repeat(129)), "long id");
        check(!ProbePolicy.isChallenge("text/html", "<html>403 Forbidden</html>"), "403 not a challenge");
        check(!ProbePolicy.isChallenge("application/json", "{\"captcha\":true}"), "JSON not HTML");
        check(!ProbePolicy.isChallenge("text/plain", "captcha required"), "plain text not HTML");
        check(ProbePolicy.isChallenge("text/html", "<html><div class='g-recaptcha'></div></html>"), "captcha");
        check(ProbePolicy.isChallenge("text/html", "<script src='/cdn-cgi/challenge-platform/cf-chl-test'></script>"), "challenge script");
        check(ProbePolicy.isChallenge(null, "<!DOCTYPE html><html>Verify you are human</html>"), "HTML sniffing");
        for (String ip : new String[] {"1.2.3.4", "255.255.255.255", "::", "::1", "2001:db8::1234", "::ffff:192.0.2.1", "1:2:3:4:5:6:7:8"}) {
            check(ProbePolicy.isIpLiteral(ip), "valid literal " + ip);
        }
        for (String ip : new String[] {"", "256.1.1.1", "01.2.3.4", "127.1", "example.com", "https://example.com/?secret", "1:2:3", ":::1", ":1::2", "1::2:", "1::2::3", "1:2:3:4:5:6:7:8::", "fe80::1%eth0", "[::1]"}) {
            check(!ProbePolicy.isIpLiteral(ip), "invalid literal " + ip);
        }
        check(ProbePolicy.CHECK_LIMIT == 16384, "check cap");
        check(ProbePolicy.DOWNLOAD_BYTES == 65536, "download cap");
        check(ProbePolicy.DEADLINE_MS <= 60000, "deadline cap");
        System.out.println("ProbePolicySelfTest: PASS");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
