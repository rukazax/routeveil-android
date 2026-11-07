package io.github.rukazax.routeveil;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** One read-only user-exported snapshot, accessible externally only with an explicit URI grant. */
public final class DiagnosticProvider extends ContentProvider {
    public static Uri reportUri(Context context) {
        return new Uri.Builder().scheme("content")
                .authority(context.getPackageName() + ".diagnostics").appendPath("report").build();
    }

    @Override public void attachInfo(Context context, ProviderInfo info) {
        if (info.exported || !info.grantUriPermissions
                || !(context.getPackageName() + ".diagnostics").equals(info.authority)) {
            throw new SecurityException("unsafe_diagnostics_provider_configuration");
        }
        super.attachInfo(context, info);
    }

    @Override public boolean onCreate() { return true; }

    private void requireReport(Uri uri) {
        if (getContext() == null || !reportUri(getContext()).equals(uri)) {
            throw new SecurityException("unsupported_diagnostics_uri");
        }
    }

    @Override public String getType(Uri uri) {
        requireReport(uri);
        return "text/plain";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        requireReport(uri);
        if (!"r".equals(mode)) throw new SecurityException("diagnostics_read_only");
        try {
            return DiagnosticLog.openReport(getContext());
        } catch (IOException failure) {
            // Do not expose internal paths or exception text to recipient apps.
            throw new FileNotFoundException("diagnostics_unavailable");
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        requireReport(uri);
        if (selection != null || selectionArgs != null || sortOrder != null) {
            throw new IllegalArgumentException("diagnostics_query_arguments_not_supported");
        }
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection.clone();
        if (columns.length > 2) throw new IllegalArgumentException("unsupported_columns");
        for (String column : columns) {
            if (!OpenableColumns.DISPLAY_NAME.equals(column) && !OpenableColumns.SIZE.equals(column)) {
                throw new IllegalArgumentException("unsupported_column");
            }
        }
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        try {
            File file = DiagnosticLog.reportFile(getContext());
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                row[i] = OpenableColumns.DISPLAY_NAME.equals(columns[i])
                        ? DiagnosticLog.EXPORT_NAME : file.length();
            }
            cursor.addRow(row);
        } catch (IOException ignored) {
            // A cleared/not-yet-exported report has no metadata row.
        }
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new SecurityException("diagnostics_read_only");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new SecurityException("diagnostics_read_only");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new SecurityException("diagnostics_read_only");
    }
}
