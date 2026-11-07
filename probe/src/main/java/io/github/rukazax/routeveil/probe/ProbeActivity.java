package io.github.rukazax.routeveil.probe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Informational only: opening this activity never starts network work. */
public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding, padding, padding);
        text.setTextSize(18);
        text.setText("Routeveil Probe\n\n"
                + "An on-demand diagnostic helper for Routeveil. It does not establish a VPN.\n\n"
                + "Only the matching, same-signed Routeveil app can request a test. "
                + "A test requires this helper to be routed through an active VPN, checks "
                + "the Tor Project endpoint, then downloads 64 KiB from Cloudflare only "
                + "if the endpoint confirms a Tor exit.\n\n"
                + "No telemetry, saved results, background polling, or WebView. "
                + "Network endpoints necessarily observe the connection's exit IP.");
        setContentView(text);
    }
}
