package io.github.rukazax.routeveil;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Framework-only UI. Tunnel state belongs to TunnelService, never to this screen. */
public final class MainActivity extends Activity {
    private static final int REQUEST_VPN = 40;
    private static final int REQUEST_NOTIFICATIONS = 41;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private boolean loadingApps;
    private boolean dark;
    private int foreground;
    private int muted;
    private int surface;
    private int accent;
    private SharedPreferences preferences;
    private Spinner transport;
    private EditText bridges;
    private EditText rotation;
    private TextView pill;
    private TextView status;
    private TextView detail;
    private TextView directSummary;
    private TextView challengeHint;
    private Button connect;
    private Button rotate;
    private Button optimize;
    private Button chooseApps;
    private Button shareDiagnostics;
    private Button clearDiagnostics;
    private boolean diagnosticsBusy;
    private Set<String> draftDirect;
    private String pendingAction;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refreshStatus();
            main.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? R.style.AppTheme_Dark : R.style.AppTheme);
        super.onCreate(savedInstanceState);
        Settings.initDefaults(this);
        preferences = Settings.prefs(this);
        draftDirect = Settings.directPackages(this);
        foreground = Color.parseColor(dark ? "#EFF3EF" : "#202A25");
        muted = Color.parseColor(dark ? "#BAC7BF" : "#56665C");
        surface = Color.parseColor(dark ? "#202923" : "#FFFFFF");
        accent = Color.parseColor(dark ? "#95D3BC" : "#397C68");
        buildScreen();
        loadDraft(savedInstanceState);
        refreshStatus();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(Color.parseColor(dark ? "#131715" : "#F3F4F1"));
        // Target 36 enforces edge-to-edge. Keep every control clear of bars, cutouts and IME.
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        LinearLayout page = column();
        page.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_routeveil);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout titles = column();
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(text(R.string.app_name, 30, true));
        titles.addView(text(R.string.app_tagline, 14, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(header);

        LinearLayout warning = card(page, R.string.experimental_title);
        warning.addView(text(R.string.experimental_warning, 14, false));

        LinearLayout connection = card(page, R.string.connection_title);
        pill = text(R.string.status_disconnected, 13, true);
        pill.setPadding(dp(12), dp(7), dp(12), dp(7));
        connection.addView(pill, new LinearLayout.LayoutParams(-2, -2));
        status = text(R.string.status_idle, 19, true);
        status.setPadding(0, dp(12), 0, dp(4));
        connection.addView(status);
        detail = text(R.string.status_detail_idle, 14, false);
        detail.setTextIsSelectable(true);
        connection.addView(detail);
        // Stack buttons: long translations remain readable with large system fonts.
        connect = button(connection, R.string.connect, v -> beginConnection(TunnelService.ACTION_START));
        button(connection, R.string.disconnect, v -> {
            pendingAction = null;
            sendServiceAction(TunnelService.ACTION_STOP, false);
            refreshStatus();
        });
        rotate = button(connection, R.string.new_identity,
                v -> sendServiceAction(TunnelService.ACTION_ROTATE, false));
        optimize = button(connection, R.string.optimize, v -> new AlertDialog.Builder(this)
                .setTitle(R.string.optimize_consent_title)
                .setMessage(R.string.optimize_consent)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.start_test,
                        (dialog, which) -> beginConnection(TunnelService.ACTION_OPTIMIZE)).show());
        connection.addView(text(R.string.optimize_hint, 13, false));

        LinearLayout routing = card(page, R.string.transport_title);
        transport = new Spinner(this);
        transport.setContentDescription(getString(R.string.transport_title));
        String[] labels = {getString(R.string.transport_auto), getString(R.string.transport_snowflake),
                getString(R.string.transport_obfs4), getString(R.string.transport_webtunnel),
                getString(R.string.transport_direct)};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        transport.setAdapter(adapter);
        routing.addView(transport, new LinearLayout.LayoutParams(-1, dp(56)));
        routing.addView(text(R.string.transport_hint, 14, false));
        TextView bridgeLabel = text(R.string.bridges_title, 17, true);
        bridgeLabel.setPadding(0, dp(20), 0, dp(4));
        routing.addView(bridgeLabel);
        bridges = new EditText(this);
        bridges.setId(View.generateViewId());
        bridgeLabel.setLabelFor(bridges.getId());
        bridges.setHint(R.string.bridges_hint);
        bridges.setTextSize(14);
        bridges.setTypeface(Typeface.MONOSPACE);
        bridges.setTextColor(foreground);
        bridges.setHintTextColor(muted);
        bridges.setGravity(Gravity.TOP | Gravity.START);
        bridges.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        bridges.setMinLines(4);
        // Do not trim, split, normalize, validate or log the user's bridge text here.
        bridges.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        bridges.setSaveEnabled(false);
        routing.addView(bridges, new LinearLayout.LayoutParams(-1, -2));
        routing.addView(text(R.string.bridges_explanation, 13, false));
        TextView rotationLabel = text(R.string.rotation_title, 17, true);
        rotationLabel.setPadding(0, dp(20), 0, dp(4));
        routing.addView(rotationLabel);
        rotation = new EditText(this);
        rotation.setId(View.generateViewId());
        rotationLabel.setLabelFor(rotation.getId());
        rotation.setInputType(InputType.TYPE_CLASS_NUMBER);
        rotation.setSingleLine(true);
        rotation.setTextColor(foreground);
        rotation.setHintTextColor(muted);
        rotation.setHint(R.string.rotation_hint);
        routing.addView(rotation, new LinearLayout.LayoutParams(-1, -2));
        routing.addView(text(R.string.rotation_hint, 13, false));

        LinearLayout apps = card(page, R.string.direct_title);
        apps.addView(text(R.string.direct_warning, 14, false));
        TextView disclosure = text(R.string.preset_disclosure, 13, false);
        disclosure.setPadding(0, dp(12), 0, dp(12));
        apps.addView(disclosure);
        directSummary = text(R.string.direct_none, 13, false);
        directSummary.setTextIsSelectable(true);
        apps.addView(directSummary);
        chooseApps = button(apps, R.string.choose_apps, v -> loadAppChoices());
        button(apps, R.string.save_settings, v -> {
            if (saveSettings()) toast(R.string.settings_saved);
        });
        apps.addView(text(R.string.save_hint, 13, false));

        LinearLayout help = card(page, R.string.help_title);
        button(help, R.string.captcha_help, v -> showCaptchaHelp());
        challengeHint = text(R.string.challenge_available, 13, false);
        help.addView(challengeHint);
        LinearLayout diagnostics = card(page, R.string.diagnostics_title);
        diagnostics.addView(text(R.string.diagnostics_summary, 13, false));
        shareDiagnostics = button(diagnostics, R.string.share_diagnostics, v ->
                new AlertDialog.Builder(this).setTitle(R.string.share_diagnostics)
                        .setMessage(R.string.diagnostics_share_consent)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.continue_share, (dialog, which) -> runDiagnostics(false)).show());
        clearDiagnostics = button(diagnostics, R.string.clear_diagnostics, v ->
                new AlertDialog.Builder(this).setTitle(R.string.clear_diagnostics)
                        .setMessage(R.string.diagnostics_clear_consent)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.clear_diagnostics, (dialog, which) -> runDiagnostics(true)).show());
        setContentView(scroll);
        scroll.requestApplyInsets();
    }

    private void runDiagnostics(boolean clear) {
        if (diagnosticsBusy) return;
        diagnosticsBusy = true;
        shareDiagnostics.setEnabled(false);
        clearDiagnostics.setEnabled(false);
        new Thread(() -> {
            boolean success = false;
            try {
                // Revoke grants to the old snapshot before replacing or deleting it.
                revokeUriPermission(DiagnosticProvider.reportUri(this), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (clear) DiagnosticLog.clear(getApplicationContext());
                else DiagnosticLog.export(getApplicationContext());
                success = true;
            } catch (IOException | RuntimeException ignored) {
                // Never log errors from diagnostics, especially exception messages or paths.
            }
            final boolean done = success;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                diagnosticsBusy = false;
                shareDiagnostics.setEnabled(true);
                clearDiagnostics.setEnabled(true);
                if (!done) toast(R.string.diagnostics_failed);
                else if (clear) toast(R.string.diagnostics_cleared);
                else shareDiagnosticSnapshot();
            });
        }, "routeveil-diagnostics").start();
    }

    private void shareDiagnosticSnapshot() {
        Uri report = DiagnosticProvider.reportUri(this);
        ClipData clip = ClipData.newRawUri(getString(R.string.diagnostics_title), report);
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, report)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(clip);
        Intent chooser = Intent.createChooser(send, getString(R.string.share_diagnostics));
        chooser.setClipData(clip);
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(chooser);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            toast(R.string.diagnostics_share_unavailable);
        }
    }

    private void loadDraft(Bundle saved) {
        String chosen = saved == null ? Settings.transport(this) : saved.getString("draft_transport", "auto");
        for (int i = 0; i < Settings.TRANSPORTS.length; i++) {
            if (Settings.TRANSPORTS[i].equals(chosen)) transport.setSelection(i);
        }
        bridges.setText(saved == null ? Settings.bridgeLines(this) : saved.getString("draft_bridges", ""));
        rotation.setText(saved == null ? Long.toString(Settings.rotationSeconds(this))
                : saved.getString("draft_rotation", "0"));
        if (saved != null) {
            ArrayList<String> packages = saved.getStringArrayList("draft_direct");
            if (packages != null) draftDirect = new HashSet<>(packages);
            pendingAction = saved.getString("pending_action");
        }
        draftDirect.remove(getPackageName());
        draftDirect.remove(Settings.PROBE_PACKAGE);
        refreshDirectSummary();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("draft_transport", Settings.TRANSPORTS[transport.getSelectedItemPosition()]);
        out.putString("draft_bridges", bridges.getText().toString());
        out.putString("draft_rotation", rotation.getText().toString());
        out.putStringArrayList("draft_direct", new ArrayList<>(draftDirect));
        out.putString("pending_action", pendingAction);
        super.onSaveInstanceState(out);
    }

    private boolean saveSettings() {
        String rawSeconds = rotation.getText().toString().trim();
        long seconds;
        try {
            seconds = Long.parseLong(rawSeconds);
            if (seconds < 0 || (seconds > 0 && seconds < 60) || seconds > 86400) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException invalid) {
            rotation.setError(getString(R.string.rotation_error));
            rotation.requestFocus();
            return false;
        }
        rotation.setError(null);
        draftDirect.remove(getPackageName());
        draftDirect.remove(Settings.PROBE_PACKAGE);
        preferences.edit()
                .putString(Settings.KEY_TRANSPORT, Settings.TRANSPORTS[transport.getSelectedItemPosition()])
                .putString(Settings.KEY_BRIDGE_LINES, bridges.getText().toString())
                .putString(Settings.KEY_ROTATION_SECONDS, Long.toString(seconds))
                .putStringSet(Settings.KEY_DIRECT_PACKAGES, new HashSet<>(draftDirect)).apply();
        return true;
    }

    private void beginConnection(String action) {
        if (pendingAction != null || !saveSettings()) return;
        pendingAction = action;
        refreshStatus();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        } else {
            prepareVpn();
        }
    }

    private void prepareVpn() {
        if (pendingAction == null) return;
        try {
            Intent consent = VpnService.prepare(this);
            if (consent != null) startActivityForResult(consent, REQUEST_VPN);
            else finishConnectionRequest();
        } catch (RuntimeException failure) {
            pendingAction = null;
            toast(R.string.vpn_prepare_failed);
            refreshStatus();
        }
    }

    private void finishConnectionRequest() {
        String action = pendingAction;
        pendingAction = null;
        if (action != null) sendServiceAction(action, true);
        refreshStatus();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
                toast(R.string.notifications_denied);
            }
            // Notification permission is not VPN consent. Android allows an FGS even if denied.
            prepareVpn();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) finishConnectionRequest();
            else {
                pendingAction = null;
                toast(R.string.vpn_denied);
                refreshStatus();
            }
        }
    }

    private void sendServiceAction(String action, boolean foregroundStart) {
        Intent command = new Intent(this, TunnelService.class).setAction(action);
        try {
            if (foregroundStart) startForegroundService(command);
            else startService(command);
            toast(R.string.request_sent);
        } catch (RuntimeException failure) {
            toast(R.string.service_failed);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        main.removeCallbacks(poll);
        main.post(poll);
    }

    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void refreshStatus() {
        boolean connected = preferences.getBoolean("connected", false);
        pill.setText(connected ? R.string.status_connected : R.string.status_disconnected);
        pill.setTextColor(connected ? accent : muted);
        pill.setBackground(round(connected ? (dark ? 0xFF254438 : 0xFFE2F1E9)
                : (dark ? 0xFF303B34 : 0xFFEBEEEA), 24));
        String serviceStatus = preferences.getString("status", "");
        status.setText(serviceStatus == null || serviceStatus.isEmpty()
                ? getString(R.string.status_idle) : serviceStatus);
        String serviceDetail = preferences.getString("detail", "");
        detail.setText(serviceDetail == null || serviceDetail.isEmpty()
                ? getString(R.string.status_detail_idle) : serviceDetail);
        connect.setEnabled(!connected && pendingAction == null);
        optimize.setEnabled(pendingAction == null);
        rotate.setEnabled(connected && pendingAction == null);
        challengeHint.setVisibility(approvedUrl(preferences.getString("challenge_url", "")) != null
                ? View.VISIBLE : View.GONE);
    }

    private void refreshDirectSummary() {
        List<String> packages = new ArrayList<>(draftDirect);
        Collections.sort(packages);
        directSummary.setText(packages.isEmpty() ? getString(R.string.direct_none)
                : getString(R.string.direct_selected, packages.size()) + "\n" + String.join("\n", packages));
    }

    private void loadAppChoices() {
        if (loadingApps) return;
        loadingApps = true;
        chooseApps.setEnabled(false);
        chooseApps.setText(R.string.apps_loading);
        new Thread(() -> {
            List<AppChoice> choices = new ArrayList<>();
            boolean failed = false;
            try {
                PackageManager pm = getPackageManager();
                Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                Map<String, ApplicationInfo> launchable = new HashMap<>();
                for (ResolveInfo result : pm.queryIntentActivities(launcher, 0)) {
                    if (result.activityInfo != null && result.activityInfo.enabled) {
                        ApplicationInfo app = result.activityInfo.applicationInfo;
                        launchable.put(app.packageName, app);
                    }
                }
                // Visibility is intentionally limited by Android and the manifest's launcher queries.
                for (ApplicationInfo app : pm.getInstalledApplications(0)) {
                    if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                        launchable.put(app.packageName, app);
                    }
                }
                for (ApplicationInfo app : launchable.values()) {
                    if (!app.enabled || app.packageName.equals(getPackageName())
                            || app.packageName.equals(Settings.PROBE_PACKAGE)) continue;
                    if (pm.checkPermission(Manifest.permission.INTERNET, app.packageName)
                            != PackageManager.PERMISSION_GRANTED) continue;
                    choices.add(new AppChoice(app.packageName, app.loadLabel(pm).toString()));
                }
                Collator collator = Collator.getInstance(getResources().getConfiguration().getLocales().get(0));
                choices.sort((a, b) -> {
                    int byLabel = collator.compare(a.label, b.label);
                    return byLabel != 0 ? byLabel : a.packageName.compareTo(b.packageName);
                });
            } catch (RuntimeException failure) {
                failed = true;
            }
            final boolean error = failed;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                loadingApps = false;
                chooseApps.setEnabled(true);
                chooseApps.setText(R.string.choose_apps);
                if (error) toast(R.string.apps_failed);
                else showAppChoices(choices);
            });
        }, "routeveil-visible-apps").start();
    }

    private void showAppChoices(List<AppChoice> apps) {
        if (apps.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(R.string.apps_dialog_title)
                    .setMessage(R.string.apps_empty).setPositiveButton(R.string.close, null).show();
            return;
        }
        Set<String> selection = new HashSet<>(draftDirect);
        CharSequence[] labels = new CharSequence[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppChoice app = apps.get(i);
            labels[i] = app.label + "\n" + app.packageName;
            checked[i] = selection.contains(app.packageName);
        }
        // Custom title allows explanatory copy together with the framework multi-choice list.
        LinearLayout title = column();
        title.setPadding(dp(24), dp(20), dp(24), dp(8));
        title.addView(text(R.string.apps_dialog_title, 19, true));
        title.addView(text(R.string.apps_dialog_hint, 13, false));
        new AlertDialog.Builder(this).setCustomTitle(title)
                .setMultiChoiceItems(labels, checked, (dialog, index, selected) -> {
                    String packageName = apps.get(index).packageName;
                    if (selected && !packageName.equals(getPackageName())
                            && !packageName.equals(Settings.PROBE_PACKAGE)) selection.add(packageName);
                    else selection.remove(packageName);
                })
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.apply_selection, (dialog, which) -> {
                    selection.remove(getPackageName());
                    selection.remove(Settings.PROBE_PACKAGE);
                    draftDirect = selection;
                    refreshDirectSummary();
                }).show();
    }

    private void showCaptchaHelp() {
        String serviceUrl = preferences.getString("challenge_url", "");
        Uri approved = approvedUrl(serviceUrl);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.help_title).setMessage(R.string.captcha_explanation)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.open_bridges,
                        (d, w) -> openApprovedUrl("https://bridges.torproject.org/"));
        if (approved != null) {
            dialog.setNeutralButton(R.string.open_challenge, (d, w) -> openApprovedUrl(serviceUrl));
        }
        dialog.show();
    }

    /** Strict authority allowlist; no userinfo, custom ports, suffix matches or WebViews. */
    static Uri approvedUrl(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            URI parsed = new URI(raw);
            String host = parsed.getHost();
            if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getRawUserInfo() != null
                    || (parsed.getPort() != -1 && parsed.getPort() != 443) || host == null) return null;
            if (!"bridges.torproject.org".equalsIgnoreCase(host)
                    && !"check.torproject.org".equalsIgnoreCase(host)) return null;
            return Uri.parse(parsed.toASCIIString());
        } catch (URISyntaxException | IllegalArgumentException invalid) {
            return null;
        }
    }

    private void openApprovedUrl(String raw) {
        Uri uri = approvedUrl(raw);
        if (uri == null) {
            toast(R.string.link_blocked);
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException | SecurityException failure) {
            toast(R.string.browser_missing);
        }
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout card(LinearLayout parent, int title) {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(round(surface, 20));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(18);
        parent.addView(card, params);
        TextView heading = text(title, 18, true);
        heading.setPadding(0, 0, 0, dp(12));
        card.addView(heading);
        return card;
    }

    private TextView text(int resource, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(resource);
        view.setTextSize(size);
        view.setTextColor(bold ? foreground : muted);
        view.setLineSpacing(dp(2), 1.05f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(LinearLayout parent, int label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        parent.addView(button, params);
        return button;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(int resource) {
        Toast.makeText(this, resource, Toast.LENGTH_LONG).show();
    }

    private static final class AppChoice {
        final String packageName;
        final String label;
        AppChoice(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }
}
