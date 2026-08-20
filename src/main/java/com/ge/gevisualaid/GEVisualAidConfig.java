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

    // -----------------------------------------------------------------------
    // HTTP Server (Plugin v2.13)
    // -----------------------------------------------------------------------
    @ConfigSection(name = "HTTP Server",
            description = "Serve the GE state over a local HTTP endpoint — avoids file read/write race conditions",
            position = 7)
    String httpSection = "http";

    @ConfigItem(keyName = "httpServerEnabled", name = "Enable HTTP server",
            description = "Start a local HTTP server serving the GE state at http://127.0.0.1:<port>/state",
            section = httpSection, position = 0)
    default boolean httpServerEnabled() { return true; }

    @ConfigItem(keyName = "httpServerPort", name = "Port",
            description = "Local port for the HTTP server (default 8081). Change if another process uses this port.",
            section = httpSection, position = 1)
    @Range(min = 1024, max = 65535)
    default int httpServerPort() { return 8081; }

    // -----------------------------------------------------------------------
    // Scene & Tiles (Plugin v2.22)
    //
    // All off by default. The master switch gates every client read in the
    // scene block, so a VM that only flips pays nothing for features it does
    // not use — the block collapses to a single scene_enabled=false line.
    // -----------------------------------------------------------------------
    @ConfigSection(name = "Scene & Tiles",
            description = "MASTER SWITCH for everything below, plus camera, canvas and "
                    + "hovered tile geometry. Turn this off and none of the sections from "
                    + "here down are computed at all. All off by default.",
            position = 8, closedByDefault = true)
    String sceneSection = "scene";

    // Everything below was one 24-item "Scene & Tiles" list, which meant
    // finding a setting required reading all of it. Same keyNames, so no
    // saved setting is lost - only where it appears in the panel changes.
    @ConfigSection(name = "Movement & Pathing",
            description = "Where the player is heading: movement flags, the Shortest Path "
                    + "route, and named waypoints resolved to screen coordinates. "
                    + "Requires Scene & Tiles enabled.",
            position = 9, closedByDefault = true)
    String moveSection = "movement";

    @ConfigSection(name = "Skilling",
            description = "Per-skill trackers: agility courses, Blast Furnace, runite rocks "
                    + "and sailing. Requires Scene & Tiles enabled.",
            position = 10, closedByDefault = true)
    String skillSection = "skilling";

    @ConfigSection(name = "Magic & Runes",
            description = "Rune counts and how many casts of a chosen spell remain. "
                    + "Requires Scene & Tiles enabled.",
            position = 11, closedByDefault = true)
    String magicSection = "magic";

    @ConfigSection(name = "Player & Combat",
            description = "Vitals, active prayers, incoming attack style, what the player is "
                    + "currently doing, and NPC aggression. Requires Scene & Tiles enabled.",
            position = 12, closedByDefault = true)
    String combatSection = "playercombat";

    @ConfigSection(name = "Bank",
            description = "Quantities of watched items, read while the bank is open and "
                    + "reported as last-known once it closes. Requires Scene & Tiles enabled.",
            position = 13, closedByDefault = true)
    String bankSection = "bank";

    @ConfigSection(name = "Interface & Widgets",
            description = "Screen coordinates of named interface widgets. "
                    + "Requires Scene & Tiles enabled.",
            position = 14, closedByDefault = true)
    String widgetSection = "widgets";

    @ConfigItem(keyName = "sceneTrackingEnabled", name = "Enable scene tracking",
            description = "Master switch. When off, none of the options below are computed "
                    + "at all and the output carries a single scene_enabled=false line. "
                    + "Leave off on accounts that only flip.",
            section = sceneSection, position = 0)
    default boolean sceneTrackingEnabled() { return false; }

    @ConfigItem(keyName = "cameraStateEnabled", name = "Camera state",
            description = "Emit detached_camera (whether the Detached Camera plugin's "
                    + "toggle is currently engaged) plus camera_world_x/y/z. Very cheap.",
            section = sceneSection, position = 1)
    default boolean cameraStateEnabled() { return true; }

    @ConfigItem(keyName = "canvasGeometryEnabled", name = "Canvas geometry",
            description = "Emit the canvas position, size, DPI scale and viewport bounds. "
                    + "Cheap, and required for waypoints — enabling waypoints turns this "
                    + "on regardless.",
            section = sceneSection, position = 2)
    default boolean canvasGeometryEnabled() { return true; }

    @ConfigItem(keyName = "waypointsEnabled", name = "Waypoint screen coordinates",
            description = "Resolve the named world tiles below to live screen pixels every "
                    + "tick. Cost is one projection per tile, so a handful is negligible.",
            section = moveSection, position = 2)
    default boolean waypointsEnabled() { return false; }

    @ConfigItem(keyName = "waypointList", name = "Waypoints (always on)",
            description = "Resolved whenever waypoints are enabled, alongside any enabled "
                    + "bundles. Single tile: name:x:y or name:x:y:plane. Cluster area: "
                    + "name:x1:y1-x2:y2 or name:x1:y1-x2:y2:plane. Separate entries with "
                    + "commas or new lines. Example: combat:2882:3542, "
                    + "pen:2437:9161-2440:9164",
            section = moveSection, position = 3)
    default String waypointList() { return ""; }

    @ConfigItem(keyName = "hoverTileEnabled", name = "Hovered tile",
            description = "Emit the world coordinates of the tile under the mouse cursor. "
                    + "This is the most expensive option here — it sweeps the scene every "
                    + "tick — so leave it off unless something is actually reading it.",
            section = sceneSection, position = 3)
    default boolean hoverTileEnabled() { return false; }

    @ConfigItem(keyName = "loadingLinesEnabled", name = "Loading lines",
            description = "Emit the four scene-reload boundaries in world coordinates, the "
                    + "distance to each, and a click box on the nearest. A reload shifts the "
                    + "scene base and invalidates cached screen coordinates, so it helps to "
                    + "see one coming. Cheap.",
            section = sceneSection, position = 4)
    default boolean loadingLinesEnabled() { return false; }

    @ConfigItem(keyName = "movementFlagsEnabled", name = "Movement flags",
            description = "Emit collision data for the eight tiles around the player - "
                    + "walkable, world coordinate and click box each - plus the raw flag "
                    + "values for the player's tile and the hovered tile. Cheap, but adds "
                    + "around 70 lines to the output.",
            section = moveSection, position = 0)
    default boolean movementFlagsEnabled() { return false; }

    @ConfigItem(keyName = "pathTrackingEnabled", name = "Shortest Path route",
            description = "Drive the Shortest Path plugin and read its route back as "
                    + "clickable pixels. Set a destination with "
                    + "GET /path?x=..&y=..&plane=.. on this plugin's HTTP port, or clear it "
                    + "with /path?clear=1. Requires the Shortest Path plugin installed; "
                    + "path_plugin_found reports whether it was found.",
            section = moveSection, position = 1)
    default boolean pathTrackingEnabled() { return false; }

    @ConfigItem(keyName = "agilityTrackingEnabled", name = "Agility obstacles & marks",
            description = "Mirror RuneLite's Agility plugin - the course obstacles it is "
                    + "highlighting, marks of grace, traps and the Werewolf stick, each as "
                    + "a click box, plus which obstacle each mark sits beside. Obstacles "
                    + "are sorted nearest first. Requires the Agility plugin enabled; "
                    + "agility_plugin_found reports whether it was found. "
                    + "NOTE: the course ORDER comes from Rooftop Agility Improved and is "
                    + "published separately as rooftop_next_*, which needs no setting here.",
            section = skillSection, position = 0)
    default boolean agilityTrackingEnabled() { return false; }

    @ConfigItem(keyName = "agilityCourseOrder", name = "Agility course order (fallback only)",
            description = "LEAVE THIS EMPTY if Rooftop Agility Improved is installed - it "
                    + "knows every implemented course and rooftop_next_* is authoritative. "
                    + "This is the manual fallback for when it is not: the course's "
                    + "obstacle start tiles IN ORDER, x:y or x:y:plane, comma or newline "
                    + "separated. RuneLite's own Agility plugin does not expose course "
                    + "sequence, so without one of the two the output can only say which "
                    + "obstacle is NEAREST - and the one just completed is still in view, "
                    + "so it gets picked again. Each entry becomes agility_step_N_*, and "
                    + "agility_next_* follows the tracked progress. "
                    + "Example: 2484:3437, 2477:3420, 2474:3401",
            section = skillSection, position = 1)
    default String agilityCourseOrder() { return ""; }

    @ConfigItem(keyName = "blastFurnaceEnabled", name = "Blast Furnace steps",
            description = "Mirror the Easy Blast Furnace plugin's current instruction as a "
                    + "click box - item, object, widget or tile, whichever the step calls "
                    + "for - plus its tooltip text. Also emits coffer gp and time remaining, "
                    + "furnace contents, dispenser state and the foreman timer, all read "
                    + "from varbits so they work without any Blast Furnace plugin. The step "
                    + "mirror needs Easy Blast Furnace installed; bf_plugin_found reports "
                    + "whether it was found.",
            section = skillSection, position = 2)
    default boolean blastFurnaceEnabled() { return false; }

    @ConfigItem(keyName = "runeTrackingEnabled", name = "Runes / remaining casts",
            description = "Count runes across inventory, rune pouch and an equipped staff, "
                    + "and work out how many casts are left for the spell configured below. "
                    + "Needs no other plugin. Cheap.",
            section = magicSection, position = 0)
    default boolean runeTrackingEnabled() { return false; }

    @ConfigItem(keyName = "castSpell", name = "Spell rune cost",
            description = "The runes ONE cast of your spell uses, as type:count pairs - for "
                    + "example fire:5,air:4 for Fire Blast. rune_cast_remaining is then the "
                    + "number of casts available and rune_cast_limiter names the rune that "
                    + "runs out first. Spell tables are not shipped because they would rot; "
                    + "put in the spell you are actually casting.",
            section = magicSection, position = 1)
    default String castSpell() { return ""; }

    @ConfigItem(keyName = "sailingEnabled", name = "Sailing",
            description = "Read the Sailing plugin's boat model - cargo hold, helm, mast and "
                    + "each salvaging hook as a click box, with whether a crewmate is on "
                    + "each hook. Requires the Sailing plugin; sail_plugin_found reports "
                    + "whether it was found. For the crystal extractor and salvage state, "
                    + "use an anchored wildcard in the scenery filter instead - those swap "
                    + "object ID when they change state.",
            section = skillSection, position = 4)
    default boolean sailingEnabled() { return false; }

    @ConfigItem(keyName = "bankTrackingEnabled", name = "Bank quantities",
            description = "Report bank quantities for a watch list of items. The whole bank "
                    + "is far too large to export, so only the items below are reported.",
            section = bankSection, position = 0)
    default boolean bankTrackingEnabled() { return false; }

    @ConfigItem(keyName = "bankWatchList", name = "Bank items",
            description = "label=Item name or label=itemId, comma or newline separated. The "
                    + "label alone also works. Names must match EXACTLY, so Logs does not "
                    + "also collect Oak logs. Example: logs=Logs, air=Air rune, coins=995",
            section = bankSection, position = 1)
    default String bankWatchList() { return ""; }

    @ConfigItem(keyName = "attackStyleEnabled", name = "Incoming attack style",
            description = "Infer whether you are being attacked by melee, ranged or magic, "
                    + "from projectiles aimed at you and attacker distance. The client does "
                    + "not label attack styles, so att_style is a best guess and att_basis "
                    + "says what it was based on.",
            section = combatSection, position = 2)
    default boolean attackStyleEnabled() { return false; }

    @ConfigItem(keyName = "prayerTrackingEnabled", name = "Active prayers",
            description = "List which prayers are currently switched on, with the overhead "
                    + "called out separately. Cheap.",
            section = combatSection, position = 1)
    default boolean prayerTrackingEnabled() { return false; }

    @ConfigItem(keyName = "vitalsEnabled", name = "Vitals",
            description = "Emit hitpoints, prayer, run energy and special attack. Special "
                    + "attack is stored by the game as percent x10 and is divided here, so "
                    + "vit_spec_percent reads 0-100. Cheap.",
            section = combatSection, position = 0)
    default boolean vitalsEnabled() { return false; }

    @ConfigItem(keyName = "widgetsEnabled", name = "Widget coordinates",
            description = "Resolve named interface widgets to desktop pixels - special "
                    + "attack orb, quick prayer orb, combat style buttons, anything. Cheap.",
            section = widgetSection, position = 0)
    default boolean widgetsEnabled() { return false; }

    @ConfigItem(keyName = "widgetList", name = "Widgets",
            description = "label=group:child or label=group:child:index, comma or newline "
                    + "separated. Find the ids with RuneLite's Widget Inspector under "
                    + "Developer Tools - they are NOT hardcoded here because widget ids "
                    + "move between client versions. "
                    + "Example: spec=160:35, prayer=160:31, style2=593:5",
            section = widgetSection, position = 1)
    default String widgetList() { return ""; }

    @ConfigItem(keyName = "playerActivityEnabled", name = "Player activity",
            description = "Emit what the local player is doing - animation id, how long it "
                    + "has been running, how long since any animation, whether they are "
                    + "moving, and what they are interacting with. There is no skill name "
                    + "in the client, only animation ids that differ per tool, so read "
                    + "player_act_animation while performing the action and match on that "
                    + "id. Cheap.",
            section = combatSection, position = 3)
    default boolean playerActivityEnabled() { return false; }

    @ConfigItem(keyName = "runiteTrackingEnabled", name = "Runite rock tracker",
            description = "Mirror the Runite Rocks plugin's world map into the output - "
                    + "every rock it has seen on every world visited, with availability and "
                    + "respawn countdown, most actionable first. Hop with "
                    + "GET /hop?world=100, which works whether or not that plugin is "
                    + "installed. runite_plugin_found reports whether it was found.",
            section = skillSection, position = 3)
    default boolean runiteTrackingEnabled() { return false; }

    @ConfigItem(keyName = "aggroTimerEnabled", name = "NPC aggression timer",
            description = "Emit the time until NPCs become unaggressive. Cheap.",
            section = combatSection, position = 4)
    default boolean aggroTimerEnabled() { return false; }

    @ConfigItem(keyName = "aggroUseRuneLitePlugin", name = "Use RuneLite's aggression timer",
            description = "Read the countdown straight off RuneLite's own NPC Aggression "
                    + "Timer plugin, which must be enabled with 'Show timer' on. Turn this "
                    + "off to use this plugin's own estimate instead, which needs no other "
                    + "plugin but only becomes accurate after a teleport.",
            section = combatSection, position = 5)
    default boolean aggroUseRuneLitePlugin() { return true; }

    // -----------------------------------------------------------------------
    // Waypoint Bundles (Plugin v2.25)
    //
    // Ten named sets, each independently toggleable. Only enabled bundles are
    // parsed or resolved, so a disabled set costs nothing at all. RuneLite
    // config items must be declared statically, which is why there is a fixed
    // number of slots rather than an arbitrary list.
    // -----------------------------------------------------------------------
    @ConfigSection(name = "Waypoint Bundles",
            description = "Named sets of waypoints that can be switched on and off "
                    + "independently. Requires waypoints to be enabled in Movement & Pathing.",
            position = 15, closedByDefault = true)
    String bundleSection = "bundles";

    @ConfigItem(keyName = "bundle1Enabled", name = "1. Enabled",
            description = "Resolve the waypoints in bundle 1",
            section = bundleSection, position = 10)
    default boolean bundle1Enabled() { return false; }

    @ConfigItem(keyName = "bundle1Name", name = "1. Name",
            description = "Label for bundle 1, reported as wp_<name>_bundle in the output",
            section = bundleSection, position = 11)
    default String bundle1Name() { return ""; }

    @ConfigItem(keyName = "bundle1Waypoints", name = "1. Waypoints",
            description = "Single tile: name:x:y[:plane]. Cluster area: "
                    + "name:x1:y1-x2:y2[:plane]. Comma or newline separated.",
            section = bundleSection, position = 12)
    default String bundle1Waypoints() { return ""; }

    @ConfigItem(keyName = "bundle2Enabled", name = "2. Enabled",
            description = "Resolve the waypoints in bundle 2",
            section = bundleSection, position = 20)
    default boolean bundle2Enabled() { return false; }

    @ConfigItem(keyName = "bundle2Name", name = "2. Name",
            description = "Label for bundle 2, reported as wp_<name>_bundle in the output",
            section = bundleSection, position = 21)
    default String bundle2Name() { return ""; }

    @ConfigItem(keyName = "bundle2Waypoints", name = "2. Waypoints",
            description = "Single tile: name:x:y[:plane]. Cluster area: "
                    + "name:x1:y1-x2:y2[:plane]. Comma or newline separated.",
            section = bundleSection, position = 22)
    default String bundle2Waypoints() { return ""; }

    @ConfigItem(keyName = "bundle3Enabled", name = "3. Enabled",
            description = "Resolve the waypoints in bundle 3",
            section = bundleSection, position = 30)
    default boolean bundle3Enabled() { return false; }

    @ConfigItem(keyName = "bundle3Name", name = "3. Name",
            description = "Label for bundle 3, reported as wp_<name>_bundle in the output",
            section = bundleSection, position = 31)
    default String bundle3Name() { return ""; }

    @ConfigItem(keyName = "bundle3Waypoints", name = "3. Waypoints",
            description = "Single tile: name:x:y[:plane]. Cluster area: "
                    + "name:x1:y1-x2:y2[:plane]. Comma or newline separated.",
            section = bundleSection, position = 32)
    default String bundle3Waypoints() { return ""; }

    @ConfigItem(keyName = "bundle4Enabled", name = "4. Enabled",
            description = "Resolve the waypoints in bundle 4",
            section = bundleSection, position = 40)
    default boolean bundle4Enabled() { return false; }

    @ConfigItem(keyName = "bundle4Name", name = "4. Name",
            description = "Label for bundle 4, reported as wp_<name>_bundle in the output",
            section = bundleSection, position = 41)
    default String bundle4Name() { return ""; }

    @ConfigItem(keyName = "bundle4Waypoints", name = "4. Waypoints",
            description = "Single tile: name:x:y[:plane]. Cluster area: "
                    + "name:x1:y1-x2:y2[:plane]. Comma or newline separated.",
            section = bundleSection, position = 42)
    default String bundle4Waypoints() { return ""; }

    @ConfigItem(keyName = "bundle5Enabled", name = "5. Enabled",
            description = "Resolve the waypoints in bundle 5",
            section = bundleSection, position = 50)
    default boolean bundle5Enabled() { return false; }

    @ConfigItem(keyName = "bundle5Name", name = "5. Name",
            description = "Label for bundle 5, reported as wp_<name>_bundle in the output",
            section = bundleSection, position = 51)
    default String bundle5Name() { return ""; }

    @ConfigItem(keyName = "bundle5Waypoints", name = "5. Waypoints",
            description = "Single tile: name:x:y[:plane]. Cluster area: "
                    + "name:x1:y1-x2:y2[:plane]. Comma or newline separated.",
            section = bundleSection, position = 52)
    default String bundle5Waypoints() { return ""; }

    @ConfigItem(keyName = "bundle6Enabled", name = "6. Enabled",
            description = "Resolve the waypoints in bundle 6",
            section = bundleSection, position = 60)
    default boolean bundle6Enabled() { return false; }

    @ConfigItem(keyName = "bundle6Name", name = "6. Name",
            description = "Label for bundle 6, reported as wp_<name>_bundle in the output",
            section = bundleSection, position = 61)
    default String bundle6Name() { return ""; }

    @ConfigItem(keyName = "bundle6Waypoints", name = "6. Waypoints",
            description = "Single tile: name:x:y[:plane]. Cluster area: "
                    + "name:x1:y1-x2:y2[:plane]. Comma or newline separated.",
            section = bundleSection, position = 62)
    default String bundle6Waypoints() { return ""; }

    @ConfigItem(keyName = "bundle7Enabled", name = "7. Enabled",
            description = "Resolve the waypoints in bundle 7",
            section = bundleSection, position = 70)
    default boolean bundle7Enabled() { return false; }

    @ConfigItem(keyName = "bundle7Name", name = "7. Name",
            description = "Label for bundle 7, reported as wp_<name>_bundle in the output",
            section = bundleSection, position = 71)
    default String bundle7Name() { return ""; }

    @ConfigItem(keyName = "bundle7Waypoints", name = "7. Waypoints",
            description = "Single tile: name:x:y[:plane]. Cluster area: "
                    + "name:x1:y1-x2:y2[:plane]. Comma or newline separated.",
            section = bundleSection, position = 72)
    default String bundle7Waypoints() { return ""; }

    @ConfigItem(keyName = "bundle8Enabled", name = "8. Enabled",
            description = "Resolve the waypoints in bundle 8",
            section = bundleSection, position = 80)
    default boolean bundle8Enabled() { return false; }

    @ConfigItem(keyName = "bundle8Name", name = "8. Name",
            description = "Label for bundle 8, reported as wp_<name>_bundle in the output",
            section = bundleSection, position = 81)
    default String bundle8Name() { return ""; }

    @ConfigItem(keyName = "bundle8Waypoints", name = "8. Waypoints",
            description = "Single tile: name:x:y[:plane]. Cluster area: "
                    + "name:x1:y1-x2:y2[:plane]. Comma or newline separated.",
            section = bundleSection, position = 82)
    default String bundle8Waypoints() { return ""; }

    @ConfigItem(keyName = "bundle9Enabled", name = "9. Enabled",
            description = "Resolve the waypoints in bundle 9",
            section = bundleSection, position = 90)
    default boolean bundle9Enabled() { return false; }

    @ConfigItem(keyName = "bundle9Name", name = "9. Name",
            description = "Label for bundle 9, reported as wp_<name>_bundle in the output",
            section = bundleSection, position = 91)
    default String bundle9Name() { return ""; }

    @ConfigItem(keyName = "bundle9Waypoints", name = "9. Waypoints",
            description = "Single tile: name:x:y[:plane]. Cluster area: "
                    + "name:x1:y1-x2:y2[:plane]. Comma or newline separated.",
            section = bundleSection, position = 92)
    default String bundle9Waypoints() { return ""; }

    @ConfigItem(keyName = "bundle10Enabled", name = "10. Enabled",
            description = "Resolve the waypoints in bundle 10",
            section = bundleSection, position = 100)
    default boolean bundle10Enabled() { return false; }

    @ConfigItem(keyName = "bundle10Name", name = "10. Name",
            description = "Label for bundle 10, reported as wp_<name>_bundle in the output",
            section = bundleSection, position = 101)
    default String bundle10Name() { return ""; }

    @ConfigItem(keyName = "bundle10Waypoints", name = "10. Waypoints",
            description = "Single tile: name:x:y[:plane]. Cluster area: "
                    + "name:x1:y1-x2:y2[:plane]. Comma or newline separated.",
            section = bundleSection, position = 102)
    default String bundle10Waypoints() { return ""; }

    // -----------------------------------------------------------------------
    // Ground Items & NPCs (Plugin v2.27)
    // -----------------------------------------------------------------------
    @ConfigSection(name = "Ground Items & NPCs",
            description = "Find ground items and NPCs by name or ID and emit click boxes "
                    + "for them. Off by default. Requires scene tracking enabled.",
            position = 16, closedByDefault = true)
    String objectSection = "objects";

    @ConfigItem(keyName = "groundItemsEnabled", name = "Track ground items",
            description = "Scan for matching ground items near the player and emit a click "
                    + "box and despawn countdown for each",
            section = objectSection, position = 0)
    default boolean groundItemsEnabled() { return false; }

    @ConfigItem(keyName = "groundItemFilter", name = "Item names / IDs",
            description = "Comma or newline separated. A numeric entry matches an item ID, "
                    + "anything else matches the item name exactly (case insensitive). "
                    + "Optional label= gives a stable output key; optional @x:y pins the "
                    + "match to a position, or @x1:y1-x2:y2 bounds it to a rectangle; "
                    + "optional ~R sets this entry's own radius. Use * to match any named "
                    + "item. "
                    + "Example: Big bones, 532, loot=Dragon bones@2437:9161",
            section = objectSection, position = 1)
    default String groundItemFilter() { return ""; }

    @ConfigItem(keyName = "npcTrackingEnabled", name = "Track NPCs",
            description = "Emit a click box for matching NPCs, taken from the NPC's own "
                    + "model clickbox",
            section = objectSection, position = 2)
    default boolean npcTrackingEnabled() { return false; }

    @ConfigItem(keyName = "npcFilter", name = "NPC names / IDs",
            description = "Comma or newline separated. A numeric entry matches an NPC ID, "
                    + "anything else matches the NPC name exactly (case insensitive). "
                    + "Optional label= gives a stable output key; optional @x:y pins the "
                    + "match to a position, or @x1:y1-x2:y2 bounds it to a rectangle; "
                    + "optional ~R limits it to that many tiles. Use * to match any named "
                    + "NPC. "
                    + "Example: Rock crab, 1613, teller=Banker@3163:3487",
            section = objectSection, position = 3)
    default String npcFilter() { return ""; }

    @ConfigItem(keyName = "gameObjectsEnabled", name = "Track scenery / game objects",
            description = "Emit a click box for matching scenery — cave entrances, doors, "
                    + "ladders, banks, trees, altars. Covers game, wall, decorative and "
                    + "ground object layers.",
            section = objectSection, position = 6)
    default boolean gameObjectsEnabled() { return false; }

    @ConfigItem(keyName = "gameObjectFilter", name = "Scenery names / IDs",
            description = "Comma or newline separated. A numeric entry matches an object ID, "
                    + "anything else matches the object name exactly (case insensitive). "
                    + "Names for doors and quest-state objects are resolved through their "
                    + "varbit impostor, so use the name you see in game. "
                    + "Optional label= gives a stable output key; optional @x:y pins the "
                    + "match to a position - use this to separate two entrances that share "
                    + "an ID. Use * to match any named object: on its own it lists "
                    + "everything nearby so you can find IDs, and anchored it reports "
                    + "whatever is on that tile right now - which is how to tell a live "
                    + "rock from a mined one, since the actions list only offers Mine "
                    + "while it is live. "
                    + "Use #Action to match anything offering that right-click action - "
                    + "#Mine gives the nearest LIVE rock and moves to the next one by "
                    + "itself as each is mined out, since a depleted rock loses its Mine "
                    + "action. Also #Chop-down, #Enter, #Bank, #Climb-down. "
                    + "Add ~R to give one entry its own search radius. Better still, "
                    + "@x1:y1-x2:y2 bounds an entry to a RECTANGLE, which a radius cannot - "
                    + "use it to stop matching ore through a wall. Corners in any order, "
                    + "optional trailing plane. "
                    + "Example: coal=Coal rocks@2437:9161-2450:9174, chest=4483~45, *",
            section = objectSection, position = 7)
    default String gameObjectFilter() { return ""; }

    @ConfigItem(keyName = "objectSearchRadius", name = "Ground item / scenery search radius",
            description = "How many tiles around the player to scan for ground items and "
                    + "scenery. Cost grows with the square of this, so 52 (the whole loaded "
                    + "scene) is roughly twelve times the work of 15 - raise it only as far "
                    + "as you need. An @x:y anchor finds a match at any distance without "
                    + "raising this. NPC matching is not affected by this.",
            section = objectSection, position = 4)
    @Range(min = 1, max = 52)
    default int objectSearchRadius() { return 15; }

    @ConfigItem(keyName = "objectAnchorTolerance", name = "Anchor tolerance (tiles)",
            description = "How far from an @x:y anchor a match may be. Some slack is needed "
                    + "because a multi-tile object reports its base tile, which is not "
                    + "necessarily the tile you would aim at.",
            section = objectSection, position = 8)
    @Range(min = 0, max = 10)
    default int objectAnchorTolerance() { return 2; }

    @ConfigItem(keyName = "objectMaxResults", name = "Max results each",
            description = "Cap on how many ground items and how many NPCs are emitted. "
                    + "Nearest first, so the cap drops the furthest matches. Labelled entries are always emitted and do not count towards this cap.",
            section = objectSection, position = 5)
    @Range(min = 1, max = 12)
    default int objectMaxResults() { return 6; }

    // -----------------------------------------------------------------------
    // Entity Sets (Plugin v2.65)
    //
    // The scenery / NPC / ground item filters were a single global each, so
    // configuring one activity overwrote another: setting up the Blast
    // Furnace wiped the sailing and mining filters. These are ten named sets
    // on the SAME model as Waypoint Bundles above -- each holds all three
    // families together, and an enabled set is MERGED with the always-on
    // boxes in Ground Items & NPCs rather than replacing them.
    //
    // Entry syntax is identical to the always-on boxes. Labels are NOT
    // prefixed with the set name, so a consumer's key does not change when a
    // set is reorganised. If two enabled sets use the same label the first
    // wins and the collision is reported in entity_set_conflicts -- a
    // duplicate label is otherwise silently DROPPED by selectResults.
    //
    // RuneLite config items must be declared statically, hence a fixed ten
    // slots rather than an arbitrary list.
    // -----------------------------------------------------------------------
    @ConfigSection(name = "Entity Sets",
            description = "Named sets of scenery / NPC / item filters that can be switched "
                    + "on and off as a unit, and merged with the always-on boxes in "
                    + "Ground Items & NPCs. Switch one on remotely with /filter?entityset=<name>.",
            position = 17, closedByDefault = true)
    String entitySetSection = "entitysets";

    @ConfigItem(keyName = "set1Enabled", name = "1. Enabled",
            description = "Merge set 1's entries into the live scenery / NPC / item filters",
            section = entitySetSection, position = 10)
    default boolean set1Enabled() { return false; }

    @ConfigItem(keyName = "set1Name", name = "1. Name",
            description = "Label for set 1, e.g. blastfurnace. This is the name "
                    + "/filter?entityset=<name> switches on, and it is reported in "
                    + "entity_sets_available and entity_sets_active.",
            section = entitySetSection, position = 11)
    default String set1Name() { return ""; }

    @ConfigItem(keyName = "set1Scenery", name = "1. Scenery",
            description = "Scenery entries for set 1. Same syntax as the always-on "
                    + "Scenery box, and merged with it rather than replacing it.",
            section = entitySetSection, position = 12)
    default String set1Scenery() { return ""; }

    @ConfigItem(keyName = "set1Npcs", name = "1. NPCs",
            description = "NPC entries for set 1. Same syntax as the always-on NPC box.",
            section = entitySetSection, position = 13)
    default String set1Npcs() { return ""; }

    @ConfigItem(keyName = "set1Items", name = "1. Ground items",
            description = "Ground item entries for set 1. Same syntax as the always-on "
                    + "item box.",
            section = entitySetSection, position = 14)
    default String set1Items() { return ""; }

    @ConfigItem(keyName = "set2Enabled", name = "2. Enabled",
            description = "Merge set 2's entries into the live scenery / NPC / item filters",
            section = entitySetSection, position = 20)
    default boolean set2Enabled() { return false; }

    @ConfigItem(keyName = "set2Name", name = "2. Name",
            description = "Label for set 2, e.g. blastfurnace. This is the name "
                    + "/filter?entityset=<name> switches on, and it is reported in "
                    + "entity_sets_available and entity_sets_active.",
            section = entitySetSection, position = 21)
    default String set2Name() { return ""; }

    @ConfigItem(keyName = "set2Scenery", name = "2. Scenery",
            description = "Scenery entries for set 2. Same syntax as the always-on "
                    + "Scenery box, and merged with it rather than replacing it.",
            section = entitySetSection, position = 22)
    default String set2Scenery() { return ""; }

    @ConfigItem(keyName = "set2Npcs", name = "2. NPCs",
            description = "NPC entries for set 2. Same syntax as the always-on NPC box.",
            section = entitySetSection, position = 23)
    default String set2Npcs() { return ""; }

    @ConfigItem(keyName = "set2Items", name = "2. Ground items",
            description = "Ground item entries for set 2. Same syntax as the always-on "
                    + "item box.",
            section = entitySetSection, position = 24)
    default String set2Items() { return ""; }

    @ConfigItem(keyName = "set3Enabled", name = "3. Enabled",
            description = "Merge set 3's entries into the live scenery / NPC / item filters",
            section = entitySetSection, position = 30)
    default boolean set3Enabled() { return false; }

    @ConfigItem(keyName = "set3Name", name = "3. Name",
            description = "Label for set 3, e.g. blastfurnace. This is the name "
                    + "/filter?entityset=<name> switches on, and it is reported in "
                    + "entity_sets_available and entity_sets_active.",
            section = entitySetSection, position = 31)
    default String set3Name() { return ""; }

    @ConfigItem(keyName = "set3Scenery", name = "3. Scenery",
            description = "Scenery entries for set 3. Same syntax as the always-on "
                    + "Scenery box, and merged with it rather than replacing it.",
            section = entitySetSection, position = 32)
    default String set3Scenery() { return ""; }

    @ConfigItem(keyName = "set3Npcs", name = "3. NPCs",
            description = "NPC entries for set 3. Same syntax as the always-on NPC box.",
            section = entitySetSection, position = 33)
    default String set3Npcs() { return ""; }

    @ConfigItem(keyName = "set3Items", name = "3. Ground items",
            description = "Ground item entries for set 3. Same syntax as the always-on "
                    + "item box.",
            section = entitySetSection, position = 34)
    default String set3Items() { return ""; }

    @ConfigItem(keyName = "set4Enabled", name = "4. Enabled",
            description = "Merge set 4's entries into the live scenery / NPC / item filters",
            section = entitySetSection, position = 40)
    default boolean set4Enabled() { return false; }

    @ConfigItem(keyName = "set4Name", name = "4. Name",
            description = "Label for set 4, e.g. blastfurnace. This is the name "
                    + "/filter?entityset=<name> switches on, and it is reported in "
                    + "entity_sets_available and entity_sets_active.",
            section = entitySetSection, position = 41)
    default String set4Name() { return ""; }

    @ConfigItem(keyName = "set4Scenery", name = "4. Scenery",
            description = "Scenery entries for set 4. Same syntax as the always-on "
                    + "Scenery box, and merged with it rather than replacing it.",
            section = entitySetSection, position = 42)
    default String set4Scenery() { return ""; }

    @ConfigItem(keyName = "set4Npcs", name = "4. NPCs",
            description = "NPC entries for set 4. Same syntax as the always-on NPC box.",
            section = entitySetSection, position = 43)
    default String set4Npcs() { return ""; }

    @ConfigItem(keyName = "set4Items", name = "4. Ground items",
            description = "Ground item entries for set 4. Same syntax as the always-on "
                    + "item box.",
            section = entitySetSection, position = 44)
    default String set4Items() { return ""; }

    @ConfigItem(keyName = "set5Enabled", name = "5. Enabled",
            description = "Merge set 5's entries into the live scenery / NPC / item filters",
            section = entitySetSection, position = 50)
    default boolean set5Enabled() { return false; }

    @ConfigItem(keyName = "set5Name", name = "5. Name",
            description = "Label for set 5, e.g. blastfurnace. This is the name "
                    + "/filter?entityset=<name> switches on, and it is reported in "
                    + "entity_sets_available and entity_sets_active.",
            section = entitySetSection, position = 51)
    default String set5Name() { return ""; }

    @ConfigItem(keyName = "set5Scenery", name = "5. Scenery",
            description = "Scenery entries for set 5. Same syntax as the always-on "
                    + "Scenery box, and merged with it rather than replacing it.",
            section = entitySetSection, position = 52)
    default String set5Scenery() { return ""; }

    @ConfigItem(keyName = "set5Npcs", name = "5. NPCs",
            description = "NPC entries for set 5. Same syntax as the always-on NPC box.",
            section = entitySetSection, position = 53)
    default String set5Npcs() { return ""; }

    @ConfigItem(keyName = "set5Items", name = "5. Ground items",
            description = "Ground item entries for set 5. Same syntax as the always-on "
                    + "item box.",
            section = entitySetSection, position = 54)
    default String set5Items() { return ""; }

    @ConfigItem(keyName = "set6Enabled", name = "6. Enabled",
            description = "Merge set 6's entries into the live scenery / NPC / item filters",
            section = entitySetSection, position = 60)
    default boolean set6Enabled() { return false; }

    @ConfigItem(keyName = "set6Name", name = "6. Name",
            description = "Label for set 6, e.g. blastfurnace. This is the name "
                    + "/filter?entityset=<name> switches on, and it is reported in "
                    + "entity_sets_available and entity_sets_active.",
            section = entitySetSection, position = 61)
    default String set6Name() { return ""; }

    @ConfigItem(keyName = "set6Scenery", name = "6. Scenery",
            description = "Scenery entries for set 6. Same syntax as the always-on "
                    + "Scenery box, and merged with it rather than replacing it.",
            section = entitySetSection, position = 62)
    default String set6Scenery() { return ""; }

    @ConfigItem(keyName = "set6Npcs", name = "6. NPCs",
            description = "NPC entries for set 6. Same syntax as the always-on NPC box.",
            section = entitySetSection, position = 63)
    default String set6Npcs() { return ""; }

    @ConfigItem(keyName = "set6Items", name = "6. Ground items",
            description = "Ground item entries for set 6. Same syntax as the always-on "
                    + "item box.",
            section = entitySetSection, position = 64)
    default String set6Items() { return ""; }

    @ConfigItem(keyName = "set7Enabled", name = "7. Enabled",
            description = "Merge set 7's entries into the live scenery / NPC / item filters",
            section = entitySetSection, position = 70)
    default boolean set7Enabled() { return false; }

    @ConfigItem(keyName = "set7Name", name = "7. Name",
            description = "Label for set 7, e.g. blastfurnace. This is the name "
                    + "/filter?entityset=<name> switches on, and it is reported in "
                    + "entity_sets_available and entity_sets_active.",
            section = entitySetSection, position = 71)
    default String set7Name() { return ""; }

    @ConfigItem(keyName = "set7Scenery", name = "7. Scenery",
            description = "Scenery entries for set 7. Same syntax as the always-on "
                    + "Scenery box, and merged with it rather than replacing it.",
            section = entitySetSection, position = 72)
    default String set7Scenery() { return ""; }

    @ConfigItem(keyName = "set7Npcs", name = "7. NPCs",
            description = "NPC entries for set 7. Same syntax as the always-on NPC box.",
            section = entitySetSection, position = 73)
    default String set7Npcs() { return ""; }

    @ConfigItem(keyName = "set7Items", name = "7. Ground items",
            description = "Ground item entries for set 7. Same syntax as the always-on "
                    + "item box.",
            section = entitySetSection, position = 74)
    default String set7Items() { return ""; }

    @ConfigItem(keyName = "set8Enabled", name = "8. Enabled",
            description = "Merge set 8's entries into the live scenery / NPC / item filters",
            section = entitySetSection, position = 80)
    default boolean set8Enabled() { return false; }

    @ConfigItem(keyName = "set8Name", name = "8. Name",
            description = "Label for set 8, e.g. blastfurnace. This is the name "
                    + "/filter?entityset=<name> switches on, and it is reported in "
                    + "entity_sets_available and entity_sets_active.",
            section = entitySetSection, position = 81)
    default String set8Name() { return ""; }

    @ConfigItem(keyName = "set8Scenery", name = "8. Scenery",
            description = "Scenery entries for set 8. Same syntax as the always-on "
                    + "Scenery box, and merged with it rather than replacing it.",
            section = entitySetSection, position = 82)
    default String set8Scenery() { return ""; }

    @ConfigItem(keyName = "set8Npcs", name = "8. NPCs",
            description = "NPC entries for set 8. Same syntax as the always-on NPC box.",
            section = entitySetSection, position = 83)
    default String set8Npcs() { return ""; }

    @ConfigItem(keyName = "set8Items", name = "8. Ground items",
            description = "Ground item entries for set 8. Same syntax as the always-on "
                    + "item box.",
            section = entitySetSection, position = 84)
    default String set8Items() { return ""; }

    @ConfigItem(keyName = "set9Enabled", name = "9. Enabled",
            description = "Merge set 9's entries into the live scenery / NPC / item filters",
            section = entitySetSection, position = 90)
    default boolean set9Enabled() { return false; }

    @ConfigItem(keyName = "set9Name", name = "9. Name",
            description = "Label for set 9, e.g. blastfurnace. This is the name "
                    + "/filter?entityset=<name> switches on, and it is reported in "
                    + "entity_sets_available and entity_sets_active.",
            section = entitySetSection, position = 91)
    default String set9Name() { return ""; }

    @ConfigItem(keyName = "set9Scenery", name = "9. Scenery",
            description = "Scenery entries for set 9. Same syntax as the always-on "
                    + "Scenery box, and merged with it rather than replacing it.",
            section = entitySetSection, position = 92)
    default String set9Scenery() { return ""; }

    @ConfigItem(keyName = "set9Npcs", name = "9. NPCs",
            description = "NPC entries for set 9. Same syntax as the always-on NPC box.",
            section = entitySetSection, position = 93)
    default String set9Npcs() { return ""; }

    @ConfigItem(keyName = "set9Items", name = "9. Ground items",
            description = "Ground item entries for set 9. Same syntax as the always-on "
                    + "item box.",
            section = entitySetSection, position = 94)
    default String set9Items() { return ""; }

    @ConfigItem(keyName = "set10Enabled", name = "10. Enabled",
            description = "Merge set 10's entries into the live scenery / NPC / item filters",
            section = entitySetSection, position = 100)
    default boolean set10Enabled() { return false; }

    @ConfigItem(keyName = "set10Name", name = "10. Name",
            description = "Label for set 10, e.g. blastfurnace. This is the name "
                    + "/filter?entityset=<name> switches on, and it is reported in "
                    + "entity_sets_available and entity_sets_active.",
            section = entitySetSection, position = 101)
    default String set10Name() { return ""; }

    @ConfigItem(keyName = "set10Scenery", name = "10. Scenery",
            description = "Scenery entries for set 10. Same syntax as the always-on "
                    + "Scenery box, and merged with it rather than replacing it.",
            section = entitySetSection, position = 102)
    default String set10Scenery() { return ""; }

    @ConfigItem(keyName = "set10Npcs", name = "10. NPCs",
            description = "NPC entries for set 10. Same syntax as the always-on NPC box.",
            section = entitySetSection, position = 103)
    default String set10Npcs() { return ""; }

    @ConfigItem(keyName = "set10Items", name = "10. Ground items",
            description = "Ground item entries for set 10. Same syntax as the always-on "
                    + "item box.",
            section = entitySetSection, position = 104)
    default String set10Items() { return ""; }
}
