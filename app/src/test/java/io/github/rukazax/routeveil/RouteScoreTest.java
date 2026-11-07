package io.github.rukazax.routeveil;
import org.junit.Test;
import static org.junit.Assert.*;
public class RouteScoreTest {
    @Test public void rejectsDirectAndIncompleteProbes() {
        assertEquals(Double.POSITIVE_INFINITY, RouteScore.cost(false,65536,1,1),0);
        assertEquals(Double.POSITIVE_INFINITY, RouteScore.cost(true,65535,1,1),0);
        assertEquals(Double.POSITIVE_INFINITY, RouteScore.cost(true,65536,0,1),0);
    }
    @Test public void measuresCompletionAndLatency() { assertEquals(1250,RouteScore.cost(true,65536,1000,1000),0); }
    @Test public void requiresHysteresis() {
        assertFalse(RouteScore.shouldReplace(1000,900));
        assertTrue(RouteScore.shouldReplace(1000,800));
        assertFalse(RouteScore.shouldReplace(1000,Double.POSITIVE_INFINITY));
        assertTrue(RouteScore.shouldReplace(Double.POSITIVE_INFINITY,1000));
    }
    @Test public void validatesRotationBounds() {
        assertEquals(0,RouteScore.rotationSeconds("3"));
        assertEquals(0,RouteScore.rotationSeconds("bad"));
        assertEquals(0,RouteScore.rotationSeconds("999999999999999"));
        assertEquals(60,RouteScore.rotationSeconds("60"));
        assertEquals(0,RouteScore.rotationSeconds("86401"));
    }
}
