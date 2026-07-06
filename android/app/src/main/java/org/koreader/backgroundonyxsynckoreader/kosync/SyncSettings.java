package org.koreader.backgroundonyxsynckoreader.kosync;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persistent sync configuration and status, backed by SharedPreferences.
 *
 * Only the MD5 hex of the password is ever stored — it is computed once when
 * the user types the password (same as CrossPoint does internally), so the
 * x-auth-key credential lines up byte-for-byte across both devices. The
 * plaintext password never touches disk or logs.
 */
public class SyncSettings {

    public static final String MATCH_BINARY = "binary";     // KOReader partial MD5 (default)
    public static final String MATCH_FILENAME = "filename"; // MD5 of filename

    private static final String PREFS = "kosync_settings";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD_MD5 = "password_md5";
    private static final String KEY_MATCH_METHOD = "match_method";
    private static final String KEY_LAST_SYNC_MS = "last_sync_ms";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_LAST_BOOK = "last_book";

    private static final String DEFAULT_SERVER_URL = "https://sync.koreader.rocks";

    private final SharedPreferences prefs;

    public SyncSettings(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, "");
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, "");
    }

    public String getPasswordMd5() {
        return prefs.getString(KEY_PASSWORD_MD5, "");
    }

    public String getMatchMethod() {
        return prefs.getString(KEY_MATCH_METHOD, MATCH_BINARY);
    }

    public void setServerUrl(String url) {
        prefs.edit().putString(KEY_SERVER_URL, url.trim()).apply();
    }

    public void setUsername(String username) {
        prefs.edit().putString(KEY_USERNAME, username.trim()).apply();
    }

    /** Hashes and stores the password; the plaintext is discarded here. */
    public void setPassword(String plaintextPassword) {
        prefs.edit().putString(KEY_PASSWORD_MD5,
                KoReaderDocumentHash.md5Hex(plaintextPassword)).apply();
    }

    public void setMatchMethod(String method) {
        prefs.edit().putString(KEY_MATCH_METHOD, method).apply();
    }

    public boolean hasCredentials() {
        return !getUsername().isEmpty() && !getPasswordMd5().isEmpty();
    }

    /**
     * Base URL normalized like CrossPoint's KOReaderCredentialStore: default
     * server when empty, http:// prepended when no scheme, trailing slashes
     * stripped.
     */
    public String getBaseUrl() {
        String url = getServerUrl();
        if (url.isEmpty()) {
            url = DEFAULT_SERVER_URL;
        } else if (!url.contains("://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    // --- Status area -------------------------------------------------------

    public long getLastSyncMs() {
        return prefs.getLong(KEY_LAST_SYNC_MS, 0);
    }

    public String getLastError() {
        return prefs.getString(KEY_LAST_ERROR, "");
    }

    public String getLastBook() {
        return prefs.getString(KEY_LAST_BOOK, "");
    }

    public void recordSyncSuccess(String bookName) {
        prefs.edit()
                .putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis())
                .putString(KEY_LAST_BOOK, bookName)
                .putString(KEY_LAST_ERROR, "")
                .apply();
    }

    public void recordSyncError(String error) {
        prefs.edit().putString(KEY_LAST_ERROR, error).apply();
    }
}
