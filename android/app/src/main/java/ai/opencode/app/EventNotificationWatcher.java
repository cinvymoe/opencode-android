package ai.opencode.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Subscribes to the server SSE stream in a background thread and posts system notifications. */
public class EventNotificationWatcher {
    private static final String TAG = "EventWatcher";
    private static final String PREFS = "CapacitorStorage";
    private static final String SERVER_KEY = "opencode.android.defaultServer";
    private static final String PASSWORD_KEY = "opencode.android.defaultServerPassword";
    private static final String MUTED_KEY = "opencode.android.notifications.muted";
    private static final String CHANNEL_ID = "opencode_alerts";
    private static final int NOTIFICATION_BASE_ID = 1000;
    private static final long MAX_BACKOFF_MS = 60_000L;
    private static final int MAX_SEEN_IDS = 500;
    private static final long DEDUP_WINDOW_MS = 15_000L;

    private final Context context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final LinkedHashMap<String, Long> recentNotifications = new LinkedHashMap<>();
    private Thread thread;
    private volatile HttpURLConnection connection;

    public EventNotificationWatcher(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        thread = new Thread(this::runLoop, "event-notification-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running.set(false);
        Thread t = thread;
        if (t != null) t.interrupt();
        HttpURLConnection c = connection;
        if (c != null) c.disconnect();
    }

    private void runLoop() {
        LinkedHashSet<String> seenIds = new LinkedHashSet<>();
        long backoffMs = 1_000L;
        while (running.get()) {
            try {
                connectAndRead(seenIds);
                backoffMs = 1_000L;
            } catch (Exception e) {
                Log.i(TAG, "error: " + e.getMessage());
            }
            if (!running.get()) break;
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        }
    }

    private void connectAndRead(LinkedHashSet<String> seenIds) throws Exception {
        String server = serverUrl();
        if (server == null) {
            Log.i(TAG, "error: no server configured");
            return;
        }
        URL url = new URL(server + "/api/event");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        connection = conn;
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        applyAuth(conn);
        int status = conn.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            Log.i(TAG, "error: HTTP " + status);
            return;
        }
        Log.i(TAG, "connected to " + server);
        try {
            InputStream body = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            String eventLine = null;
            String dataLine = null;
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (dataLine != null) {
                        handleFrame(eventLine, dataLine, seenIds);
                    }
                    eventLine = null;
                    dataLine = null;
                    continue;
                }
                if (line.startsWith(":")) continue; // heartbeat comment
                if (line.startsWith("event:")) {
                    eventLine = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    dataLine = line.substring(5).trim();
                }
            }
            Log.i(TAG, "stream ended");
        } finally {
            conn.disconnect();
            connection = null;
        }
    }

    private void handleFrame(String eventLine, String dataLine, LinkedHashSet<String> seenIds) {
        try {
            JSONObject json = new JSONObject(dataLine);
            String type = eventLine != null ? eventLine : json.optString("type", "");
            if (type.isEmpty()) type = json.optString("type", "");
            String id = json.optString("id", "");
            if (!id.isEmpty()) {
                if (seenIds.contains(id)) return;
                seenIds.add(id);
                while (seenIds.size() > MAX_SEEN_IDS) {
                    java.util.Iterator<String> it = seenIds.iterator();
                    it.next();
                    it.remove();
                }
            }
            if (isMuted()) return;
            JSONObject data = json.optJSONObject("data");
            if (data == null) return;
            switch (type) {
                case "session.execution.succeeded":
                    notifySession(data.optString("sessionID", null), context.getString(R.string.notification_response_ready_title), null, id);
                    break;
                case "session.execution.failed":
                    String error = data.optJSONObject("error") != null
                        ? data.optJSONObject("error").optString("message", null)
                        : null;
                    notifySession(data.optString("sessionID", null), context.getString(R.string.notification_failed_title), error, id);
                    break;
                case "form.created": {
                    JSONObject form = data.optJSONObject("form");
                    if (form == null) break;
                    notifySession(form.optString("sessionID", null), context.getString(R.string.notification_question_title), null, id);
                    break;
                }
                case "permission.asked":
                    notifySession(data.optString("sessionID", null), context.getString(R.string.notification_permission_title), null, id);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            Log.i(TAG, "error: frame parse " + e.getMessage());
        }
    }

    private void notifySession(String sessionID, String title, String error, String eventId) {
        // The bus can emit the same completion more than once (duplicate frames
        // with distinct ids); collapse repeats for the same type+session shortly
        // after each other instead of alerting twice.
        long now = System.currentTimeMillis();
        String key = title + "\0" + (sessionID != null ? sessionID : "");
        Long last = recentNotifications.get(key);
        if (last != null && now - last < DEDUP_WINDOW_MS) return;
        recentNotifications.put(key, now);
        if (recentNotifications.size() > 100) {
            java.util.Iterator<java.util.Map.Entry<String, Long>> it = recentNotifications.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > DEDUP_WINDOW_MS) it.remove();
                else break;
            }
        }
        // Never surface the raw session id; fall back to a generic label when the
        // session title cannot be resolved.
        String description;
        if (sessionID != null && !sessionID.isEmpty()) {
            SessionMeta meta = sessionMeta(sessionID);
            if (meta != null && meta.parentID != null && !meta.parentID.isEmpty()) return; // child session: skip
            description = meta != null && meta.title != null && !meta.title.isEmpty()
                ? meta.title
                : context.getString(R.string.notification_session_fallback);
        } else {
            description = context.getString(R.string.notification_session_fallback);
        }
        postNotification(title, error != null ? error : description, sessionID, eventId);
    }

    private void postNotification(String title, String contentText, String sessionID, String eventId) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        // The channel is immutable after first creation, so build it with sound,
        // vibration, and badge explicitly. A dedicated id avoids inheriting the
        // silent state an earlier plugin-created channel may have on the device.
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_alerts_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT);
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build();
        channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrs);
        channel.enableVibration(true);
        channel.setShowBadge(true);
        manager.createNotificationChannel(channel);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
            // Belt and braces for OEMs that route builder-level sound independently
            // of the channel: the alert belongs on the notification stream.
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), AudioAttributes.USAGE_NOTIFICATION)
            .setAutoCancel(true)
            .setContentIntent(deepLinkIntent(sessionID))
            .build();
        int notificationId = NOTIFICATION_BASE_ID + (eventId != null ? Math.abs(eventId.hashCode()) % 100_000 : (int) (System.currentTimeMillis() % 100_000));
        manager.notify(notificationId, notification);
        Log.i(TAG, "notified: " + title);
    }

    // Taps open the app directly on the session via the opencode:// deep link,
    // which the web layer turns into a tab navigation.
    private PendingIntent deepLinkIntent(String sessionID) {
        Intent intent = new Intent(
            Intent.ACTION_VIEW,
            Uri.parse("opencode://session/" + (sessionID != null ? sessionID : "")));
        intent.setClassName(context, "ai.opencode.app.MainActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(context, 0, intent, piFlags);
    }

    private boolean isMuted() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return "1".equals(prefs.getString(MUTED_KEY, "0"));
    }

    private String serverUrl() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String server = prefs.getString(SERVER_KEY, null);
        if (server == null || server.isEmpty()) return null;
        if (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        return server;
    }

    // The web client authenticates with Basic base64("opencode:<password>"); mirror that here.
    private void applyAuth(HttpURLConnection conn) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String password = prefs.getString(PASSWORD_KEY, null);
        if (password == null || password.isEmpty()) return;
        String token = Base64.encodeToString(("opencode:" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        conn.setRequestProperty("Authorization", "Basic " + token);
    }

    private static class SessionMeta {
        final String title;
        final String parentID;
        SessionMeta(String title, String parentID) {
            this.title = title;
            this.parentID = parentID;
        }
    }

    private SessionMeta sessionMeta(String sessionID) {
        try {
            String server = serverUrl();
            if (server == null) return null;
            URL url = new URL(server + "/api/session/" + sessionID);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            applyAuth(conn);
            conn.setRequestMethod("GET");
            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                return null;
            }
            try (InputStream body = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                JSONObject json = new JSONObject(sb.toString());
                // The API wraps payloads in a "data" envelope.
                JSONObject data = json.optJSONObject("data");
                if (data == null) return new SessionMeta(null, null);
                return new SessionMeta(data.optString("title", null), data.optString("parentID", null));
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.i(TAG, "error: session meta " + e.getMessage());
            return null;
        }
    }
}
