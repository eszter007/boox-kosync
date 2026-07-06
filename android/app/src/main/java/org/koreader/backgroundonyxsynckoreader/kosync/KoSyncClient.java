package org.koreader.backgroundonyxsynckoreader.kosync;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal KOReader-Sync (kosync) protocol client.
 *
 * Wire format matches CrossPoint's KOReaderSyncClient:
 *   GET  {base}/users/auth               -> 200 ok / 401 bad credentials
 *   GET  {base}/syncs/progress/{docHash} -> progress JSON / 404 none
 *   PUT  {base}/syncs/progress           -> {document, progress, percentage, device, device_id}
 * Auth is the kosync header pair x-auth-user / x-auth-key, where the key is
 * the MD5 hex of the password (never the plaintext).
 */
public class KoSyncClient {

    private static final String TAG = "KoSyncClient";
    private static final int TIMEOUT_MS = 15000;

    public enum Error {
        OK, NO_CREDENTIALS, NETWORK_ERROR, AUTH_FAILED, SERVER_ERROR, JSON_ERROR, NOT_FOUND
    }

    public static class Progress {
        public String document;
        public String progress;      // xpath string (KOReader/CrossPoint) or page number
        public double percentage;    // 0.0 .. 1.0
        public String device;
        public String deviceId;
        public long timestampSec;    // server-side timestamp, epoch seconds
    }

    public static class Result {
        public final Error error;
        public final int httpCode;
        public final Progress progress;

        Result(Error error, int httpCode, Progress progress) {
            this.error = error;
            this.httpCode = httpCode;
            this.progress = progress;
        }
    }

    private final SyncSettings settings;
    private final String deviceName;
    private final String deviceId;

    public KoSyncClient(SyncSettings settings, String deviceName, String deviceId) {
        this.settings = settings;
        this.deviceName = deviceName;
        this.deviceId = deviceId;
    }

    public Result authenticate() {
        if (!settings.hasCredentials()) {
            return new Result(Error.NO_CREDENTIALS, 0, null);
        }
        try {
            HttpURLConnection conn = open("/users/auth", "GET");
            int code = conn.getResponseCode();
            drain(conn);
            Log.d(TAG, "auth -> " + code);
            if (code == 200) return new Result(Error.OK, code, null);
            if (code == 401) return new Result(Error.AUTH_FAILED, code, null);
            return new Result(Error.SERVER_ERROR, code, null);
        } catch (Exception e) {
            Log.w(TAG, "auth failed: " + e);
            return new Result(Error.NETWORK_ERROR, 0, null);
        }
    }

    public Result getProgress(String documentHash) {
        if (!settings.hasCredentials()) {
            return new Result(Error.NO_CREDENTIALS, 0, null);
        }
        try {
            HttpURLConnection conn = open("/syncs/progress/" + documentHash, "GET");
            int code = conn.getResponseCode();
            String body = readBody(conn);
            Log.d(TAG, "getProgress " + documentHash + " -> " + code);
            if (code == 200 && body != null) {
                try {
                    JSONObject json = new JSONObject(body);
                    Progress p = new Progress();
                    p.document = documentHash;
                    p.progress = json.optString("progress", "");
                    p.percentage = json.optDouble("percentage", 0.0);
                    p.device = json.optString("device", "");
                    p.deviceId = json.optString("device_id", "");
                    p.timestampSec = json.optLong("timestamp", 0);
                    // A 200 with no percentage/timestamp means the server has
                    // no progress for this document (some servers do this
                    // instead of returning 404).
                    if (p.timestampSec == 0 && !json.has("percentage")) {
                        return new Result(Error.NOT_FOUND, code, null);
                    }
                    return new Result(Error.OK, code, p);
                } catch (Exception e) {
                    Log.w(TAG, "getProgress JSON parse failed: " + e);
                    return new Result(Error.JSON_ERROR, code, null);
                }
            }
            if (code == 401) return new Result(Error.AUTH_FAILED, code, null);
            if (code == 404) return new Result(Error.NOT_FOUND, code, null);
            return new Result(Error.SERVER_ERROR, code, null);
        } catch (Exception e) {
            Log.w(TAG, "getProgress failed: " + e);
            return new Result(Error.NETWORK_ERROR, 0, null);
        }
    }

    public Result updateProgress(String documentHash, String progress, double percentage) {
        if (!settings.hasCredentials()) {
            return new Result(Error.NO_CREDENTIALS, 0, null);
        }
        try {
            JSONObject json = new JSONObject();
            json.put("document", documentHash);
            json.put("progress", progress);
            json.put("percentage", percentage);
            json.put("device", deviceName);
            json.put("device_id", deviceId);
            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            HttpURLConnection conn = open("/syncs/progress", "PUT");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            drain(conn);
            Log.d(TAG, "updateProgress " + documentHash + " " + percentage + " -> " + code);
            if (code == 200 || code == 202) return new Result(Error.OK, code, null);
            if (code == 401) return new Result(Error.AUTH_FAILED, code, null);
            return new Result(Error.SERVER_ERROR, code, null);
        } catch (Exception e) {
            Log.w(TAG, "updateProgress failed: " + e);
            return new Result(Error.NETWORK_ERROR, 0, null);
        }
    }

    public static String errorString(Result result) {
        switch (result.error) {
            case OK: return "Success";
            case NO_CREDENTIALS: return "No credentials configured";
            case NETWORK_ERROR: return "Network error";
            case AUTH_FAILED: return "Authentication failed";
            case SERVER_ERROR: return "Server error (HTTP " + result.httpCode + ")";
            case JSON_ERROR: return "Bad server response";
            case NOT_FOUND: return "No progress found on server";
            default: return "Unknown error";
        }
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        URL url = new URL(settings.getBaseUrl() + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/vnd.koreader.v1+json");
        conn.setRequestProperty("x-auth-user", settings.getUsername());
        conn.setRequestProperty("x-auth-key", settings.getPasswordMd5());
        return conn;
    }

    private static String readBody(HttpURLConnection conn) {
        try {
            InputStream stream = conn.getResponseCode() >= 400
                    ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                char[] buf = new char[1024];
                int n;
                while ((n = reader.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            conn.disconnect();
        }
    }

    private static void drain(HttpURLConnection conn) {
        readBody(conn);
    }
}
