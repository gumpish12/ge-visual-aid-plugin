package com.ge.gevisualaid;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

@Slf4j
@Singleton
public class PushoverNotifier
{
    @Inject
    private GEVisualAidConfig config;

    private String lastSentKey = "";
    private long lastSentTime  = 0;
    private static final long COOLDOWN_MS = 5000;

    public void send(String title, String message, boolean urgent)
    {
        if (!config.pushoverEnabled()) return;
        String appKey  = config.pushoverAppKey();
        String userKey = config.pushoverUserKey();
        if (appKey.isEmpty() || userKey.isEmpty()) return;

        String key = title + message;
        long now = System.currentTimeMillis();
        if (key.equals(lastSentKey) && (now - lastSentTime) < COOLDOWN_MS) return;
        lastSentKey  = key;
        lastSentTime = now;

        // Priority: urgent dump alerts use priority 1 (bypasses silent mode)
        // if configured, otherwise priority 0 (normal)
        int priority = (urgent && config.pushoverUrgentBypassSilent()) ? 1 : 0;

        String body = "token="   + encode(appKey)
                + "&user="   + encode(userKey)
                + "&title="  + encode(title)
                + "&message=" + encode(message)
                + "&priority=" + priority
                + (priority == 1 ? "&retry=30&expire=120" : "");

        new Thread(() ->
        {
            try
            {
                HttpURLConnection conn = (HttpURLConnection)
                        new URL("https://api.pushover.net/1/messages.json").openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                try (OutputStream os = conn.getOutputStream())
                {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code != 200)
                    log.warn("Pushover returned: {}", code);
                conn.disconnect();
            }
            catch (Exception e)
            {
                log.warn("Pushover error: {}", e.getMessage());
            }
        }, "ge-visual-aid-pushover").start();
    }

    private String encode(String s)
    {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
}