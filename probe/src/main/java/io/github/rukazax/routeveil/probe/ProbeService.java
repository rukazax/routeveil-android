package io.github.rukazax.routeveil.probe;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

/** Separate-UID, bound-only, on-demand measurements. No started-service work. */
public final class ProbeService extends Service {
    public static final int REQUEST = 1;
    public static final int REPLY = 2;
    private final AtomicReference<Job> current = new AtomicReference<>();
    private ExecutorService worker;
    private ScheduledExecutorService timers;
    private ConnectivityManager connectivity;
    private Messenger messenger;
    private volatile boolean destroyed;

    @Override public void onCreate() {
        super.onCreate();
        connectivity = getSystemService(ConnectivityManager.class);
        worker = Executors.newSingleThreadExecutor();
        // A blocked disconnect in the VPN monitor must not delay the deadline reply.
        timers = Executors.newScheduledThreadPool(2);
        messenger = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message message) { accept(message); }
        });
    }

    @Override public IBinder onBind(Intent intent) {
        // Android enforces the service's signature permission here. onBind runs as
        // a lifecycle callback, NOT with the original caller's Binder identity.
        return destroyed ? null : messenger.getBinder();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    private boolean authorised(int uid) {
        if (uid < 0) return false;
        try {
            PackageManager pm = getPackageManager();
            int hostUid = pm.getApplicationInfo(ProbePolicy.HOST_PACKAGE, 0).uid;
            String[] packages = pm.getPackagesForUid(uid);
            // Reject shared-UID ambiguity as well as other same-signed apps.
            return uid == hostUid && uid != Process.myUid()
                    && packages != null && packages.length == 1
                    && ProbePolicy.HOST_PACKAGE.equals(packages[0])
                    && pm.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH
                    && checkPermission(ProbePolicy.PERMISSION, -1, uid) == PackageManager.PERMISSION_GRANTED;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return false;
        }
    }

    private void accept(Message message) {
        // Messenger stamps sendingUid in its Binder stub, unlike arbitrary Bundle data.
        if (!authorised(message.sendingUid) || message.replyTo == null || destroyed) return;
        String id = null;
        try { id = message.getData().getString("request_id"); }
        catch (RuntimeException ignored) { /* Malformed parcel: fixed error only. */ }
        if (message.what != REQUEST || !ProbePolicy.validRequestId(id)) {
            reply(message.replyTo, ProbePolicy.validRequestId(id) ? id : "", "error", "bad_request", "", 0, 0, 0);
            return;
        }
        Job job = new Job(id, message.replyTo);
        if (!current.compareAndSet(null, job)) {
            reply(message.replyTo, id, "unavailable", "busy", "", 0, 0, 0);
            return;
        }
        try {
            job.deadline = timers.schedule(() -> job.abort("unavailable", "deadline"),
                    Math.max(0, ProbePolicy.DEADLINE_MS - (SystemClock.elapsedRealtime() - job.started)), TimeUnit.MILLISECONDS);
            job.monitor = timers.scheduleWithFixedDelay(() -> {
                if (!job.done.get() && !hasVpn()) job.abort("unavailable", "vpn_lost");
            }, 200, 200, TimeUnit.MILLISECONDS);
            worker.execute(job);
        } catch (RejectedExecutionException ignored) {
            job.abort("unavailable", "stopped");
            job.cancelTimers();
            current.compareAndSet(job, null);
        }
    }

    private boolean hasVpn() {
        try {
            Network network = connectivity == null ? null : connectivity.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null : connectivity.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (RuntimeException ignored) { return false; }
    }

    @Override public void onDestroy() {
        destroyed = true;
        Job job = current.get();
        worker.shutdownNow();
        timers.shutdownNow();
        if (job != null) job.abort("unavailable", "stopped");
        // Also disconnect when a deadline already sent its terminal reply but
        // the worker is still unwinding an uninterruptible platform operation.
        if (job != null) job.disconnectActive();
        super.onDestroy();
    }

    private static void reply(Messenger target, String id, String status, String code,
                              String ip, long elapsed, long download, long bytes) {
        Bundle data = new Bundle();
        data.putString("request_id", id);
        data.putString("status", status);
        data.putString("ip", ip);
        data.putLong("elapsed_ms", elapsed);
        data.putLong("download_ms", download);
        data.putLong("bytes", bytes);
        data.putString("challenge_url", "challenge".equals(status) ? ProbePolicy.CHALLENGE_URL : null);
        data.putString("error_code", code);
        Message response = Message.obtain(null, REPLY);
        response.setData(data);
        try { target.send(response); }
        catch (RemoteException | RuntimeException ignored) { /* No logs or persisted results. */ }
    }

    private final class Job implements Runnable {
        final String id;
        final Messenger target;
        final long started = SystemClock.elapsedRealtime();
        final AtomicBoolean done = new AtomicBoolean();
        final Object connectionLock = new Object();
        volatile Thread thread;
        volatile String abortStatus;
        volatile String abortCode;
        volatile String ip = "";
        volatile long elapsed;
        volatile long download;
        volatile long bytes;
        volatile ScheduledFuture<?> deadline;
        volatile ScheduledFuture<?> monitor;
        HttpURLConnection active; // connectionLock

        Job(String id, Messenger target) { this.id = id; this.target = target; }

        void finish(String status, String code) {
            synchronized (connectionLock) {
                if (abortCode != null) {
                    status = abortStatus;
                    code = abortCode;
                } else if (SystemClock.elapsedRealtime() - started >= ProbePolicy.DEADLINE_MS) {
                    status = "unavailable";
                    code = "deadline";
                } else if (!"vpn_required".equals(code) && !hasVpn()) {
                    status = "unavailable";
                    code = "vpn_lost";
                }
                if (!done.compareAndSet(false, true)) return;
            }
            cancelTimers();
            reply(target, id, status, code, ip, elapsed, download, bytes);
        }

        void disconnectActive() {
            HttpURLConnection connection;
            synchronized (connectionLock) { connection = active; }
            if (connection != null) connection.disconnect();
        }

        void cancelTimers() {
            ScheduledFuture<?> task = deadline;
            if (task != null) task.cancel(false);
            task = monitor;
            if (task != null) task.cancel(false);
        }

        void abort(String status, String code) {
            HttpURLConnection connection;
            synchronized (connectionLock) {
                if (done.get()) return;
                abortStatus = status;
                abortCode = code;
                connection = active;
                // Serialize with final thread release, so a late cancellation
                // cannot interrupt this executor thread after a new job starts.
                if (thread != null) thread.interrupt();
            }
            // Deliver before disconnect: platform DNS/socket teardown can block.
            finish(status, code);
            if (connection != null) connection.disconnect();
        }

        void guard() throws Failure {
            if (abortCode != null) throw new Failure(abortStatus, abortCode);
            if (destroyed || Thread.currentThread().isInterrupted()) throw new Failure("unavailable", "stopped");
            if (SystemClock.elapsedRealtime() - started >= ProbePolicy.DEADLINE_MS)
                throw new Failure("unavailable", "deadline");
            if (!hasVpn()) throw new Failure("unavailable", "vpn_lost");
        }

        HttpsURLConnection open(String fixedUrl) throws IOException, Failure {
            guard();
            // No Network.openConnection, bindProcessToNetwork, protect(), custom
            // proxy, TrustManager, HostnameVerifier, cookies, or disk HTTP cache.
            HttpsURLConnection connection = (HttpsURLConnection) new URL(fixedUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(ProbePolicy.IO_TIMEOUT_MS);
            connection.setReadTimeout(ProbePolicy.IO_TIMEOUT_MS);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("Cache-Control", "no-store");
            // Fixed, non-identifying agent; never platform/device/version metadata.
            connection.setRequestProperty("User-Agent", "Routeveil-Probe");
            synchronized (connectionLock) {
                if (abortCode != null || destroyed) {
                    connection.disconnect();
                    throw new Failure("unavailable", "stopped");
                }
                active = connection;
            }
            return connection;
        }

        void close(HttpURLConnection connection) {
            connection.disconnect();
            synchronized (connectionLock) { if (active == connection) active = null; }
        }

        @Override public void run() {
            thread = Thread.currentThread();
            try {
                if (!hasVpn()) throw new Failure("unavailable", "vpn_required");
                guard();
                checkTor();
                guard();
                download();
                guard();
                finish("ok", "none");
            } catch (Failure failure) {
                finish(failure.status, failure.code);
            } catch (SocketTimeoutException ignored) {
                finish("unavailable", "timeout");
            } catch (SSLException ignored) {
                finish("error", "tls");
            } catch (IOException ignored) {
                finish("unavailable", "io");
            } catch (JSONException ignored) {
                finish("error", "invalid_json");
            } catch (SecurityException ignored) {
                finish("error", "network_denied");
            } catch (RuntimeException ignored) {
                finish("error", "internal");
            } finally {
                cancelTimers();
                synchronized (connectionLock) {
                    thread = null;
                    // Clear cancellation before the worker handles another job.
                    Thread.interrupted();
                }
                current.compareAndSet(this, null);
            }
        }

        void checkTor() throws IOException, Failure, JSONException {
            long begin = SystemClock.elapsedRealtime();
            HttpsURLConnection connection = open(ProbePolicy.CHECK_URL);
            String body;
            String contentType;
            int status;
            try {
                status = connection.getResponseCode();
                guard();
                if (status >= 300 && status < 400) throw new Failure("unavailable", "check_http");
                requireIdentity(connection);
                contentType = connection.getContentType();
                long length = connection.getContentLengthLong();
                if (length > ProbePolicy.CHECK_LIMIT) throw new Failure("error", "check_large");
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (stream == null) throw new Failure("unavailable", "check_http");
                try (InputStream input = stream) { body = readCheck(input, length); }
                guard();
            } finally {
                elapsed = Math.max(0, SystemClock.elapsedRealtime() - begin);
                close(connection);
            }
            // HTTP 403 alone is never evidence of a challenge. Only bounded HTML
            // from this exact check endpoint with explicit markers qualifies.
            if (ProbePolicy.isChallenge(contentType, body)) throw new Failure("challenge", "challenge");
            if (status != 200) throw new Failure("unavailable", "check_http");
            JSONObject json = new JSONObject(body);
            Object tor = json.opt("IsTor");
            Object address = json.opt("IP");
            if (!(tor instanceof Boolean) || !(address instanceof String)
                    || !ProbePolicy.isIpLiteral((String) address)) throw new Failure("error", "invalid_json");
            ip = (String) address;
            if (!Boolean.TRUE.equals(tor)) throw new Failure("not_tor", "not_tor");
        }

        String readCheck(InputStream input, long length) throws IOException, Failure {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            while (output.size() < ProbePolicy.CHECK_LIMIT) {
                guard();
                int count = input.read(buffer, 0, Math.min(buffer.length, ProbePolicy.CHECK_LIMIT - output.size()));
                if (count == -1) {
                    if (length >= 0 && output.size() != length) throw new Failure("error", "check_short");
                    return output.toString(StandardCharsets.UTF_8.name());
                }
                output.write(buffer, 0, count);
                if (length >= 0 && output.size() == length) return output.toString(StandardCharsets.UTF_8.name());
            }
            // Unknown-length responses that fill the cap are rejected without
            // consuming a seventeenth KiB or requiring unbounded end-of-body work.
            throw new Failure("error", "check_large");
        }

        void download() throws IOException, Failure {
            long begin = SystemClock.elapsedRealtime();
            HttpsURLConnection connection = open(ProbePolicy.DOWNLOAD_URL);
            try {
                if (connection.getResponseCode() != 200) throw new Failure("unavailable", "download_http");
                guard();
                requireIdentity(connection);
                // A declared exact framing length proves completion at the cap,
                // without reading an extra byte. Reject unknown/chunked lengths.
                if (connection.getContentLengthLong() != ProbePolicy.DOWNLOAD_BYTES
                        || connection.getHeaderField("Transfer-Encoding") != null)
                    throw new Failure("error", "download_size");
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    while (bytes < ProbePolicy.DOWNLOAD_BYTES) {
                        guard();
                        int count = input.read(buffer, 0, (int) Math.min(buffer.length, ProbePolicy.DOWNLOAD_BYTES - bytes));
                        if (count == -1) throw new Failure("error", "download_short");
                        bytes += count;
                    }
                }
                guard();
            } finally {
                download = Math.max(0, SystemClock.elapsedRealtime() - begin);
                close(connection);
            }
        }

        void requireIdentity(HttpURLConnection connection) throws Failure {
            String encoding = connection.getContentEncoding();
            if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new Failure("error", "encoding");
        }
    }

    private static final class Failure extends Exception {
        final String status;
        final String code;
        Failure(String status, String code) { this.status = status; this.code = code; }
    }
}
