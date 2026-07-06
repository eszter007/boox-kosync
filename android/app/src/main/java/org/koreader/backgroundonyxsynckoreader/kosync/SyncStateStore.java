package org.koreader.backgroundonyxsynckoreader.kosync;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Per-book sync bookkeeping, keyed by book path.
 *
 * Two jobs:
 *  - Echo suppression: when we write pulled remote progress into Onyx's
 *    Metadata provider, our own watcher sees that change. Recording exactly
 *    what we wrote lets the watcher tell "user turned a page in NeoReader"
 *    apart from "we wrote this ourselves" and avoid pushing it back.
 *  - Push dedup: remembering the last pushed progress avoids re-pushing an
 *    unchanged position on every poll tick.
 */
public class SyncStateStore {

    private static final String PREFS = "kosync_book_state";

    private final SharedPreferences prefs;

    public static class BookState {
        public String lastPushedProgress = "";   // Onyx progress string we last pushed, e.g. "42/300"
        public String lastWrittenProgress = "";  // progress string we last wrote into Onyx ourselves
        public long lastWrittenAccessMs = 0;     // lastAccess we wrote alongside it

        // Position calibration: a NeoReader-authored (internal position,
        // percentage) pair, used to convert a pulled percentage into
        // NeoReader's position units (~character offset, book-specific).
        public long calibPosition = -1;
        public double calibPercentage = 0;
    }

    public SyncStateStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public BookState get(String path) {
        BookState state = new BookState();
        String raw = prefs.getString(key(path), null);
        if (raw != null) {
            try {
                JSONObject json = new JSONObject(raw);
                state.lastPushedProgress = json.optString("pushed", "");
                state.lastWrittenProgress = json.optString("written", "");
                state.lastWrittenAccessMs = json.optLong("writtenAccess", 0);
                state.calibPosition = json.optLong("calibPos", -1);
                state.calibPercentage = json.optDouble("calibPct", 0);
            } catch (Exception ignored) {
            }
        }
        return state;
    }

    public void put(String path, BookState state) {
        try {
            JSONObject json = new JSONObject();
            json.put("pushed", state.lastPushedProgress);
            json.put("written", state.lastWrittenProgress);
            json.put("writtenAccess", state.lastWrittenAccessMs);
            json.put("calibPos", state.calibPosition);
            json.put("calibPct", state.calibPercentage);
            prefs.edit().putString(key(path), json.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static String key(String path) {
        return "book." + KoReaderDocumentHash.md5Hex(path);
    }
}
