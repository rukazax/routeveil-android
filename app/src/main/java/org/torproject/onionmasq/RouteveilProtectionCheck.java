package org.torproject.onionmasq;

import android.system.Os;
import android.system.OsConstants;
import android.os.ParcelFileDescriptor;
import java.io.FileDescriptor;

/** A bounded readiness check of the stock asynchronous service binding; no network I/O. */
public final class RouteveilProtectionCheck {
    private RouteveilProtectionCheck() {}
    public static boolean ready() {
        FileDescriptor socket = null;
        try {
            socket = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, OsConstants.IPPROTO_TCP);
            try (ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(socket)) {
                return OnionMasq.protect(duplicate.getFd());
            }
        } catch (Exception ignored) {
            return false;
        } finally {
            if (socket != null) try { Os.close(socket); } catch (Exception ignored) { }
        }
    }
}
