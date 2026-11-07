package io.github.rukazax.routeveil;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Shared contract between the Activity and TunnelService. No bridge parsing occurs here. */
public final class Settings {
    public static final String PREFS_NAME = "routeveil";
    public static final String PROBE_PACKAGE = "io.github.rukazax.routeveil.probe";
    public static final String KEY_TRANSPORT = "transport";
    public static final String KEY_BRIDGE_LINES = "bridge_lines";
    public static final String KEY_ROTATION_SECONDS = "rotation_seconds";
    public static final String KEY_DIRECT_PACKAGES = "direct_packages";
    private static final String KEY_INITIALIZED = "defaults_initialized_v1";
    public static final String[] TRANSPORTS = {"auto", "snowflake", "obfs4", "webtunnel", "direct"};

    private Settings() { }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Installs editable convenience presets once, never repopulates a deliberately empty set. */
    public static synchronized void initDefaults(Context context) {
        SharedPreferences p = prefs(context);
        if (p.getBoolean(KEY_INITIALIZED, false)) return;
        SharedPreferences.Editor e = p.edit();
        if (!p.contains(KEY_TRANSPORT)) e.putString(KEY_TRANSPORT, "auto");
        if (!p.contains(KEY_BRIDGE_LINES)) e.putString(KEY_BRIDGE_LINES, "");
        if (!p.contains(KEY_ROTATION_SECONDS)) e.putString(KEY_ROTATION_SECONDS, "0");
        if (!p.contains(KEY_DIRECT_PACKAGES)) {
            e.putStringSet(KEY_DIRECT_PACKAGES, new HashSet<>(Arrays.asList(
                    "ru.gosuslugi.gosuslugi", "ru.yandex.yandexnavi", "ru.yandex.taxi",
                    "com.vkontakte.android", "ru.oneme.app")));
        }
        e.putBoolean(KEY_INITIALIZED, true).apply();
    }

    /** Always a detached mutable copy; the VPN app itself can never be excluded. */
    public static Set<String> directPackages(Context context) {
        initDefaults(context);
        Set<String> stored = prefs(context).getStringSet(KEY_DIRECT_PACKAGES, null);
        Set<String> result = stored == null ? new HashSet<>() : new HashSet<>(stored);
        result.remove(context.getPackageName());
        result.remove(PROBE_PACKAGE);
        return result;
    }

    public static String transport(Context context) {
        return prefs(context).getString(KEY_TRANSPORT, "auto");
    }

    public static String bridgeLines(Context context) {
        return prefs(context).getString(KEY_BRIDGE_LINES, "");
    }

    /** Stored as text; malformed external values safely disable automatic rotation. */
    public static long rotationSeconds(Context context) {
        try {
            long seconds = Long.parseLong(prefs(context).getString(KEY_ROTATION_SECONDS, "0"));
            return seconds >= 60 && seconds <= 86400 ? seconds : 0;
        } catch (NumberFormatException | ClassCastException ignored) {
            return 0;
        }
    }
}
