package io.github.rukazax.routeveil;
import org.junit.Test;
import static org.junit.Assert.*;
public class TransportPlanTest {
    private final String snow="snowflake 192.0.2.3:1 2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://example.org/ fronts=example.org";
    private final String obfs="obfs4 192.0.2.1:443 2B280B23E1107BB62ABFC40DDCC8824814F80A72 cert=example iat-mode=0";
    @Test public void autoNeverIncludesDirect() {
        for(TransportPlan.Profile p:TransportPlan.create("auto","",snow,obfs))assertTrue(p.bridge());
    }
    @Test(expected=IllegalArgumentException.class) public void malformedNeverBecomesDirect(){TransportPlan.create("auto","invalid",snow,obfs);}
    @Test(expected=IllegalArgumentException.class) public void missingWebTunnelFails(){TransportPlan.create("webtunnel","",snow,obfs);}
    @Test public void explicitDirectOnly(){assertNull(TransportPlan.create("direct","",snow,obfs).get(0).lines);}
    @Test public void mixedTransportsAreSeparateProfiles(){
        java.util.List<TransportPlan.Profile> p=TransportPlan.create("auto",snow+"\n"+obfs,snow,obfs);
        assertEquals("snowflake",p.get(0).type);assertEquals("obfs4",p.get(1).type);assertTrue(p.size()<=4);
        assertFalse(p.get(0).lines.contains("obfs4"));
    }
    @Test(expected=IllegalArgumentException.class) public void unsupportedSqsRejected(){TransportPlan.validateAndGroup(snow+" sqs-queue-url=https://example.org/");}
    @Test(expected=IllegalArgumentException.class) public void missingCertRejected(){TransportPlan.validateAndGroup("obfs4 192.0.2.1:443");}
    @Test public void commentsAndBridgePrefix(){assertTrue(TransportPlan.validateAndGroup("# comment\nBridge "+obfs).containsKey("obfs4"));}
}
