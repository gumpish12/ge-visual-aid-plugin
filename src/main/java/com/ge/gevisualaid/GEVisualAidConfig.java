package com.ge.gevisualaid;

import net.runelite.client.config.*;
import java.awt.Color;

@ConfigGroup("ge-visual-aid")
public interface GEVisualAidConfig extends Config
{
    // -----------------------------------------------------------------------
    // File Output
    // -----------------------------------------------------------------------
    @ConfigSection(name = "File Output",
            description = "Write GE state to a file each tick for assistive technology integrations",
            position = 0)
    String fileSection = "file";

    @ConfigItem(keyName = "fileOutputEnabled", name = "Enable file output",
            description = "Write GE state to a file each tick",
            section = fileSection, position = 0)
    default boolean fileOutputEnabled() { return true; }

    @ConfigItem(keyName = "outputFolder", name = "Output folder",
            description = "Folder where the state file is written. Filename uses account name automatically.",
            section = fileSection, position = 1)
    default String outputFolder() { return "C:\\"; }

    // -----------------------------------------------------------------------
    // Overlay
    // -----------------------------------------------------------------------
    @ConfigSection(name = "Overlay",
            description = "Pulsing visual highlight over suggested GE actions",
            position = 1)
    String overlaySection = "overlay";

    @ConfigItem(keyName = "overlayEnabled", name = "Enable overlay",
            description = "Draw a pulsing highlight box over the suggested action",
            section = overlaySection, position = 0)
    default boolean overlayEnabled() { return true; }

    @ConfigItem(keyName = "overlayColorNormal", name = "Normal action colour",
            description = "Colour for standard buy/sell/confirm actions",
            section = overlaySection, position = 1)
    default Color overlayColorNormal() { return new Color(0, 200, 255, 180); }

    @ConfigItem(keyName = "overlayColorDump", name = "Dump alert colour",
            description = "Colour for urgent dump/abort actions",
            section = overlaySection, position = 2)
    default Color overlayColorDump() { return new Color(255, 50, 50, 180); }

    @ConfigItem(keyName = "overlayColorModify", name = "Modify colour",
            description = "Colour for modify offer actions",
            section = overlaySection, position = 3)
    default Color overlayColorModify() { return new Color(255, 165, 0, 180); }

    @ConfigItem(keyName = "overlayColorCollect", name = "Collect colour",
            description = "Colour for collect actions",
            section = overlaySection, position = 4)
    default Color overlayColorCollect() { return new Color(0, 220, 100, 180); }

    @ConfigItem(keyName = "overlayPulse", name = "Pulse effect",
            description = "Make the highlight box pulse in and out",
            section = overlaySection, position = 5)
    default boolean overlayPulse() { return true; }

    @ConfigItem(keyName = "overlayPulseSpeed", name = "Pulse speed",
            description = "How fast the highlight pulses",
            section = overlaySection, position = 6)
    default PulseSpeed overlayPulseSpeed() { return PulseSpeed.MEDIUM; }

    @ConfigItem(keyName = "overlayBorderThickness", name = "Border thickness",
            description = "Thickness of the highlight border in pixels",
            section = overlaySection, position = 7)
    @Range(min = 1, max = 10)
    default int overlayBorderThickness() { return 2; }

    // -----------------------------------------------------------------------
    // Sound
    // -----------------------------------------------------------------------
    @ConfigSection(name = "Sound Alerts",
            description = "Audio cues for GE actions",
            position = 2)
    String soundSection = "sound";

    @ConfigItem(keyName = "soundEnabled", name = "Enable sound alerts",
            description = "Play sounds for GE events",
            section = soundSection, position = 0)
    default boolean soundEnabled() { return false; }

    @ConfigItem(keyName = "soundOnAction", name = "Beep on action required",
            description = "Play a beep when action is required",
            section = soundSection, position = 1)
    default boolean soundOnAction() { return true; }

    @ConfigItem(keyName = "soundOnDumpAlert", name = "Urgent beep on dump alert",
            description = "Play urgent triple beep on dump alerts",
            section = soundSection, position = 2)
    default boolean soundOnDumpAlert() { return true; }

    @ConfigItem(keyName = "soundOnOfferComplete", name = "Beep on offer complete",
            description = "Play a two-tone beep when an offer completes",
            section = soundSection, position = 3)
    default boolean soundOnOfferComplete() { return true; }

    @ConfigItem(keyName = "soundVolume", name = "Volume",
            description = "Sound alert volume",
            section = soundSection, position = 4)
    @Range(min = 1, max = 100)
    default int soundVolume() { return 80; }

    @ConfigItem(keyName = "soundCooldownSeconds", name = "Cooldown (seconds)",
            description = "Minimum seconds between repeated sound alerts",
            section = soundSection, position = 5)
    @Range(min = 1, max = 60)
    default int soundCooldownSeconds() { return 3; }

    // -----------------------------------------------------------------------
    // Discord
    // -----------------------------------------------------------------------
    @ConfigSection(name = "Discord Notifications",
            description = "Send notifications to Discord via webhook",
            position = 3)
    String discordSection = "discord";

    @ConfigItem(keyName = "discordEnabled", name = "Enable Discord",
            description = "Send webhook messages to Discord",
            section = discordSection, position = 0)
    default boolean discordEnabled() { return false; }

    @ConfigItem(keyName = "discordWebhookUrl", name = "Webhook URL",
            description = "Your Discord channel webhook URL",
            section = discordSection, position = 1)
    default String discordWebhookUrl() { return ""; }

    @ConfigItem(keyName = "discordNotifyActionRequired", name = "Notify on action required",
            description = "Send message when action is required",
            section = discordSection, position = 2)
    default boolean discordNotifyActionRequired() { return true; }

    @ConfigItem(keyName = "discordNotifyOfferComplete", name = "Notify on offer complete",
            description = "Send message when an offer completes",
            section = discordSection, position = 3)
    default boolean discordNotifyOfferComplete() { return true; }

    @ConfigItem(keyName = "discordNotifyCollect", name = "Notify on collect needed",
            description = "Send message when items need collecting",
            section = discordSection, position = 4)
    default boolean discordNotifyCollect() { return true; }

    @ConfigItem(keyName = "discordNotifyDumpAlert", name = "Notify on dump alert",
            description = "Send message on urgent dump alerts",
            section = discordSection, position = 5)
    default boolean discordNotifyDumpAlert() { return true; }

    @ConfigItem(keyName = "discordNotifyIdle", name = "Notify when action pending too long",
            description = "Send message if action required for too long with no change",
            section = discordSection, position = 6)
    default boolean discordNotifyIdle() { return true; }

    @ConfigItem(keyName = "discordNotifyStuck", name = "Notify on stuck offer",
            description = "Send message if an offer has not progressed",
            section = discordSection, position = 7)
    default boolean discordNotifyStuck() { return true; }

    @ConfigItem(keyName = "discordNotifyGEFull", name = "Notify when GE full",
            description = "Send message when all 8 slots are occupied",
            section = discordSection, position = 8)
    default boolean discordNotifyGEFull() { return true; }

    // -----------------------------------------------------------------------
    // Pushover
    // -----------------------------------------------------------------------
    @ConfigSection(name = "Pushover Notifications",
            description = "Send push notifications to your phone via Pushover",
            position = 4)
    String pushoverSection = "pushover";

    @ConfigItem(keyName = "pushoverEnabled", name = "Enable Pushover",
            description = "Send push notifications via Pushover",
            section = pushoverSection, position = 0)
    default boolean pushoverEnabled() { return false; }

    @ConfigItem(keyName = "pushoverAppKey", name = "App key",
            description = "Your Pushover application API key",
            section = pushoverSection, position = 1)
    default String pushoverAppKey() { return ""; }

    @ConfigItem(keyName = "pushoverUserKey", name = "User key",
            description = "Your Pushover user key",
            section = pushoverSection, position = 2)
    default String pushoverUserKey() { return ""; }

    @ConfigItem(keyName = "pushoverUrgentBypassSilent",
            name = "Dump alerts bypass silent mode",
            description = "Dump alert notifications bypass phone silent mode",
            section = pushoverSection, position = 3)
    default boolean pushoverUrgentBypassSilent() { return true; }

    @ConfigItem(keyName = "pushoverNotifyActionRequired", name = "Notify on action required",
            section = pushoverSection, position = 4,
            description = "Send push notification when action is required")
    default boolean pushoverNotifyActionRequired() { return true; }

    @ConfigItem(keyName = "pushoverNotifyOfferComplete", name = "Notify on offer complete",
            section = pushoverSection, position = 5,
            description = "Send push notification when an offer completes")
    default boolean pushoverNotifyOfferComplete() { return true; }

    @ConfigItem(keyName = "pushoverNotifyDumpAlert", name = "Notify on dump alert",
            section = pushoverSection, position = 6,
            description = "Send urgent push notification on dump alerts")
    default boolean pushoverNotifyDumpAlert() { return true; }

    // -----------------------------------------------------------------------
    // Smart Alerts
    // -----------------------------------------------------------------------
    @ConfigSection(name = "Smart Alerts",
            description = "Configurable threshold based alerts",
            position = 5)
    String smartSection = "smart";

    @ConfigItem(keyName = "idleAlertEnabled", name = "Idle alert",
            description = "Alert if action required for too long with no progress",
            section = smartSection, position = 0)
    default boolean idleAlertEnabled() { return true; }

    @ConfigItem(keyName = "idleAlertSeconds", name = "Idle alert threshold (seconds)",
            description = "How many seconds before sending idle alert",
            section = smartSection, position = 1)
    @Range(min = 10, max = 300)
    default int idleAlertSeconds() { return 30; }

    @ConfigItem(keyName = "offerStuckEnabled", name = "Stuck offer alert",
            description = "Alert if an offer has not progressed",
            section = smartSection, position = 2)
    default boolean offerStuckEnabled() { return true; }

    @ConfigItem(keyName = "offerStuckMinutes", name = "Stuck offer threshold (minutes)",
            description = "How many minutes before an offer is considered stuck",
            section = smartSection, position = 3)
    @Range(min = 1, max = 60)
    default int offerStuckMinutes() { return 10; }

    @ConfigItem(keyName = "geFulAlertEnabled", name = "GE full alert",
            description = "Alert when all 8 GE slots are occupied",
            section = smartSection, position = 4)
    default boolean geFullAlertEnabled() { return true; }

    // -----------------------------------------------------------------------
    // Tracking
    // -----------------------------------------------------------------------
    @ConfigSection(name = "Tracking",
            description = "Profit loss and session tracking",
            position = 6)
    String trackingSection = "tracking";

    @ConfigItem(keyName = "profitTrackingEnabled", name = "Enable profit tracking",
            description = "Track profit and loss per flip across sessions",
            section = trackingSection, position = 0)
    default boolean profitTrackingEnabled() { return true; }

    @ConfigItem(keyName = "sessionSummaryEnabled", name = "Show session summary",
            description = "Show total GP and flips in the panel",
            section = trackingSection, position = 1)
    default boolean sessionSummaryEnabled() { return true; }
}