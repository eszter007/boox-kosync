package org.koreader.backgroundonyxsynckoreader.contentprovider;

import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Writes reading-session rows into Onyx's statistics provider so reading done
 * on OTHER kosync devices (e.g. an Xteink running CrossPoint) shows up in the
 * Boox reading statistics. Local NeoReader reading is recorded by Onyx
 * natively and never touched here.
 *
 * kosync only carries percentage + timestamp, so per-page durations are
 * ESTIMATES: the time between the previous local state and the remote
 * timestamp, spread across the pages read, clamped to a plausible
 * seconds-per-page range. Page counts and reading days are accurate;
 * reading time is approximate.
 */
public class OnyxStatisticsRepository {

    private static final String TAG = "OnyxStatsRepo";

    private static final Uri STATS_URI =
            Uri.parse("content://com.onyx.kreader.statistics.provider/OnyxStatisticsModel");
    private static final Uri ACCOUNT_URI = Uri.parse(
            "content://com.onyx.account.database.ContentProvider.KSyncAccountContentProvider/OnyxAccountModel");

    private static final int TYPE_READ_TIME = 1;
    private static final int STATUS_LOCAL = 0;

    // Estimated per-page reading time bounds (e-ink page turns).
    private static final long MIN_PAGE_MS = 15_000;
    private static final long MAX_PAGE_MS = 120_000;
    private static final int APPLY_BATCH_SIZE = 20;
    // Safety valve: a huge remote jump (e.g. finished book elsewhere) should
    // not generate thousands of synthetic rows.
    private static final int MAX_ROWS_PER_SYNC = 300;

    private final Context context;

    public OnyxStatisticsRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Records estimated reading sessions for a remote progress jump from
     * fromPage (exclusive) to toPage (inclusive), ending at endTimeMs.
     * Best-effort: failures are logged, never thrown.
     */
    public void recordRemoteReading(OnyxMetadataRepository.Book book,
                                    int fromPage, int toPage, int totalPages,
                                    long previousLocalMs, long endTimeMs,
                                    String deviceName) {
        if (toPage <= fromPage || totalPages <= 0 || book.uuid == null) {
            return;
        }
        String accountId = getAccountId();
        if (accountId == null) {
            Log.d(TAG, "No logged-in Onyx account — skipping stats for " + book.path);
            return;
        }

        int pages = toPage - fromPage;
        // Available window: between the last known local state and the remote
        // timestamp. Unknown window (no previous state) estimates 60s/page.
        long windowMs = previousLocalMs > 0 && endTimeMs > previousLocalMs
                ? endTimeMs - previousLocalMs : pages * 60_000L;
        long perPageMs = Math.max(MIN_PAGE_MS, Math.min(MAX_PAGE_MS, windowMs / pages));

        int step = 1;
        if (pages > MAX_ROWS_PER_SYNC) {
            step = (pages + MAX_ROWS_PER_SYNC - 1) / MAX_ROWS_PER_SYNC;
        }

        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        for (int page = fromPage + 1; page <= toPage; page += step) {
            int coveredPages = Math.min(step, toPage - page + 1);
            long duration = perPageMs * coveredPages;
            // Contiguous sessions stacking backwards from the remote
            // timestamp: the row reaching toPage ends exactly at endTimeMs.
            long eventTime = endTimeMs - (long) (toPage - page + 1) * perPageMs;

            ContentValues cv = new ContentValues();
            cv.put("accountId", accountId);
            cv.put("docId", book.uuid);
            cv.put("md5", book.hashTag);
            cv.put("title", book.name);
            cv.put("name", book.name);
            cv.put("path", book.path);
            cv.put("type", TYPE_READ_TIME);
            cv.put("status", STATUS_LOCAL);
            cv.put("eventTime", eventTime);
            cv.put("durationTime", duration);
            cv.put("currPage", page);
            cv.put("lastPage", totalPages);
            cv.put("readingProgress", (float) page / totalPages);
            cv.put("hideRecord", 0);
            cv.put("sid", UUID.randomUUID().toString());
            cv.put("action", "add");
            cv.put("comment", "synced from " + deviceName);
            ops.add(ContentProviderOperation.newInsert(STATS_URI).withValues(cv).build());
        }

        try (ContentProviderClient client =
                     context.getContentResolver().acquireUnstableContentProviderClient(STATS_URI)) {
            if (client == null) {
                Log.w(TAG, "Could not acquire statistics provider");
                return;
            }
            for (int i = 0; i < ops.size(); i += APPLY_BATCH_SIZE) {
                client.applyBatch(new ArrayList<>(
                        ops.subList(i, Math.min(ops.size(), i + APPLY_BATCH_SIZE))));
            }
            // Refresh Onyx's statistics widget, same as upstream did.
            context.sendBroadcast(new Intent("com.onyx.statisticswidget.action.UPDATE"));
            Log.i(TAG, "Recorded " + ops.size() + " estimated session row(s), pages "
                    + (fromPage + 1) + "-" + toPage + " (~" + perPageMs / 1000 + "s/page) for "
                    + book.path);
        } catch (Exception e) {
            Log.e(TAG, "Failed to insert stats for " + book.path, e);
        }
    }

    private String getAccountId() {
        try (Cursor cursor = context.getContentResolver().query(
                ACCOUNT_URI, new String[]{"accountId"},
                "loggedIn = ?", new String[]{"1"}, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "getAccountId failed: " + e.getMessage());
        }
        return null;
    }
}
