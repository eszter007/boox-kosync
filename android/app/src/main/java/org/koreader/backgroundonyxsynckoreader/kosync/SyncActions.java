package org.koreader.backgroundonyxsynckoreader.kosync;

import android.app.ActivityManager;
import android.content.Context;

import org.koreader.backgroundonyxsynckoreader.contentprovider.OnyxMetadataRepository;

import java.io.File;

/**
 * Sync operations shared by the background service (automatic sync) and the
 * settings UI (manual "Apply remote" / "Upload local", which deliberately
 * skip the newest-timestamp check — the user decides).
 */
public final class SyncActions {

    /** NeoReader's package — the app whose Metadata rows we sync. */
    public static final String NEOREADER_PACKAGE = "com.onyx.kreader";

    private SyncActions() {
    }

    /** Document hash for the configured match method, or null if unhashable. */
    public static String documentHash(SyncSettings settings, String path) {
        if (SyncSettings.MATCH_FILENAME.equals(settings.getMatchMethod())) {
            return KoReaderDocumentHash.fromFilename(path);
        }
        return KoReaderDocumentHash.fromContent(new File(path));
    }

    /**
     * Writes remote progress into the Onyx provider: progress column (library
     * shelf) plus NeoReader's internal position when a calibration pair is
     * available. Records what was written for echo suppression.
     *
     * @return the written "page/total" string, or null on failure
     */
    public static String applyRemote(Context context,
                                     OnyxMetadataRepository repository, SyncStateStore stateStore,
                                     OnyxMetadataRepository.Book book,
                                     KoSyncClient.Progress remote) {
        int total = book.totalPages();
        if (total <= 0) {
            return null;
        }
        double pct = Math.max(0.0, Math.min(1.0, remote.percentage));
        int page = Math.max(1, Math.min(total, (int) Math.round(pct * total)));
        long remoteMs = remote.timestampSec * 1000L;

        SyncStateStore.BookState state = stateStore.get(book.path);
        long positionV2 = -1;
        if (state.calibPosition >= 0 && state.calibPercentage > 0.005) {
            positionV2 = Math.max(0,
                    Math.round(state.calibPosition * (pct / state.calibPercentage)));
        }

        // NeoReader only reads the position back from Metadata on a COLD
        // start; a cached background process restores its private store and
        // overwrites our row. Kill it (background only — a foreground reading
        // session is never touched) BEFORE writing, so it can't flush stale
        // state over the top and the next open picks up the synced position.
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            am.killBackgroundProcesses(NEOREADER_PACKAGE);
        }

        if (!repository.writeProgress(book, page, total, remoteMs, positionV2)) {
            return null;
        }
        String written = page + "/" + total;
        state.lastWrittenProgress = written;
        state.lastWrittenAccessMs = remoteMs;
        stateStore.put(book.path, state);

        // Reading done on the other device should show up in the Boox reading
        // statistics too (estimated durations; local NeoReader reading is
        // recorded by Onyx natively). Best-effort — never fails the sync.
        int localPage = book.currentPage();
        if (page > localPage) {
            try {
                new org.koreader.backgroundonyxsynckoreader.contentprovider.OnyxStatisticsRepository(context)
                        .recordRemoteReading(book, localPage, page, total,
                                book.lastAccessMs, remoteMs,
                                remote.device != null && !remote.device.isEmpty()
                                        ? remote.device : "remote device");
            } catch (Exception ignored) {
            }
        }
        return written;
    }

    /**
     * Pushes the book's current local progress to the server and records it
     * for push dedup.
     */
    public static KoSyncClient.Result uploadLocal(KoSyncClient client, SyncStateStore stateStore,
                                                  OnyxMetadataRepository.Book book, String hash) {
        int page = book.currentPage();
        int total = book.totalPages();
        double percentage = Math.min(1.0, (double) page / total);
        KoSyncClient.Result result = client.updateProgress(hash, String.valueOf(page), percentage);
        if (result.error == KoSyncClient.Error.OK) {
            SyncStateStore.BookState state = stateStore.get(book.path);
            state.lastPushedProgress = book.progress;
            stateStore.put(book.path, state);
        }
        return result;
    }
}
