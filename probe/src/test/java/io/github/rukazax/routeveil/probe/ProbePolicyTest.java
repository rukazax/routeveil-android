package io.github.rukazax.routeveil.probe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JUnit port of the dependency-free main() self-test. */
public class ProbePolicyTest {
    @Test public void requestIdBounds() {
        assertTrue(ProbePolicy.validRequestId("probe-1"));
        assertFalse(ProbePolicy.validRequestId(null));
        assertFalse(ProbePolicy.validRequestId(""));
        assertFalse(ProbePolicy.validRequestId("x".repeat(129)));
    }

    @Test public void challengeRequiresHtmlMarkers() {
        assertFalse(ProbePolicy.isChallenge("text/html", "<html>403 Forbidden</html>"));
        assertFalse(ProbePolicy.isChallenge("application/json", "{\"captcha\":true}"));
        assertFalse(ProbePolicy.isChallenge("text/plain", "captcha required"));
        assertTrue(ProbePolicy.isChallenge("text/html", "<html><div class='g-recaptcha'></div></html>"));
        assertTrue(ProbePolicy.isChallenge("text/html", "<script src='/cdn-cgi/challenge-platform/cf-chl-test'></script>"));
        assertTrue(ProbePolicy.isChallenge(null, "<!DOCTYPE html><html>Verify you are human</html>"));
    }

    @Test public void ipLiteralValidation() {
        for (String ip : new String[]{"1.2.3.4", "255.255.255.255", "::", "::1", "2001:db8::1234",
                "::ffff:192.0.2.1", "1:2:3:4:5:6:7:8"}) assertTrue(ip, ProbePolicy.isIpLiteral(ip));
        for (String ip : new String[]{"", "256.1.1.1", "01.2.3.4", "127.1", "example.com",
                "https://example.com/?secret", "1:2:3", ":::1", ":1::2", "1::2:", "1::2::3",
                "1:2:3:4:5:6:7:8::", "fe80::1%eth0", "[::1]"}) assertFalse(ip, ProbePolicy.isIpLiteral(ip));
    }

    @Test public void fixedCaps() {
        assertEquals(16384, ProbePolicy.CHECK_LIMIT);
        assertEquals(65536, ProbePolicy.DOWNLOAD_BYTES);
        assertTrue(ProbePolicy.DEADLINE_MS <= 60000);
        assertEquals("https://check.torproject.org/", ProbePolicy.CHALLENGE_URL);
    }
}
