package io.github.rukazax.routeveil;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Local, bounded, structured diagnostics. Never pass exception text, service detail, URLs,
 * bridge lines, identifiers or network responses as safeCode. Callers must use fixed codes.
 * No logcat, native logs, device identifiers, app inventories or network payloads are read.
 */
public final class DiagnosticLog {
    private static final int LOG_LIMIT = 128 * 1024;
    private static final int EXPORT_LIMIT = 300 * 1024;
    private static final String CURRENT = "events.jsonl";
    private static final String PREVIOUS = "events.previous.jsonl";
    static final String EXPORT_NAME = "routeveil-diagnostics.txt";
    private static final Set<String> EVENTS = new HashSet<>(Arrays.asList(
            "service", "connect", "bootstrap", "probe", "rotation", "network", "stop", "error"));
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_.:-]{1,80}$");
    // Agreed compile-time service codes. Regex alone would also accept IPs and identifiers.
    private static final Set<String> SAFE_CODES = new HashSet<>(Arrays.asList(
            "created", "core_ready", "core_init_failed", "destroyed", "requested",
            "profile_direct", "profile_snowflake", "profile_obfs4", "profile_webtunnel",
            "connected", "all_failed", "cancelled", "ready", "timeout", "engine_error",
            "helper_missing", "ok", "not_tor", "unavailable", "challenge", "error",
            "winner_selected", "no_verified_result", "cooldown", "success", "failed",
            "lost", "private_dns", "lockdown", "complete", "engine_timeout", "revoked",
            "configuration", "lifecycle", "wifi", "cellular", "other", "offline", "redacted"));

    private DiagnosticLog() { }

    /** Best-effort logging must never break the VPN. Disk failures are not logged elsewhere. */
    public static synchronized void event(Context context, String event, String safeCode) {
        String category = EVENTS.contains(event) ? event : "error";
        String code = EVENTS.contains(event) ? sanitizeCode(safeCode) : "redacted";
        byte[] record = record(Instant.now().toString(), category, code).getBytes(StandardCharsets.UTF_8);
        try {
            File directory = safeDirectory(context.getFilesDir(), "diagnostics", true);
            File current = safeFile(directory, CURRENT);
            File previous = safeFile(directory, PREVIOUS);
            if (previous.exists() && previous.length() > LOG_LIMIT) deleteExpected(previous);
            if (current.exists() && current.length() > LOG_LIMIT) deleteExpected(current);
            if (current.length() + record.length > LOG_LIMIT) {
                deleteExpected(previous);
                if (!current.renameTo(previous)) throw new IOException("rotation_failed");
            }
            try (FileOutputStream output = new FileOutputStream(current, true)) {
                output.write(record);
            }
        } catch (IOException | SecurityException ignored) {
            // Deliberately no raw/native log fallback.
        }
    }

    /** Creates a frozen text snapshot. Only explicit user consent should call this method. */
    public static synchronized File export(Context context) throws IOException {
        StringBuilder report = new StringBuilder(2 * LOG_LIMIT + 1024);
        report.append("Routeveil safe diagnostics v1\n")
                .append("timestampUTC: ").append(Instant.now()).append('\n')
                .append("androidSdk: ").append(Build.VERSION.SDK_INT).append('\n')
                .append("appVersion: ").append(appVersion(context)).append('\n')
                .append("Fields: timestampUTC, event, code. Static codes only.\n")
                .append("No IPs, URLs, bridge lines, app lists, identifiers, messages, logcat or native logs.\n\n");
        File directory = safeDirectory(context.getFilesDir(), "diagnostics", false);
        if (directory.exists()) {
            appendValidated(report, safeFile(directory, PREVIOUS));
            appendValidated(report, safeFile(directory, CURRENT));
        }
        byte[] bytes = report.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > EXPORT_LIMIT) throw new IOException("export_limit");
        File shared = safeDirectory(context.getCacheDir(), "shared", true);
        File target = safeFile(shared, EXPORT_NAME);
        // Replace the inode: a recipient holding the old read descriptor must not see a later export.
        deleteExpected(target);
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            output.write(bytes);
        }
        return target;
    }

    /** Deletes ONLY our three exact regular files, never directories or unexpected children. */
    public static synchronized void clear(Context context) throws IOException {
        File directory = safeDirectory(context.getFilesDir(), "diagnostics", false);
        IOException failure = null;
        if (directory.exists()) {
            for (String name : new String[]{CURRENT, PREVIOUS}) {
                try { deleteExpected(safeFile(directory, name)); }
                catch (IOException error) { failure = error; }
            }
        }
        try {
            File shared = safeDirectory(context.getCacheDir(), "shared", false);
            if (shared.exists()) deleteExpected(safeFile(shared, EXPORT_NAME));
        } catch (IOException error) {
            failure = error;
        }
        if (failure != null) throw failure;
    }

    static synchronized File reportFile(Context context) throws IOException {
        File shared = safeDirectory(context.getCacheDir(), "shared", false);
        File file = safeFile(shared, EXPORT_NAME);
        if (!file.isFile() || file.length() > EXPORT_LIMIT) throw new FileNotFoundException("no_report");
        return file;
    }

    static synchronized ParcelFileDescriptor openReport(Context context) throws IOException {
        return ParcelFileDescriptor.open(reportFile(context), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static String sanitizeCode(String code) {
        if (code == null || !CODE.matcher(code).matches() || !SAFE_CODES.contains(code)) return "redacted";
        return code;
    }

    private static String record(String timestamp, String event, String code) {
        // All values have been validated; none can contain JSON quotes, escapes or newlines.
        return "{\"timestampUTC\":\"" + timestamp + "\",\"event\":\"" + event
                + "\",\"code\":\"" + code + "\"}\n";
    }

    private static void appendValidated(StringBuilder report, File source) throws IOException {
        if (!source.exists() || source.length() > LOG_LIMIT) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(source), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 300) continue;
                try {
                    JSONObject object = new JSONObject(line);
                    if (object.length() != 3) continue;
                    String timestamp = object.getString("timestampUTC");
                    // Reconstruct, never blindly copy files or extra fields into the export.
                    String normalizedTimestamp = Instant.parse(timestamp).toString();
                    String event = object.getString("event");
                    if (!EVENTS.contains(event)) continue;
                    String code = sanitizeCode(object.getString("code"));
                    String safeLine = record(normalizedTimestamp, event, code);
                    if (report.length() + safeLine.length() > EXPORT_LIMIT - 1024) break;
                    report.append(safeLine);
                } catch (JSONException | DateTimeParseException ignored) {
                    // Corrupt/injected records are discarded, never exported raw.
                }
            }
        }
    }

    private static String appVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            String version = info.versionName;
            if (version == null || !version.matches("[A-Za-z0-9_.-]{1,80}")) version = "unknown";
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return version + " (" + code + ")";
        } catch (PackageManager.NameNotFoundException ignored) {
            return "unknown";
        }
    }

    private static File safeDirectory(File parent, String child, boolean create) throws IOException {
        File base = parent.getCanonicalFile();
        File directory = new File(base, child);
        if (Files.isSymbolicLink(directory.toPath())
                || !directory.getCanonicalFile().equals(directory)) throw new IOException("unsafe_directory");
        if (!directory.exists() && create && !directory.mkdir()) throw new IOException("mkdir_failed");
        if (directory.exists() && !directory.isDirectory()) throw new IOException("not_directory");
        return directory;
    }

    private static File safeFile(File parent, String name) throws IOException {
        File file = new File(parent, name);
        if (Files.isSymbolicLink(file.toPath()) || !file.getCanonicalFile().equals(file)
                || (file.exists() && !file.isFile())) throw new IOException("unsafe_file");
        return file;
    }

    private static void deleteExpected(File file) throws IOException {
        // Callers already resolved and checked one of the fixed file paths, not user input.
        if (file.exists() && (!file.isFile() || !file.delete())) throw new IOException("delete_failed");
    }
}
