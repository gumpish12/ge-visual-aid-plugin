package com.ge.gevisualaid;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Slf4j
@Singleton
public class DiscordNotifier
{
    @Inject
    private GEVisualAidConfig config;

    private String lastSentKey = "";
    private long lastSentTime  = 0;
    private static final long COOLDOWN_MS = 5000;

    public void send(String title, String description, int color, boolean urgent)
    {
        if (!config.discordEnabled()) return;
        String url = config.discordWebhookUrl();
        if (url == null || url.isEmpty()) return;

        String key = title + description;
        long now = System.currentTimeMillis();
        if (key.equals(lastSentKey) && (now - lastSentTime) < COOLDOWN_MS) return;
        lastSentKey  = key;
        lastSentTime = now;

        String embed = "{"
                + "\"embeds\": [{"
                + "\"title\": \"" + escape(title) + "\","
                + "\"description\": \"" + escape(description) + "\","
                + "\"color\": " + color + ","
                + "\"footer\": {\"text\": \"GE Visual Aid\"},"
                + "\"timestamp\": \"" + Instant.now() + "\""
                + "}]}";

        sendAsync(url, embed);
    }

    public void sendOfferComplete(String itemName, String offerType,
                                  int quantity, int priceEach, long profit)
    {
        if (!config.discordEnabled() || !config.discordNotifyOfferComplete()) return;
        String title = "✅ Offer Complete — " + itemName;
        String desc  = offerType + " x" + quantity
                + " @ " + String.format("%,d", priceEach) + "gp"
                + (profit != 0 ? "\nProfit: " + String.format("%,d", profit) + "gp" : "");
        send(title, desc, 5763719, false);
    }

    public void sendActionRequired(String action, String itemName, boolean dumpAlert)
    {
        if (!config.discordEnabled() || !config.discordNotifyActionRequired()) return;
        String title = dumpAlert ? "🚨 DUMP ALERT" : "🔔 Action Required";
        String desc  = formatAction(action)
                + (itemName.isEmpty() ? "" : " — " + itemName);
        int color    = dumpAlert ? 15158332 : 3447003;
        send(title, desc, color, dumpAlert);
    }

    public void sendCollectNeeded()
    {
        if (!config.discordEnabled() || !config.discordNotifyCollect()) return;
        send("📦 Collect Needed",
                "Items are ready to collect from the Grand Exchange.",
                15105570, false);
    }

    public void sendIdleAlert(String itemName)
    {
        if (!config.discordEnabled() || !config.discordNotifyIdle()) return;
        send("⏳ Action Pending",
                "GE Visual Aid is still waiting for action"
                        + (itemName.isEmpty() ? "." : " on " + itemName + "."),
                10197915, false);
    }

    public void sendOfferStuck(String itemName, int slotIndex)
    {
        if (!config.discordEnabled() || !config.discordNotifyStuck()) return;
        send("⚠️ Offer May Be Stuck",
                "Slot " + (slotIndex + 1) + " (" + itemName
                        + ") has not progressed in "
                        + config.offerStuckMinutes() + " minutes.",
                16776960, false);
    }

    public void sendGEFull()
    {
        if (!config.discordEnabled() || !config.discordNotifyGEFull()) return;
        send("🚫 GE Full",
                "All 8 Grand Exchange slots are occupied.",
                15158332, false);
    }

    private String formatAction(String action)
    {
        String s = action.replace("_", " ");
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String escape(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void sendAsync(String webhookUrl, String json)
    {
        new Thread(() ->
        {
            try
            {
                HttpURLConnection conn = (HttpURLConnection)
                        new URL(webhookUrl).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                try (OutputStream os = conn.getOutputStream())
                {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code != 204 && code != 200)
                    log.warn("Discord webhook returned: {}", code);
                conn.disconnect();
            }
            catch (Exception e)
            {
                log.warn("Discord webhook error: {}", e.getMessage());
            }
        }, "ge-visual-aid-discord").start();
    }
}