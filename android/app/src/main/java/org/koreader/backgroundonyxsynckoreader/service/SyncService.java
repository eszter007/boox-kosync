package org.koreader.backgroundonyxsynckoreader.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import org.koreader.backgroundonyxsynckoreader.R;
import org.koreader.backgroundonyxsynckoreader.activity.MainActivity;
import org.koreader.backgroundonyxsynckoreader.contentprovider.OnyxMetadataRepository;
import org.koreader.backgroundonyxsynckoreader.kosync.KoSyncClient;
import org.koreader.backgroundonyxsynckoreader.kosync.SyncActions;
import org.koreader.backgroundonyxsynckoreader.kosync.SyncSettings;
import org.koreader.backgroundonyxsynckoreader.kosync.SyncStateStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Foreground service that keeps NeoReader's reading position in sync with a
 * kosync server.
 *
 * Watches Onyx's Metadata content provider (ContentObserver, with a periodic
 * poll as fallback in case the provider doesn't send change notifications):
 *  - progress changed by the user  -> debounced push to the server
 *  - lastAccess bumped (book open) -> pull; newer remote progress is written
 *    back into the Metadata provider (newest-timestamp-wins, never blind)
 */
public class SyncService extends Service {

    private static final String TAG = "SyncService";
    private static final String CHANNEL_ID = "kosync_service";
    private static final int NOTIFICATION_ID = 1;

    /** Debounce after a provider change before scanning, absorbs rapid page turns. */
    private static final long DEBOUNCE_MS = 3000;
    /** Poll fallback interval; also spaces pushes when notifications don't fire. */
    private static final long POLL_INTERVAL_MS = 60_000;

    private HandlerThread workerThread;
    private Handler worker;
    private ContentObserver observer;

    private SyncSettings settings;
    private SyncStateStore stateStore;
    private OnyxMetadataRepository repository;
    private KoSyncClient client;
    private String deviceId;

    /** Last seen provider state per path, to detect what actually changed. */
    private static class Seen {
        String progress;
        long lastAccessMs;
    }

    private final Map<String, Seen> snapshot = new HashMap<>();
    private boolean primed = false;

    private final Runnable scanRunnable = this::scan;
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            scan();
            worker.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new SyncSettings(this);
        stateStore = new SyncStateStore(this);
        repository = new OnyxMetadataRepository(this);
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        deviceId = androidId != null ? androidId : "boox-companion";
        client = new KoSyncClient(settings, "BooxCompanion", deviceId);

        startForeground(NOTIFICATION_ID, buildNotification());

        workerThread = new HandlerThread("kosync-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        observer = new ContentObserver(worker) {
            @Override
            public void onChange(boolean selfChange) {
                // Debounce: restart the timer on every change so a burst of
                // page turns results in one scan/push.
                worker.removeCallbacks(scanRunnable);
                worker.postDelayed(scanRunnable, DEBOUNCE_MS);
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    OnyxMetadataRepository.METADATA_URI, true, observer);
        } catch (Exception e) {
            Log.w(TAG, "Could not register content observer, relying on polling", e);
        }

        worker.post(pollRunnable);
        Log.i(TAG, "SyncService started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (observer != null) {
            getContentResolver().unregisterContentObserver(observer);
        }
        if (workerThread != null) {
            workerThread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, SyncService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    // --- Sync logic (runs on worker thread) --------------------------------

    private void scan() {
        if (!settings.hasCredentials()) {
            return;
        }
        List<OnyxMetadataRepository.Book> books = repository.queryBooksWithProgress();
        if (!primed) {
            // First scan after service start: learn current state without
            // syncing, so a reboot doesn't re-push every book in the library.
            for (OnyxMetadataRepository.Book book : books) {
                remember(book);
                maybeCalibrate(book, stateStore.get(book.path));
            }
            primed = true;
            // But do reconcile the most recently accessed book: if the user
            // read elsewhere while this device was off, sync it now.
            if (!books.isEmpty()) {
                OnyxMetadataRepository.Book latest = books.get(0);
                resolve(latest, stateStore.get(latest.path), latest.lastAccessMs, false);
            }
            return;
        }

        java.util.Set<String> resolvedThisScan = new java.util.HashSet<>();
        for (OnyxMetadataRepository.Book book : books) {
            Seen seen = snapshot.get(book.path);
            boolean isNew = seen == null;
            boolean progressChanged = !isNew && !equalsSafe(book.progress, seen.progress);
            boolean accessChanged = !isNew && book.lastAccessMs != seen.lastAccessMs;

            if (!isNew && !progressChanged && !accessChanged) {
                continue;
            }
            resolvedThisScan.add(book.path);

            SyncStateStore.BookState state = stateStore.get(book.path);
            boolean isOurOwnWrite = equalsSafe(book.progress, state.lastWrittenProgress)
                    && book.lastAccessMs == state.lastWrittenAccessMs;

            if (isOurOwnWrite) {
                remember(book);
                continue; // echo of a pull we just applied
            }

            maybeCalibrate(book, state);

            // Compare against the state from BEFORE this change: NeoReader
            // bumps lastAccess — and may rewrite progress after repaginating —
            // just from opening a book, so the current values always look
            // newer than any remote progress made overnight on another device.
            long localBasisMs;
            if (!isNew) {
                localBasisMs = seen.lastAccessMs;
            } else {
                // First sighting of this book: no prior state. If it is at
                // the very start there is no local progress to protect.
                localBasisMs = book.currentPage() <= 1 ? 0 : book.lastAccessMs;
            }

            if (resolve(book, state, localBasisMs, progressChanged || isNew)) {
                remember(book);
            }
            // On transient failure the snapshot keeps the old values, so the
            // same change is re-detected and retried on the next poll tick.
        }

        // Also reconcile the most recently read book against the server even
        // when nothing changed locally: another device may have pushed newer
        // progress while the book sits closed here. Our own writes set
        // lastAccess to the applied server timestamp, so an unchanged remote
        // is never re-applied, and the device-id guard ignores our own pushes.
        if (!books.isEmpty()) {
            OnyxMetadataRepository.Book latest = books.get(0);
            if (!resolvedThisScan.contains(latest.path)) {
                resolve(latest, stateStore.get(latest.path), latest.lastAccessMs, false);
            }
        }
    }

    /**
     * Conflict-safe sync for one changed book: ask the server who is newest
     * FIRST. Progress from another device that is newer than the local
     * pre-change baseline wins and is applied locally; otherwise local
     * progress (if it changed) is pushed.
     *
     * @param localBasisMs when local progress was last actually made
     *                     (pre-open-bump); remote must be strictly newer.
     * @param mayPush      whether local progress changed and may be pushed
     * @return false on a transient error, so the caller retries this change.
     */
    private boolean resolve(OnyxMetadataRepository.Book book, SyncStateStore.BookState state,
                            long localBasisMs, boolean mayPush) {
        int total = book.totalPages();
        if (total <= 0) {
            return true; // unparseable row; nothing to do
        }
        String hash = documentHash(book.path);
        if (hash == null) {
            return true; // missing/unreadable file won't fix itself by retrying
        }

        KoSyncClient.Result remote = client.getProgress(hash);
        if (remote.error == KoSyncClient.Error.OK && remote.progress != null) {
            long remoteMs = remote.progress.timestampSec * 1000L;
            boolean fromOtherDevice = !deviceId.equals(remote.progress.deviceId);
            if (fromOtherDevice && remoteMs > localBasisMs) {
                applyRemote(book, remote.progress, total, remoteMs);
                return true;
            }
        } else if (remote.error != KoSyncClient.Error.NOT_FOUND) {
            // Can't tell who is newest — never push blind over what might be
            // another device's newer progress.
            settings.recordSyncError("Sync check failed: " + KoSyncClient.errorString(remote));
            return false;
        }

        if (!mayPush) {
            return true;
        }
        return push(book, state, hash, total);
    }

    private boolean push(OnyxMetadataRepository.Book book, SyncStateStore.BookState state,
                         String hash, int total) {
        int page = book.currentPage();
        if (page <= 0) {
            Log.d(TAG, "push: unparseable progress '" + book.progress + "' for " + book.path);
            return true;
        }
        if (equalsSafe(book.progress, state.lastPushedProgress)) {
            return true; // already on the server
        }
        // Onyx gives no paragraph-level position, so progress is the page
        // number and percentage carries the cross-device position (CrossPoint
        // treats percentage as the primary sync mechanism).
        KoSyncClient.Result result = SyncActions.uploadLocal(client, stateStore, book, hash);
        if (result.error == KoSyncClient.Error.OK) {
            settings.recordSyncSuccess(book.name != null ? book.name : book.path);
            Log.i(TAG, "Pushed " + book.progress + " (" + hash + ") for " + book.path);
            return true;
        }
        settings.recordSyncError("Push failed: " + KoSyncClient.errorString(result));
        return false;
    }

    private void applyRemote(OnyxMetadataRepository.Book book, KoSyncClient.Progress remote,
                             int total, long remoteMs) {
        double pct = Math.max(0.0, Math.min(1.0, remote.percentage));
        int page = Math.max(1, Math.min(total, (int) Math.round(pct * total)));
        if (page == book.currentPage()) {
            remember(book);
            return; // same position, don't churn the provider
        }

        String written = SyncActions.applyRemote(this, repository, stateStore, book, remote);
        if (written != null) {
            // Update the snapshot to what we wrote so the next scan doesn't
            // mistake our own write for user activity.
            Seen seen = new Seen();
            seen.progress = written;
            seen.lastAccessMs = remoteMs;
            snapshot.put(book.path, seen);

            settings.recordSyncSuccess(book.name != null ? book.name : book.path);
            Log.i(TAG, "Pulled " + written + " from " + remote.device + " for " + book.path);
        }
    }

    /**
     * Records a NeoReader-authored (internal position, percentage) pair for
     * later percentage→position conversion on pulls. Skips rows we wrote
     * ourselves (their position is our estimate, not ground truth) and
     * positions too close to the start to calibrate reliably.
     */
    private void maybeCalibrate(OnyxMetadataRepository.Book book, SyncStateStore.BookState state) {
        if (equalsSafe(book.progress, state.lastWrittenProgress)) {
            return;
        }
        long position = book.positionV2();
        int page = book.currentPage();
        int total = book.totalPages();
        if (position < 0 || page <= 0 || total <= 0) {
            return;
        }
        double pct = (double) page / total;
        if (pct < 0.01) {
            return;
        }
        if (position != state.calibPosition || pct != state.calibPercentage) {
            state.calibPosition = position;
            state.calibPercentage = pct;
            stateStore.put(book.path, state);
        }
    }

    private String documentHash(String path) {
        String hash = SyncActions.documentHash(settings, path);
        if (hash == null) {
            Log.w(TAG, "Could not hash " + path + " (file missing or unreadable?)");
            settings.recordSyncError("Cannot read book file: " + path);
        }
        return hash;
    }

    private void remember(OnyxMetadataRepository.Book book) {
        Seen seen = new Seen();
        seen.progress = book.progress;
        seen.lastAccessMs = book.lastAccessMs;
        snapshot.put(book.path, seen);
    }

    private static boolean equalsSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // --- Notification -------------------------------------------------------

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        return builder
                .setContentTitle(getString(R.string.notification_title))
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }
}
