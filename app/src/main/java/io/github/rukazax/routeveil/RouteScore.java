package io.github.rukazax.routeveil;

/** Comparable only for successful same-sized, same-endpoint probes, never direct connectivity. */
public final class RouteScore {
    private RouteScore() {}
    public static double cost(boolean verifiedTor, long bytes, long downloadMs, long checkMs) {
        if (!verifiedTor || bytes != 65536 || downloadMs <= 0 || checkMs < 0) return Double.POSITIVE_INFINITY;
        // Fixed-size end-to-end completion time, with a smaller responsiveness component.
        return downloadMs + 0.25 * checkMs;
    }
    public static boolean shouldReplace(double current, double candidate) {
        return Double.isFinite(candidate) && (!Double.isFinite(current) || candidate < current * 0.85);
    }
    public static int rotationSeconds(String value) {
        try { int n = Integer.parseInt(value); return n >= 60 && n <= 86400 ? n : 0; }
        catch (RuntimeException e) { return 0; }
    }
}
