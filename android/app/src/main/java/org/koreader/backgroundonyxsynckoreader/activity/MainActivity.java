package org.koreader.backgroundonyxsynckoreader.activity;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import org.koreader.backgroundonyxsynckoreader.R;
import org.koreader.backgroundonyxsynckoreader.contentprovider.OnyxMetadataRepository;
import org.koreader.backgroundonyxsynckoreader.kosync.KoSyncClient;
import org.koreader.backgroundonyxsynckoreader.kosync.SyncActions;
import org.koreader.backgroundonyxsynckoreader.kosync.SyncSettings;
import org.koreader.backgroundonyxsynckoreader.kosync.SyncStateStore;
import org.koreader.backgroundonyxsynckoreader.service.SyncService;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Settings screen: kosync server URL, username, password (stored as MD5
 * only), document match method, an authenticate round-trip test, and a
 * status area. Mirrors CrossPoint's KOReader Sync menu so the same values
 * can be copy-typed into both devices.
 */
public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final int REQ_POST_NOTIFICATIONS = 1002;

    private SyncSettings settings;
    private OnyxMetadataRepository repository;
    private SyncStateStore stateStore;
    private EditText serverUrlField;
    private EditText usernameField;
    private EditText passwordField;
    private RadioGroup matchMethodGroup;
    private TextView authResultView;
    private TextView statusView;
    private TextView manualBookView;
    private TextView manualLocalView;
    private TextView manualRemoteView;
    private Button applyRemoteButton;
    private Button uploadLocalButton;
    private AlertDialog storageDialog;

    // Latest book + remote progress from the last "Check progress" run.
    private OnyxMetadataRepository.Book manualBook;
    private KoSyncClient.Progress manualRemote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = new SyncSettings(this);
        serverUrlField = findViewById(R.id.edit_server_url);
        usernameField = findViewById(R.id.edit_username);
        passwordField = findViewById(R.id.edit_password);
        matchMethodGroup = findViewById(R.id.group_match_method);
        authResultView = findViewById(R.id.text_auth_result);
        statusView = findViewById(R.id.text_status);

        serverUrlField.setText(settings.getServerUrl());
        usernameField.setText(settings.getUsername());
        if (!settings.getPasswordMd5().isEmpty()) {
            passwordField.setHint(R.string.password_stored_hint);
        }
        matchMethodGroup.check(SyncSettings.MATCH_FILENAME.equals(settings.getMatchMethod())
                ? R.id.radio_match_filename : R.id.radio_match_binary);

        Button authButton = findViewById(R.id.button_authenticate);
        authButton.setOnClickListener(v -> saveAndAuthenticate());

        repository = new OnyxMetadataRepository(this);
        stateStore = new SyncStateStore(this);
        manualBookView = findViewById(R.id.text_manual_book);
        manualLocalView = findViewById(R.id.text_manual_local);
        manualRemoteView = findViewById(R.id.text_manual_remote);
        applyRemoteButton = findViewById(R.id.button_apply_remote);
        uploadLocalButton = findViewById(R.id.button_upload_local);
        findViewById(R.id.button_manual_check).setOnClickListener(v -> manualCheck());
        applyRemoteButton.setOnClickListener(v -> manualApplyRemote());
        uploadLocalButton.setOnClickListener(v -> manualUploadLocal());

        requestNotificationPermissionIfNeeded();
        ensureAllFilesAccess();
        SyncService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        // Returning from system settings — dismiss the dialog if access was granted.
        if (storageDialog != null && hasAllFilesAccess()) {
            storageDialog.dismiss();
            storageDialog = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (storageDialog != null && storageDialog.isShowing()) {
            storageDialog.dismiss();
        }
        storageDialog = null;
    }

    private void saveSettings() {
        settings.setServerUrl(serverUrlField.getText().toString());
        settings.setUsername(usernameField.getText().toString());
        String password = passwordField.getText().toString();
        if (!password.isEmpty()) {
            // Hash once at entry time; the plaintext is never persisted.
            settings.setPassword(password);
            passwordField.setText("");
            passwordField.setHint(R.string.password_stored_hint);
        }
        settings.setMatchMethod(
                matchMethodGroup.getCheckedRadioButtonId() == R.id.radio_match_filename
                        ? SyncSettings.MATCH_FILENAME : SyncSettings.MATCH_BINARY);
    }

    private void saveAndAuthenticate() {
        saveSettings();
        authResultView.setText(R.string.auth_in_progress);
        new Thread(() -> {
            KoSyncClient client = new KoSyncClient(settings, "BooxCompanion", "settings-ui");
            KoSyncClient.Result result = client.authenticate();
            String message = result.error == KoSyncClient.Error.OK
                    ? getString(R.string.auth_success)
                    : KoSyncClient.errorString(result);
            runOnUiThread(() -> {
                authResultView.setText(message);
                if (result.error != KoSyncClient.Error.OK) {
                    settings.recordSyncError(message);
                }
                refreshStatus();
            });
        }).start();
        SyncService.start(this);
    }

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        long lastSync = settings.getLastSyncMs();
        if (lastSync > 0) {
            sb.append("Last sync: ")
                    .append(DateFormat.getDateTimeInstance().format(new Date(lastSync)));
            if (!settings.getLastBook().isEmpty()) {
                sb.append("\nLast book: ").append(settings.getLastBook());
            }
        } else {
            sb.append(getString(R.string.status_never_synced));
        }
        if (!settings.getLastError().isEmpty()) {
            sb.append("\nLast error: ").append(settings.getLastError());
        }
        statusView.setText(sb.toString());
    }

    // --- Manual sync ---------------------------------------------------------

    private KoSyncClient buildClient() {
        String androidId = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ANDROID_ID);
        return new KoSyncClient(settings, "BooxCompanion",
                androidId != null ? androidId : "boox-companion");
    }

    private static String formatPercent(int page, int total) {
        return String.format(Locale.US, "page %d/%d (%.1f%%)", page, total,
                100.0 * page / total);
    }

    /**
     * Fetches the most recently read book and its server-side progress, and
     * arms the Apply remote / Upload local buttons. Both deliberately skip
     * the automatic newest-timestamp rule — here the user decides.
     */
    private void manualCheck() {
        saveSettings();
        manualBookView.setText(R.string.manual_checking);
        manualLocalView.setText("");
        manualRemoteView.setText("");
        applyRemoteButton.setEnabled(false);
        uploadLocalButton.setEnabled(false);
        new Thread(() -> {
            List<OnyxMetadataRepository.Book> books = repository.queryBooksWithProgress();
            if (books.isEmpty()) {
                runOnUiThread(() -> manualBookView.setText(R.string.manual_no_books));
                return;
            }
            OnyxMetadataRepository.Book book = books.get(0);
            String hash = SyncActions.documentHash(settings, book.path);
            KoSyncClient.Result result = hash != null ? buildClient().getProgress(hash) : null;
            runOnUiThread(() -> {
                manualBook = book;
                manualRemote = (result != null && result.error == KoSyncClient.Error.OK)
                        ? result.progress : null;
                manualBookView.setText(book.name != null ? book.name : book.path);
                String local = "Local: " + formatPercent(book.currentPage(), book.totalPages());
                if (book.lastAccessMs > 0) {
                    local += " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(new Date(book.lastAccessMs));
                }
                manualLocalView.setText(local);
                if (manualRemote != null) {
                    int total = book.totalPages();
                    int remotePage = Math.max(1, Math.min(total,
                            (int) Math.round(manualRemote.percentage * total)));
                    manualRemoteView.setText(String.format(Locale.US,
                            "Remote: page %d/%d (%.1f%%) · %s · %s",
                            remotePage, total, manualRemote.percentage * 100,
                            manualRemote.device,
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(new Date(manualRemote.timestampSec * 1000L))));
                } else if (result != null && result.error == KoSyncClient.Error.NOT_FOUND) {
                    manualRemoteView.setText(R.string.manual_no_remote);
                } else {
                    manualRemoteView.setText("Remote: " + (result != null
                            ? KoSyncClient.errorString(result) : "cannot hash book file"));
                }
                applyRemoteButton.setEnabled(manualRemote != null && book.totalPages() > 0);
                uploadLocalButton.setEnabled(hash != null
                        && book.currentPage() > 0 && book.totalPages() > 0);
            });
        }).start();
    }

    private void manualApplyRemote() {
        final OnyxMetadataRepository.Book book = manualBook;
        final KoSyncClient.Progress remote = manualRemote;
        if (book == null || remote == null) {
            return;
        }
        applyRemoteButton.setEnabled(false);
        new Thread(() -> {
            String written = SyncActions.applyRemote(this, repository, stateStore, book, remote);
            runOnUiThread(() -> {
                manualRemoteView.setText(written != null
                        ? getString(R.string.manual_apply_done)
                        : "Apply failed — book not found in Onyx library?");
                if (written != null) {
                    settings.recordSyncSuccess(book.name != null ? book.name : book.path);
                }
                refreshStatus();
                manualCheck();
            });
        }).start();
    }

    private void manualUploadLocal() {
        final OnyxMetadataRepository.Book book = manualBook;
        if (book == null) {
            return;
        }
        uploadLocalButton.setEnabled(false);
        new Thread(() -> {
            String hash = SyncActions.documentHash(settings, book.path);
            KoSyncClient.Result result = hash != null
                    ? SyncActions.uploadLocal(buildClient(), stateStore, book, hash) : null;
            boolean ok = result != null && result.error == KoSyncClient.Error.OK;
            runOnUiThread(() -> {
                manualRemoteView.setText(ok
                        ? getString(R.string.manual_upload_done)
                        : "Upload failed: " + (result != null
                                ? KoSyncClient.errorString(result) : "cannot hash book file"));
                if (ok) {
                    settings.recordSyncSuccess(book.name != null ? book.name : book.path);
                }
                refreshStatus();
                manualCheck();
            });
        }).start();
    }

    // --- Permissions ---------------------------------------------------------

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_POST_NOTIFICATIONS);
        }
    }

    private boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || Environment.isExternalStorageManager();
    }

    /**
     * Binary document matching reads the book files themselves, which live
     * in shared storage — that needs "All files access" on Android 11+.
     */
    private void ensureAllFilesAccess() {
        if (hasAllFilesAccess()) {
            return;
        }
        storageDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.storage_dialog_title)
                .setMessage(R.string.storage_dialog_message)
                .setPositiveButton(R.string.storage_dialog_open_settings, (d, w) -> {
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        Log.w(TAG, "Could not open app-specific settings, falling back", e);
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                })
                .setNegativeButton(R.string.storage_dialog_cancel, null)
                .show();
    }
}
