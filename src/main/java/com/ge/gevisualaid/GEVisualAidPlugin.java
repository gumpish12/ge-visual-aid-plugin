package com.ge.gevisualaid;

import com.google.inject.Provides;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Actor;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Projectile;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.Skill;
import net.runelite.api.DecorativeObject;
import net.runelite.api.DynamicObject;
import net.runelite.api.EnumComposition;
import net.runelite.api.GameObject;
import net.runelite.api.Renderable;
import net.runelite.api.GroundObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import java.time.LocalDate;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.WorldService;
import net.runelite.client.util.WorldUtil;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBox;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.ui.overlay.infobox.Timer;

import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@PluginDescriptor(
        name = "GE Visual Aid",
        description = "A Grand Exchange accessibility and notification assistant. " +
                "Draws configurable pulsing highlight overlays around active GE actions to assist players " +
                "with visual impairments. Plays audio beeps for action cues. Sends real-time notifications " +
                "via Discord and Pushover including priority alerts that bypass phone silent mode. " +
                "Tracks all 8 GE slots with live progress bars and persistent profit/loss history. " +
                "Outputs full GE state each tick for integration with assistive technology — from Philips Hue " +
                "lights that change colour when action is required, to haptic feedback devices that pulse when " +
                "your offer completes, to custom screen readers and voice announcement systems.",
        tags = {"accessibility", "ge", "grand exchange", "notification", "discord", "overlay", "visual"}
)
public class GEVisualAidPlugin extends Plugin
{
    @Inject private Client             client;
    @Inject private PluginManager      pluginManager;
    @Inject private GEVisualAidConfig  config;
    @Inject private OverlayManager     overlayManager;
    @Inject private GEVisualAidOverlay overlay;
    @Inject private GEVisualAidPanel   panel;
    @Inject private DiscordNotifier    discord;
    @Inject private PushoverNotifier   pushover;
    @Inject private SoundAlert         sound;
    @Inject private SessionTracker     session;
    @Inject private BondTracker        bondTracker;
    @Inject private ClientToolbar      clientToolbar;
    @Inject private ItemManager        itemManager;
    @Inject private InfoBoxManager      infoBoxManager;
    @Inject private ConfigManager       configManager;
    @Inject private WorldService        worldService;

    private Object           suggestionManager            = null;
    private Object           accountStatusManager         = null;
    private Object           suggestionPreferencesManager = null;
    private Plugin           apmPlugin                    = null;
    private Object           profitCalculator             = null;  // com.flippingcopilot.util.ProfitCalculator
    private NavigationButton navButton;

    private final SlotState[]     slots          = new SlotState[8];
    private final InventorySlot[] inventorySlots = new InventorySlot[28];


    private long inventoryValueGp = 0;
    private long bankValueGp      = 0;
    private long equipmentValueGp = 0;

    private long lastInputMs   = System.currentTimeMillis();
    private long lastMouseTicks = 0;
    private long lastKeyTicks   = 0;
    private static final long LOGOUT_THRESHOLD_SECONDS = 1200; // 20 minutes

    private long   lastSuggestionChangeMs = 0;
    private String lastSuggestionKey      = "";

    private String  lastAction    = "";
    private String  lastItemName  = "";
    private boolean lastDumpAlert = false;
    private long    actionSinceMs = 0;

    // -----------------------------------------------------------------------
    // Login / connection state tracking (added for AHK login navigation)
    // -----------------------------------------------------------------------
    // Schema version for the .txt output. Bump when fields are added or
    // semantics change so consumers can negotiate compatibility.
    //  2.0 — initial plugin-driven login state (game_state, welcome_screen_visible, etc.)
    //  2.1 — Welcome screen detection rewritten to use GameState=LOGGING_IN
    //        as the primary signal (verified empirically), widget 378 scan
    //        widened, new diagnostic field visible_login_widgets added.
    //  2.2 — Logout file-path fix. Previously, writeRaw chose the output
    //        file based on the CURRENT logged-in player ("Gump12_ge_visual_aid.txt"
    //        when in-game, "ge_visual_aid.txt" when not). After logout the
    //        named file went stale forever — consumers monitoring it kept
    //        seeing logged_in=true. Now we track lastKnownPlayerName and
    //        when no current player is present we write to BOTH the generic
    //        ge_visual_aid.txt AND the last-known-named file, so any
    //        consumer watching either path sees the current state.
    //  2.3 — Staleness fix (attempted via ClientTick). RuneLite's GameTick
    //        event only fires when GameState=LOGGED_IN, so after logout/
    //        disconnect the plugin wrote the .txt ONCE (on state change)
    //        and then went silent until the next state change. Tried
    //        subscribing to ClientTick at 1Hz throttle — but empirical
    //        test (Gump12, 2026-05-28) showed ClientTick ALSO appears to
    //        be gated on the in-game loop in this RuneLite version: the
    //        timestamp stayed frozen between actual state transitions.
    //        See v2.4 for the working fix.
    //  2.4 — Real staleness fix using ScheduledExecutorService. A Java-
    //        level scheduled task fires every 1000ms independent of any
    //        RuneLite event loop. While LOGGED_IN it returns immediately
    //        (GameTick already handles in-game writes). When not LOGGED_IN
    //        it calls writeLoggedOut() so the timestamp stays current.
    //        Scheduler started in startUp() and cancelled in shutDown()
    //        so it cleans up properly when the plugin is disabled.
    //  2.5 — Added portfolio_unrealised_profit field, pulled from
    //        Flipping Copilot via the same reflection chain we already
    //        use for profitCalculator. Path:
    //          profitCalculator
    //            .portfolioStateRS       (field, type PortfolioStateRS)
    //            .get()                  (ReactiveStateImpl, returns PortfolioState)
    //            .getSummaryData()       (PortfolioSummaryData)
    //            .getUnrealizedProfit()  (long, raw gp)
    //        Returns 0 if any link in the chain is null or throws, so
    //        the plugin degrades safely if Flipping Copilot renames a
    //        field in a future release.
    //  2.6 — Extended portfolio block with the remaining four
    //        PortfolioSummaryData fields, each with a portfolio_ prefix
    //        so they coexist with (not replace) the existing wealth
    //        fields the plugin already computes:
    //          portfolio_market_value    ← getPortfolioMarketValue()
    //          portfolio_cash_value      ← getCashValue()
    //          portfolio_assets_value    ← getAssetsValue()
    //          portfolio_locked_buy_cash ← getLockedBuyCash()
    //        Single reflection traversal per write — getPortfolioSummary()
    //        grabs the whole PortfolioSummaryData and returns a long[5]
    //        consumed by all five emit lines. Same null-safe degradation
    //        as v2.5.
    //  2.7 — Bug fix for the untracked-inventory alert. v2.6 treated
    //        "item ID is a key in itemCardDataByItemId" as "item is
    //        in the portfolio" — wrong. That map contains entries for
    //        every item FC knows about (including inventory items not
    //        currently being flipped); the actual in/out flag is
    //        isInPortfolio on each PortfolioItemCardData value.
    //        Observed symptom: user added Earth orb to portfolio in
    //        FC's UI, but the untracked list kept reporting it because
    //        the map entry was created with isInPortfolio=false long
    //        before the user's "add" action set the flag to true —
    //        v2.6 saw the key both before and after and changed nothing.
    //
    //        v2.7 iterates the map entries and reads each value's
    //        isInPortfolio flag (also accepts portfolioQuantity>0 as a
    //        partial-portfolio safety). Adds two diagnostic counters so
    //        you can cross-check against FC's panel:
    //          portfolio_known_item_count    — entries in the map total
    //          portfolio_in_portfolio_count  — entries marked tracked
    //  2.8 — Partial bug fix + per-item diagnostic. v2.7 caught three
    //        of four reported untracked items (Teleport, Virtus robe
    //        bottom, Virtus mask) but missed Earth orb specifically.
    //        Theory: stackable consumables like Earth orb get added to
    //        FC with isPartiallyInPortfolio=true rather than
    //        isInPortfolio=true (since the user typically adds a few
    //        out of a stack), and v2.7 only checked isInPortfolio.
    //
    //        Two changes:
    //          • Tracking check now also accepts isPartiallyInPortfolio
    //            and notInPortfolioQuantity < runeliteInventoryQuantity
    //            as positive signals. An item is tracked if ANY of the
    //            four FC-side indicators say so.
    //          • New diagnostic field untracked_inv_card_data emits the
    //            FC card-data state for each item still in the alert
    //            list, so we can verify exactly what FC reports for the
    //            stubborn Earth orb case. Format:
    //            slot:id:known=y/n:inPort=y/n:partial=y/n:portQty=N
    //            pipe-separated rows.
    //        If after v2.8 the diagnostic shows known=y but all four
    //        flags stay negative after the user's "add Earth orb"
    //        action, FC stores that intent somewhere outside
    //        PortfolioItemCardData and we need to look elsewhere.
    //  2.9 — Real fix for the Earth-orb case. v2.8 diagnostic confirmed
    //        the suspicion: Earth orb has known=n after "add to
    //        portfolio" — FC never created a PortfolioItemCardData
    //        entry for it. Looking at the FC class graph clarified
    //        why: itemCardDataByItemId only contains items FC has
    //        enough state to build a card for (inventory items that
    //        are also in the server's portfolio list, items in
    //        offers, etc.). Stackable consumables that are JUST added
    //        to the portfolio without other state don't get a card —
    //        but they DO get added to the server-side portfolioItems
    //        list, which is exposed at:
    //          suggestionManager.getSuggestion().portfolioItems
    //        (a List<Suggestion.PortfolioItem>, each with an itemId).
    //
    //        v2.9 now reads BOTH sources and unions them — an item is
    //        considered tracked if it's flagged in card-data OR if
    //        its ID appears in the suggestion's portfolioItems list.
    //        The diagnostic now also includes a suggPort=y/n flag so
    //        we can see which source flagged the item (or didn't).
    //  2.10 — Earth orb STILL untracked after add action. Diagnostic
    //        confirmed Earth orb has both known=n AND suggPort=n —
    //        the 654 items in the suggestion's portfolioItems list
    //        do NOT include item 576. So FC's "add Earth orb to
    //        portfolio" UI action writes neither the card map nor
    //        the suggestion list. Working theory: for items that
    //        don't have card-data state, the action mutates the
    //        BLOCKED items list (removing Earth orb from blocked =
    //        making it available for flipping). The plugin already
    //        reads blockedItems for the copilot_blocked_items_count
    //        field, so the reflection path is established.
    //
    //        Two changes in v2.10:
    //          • New inv_full_diag field emits FC state for EVERY
    //            inventory item (non-empty, non-coins), not just
    //            those flagged as untracked. Lets you compare the
    //            misbehaving Earth orb row side-by-side with items
    //            that DO work correctly.
    //          • Each row in both inv_full_diag and the existing
    //            untracked_inv_card_data now includes blocked=y/n
    //            for that item, plus a new
    //            portfolio_blocked_item_count summary field.
    //
    //        Tracking logic UNCHANGED in v2.10. The point of this
    //        version is to gather the data needed to decide whether
    //        "not in blocked list" is the right signal to fold in.
    //        If the diagnostic shows Earth orb flipping blocked=y→n
    //        when you click "add to portfolio", v2.11 will add that
    //        as a tracking source. If blocked doesn't move either,
    //        we need to look at toggleItemPortfolioAsync's response
    //        cache or somewhere else again.
    //  2.11 — REAL real fix for the Earth-orb case. The actual cause
    //        was noted/unnoted item-ID mismatch, not anything we'd
    //        guessed. Earth orb in inventory is item ID 576 (NOTED
    //        form, stackable). FC's portfolio stores the UNNOTED ID
    //        (575). My checks against the portfolio failed because
    //        576 ≠ 575. The other items the user added (Teleport,
    //        Virtus pieces) happened to be either always-unnoted or
    //        had their inventory IDs match the unnoted form FC uses,
    //        so they worked by coincidence.
    //
    //        FC's own InventorySlotTooltipDataProvider does the
    //        conversion via:
    //          itemController.toUnnotedItemId(rawInventoryId)
    //        before looking the item up in the portfolio. We now
    //        do the same — grab itemController via reflection from
    //        accountStatusManager (already linked in linkToCopilot),
    //        call toUnnotedItemId on each inventory item ID, and
    //        use that converted ID for ALL portfolio comparisons
    //        (card-data map keys, suggestion portfolioItems lookup).
    //
    //        Also simplified the tracking check now that we know
    //        isInPortfolio() returns (portfolioQuantity > 0) — the
    //        v2.8 four-way check collapses down to just that, plus
    //        the suggestion-list union from v2.9.
    //
    //        Diagnostic adds an "unnoted=N" field so you can see
    //        the converted ID alongside the raw inventory ID — and
    //        verify the conversion is actually firing.
    //        (v2.10's blocked-list theory wasn't tested and was
    //        almost certainly wrong, as the user pointed out.)
    //  2.12 — Remove the v2.9 suggestion-list union. User report
    //        showed 653 items in Suggestion.portfolioItems but only
    //        1 with isInPortfolio=true. 4 inventory items the user
    //        expected to see flagged as untracked were silently
    //        absorbed by the 653 union — they were items the user
    //        had flipped historically but were no longer in the
    //        active portfolio (portfolioId<0 = ghost/disappeared
    //        per the FC source).
    //
    //        Root cause: v2.9 was a workaround for the Earth-orb
    //        case while the actual fix was v2.11's unnoted-ID
    //        conversion. With v2.11 in place, cardData's
    //        isInPortfolio() flag is the canonical "currently in
    //        portfolio" signal — exactly the pattern
    //        InventorySlotTooltipDataProvider uses internally. The
    //        suggestion list adds noise (historical items) without
    //        adding signal.
    //
    //        Three changes in v2.12:
    //          • trackedIds no longer unions in suggestionIds. The
    //            set is now built purely from cardData entries with
    //            isInPortfolio=true.
    //          • cardDataSaysTracked() simplified from a four-flag
    //            OR to a single isInPortfolio() check. The other
    //            three checks were mathematically redundant given
    //            isInPortfolio() = (portfolioQuantity > 0) and
    //            isPartiallyInPortfolio() ⟹ isInPortfolio().
    //          • Diagnostic still emits suggPort=y/n per item so
    //            historical portfolio membership is visible if
    //            useful for debugging, but doesn't drive tracking.
    //  2.13 — Added a local HTTP server (com.sun.net.httpserver, no extra
    //         dependencies) serving the exact same state string at
    //         http://127.0.0.1:<port>/state. writeRaw now publishes the
    //         body to a volatile in-memory string FIRST (before the file
    //         output gate), so the endpoint never serves a partially
    //         written or stale buffer and stays current even when file
    //         output is disabled. The .txt file behaviour (including the
    //         v2.2 player-named dual-write) is unchanged. Port and an
    //         enable toggle live in the new HTTP Server config section
    //         (default 8081). The served payload is byte-identical to the
    //         .txt, so plugin_output_version semantics are unchanged.
    //  2.14 — Login notice detection. The OSRS login screen draws its
    //         server-update / client-out-of-date message boxes directly on
    //         the login canvas (not via widgets), so getLoginScreenMessage()
    //         and the widget scan can never see them and login_screen_message
    //         stays blank on those screens. Added two fields derived from
    //         getLoginIndex() so consumers can tell a notice page apart from
    //         a plain credentials screen:
    //           login_state_label   — CREDENTIALS / AUTHENTICATOR /
    //                                 SERVER_MESSAGE / INDEX_<n> / IN_GAME
    //           login_notice_visible — true when a notice/message box is up
    //         Confirmed indices: 2=credentials, 4=authenticator,
    //         24=server message box. Other screens emit INDEX_<n> so a new
    //         one can be mapped the first time it is seen. The box TEXT is
    //         still unreadable plugin-side, so distinguishing a recoverable
    //         "servers updating" notice from a "must restart" notice that
    //         shares an index still relies on the AHK pixel checks.
    //  2.15 — Two changes. (1) Single-file output: writeRaw now always
    //         writes one ge_visual_aid.txt regardless of login state. The
    //         v2.2 account-prefixed file and logged-out dual-write are gone,
    //         so consumers watch exactly one always-current path (removes the
    //         stale-named-file bug class). HTTP /state is unaffected.
    //         (2) login_index 10 mapped to CREDENTIALS — this client
    //         (revision 238) reports 10 for the standard username/password
    //         screen, not the 2 listed in the RuneLite API docs (confirmed
    //         from a clean-login dump). Index 2 remains mapped too.
    //  2.16 — Login label correction. v2.15 wrongly labelled login_index
    //         2/10 as CREDENTIALS. This client logs in via the Jagex
    //         launcher (a "Play Now / <account>" button, no username/
    //         password form), and index 10 is reported on BOTH that login
    //         screen and the welcome screen — so it is a screen-agnostic
    //         "ordinary logged-out" state, not a credentials form. Relabelled
    //         2/10 to NORMAL. Use game_state / welcome_screen_visible to
    //         distinguish the login screen from the welcome screen; use
    //         login_notice_visible / SERVER_MESSAGE for the notice boxes.
    //  2.20 — target_price always -1 / offer never confirms. With 2.19 the
    //         state file kept updating through the offer screen, but the flip
    //         still could not complete: every capture showed target_price=-1
    //         and pending_actions stuck on set_price, never reaching confirm.
    //
    //         CAUSE: not a renamed getter — a WIDENED TYPE. Copilot changed
    //         the suggestion price to 64-bit (its own wire format writes
    //         int64 for price while itemId/quantity stay int32, and the JSON
    //         field it replaced was literally named "price64"). getIntSafe()
    //         did a hard `(int) invoke(...)` cast, and casting a boxed Long
    //         to Integer throws ClassCastException — swallowed by the catch,
    //         returning -1. Every other suggestion getter still returns int,
    //         which is why only the price failed.
    //
    //         KNOCK-ON: sugPrice was permanently -1, so
    //         `offerPrice != sugPrice` was always true, set_price stayed
    //         pending forever and the `pending.isEmpty() -> confirm` branch
    //         was unreachable. Hence "never presses enter".
    //
    //         FIX: getIntSafe() now unboxes via Number.intValue() instead of
    //         a hard (int) cast, so it accepts int, long, short or any other
    //         numeric return type. One helper, both call sites (the
    //         target_price field in resolveAndWrite and sugPrice in
    //         resolveOfferScreen). Non-numeric or missing still returns -1 as
    //         before, so no behaviour changes anywhere it already worked.
    //
    //  2.19 — THE ACTUAL 2026-07-24 HANG FIX (2.18 diagnosed it wrongly).
    //         Symptom: while the GE offer screen was open the plugin wrote NO
    //         .txt at all — the file froze on the last home-screen write and
    //         only resumed once the offer screen was closed. The AHK script
    //         treats the state file as authoritative, so it kept being told to
    //         repeat the click that opened the offer screen, saw "stale", and
    //         went inert until the account disconnected.
    //
    //         MECHANISM: getOpenSlot() (varbit 4439) correctly routes to
    //         resolveOfferScreen() when a slot is open. That method read the
    //         Copilot suggestion with RAW invoke():
    //             invoke(sug,"offerType"/"getItemId"/"getPrice"/"getQuantity")
    //         invoke() throws if a method is missing. resolveAndWrite() then
    //         propagated the throw to onGameTick(), whose catch block only
    //         logged and returned — and every write in resolveAndWrite happens
    //         AFTER this point, so nothing was ever written. Note that
    //         resolveAndWrite() reads the SAME getters via the safe wrappers
    //         (getStringSafe/getIntSafe), which swallow the failure and return
    //         -1 — which is exactly why every capture shows target_price=-1.
    //
    //         FIXES:
    //         (a) resolveOfferScreen() now uses getStringSafe/getIntSafe for
    //             the four suggestion getters, matching resolveAndWrite().
    //         (b) A thrown resolve can no longer freeze the state file:
    //             onGameTick() now falls back to writeResolveFailureState(),
    //             which rebuilds ui/slot/inventory sections independently
    //             (each individually guarded) and writes an idle payload plus
    //             a resolve_error= field. The file therefore keeps ticking
    //             even while something upstream is broken, so the script sees
    //             live state instead of a frozen one.
    //         (c) onGameTick() logs the FULL stack trace instead of just
    //             e.getMessage(), so the offending method/class is visible in
    //             client.log.
    //
    //  2.18 — TWO FIXES for the 2026-07-24 all-VM hang (script clicked the
    //         suggested inventory/buy slot, the offer screen opened, and the
    //         plugin then reported the SAME action forever, so the AHK script
    //         saw "stale" and went inert until the account disconnected):
    //
    //         (a) OFFER SCREEN NOT DETECTED. geOfferScreen was
    //             isVisible(465, 26) — a hardcoded widget child index. That
    //             index moved, so with the offer screen plainly open the
    //             plugin emitted ge_offer_screen=false / ge_offer_type=none /
    //             ge_slot_open=0, and never advanced past the click that
    //             opened it. Now primarily driven by varbit 4439 (the GE slot
    //             currently being configured, 0 = none) — the exact signal
    //             getOpenSlot() already used, and stable across widget
    //             reshuffles. The widget check is retained as a secondary.
    //
    //         (b) COPILOT PREFERENCES ALL BLANK. linkToCopilot() looked up
    //             only the field names "preferencesManager" and
    //             "suggestionPreferencesManager" on FlippingCopilotPlugin;
    //             both returned null after a Copilot refactor, so
    //             suggestionPreferencesManager stayed null and every
    //             copilot_* preference emitted blank (risk level, timeframe,
    //             reserved slots, min profit, blocked count, profile).
    //             Confirmed from field output: sell_only/dump_mode/f2p_only
    //             read "false" while the rest were empty — the exact
    //             signature of the null branch, not the exception branch.
    //             Now falls back to scanning the plugin's fields by TYPE for
    //             one whose class name contains "Preferences", so a future
    //             rename cannot silently blank these again. Logs which field
    //             matched.
    //
    //  2.17 — Mapped login_index 9 to CLIENT_UPDATE: the "RuneScape has
    //         been updated / please restart RuneLite" notice box, which
    //         appears after clicking OK on a 24 server-update box once the
    //         client is out of date. It needs a RuneLite restart, not a
    //         retry. Confirmed from a live dump. 9 and 24 use different
    //         indices, so login_state_label now distinguishes the
    //         recoverable (SERVER_MESSAGE) from the restart-required
    //         (CLIENT_UPDATE) case with no pixel check. login_notice_visible
    //         is now true for both 9 and 24.
    //  2.21 — Scene / tile / camera block. Four additions, all cached on the
    //         client thread by updateSceneState() (called first thing in
    //         onGameTick) and served from a volatile string, so the HTTP
    //         handler and the off-thread idle writer never touch client state.
    //         A scene_age_ms field is emitted alongside so consumers can tell
    //         live geometry from a stale cache.
    //
    //         (A) detached_camera — read straight off the Client via
    //             getOculusOrbState(). The Detached Camera plugin does not
    //             hold this state itself, it only calls setOculusOrbState(),
    //             so no plugin-to-plugin lookup is needed or possible.
    //
    //         (B) hover_* — world coordinates of the tile under the mouse.
    //             getSelectedSceneTile() is NOT used: it returns the last
    //             RIGHT-CLICKED tile unless something calls setCheckClick /
    //             setMouseCanvasHoverPosition every frame (which is what the
    //             Tile Indicators plugin does, and which would mutate shared
    //             client state). Instead we scan the scene the way the
    //             world-location plugin does — canvas tile polygons tested
    //             against getMouseCanvasPosition() — with a cheap centre-point
    //             reject first so only a handful of tiles build a polygon.
    //             hover_valid=false whenever the mouse is off-scene or over
    //             UI; stale coordinates are never emitted.
    //
    //         (C) wp_<name>_* — named waypoints resolved from world tile to
    //             ABSOLUTE DESKTOP PIXELS every tick. Configure them in the
    //             new "Scene & Tiles" config section as
    //             name:x:y[:plane], comma or newline separated. Each emits a
    //             centre point plus the tile's bounding box, using exactly
    //             the same canvas-origin + DPI-scale maths already proven by
    //             buildClerkState(). This is the field that lets a consumer
    //             stop holding hardcoded screen boxes: it asks where a tile
    //             is right now instead of where it was when captured.
    //             wp_<name>_state is one of ok / offscreen / offscene /
    //             not_loaded / instanced / offline, never a silent 0,0.
    //
    //         (D) aggro_* — NPC aggression timer. There is no client-side
    //             API for this; the server holds it. Per the RuneLite wiki,
    //             the game remembers two tiles and resets the 10-minute timer
    //             when you move more than 10 steps from BOTH, moving the
    //             older tile under you. We track those two anchors the same
    //             way, and — also per the wiki — we can only know where they
    //             are after observing a jump big enough to guarantee a reset
    //             (a teleport, dungeon entrance or loading zone). Until then
    //             aggro_known=false and aggro_state=UNKNOWN rather than a
    //             confidently wrong countdown. Reset on every login.
    //  2.22 — Two changes, both from field feedback.
    //
    //         (1) AGGRESSION TIMER NOW READS RUNELITE'S OWN. v2.21 re-derived
    //             the 10-minute timer from scratch. It did not need to. The
    //             NPC Aggression Timer plugin (package npcunaggroarea, not
    //             npcaggroarea) publishes an AggressionTimer InfoBox, which
    //             extends the public Timer class and exposes getEndTime().
    //             So we walk InfoBoxManager.getInfoBoxes(), find it by class
    //             name, and report the exact instant RuneLite is counting
    //             down to — the same number shown on screen, using their
    //             calibration logic rather than ours.
    //
    //             It is an InfoBox, not a widget, which is why a widget
    //             lookup would never have found it.
    //
    //             Requires the NPC Aggression Timer plugin to be enabled
    //             with "Show timer" on. Three states are distinguished:
    //               • timer present            -> aggro_source=runelite
    //               • UncalibratedInfobox up   -> aggro_source=uncalibrated
    //                 (RuneLite has not located the anchors yet; teleport
    //                 away once and it calibrates)
    //               • timer seen earlier this login, now culled -> expired,
    //                 aggro_source=runelite_expired, state=UNAGGRESSIVE
    //             RuneLite culls the infobox on expiry, so without that
    //             third case "no infobox" would be ambiguous between
    //             "expired" and "plugin disabled".
    //
    //             The v2.21 internal estimate is retained as a fallback for
    //             when the RuneLite plugin is off (aggro_source=internal),
    //             and can be forced with the "Use RuneLite plugin" toggle.
    //
    //         (2) GRANULAR TOGGLES. The v2.21 scene block always computed
    //             everything. It is now behind a master switch that defaults
    //             to OFF, with a sub-toggle per feature, so a VM that only
    //             flips pays nothing: with the master off the block emits a
    //             single scene_enabled=false line and makes zero client
    //             reads. Rough per-tick cost, heaviest first:
    //               hovered tile   — scans the scene plane (only enable when
    //                                actually needed)
    //               waypoints      — one projection per configured tile
    //               canvas/camera  — a handful of field reads
    //               aggro          — one InfoBox list walk
    //  2.23 — Hover and mouse coordinates now also emitted in real desktop
    //         pixels (hover_screen_x/y, mouse_screen_x/y) using the identical
    //         canvas-origin + DPI conversion the waypoints already use, so
    //         everything in this block is directly clickable and nothing
    //         downstream has to do the arithmetic. The canvas-space fields
    //         are unchanged and still emitted — they remain the useful
    //         diagnostic when a projection needs checking.
    //
    //         NOTE ON RESOLUTION: these are NOT "4K coordinates" or "1080p
    //         coordinates" needing a mode branch. canvas_dpi_scale is read
    //         live from the display's GraphicsConfiguration, so the result is
    //         already the true pixel for whatever mode the machine is in.
    //         Emitting a second scaled variant would reintroduce exactly the
    //         multiply-by-a-factor approach that measured coordinate tables
    //         exist to avoid.
    //
    //         Also adds display_w / display_h (the physical desktop
    //         resolution) so a consumer can assert its own assumed mode
    //         against what the client is actually running at, rather than
    //         trusting a config flag.
    //  2.24 — Two additions to the waypoint block, both aimed at letting the
    //         consumer act without doing any geometry of its own.
    //
    //         (1) INSCRIBED CLICK BOX. wp_<name>_click_x1..y2 is the largest
    //             axis-aligned rectangle that fits INSIDE the tile polygon.
    //             The existing x1..y2 is the polygon's BOUNDING box, which is
    //             the wrong thing to click into: a tile renders as a squashed
    //             diamond, and a diamond fills only half its bounding box, so
    //             roughly half of all uniformly sampled points would land on a
    //             neighbouring tile. The inscribed box has no such problem —
    //             every point inside it is on the tile — so a consumer can
    //             pick a plain random x,y within it and be safe.
    //
    //             Found by binary search on a scale factor, validating all
    //             four corners with Polygon.contains(). That is deliberately
    //             general rather than using the closed-form solution for a
    //             symmetric rhombus (half-width/2, half-height/2), because
    //             tiles on sloped ground project as irregular quads where the
    //             closed form does not hold. Search converges to the closed
    //             form on flat ground anyway.
    //
    //             Both boxes are kept. The bounding box still describes the
    //             tile's full screen extent; the click box describes where it
    //             is safe to click.
    //
    //         (2) BEARING FIELDS, emitted for every waypoint regardless of
    //             state — including offscreen and offscene, where they are the
    //             whole point. A tile that cannot be projected still has a
    //             known world position, so we can always answer "which way do
    //             I turn to bring it into view": wp_<name>_rel_bearing_deg is
    //             the angle from the current camera facing, negative left,
    //             positive right, 0 dead ahead. Uses the same yaw convention
    //             as compass_degrees (0-2047, 0 = north, clockwise).
    //  2.25 — Waypoint bundles and cluster areas.
    //
    //         (1) BUNDLES. Ten named sets of waypoints, each with its own
    //             enable checkbox, in a separate "Waypoint Bundles" config
    //             section. Only enabled bundles are parsed or resolved, so a
    //             disabled set costs nothing. RuneLite config items must be
    //             declared statically, hence a fixed ten slots rather than an
    //             arbitrary number. The original always-on Waypoints field is
    //             unchanged and still works alongside them.
    //
    //             Waypoint names stay exactly as written — they are NOT
    //             prefixed with the bundle name, so a consumer's key does not
    //             change when a set is reorganised. If two enabled bundles
    //             define the same name the first wins, and the collision is
    //             reported in waypoint_name_conflicts rather than hidden.
    //
    //         (2) CLUSTERS. A rectangular area of tiles resolved to ONE
    //             clickable box, written name:x1:y1-x2:y2[:plane]. The dash
    //             is what distinguishes a cluster from a plain tile, since
    //             name:x:y:plane already uses four colon-separated fields.
    //
    //             A rectangular world area does not project to a rectangle —
    //             it projects to a quadrilateral, and the naive union of the
    //             tiles' bounding boxes would again include large regions
    //             that are not on the area at all. So we collect every tile
    //             polygon's vertices, take their convex hull (which is the
    //             projected outline of the whole area), and run the same
    //             inscribed-box search used for single tiles against that
    //             hull. Every point in the resulting click box is inside the
    //             area, and the box is far larger than a single tile's.
    //
    //             The hull is taken over all tiles rather than the four
    //             corner tiles because terrain height varies per tile: a rise
    //             in the middle of the area can project outside the quad
    //             formed by its corners.
    //  2.26 — VIEWPORT CLIPPING. Fixes a real hazard in 2.24/2.25.
    //
    //         Perspective.localToCanvas() returns null only when a tile is
    //         behind the camera or outside the loaded scene — NOT when it is
    //         merely off the side of the screen. A tile past the left edge
    //         still projects to a valid canvas point with a negative X. So
    //         until now such tiles were treated as visible: they contributed
    //         vertices to a cluster hull, counted towards tiles_visible, and
    //         could produce a click box lying partly or wholly OUTSIDE the
    //         RuneLite window. A click on that box would land on the desktop
    //         or on whatever window sits beside the client.
    //
    //         Now every click box is constrained to the 3D viewport:
    //           • a tile whose polygon does not intersect the viewport is
    //             reported offscreen and contributes nothing
    //           • the inscribed-box search additionally requires all four
    //             corners to fall inside the viewport, so a partly visible
    //             tile or cluster yields a smaller box covering only the
    //             on-screen part rather than an unsafe larger one
    //           • cluster anchoring uses the centroid of the tile centres
    //             that are actually on screen, so a cluster half off the
    //             edge still anchors somewhere valid
    //
    //         Consequence for clusters: a partly visible area returns
    //         state=partial with a click box covering only the visible
    //         portion, and tiles_visible now means "on screen" rather than
    //         "projectable".
    //  2.27 — Ground item and NPC tracking, both off by default.
    //
    //         (1) GROUND ITEMS. Scans tiles within a configurable radius for
    //             items matching a filter of names and/or item IDs, and emits
    //             a click box for each plus its despawn countdown.
    //
    //             Despawn comes from TileItem.getDespawnTime(), which is a
    //             SERVER TICK number, not a duration — it is compared against
    //             Client.getTickCount() and converted at 0.6s per tick. The
    //             client only holds a meaningful despawn tick for items whose
    //             spawn it witnessed; for items already on the ground when
    //             you arrived the value can be meaningless, so anything that
    //             computes as negative or absurdly large is reported as -1
    //             rather than as a confident wrong number.
    //
    //         (2) NPCS. Matches live NPCs by ID or name and emits a click box
    //             taken from the NPC's own convex hull — the actual model
    //             clickbox, not the tile underneath — falling back to the
    //             tile polygon when the hull is unavailable. Also emits
    //             health ratio/scale, current animation and whether the NPC
    //             is interacting with something, which is what an AFK combat
    //             module needs to tell a live target from a dead one.
    //
    //         Both lists are sorted NEAREST FIRST, so index 0 is the closest
    //         match, and both are capped to bound the payload. Both reuse the
    //         same viewport clipping as waypoints, so a click box is never
    //         produced outside the visible window.
    //  2.28 — GAME OBJECT (scenery) tracking: cave entrances, doors, ladders,
    //         banks, trees, altars — anything that is part of the map rather
    //         than an item or an NPC. Third API family after items and NPCs.
    //
    //         All four scenery layers are covered: GameObject (the usual
    //         case), WallObject (doors, gates), DecorativeObject and
    //         GroundObject. Each is a TileObject, whose getClickbox() gives
    //         the true clickable shape — preferred over the tile underneath,
    //         which is the wrong shape for anything multi-tile.
    //
    //         Two traps handled:
    //
    //         • A multi-tile GameObject is returned by EVERY tile it covers,
    //           so a 3x3 cave entrance would otherwise appear nine times.
    //           Deduplicated on getHash().
    //
    //         • Varbit-morphing objects (doors open/closed, quest-state cave
    //           entrances) report a placeholder name on their base id. Where
    //           getImpostorIds() is non-null the impostor is resolved to get
    //           the name the player actually sees. Impostor results are NOT
    //           cached because they change with game state; everything else
    //           is cached by id, which is what keeps a name filter affordable
    //           over a full radius scan.
    //
    //         inscribedBox() now also tests the four edge midpoints. Object
    //         clickboxes are composites and can be non-convex, where four
    //         corners inside the shape does not guarantee the whole box is.
    //         No effect on convex shapes, so tiles and NPCs are unchanged.
    //  2.29 — LABELS AND ANCHORS on the ground item, NPC and scenery filters.
    //
    //         The problem: two entrances to the same tunnel share an object
    //         ID and a name. A plain filter matched both, and since results
    //         are ordered nearest first, index 0 flipped between them as the
    //         player moved. A consumer could only tell them apart by testing
    //         world_x/world_y itself.
    //
    //         Filter entries may now carry either or both of:
    //
    //           label=       a stable key. The entry is emitted as
    //                        <prefix>_<label>_* instead of <prefix>_<index>_*,
    //                        and is ALWAYS emitted — state=not_found when
    //                        nothing matches — so a consumer can distinguish
    //                        "no match right now" from "misconfigured".
    //
    //           @x:y[:plane] an anchor. Only matches within
    //                        objectAnchorTolerance tiles of that position.
    //                        Tolerance exists because a multi-tile object
    //                        reports its base tile, which is not necessarily
    //                        the tile you would aim at.
    //
    //         So the two tunnel mouths become:
    //             tunnel_in=Tunnel@2437:9161
    //             tunnel_out=Tunnel@2451:9170
    //         and the consumer reads go_tunnel_in_click_x1 forever, with no
    //         disambiguation logic and no dependence on which is nearer.
    //
    //         Unlabelled entries behave exactly as in 2.28 — pooled, sorted
    //         nearest first, emitted by index. Both forms can be mixed.
    //  2.30 — WILDCARD FILTER "*" plus lazy name resolution.
    //
    //         "*" matches any named entity. On its own it is a discovery
    //         tool: point it at an area and read back the ids, names and
    //         actions of everything nearby, instead of guessing ids from a
    //         wiki that may list an impostor rather than the base object.
    //
    //         Anchored, it becomes something better — "tell me what is at
    //         this tile right now". That is the right shape for mining
    //         rocks. A depleted rock is a DIFFERENT object id from a live
    //         one, so an id filter simply stops matching when the rock is
    //         mined out, which cannot distinguish "depleted" from "I typed
    //         the wrong id". An anchored wildcard always reports whichever
    //         object currently occupies the tile, and its actions say which
    //         state it is in: a live rock offers Mine, a depleted one does
    //         not. That works for every ore type without knowing either id.
    //
    //             iron_a=*@2437:9161
    //             iron_b=*@2438:9161
    //         then test whether go_iron_a_actions contains Mine.
    //
    //         Wildcards deliberately ignore unnamed objects — scenery is
    //         full of nameless decorative pieces that would swamp the output.
    //
    //         LAZY NAMES: matching now tries ids and anchors first and only
    //         resolves a name when a name-based filter could still match at
    //         that position. An anchored filter therefore costs almost
    //         nothing over a full radius scan, since position rejects the
    //         overwhelming majority of candidates before any composition
    //         lookup happens. This matters because a wildcard would
    //         otherwise force a name lookup on every object in range.
    //  2.31 — ACTION FILTERS for scenery: #Mine, #Chop-down, #Enter, #Bank.
    //
    //         Matches any object offering that right-click action, which is
    //         the natural way to ask for "the nearest thing I can mine"
    //         without knowing a single object id.
    //
    //         This is what makes rock rotation work. A depleted rock is a
    //         different object id from a live one AND loses its Mine action,
    //         so #Mine matches only live rocks. Results are already ordered
    //         nearest first and a labelled filter takes its nearest match, so
    //             rock=#Mine
    //         resolves to the closest live rock, and the moment that one is
    //         mined out it stops matching and the label moves to the next
    //         closest by itself. No id list, no per-rock anchors, no
    //         bookkeeping on the consumer side.
    //
    //         Works for any gatherable on the same principle — #Chop-down
    //         for trees, #Cast-on, #Enter, #Climb-down for traversal.
    //
    //         Scenery only. NPCs and ground items have fixed action sets
    //         where this buys nothing, so their filters ignore #.
    //
    //         Name and actions now come from ONE composition lookup and are
    //         cached together, so adding action matching costs no extra
    //         lookups over 2.30 and removes one per emitted result.
    //  2.32 — BUG FIX plus two devtools-style additions.
    //
    //         (1) ACTION FILTERS WERE BROKEN. The filter's action was
    //             lowercased at parse time but the object's action list was
    //             built from getActions() with its original case, so the
    //             comparison was "Mine".equals("mine") and #Mine could never
    //             match anything. Now compared case-insensitively. The output
    //             keeps original case for readability.
    //
    //         (2) LOADING LINES (load_*). The scene reloads when the player
    //             crosses scene coordinate 16 or 88, which is what RuneLite's
    //             devtools "Loading lines" draws. Emits all four boundaries in
    //             world coordinates, the distance to each, and a click box on
    //             the nearest one — useful because a scene reload invalidates
    //             every cached screen coordinate, so knowing one is coming is
    //             worth more than reacting after the fact.
    //
    //         (3) MOVEMENT FLAGS (move_*). Raw collision data from
    //             WorldView.getCollisionMaps(), which is the same source
    //             devtools' "Valid movement" overlay uses. For each of the
    //             eight directions around the player: whether it is walkable,
    //             its world coordinate and its click box. Plus the raw flag
    //             int for the player's tile and the hovered tile, so anything
    //             not covered by the walkable booleans can still be decoded
    //             consumer-side.
    //  2.33 — SHORTEST PATH INTEGRATION. Drives the Shortest Path plugin
    //         programmatically and reads the route it produces back out as
    //         clickable screen pixels.
    //
    //         Set a destination with a plain HTTP call:
    //             GET /path?x=2437&y=9161&plane=0
    //             GET /path?clear=1
    //         which is better than a keybind — the consumer already speaks
    //         HTTP to this plugin, so no key handling or focus juggling.
    //
    //         The request is queued and executed in onGameTick, never in the
    //         HTTP handler, because pathfinding touches client state.
    //
    //         Everything is reflective and fails soft: Shortest Path is a
    //         plugin-hub plugin that may not be installed, and its internals
    //         may change. path_plugin_found says which. Nothing else in this
    //         plugin depends on it.
    //
    //         Entry points used, all verified against the plugin source:
    //           ShortestPathPlugin.restartPathfinding(int, Set<Integer>)  public
    //           Pathfinder.getPath() -> List<PathStep>                    public
    //           PathStep.getPackedPosition()                              lombok
    //           WorldPointUtil.packWorldPoint / unpackWorldX / Y / Plane  public
    //         Only the pathfinder field itself needs setAccessible.
    //
    //         Coordinates there are PACKED INTS, not WorldPoints, so all
    //         conversion goes through WorldPointUtil rather than assuming a
    //         bit layout that could change.
    //
    //         The emitted step is chosen from the LAST QUARTER of the
    //         currently visible run of the path — furthest progress per click
    //         while staying on screen — and re-randomised within that quarter
    //         each tick so repeated walks do not trace identical pixels.
    //  2.34 — FAIR ALLOCATION between filter entries, plus label counts.
    //
    //         (1) ROUND ROBIN. Until now every unlabelled filter entry shared
    //             one pool sorted globally by distance and capped by
    //             objectMaxResults. So "4483, Coal rocks" returned six coal
    //             rocks and no bank chest: the near rocks filled the cap and
    //             the chest, being further away, never got a slot. Each entry
    //             now gets its nearest match before any entry gets a second,
    //             and so on until the cap is reached. Every filter entry is
    //             represented whenever it matches anything at all, regardless
    //             of how far away it is.
    //
    //             Within an entry the order is still nearest first, and the
    //             overall cap is unchanged.
    //
    //         (2) LABEL COUNTS. A label took only the single nearest match,
    //             which is right for a bank chest and wrong for a rock field.
    //             Write label:N to ask for up to N:
    //
    //                 chest=4483            -> go_chest_*        (as before)
    //                 coal:3=Coal rocks     -> go_coal_count=3
    //                                          go_coal_0_*
    //                                          go_coal_1_*
    //                                          go_coal_2_*
    //
    //             Labelled entries are still exempt from objectMaxResults, so
    //             a counted label always returns its full quota. Slots with
    //             no match report state=not_found rather than vanishing, so
    //             the key set stays stable as rocks deplete and respawn.
    //
    //         Filter entries are now carried through as objects rather than
    //         as their label string, so results can be attributed back to the
    //         entry that matched them. That is what makes both of the above
    //         possible.
    //  2.35 — Search radius cap raised from 30 to 52 tiles, which is the
    //         half-width of the loaded scene and therefore as far as anything
    //         can be found at all. A bank chest 20-plus tiles away was simply
    //         outside the old cap. Cost grows with the square of the radius,
    //         so 52 is about twelve times the work of 15 - raise it only as
    //         far as needed. An @x:y anchor still finds a match at any
    //         distance without touching this, because the anchor test rejects
    //         on position before any lookup happens.
    //  2.36 — REMOTE CONFIGURATION over HTTP. Filters and toggles can now be
    //         changed by an external script without touching the RuneLite
    //         settings panel, so one running client can be repointed from
    //         coal to iron, or from mining to banking, mid-session.
    //
    //             GET /filter                          read every value back
    //             GET /filter?scenery=Coal%20rocks     set the scenery filter
    //             GET /filter?npcs=Banker&items=Coal
    //             GET /filter?scenery=*&radius=30      several at once
    //             GET /filter?sceneryon=1&hoveron=0    flip toggles
    //
    //         Keys are WHITELISTED rather than exposing setConfiguration
    //         generally: a typo can then only be rejected, never used to
    //         write some unrelated RuneLite setting. Unknown keys come back
    //         named in the response instead of failing silently.
    //
    //         Values are URL-decoded, so spaces and commas survive —
    //         "Coal%20rocks" and "iron%3DIron%20rocks%2Ccoal%3ACoal%20rocks"
    //         both arrive intact.
    //
    //         Writes are QUEUED and applied on the client thread in
    //         onGameTick, the same discipline as /path, so an HTTP thread
    //         never touches ConfigManager directly.
    //
    //         NOTE: these are real config writes and PERSIST to the RuneLite
    //         profile, exactly as if typed into the panel. They survive a
    //         restart, so a script that changes a filter owns setting it back.
    //  2.37 — RUNITE ROCK TRACKING and WORLD HOPPING.
    //
    //         (1) runite_* mirrors the Runite Rocks plugin's world map into
    //             the output: every rock it has seen, on every world visited,
    //             with availability and respawn countdown. Read by reflection
    //             through public Lombok getters —
    //                 RuniteRocksPlugin.getWorldMap()
    //                 WorldTracker.getWorld() / getRuniteRocks()
    //                 RuniteRock.isAvailable() / getRespawnTime() /
    //                             getLastSeenAt() / hasWitnessedDepletion()
    //                 Rock.getName() / getLocation() / getWorldPoint()
    //             so nothing needs setAccessible and nothing breaks if it is
    //             absent. runite_plugin_found says which.
    //
    //             Entries are sorted MOST USEFUL FIRST: available rocks
    //             before pending ones, then by soonest respawn. So runite_0
    //             is what to act on, and runite_best_world is the world to
    //             hop to.
    //
    //             hasWitnessedDepletion is surfaced as _accurate. A rock we
    //             never saw deplete has a respawn time inferred from when it
    //             was first seen missing, which can be up to a full cycle
    //             early. Treat inaccurate entries as a hint, not a schedule.
    //
    //         (2) GET /hop?world=100 hops worlds. Implemented natively via
    //             WorldService and client.hopToWorld rather than by calling
    //             into Runite Rocks, so hopping works whether or not that
    //             plugin is installed.
    //
    //             Hopping is a two-stage dance: the world switcher has to be
    //             open before hopToWorld takes effect. The request is queued,
    //             openWorldHopper() is called, and hopToWorld is retried for
    //             a few ticks until it lands. Deliberately no widget lookup —
    //             the retry loop is robust across interface id changes.
    //  2.38 — PLAYER ACTIVITY (player_act_*). What the local player is doing
    //         right now: animation, how long it has been running, whether
    //         they are moving, and what they are interacting with.
    //
    //         There is no "I am mining" flag in the client — skills are
    //         identified by ANIMATION ID, and the id differs per pickaxe, per
    //         axe, per weapon. So this deliberately does NOT ship a lookup
    //         table of animation ids to skill names. Such a table is large,
    //         incomplete, and silently mislabels when Jagex changes an id —
    //         a wrong label is worse than a raw number. Read
    //         player_act_animation while performing the action, note the id,
    //         and match on it consumer-side. Same discovery workflow as "*"
    //         for object ids.
    //
    //         The genuinely useful derived signals are timing based and do
    //         not need to know which skill it is:
    //
    //           player_act_busy            an animation is running now
    //           player_act_anim_seconds    how long the CURRENT animation has
    //                                      been running — rising means still
    //                                      working, resetting means the action
    //                                      restarted
    //           player_act_idle_seconds    how long since ANY animation. This
    //                                      is the "rock depleted / stopped
    //                                      chopping" signal, and it is skill
    //                                      agnostic
    //           player_act_last_animation  survives brief idle gaps, so a
    //                                      one-tick pause between swings does
    //                                      not read as "stopped"
    //
    //         Note the existing player_idle_seconds field elsewhere in the
    //         payload is INPUT idleness (Copilot's own metric), which is a
    //         different thing from animation idleness.
    //  2.39 — PER-ENTRY SEARCH RADIUS.  name~R  sets the radius for one
    //         filter entry, overriding the global setting:
    //
    //             coal:3=Coal rocks~8, chest=4483~45
    //
    //         so nearby ore is not confused with ore across the cave, while a
    //         bank chest is still found at forty-plus tiles. Applies to
    //         scenery, ground items and NPCs alike.
    //
    //         52 remains the hard ceiling everywhere: it is the half-width of
    //         the loaded scene, so nothing exists beyond it to find.
    //
    //         HOW THE COST WORKS, since it is not obvious. The tile sweep runs
    //         ONCE at the largest radius any entry asks for — a 45 tile entry
    //         means a 45 tile sweep regardless of what else is configured. A
    //         per-entry radius therefore buys correctness rather than speed.
    //         What it does save is lookups: beyond an entry's radius that
    //         entry stops being a candidate, so out past the ore radius only
    //         the chest filter can still match and most objects out there
    //         never have their name resolved at all.
    //  2.40 — AGILITY INTEGRATION (agility_*). Mirrors RuneLite's own Agility
    //         plugin: the course obstacles it is currently highlighting, the
    //         marks of grace it is tracking, traps, and the Werewolf stick —
    //         each as a click box in desktop pixels.
    //
    //         Read through public Lombok getters:
    //             AgilityPlugin.getObstacles()      Map<TileObject, Obstacle>
    //             AgilityPlugin.getMarksOfGrace()   List<Tile>
    //             AgilityPlugin.getAgilityLevel()   int
    //             AgilityPlugin.getSession()        AgilitySession
    //         getStickTile() is package-private so that one needs
    //         setAccessible; everything else does not. Fails soft throughout
    //         via agility_plugin_found.
    //
    //         Obstacles come from the plugin's own curated course data, so
    //         this is the real obstacle set rather than a guess from object
    //         names — no id lists to maintain consumer-side.
    //
    //         ORDERING, and the honest limitation: the Agility plugin does
    //         NOT expose course sequence. It highlights every obstacle
    //         currently in view and nothing more. Entries are therefore
    //         sorted NEAREST FIRST, which on a rooftop course is the same
    //         answer in practice because only one obstacle is ever in range
    //         at a time. agility_obstacle_0 is the one to click. On courses
    //         where two are reachable at once that assumption breaks, and the
    //         consumer should match on world coordinates instead.
    //
    //         Traps are flagged rather than separated, by testing the id
    //         against Obstacles.TRAP_OBSTACLE_IDS. Marks of grace are also
    //         findable through the ordinary gi_ ground item filter; this
    //         route additionally carries the plugin's own tracking, which
    //         remembers marks it has seen.
    //  2.41 — ORDERED AGILITY COURSE with progression tracking.
    //
    //         V2.40 sorted obstacles nearest first, which picks the one you
    //         just COMPLETED whenever it is still in view. RuneLite's Agility
    //         plugin has no course sequence to borrow, so the order is
    //         supplied in config as an ordered list of start tiles:
    //
    //             agilityCourseOrder = 2484:3437, 2477:3420, 2474:3401, ...
    //
    //         Each entry becomes agility_step_N_* with a stable index, so a
    //         consumer can always ask for a specific obstacle by number.
    //
    //         PROGRESSION RULE, and why it is shaped this way. Let B be the
    //         nearest configured step that is currently resolvable:
    //
    //           B >= progress            you have moved on. progress = B.
    //           progress - B == 1        the step just completed is still in
    //                                    view behind you. IGNORED — this is
    //                                    exactly the case that broke V2.40.
    //           progress - B >= 2        you are well behind where you were,
    //                                    which means a fall or a restart.
    //                                    progress = B.
    //
    //         So finishing a lap or falling off resets by itself, without
    //         needing to detect damage or a plane change, both of which vary
    //         by course. The single-step-back exclusion is what stops the
    //         completed obstacle being re-selected.
    //
    //         agility_next_index is the recommendation and agility_next_* is
    //         that step's click box. GET /agility?reset=1 forces progress
    //         back to zero, and /agility?next=N sets it explicitly, for when
    //         the consumer knows better than the heuristic.
    //
    //         Steps are matched to live obstacles by world position within
    //         the anchor tolerance; a step with no live obstacle still
    //         resolves as a plain tile, so it never simply vanishes.
    //  2.42 — RECTANGULAR BOUNDS on a filter entry:
    //
    //             coal=Coal rocks@2437:9161-2450:9174
    //
    //         Only matches inside that rectangle. This is the answer to ore
    //         being picked up through a wall: a radius is a circle and cannot
    //         express "this side of the wall", so widening it to reach the
    //         far rocks in your own area inevitably reached the ones next
    //         door too. A rectangle can, and the radius then stops mattering.
    //
    //         Same x1:y1-x2:y2 form the waypoint clusters already use, and it
    //         is the natural generalisation of the @x:y anchor — a point
    //         anchor is just a one-tile box. Corners may be given in any
    //         order, and an optional trailing plane restricts it further:
    //             @2437:9161-2450:9174:0
    //
    //         The sweep radius is raised automatically to whatever reaches
    //         the far corner of the box, so a bounded entry does not also
    //         need ~R. Where both are given the box wins, since it is the
    //         more specific statement.
    //
    //         Works on scenery, ground items and NPCs.
    //  2.43 — Three additions.
    //
    //         (1) ROOFTOP AGILITY IMPROVED integration (rooftop_*). That
    //             plugin carries what RuneLite's own Agility plugin lacks:
    //             real course sequence per course, branching next-obstacle
    //             ids, level gating, and which obstacle must NOT be used yet
    //             because a Mark of grace is still on the ground behind it.
    //             So rooftop_next_0_* is an authoritative answer rather than
    //             the nearest-first guess of V2.40 or the hand-written order
    //             of V2.41. Both of those remain as fallbacks.
    //
    //             Read through public methods; only the private
    //             coursesManager field needs setAccessible.
    //
    //         (2) VITALS (vit_*). Hitpoints, prayer, run energy and special
    //             attack, none of which were exposed before.
    //
    //             Special attack is VarPlayer 300, stored as PERCENT x10, so
    //             1000 means 100%. It is divided here so vit_spec_percent
    //             reads 0-100. Run energy is normalised too: newer clients
    //             report 0-10000 and older ones 0-100, so the raw value is
    //             emitted alongside a percent derived from whichever scale is
    //             in use rather than assuming one.
    //
    //         (3) WIDGET COORDINATES (wg_*). A configurable list of
    //             interface widgets resolved to desktop pixels:
    //
    //                 spec=160:35, prayer=160:31, style2=593:5
    //
    //             Deliberately generic rather than a hardcoded set of orb
    //             ids. Widget ids move between RuneLite versions, and a
    //             shipped list would rot silently; a list you capture with
    //             RuneLite's own Widget Inspector cannot. Same discovery
    //             principle as "*" for objects and animation ids for skills.
    //  2.44 — Widget STATE fields: sprite id, opacity, item id and quantity.
    //
    //         Bounds alone say where a widget is, not what it is showing. The
    //         sprite id is the game's own state flag and is often the
    //         cleanest readiness test available — the special attack orb
    //         indicator carries sprite 1607 when a spec can be used and 1064
    //         when it cannot, with opacity moving 25 -> 50 alongside. The
    //         same trick reads quick prayer on/off, which combat style is
    //         selected, run toggled, and so on, without needing a varbit for
    //         each.
    //
    //         itemId and itemQuantity are emitted too, so an inventory or
    //         equipment slot widget reports its contents as well as its box.
    //  2.45 — EASY BLAST FURNACE integration (bf_*). Mirrors that plugin's
    //         current instruction as clickable pixels, so a consumer follows
    //         the same steps a human would read off its overlay.
    //
    //         MethodHandler.getSteps() returns MethodStep[] — usually one
    //         step, sometimes several alternatives — each a subclass telling
    //         you WHAT to click:
    //             ItemStep   getItemIds()        an inventory item
    //             ObjectStep getObjectId()       a game object
    //             WidgetStep getPackedWidgetId() an interface component
    //             TileStep   getWorldPoint()     a tile to walk to
    //         All four are resolved here to the same click box shape, so the
    //         consumer does not branch on type unless it wants to.
    //
    //         Type is reported as bf_step_N_type = item|object|widget|tile,
    //         with bf_step_N_tooltip carrying that plugin's own instruction
    //         text ("Withdraw coal", "Click the conveyor belt") — which is
    //         both the most readable field and the most stable, since it does
    //         not depend on ids that may change.
    //
    //         Object steps resolve through the plugin's ObjectManager, which
    //         caches the conveyor belt, dispenser and bank chest as they
    //         spawn, so they work even when the object is off screen. Widget
    //         ids arrive PACKED (group << 16 | child) and are unpacked here.
    //         Item steps are located by scanning inventory and bank widget
    //         containers for a matching item id.
    //
    //         Reflection throughout, everything optional, reported by
    //         bf_plugin_found. The private methodHandler and objectManager
    //         fields need setAccessible; the rest are public Lombok getters.
    //  2.46 — BLAST FURNACE GAME STATE alongside the V2.45 step mirror:
    //         coffer, furnace contents, dispenser state and foreman timer.
    //
    //         Read straight from VARBITS rather than from RuneLite's Blast
    //         Furnace plugin, so none of it depends on that plugin being
    //         enabled — and there is nothing to reflect into, since the
    //         varbits are the same source it reads itself. Ids taken from
    //         VarbitID and listed in the table below so they can be checked.
    //
    //         Coffer time remaining uses the same 72,000 gp/hour figure as
    //         RuneLite's coffer overlay, so bf_coffer_seconds matches what
    //         the overlay displays.
    //
    //         bf_dispenser_state is BLAST_FURNACE_BARS_HOT:
    //             0 nothing waiting, 1 ore still cooking (do NOT put more on
    //             the belt yet), 2 bars ready but HOT — ice gloves needed,
    //             3 bars cooled and safe to take bare handed.
    //         bf_bars_ready and bf_needs_ice_gloves derive from it so a
    //         consumer does not have to remember the numbering.
    //
    //         The foreman fee is required under Smithing 60 and lasts ten
    //         minutes. RuneLite tracks it with a ForemanTimer infobox, which
    //         is read here the same way the aggression timer is — matched by
    //         class simple name, end instant from the public Timer getter.
    //         bf_foreman_needed says whether the account is even affected.
    //
    //         Contents are emitted only for non-zero entries, so the block
    //         stays small: an empty furnace adds two lines, not twenty.
    //  2.47 — RUNE COUNTING and REMAINING CASTS (rune_*).
    //
    //         NOT a reflection into the Remaining Casts plugin. Its source
    //         could not be located to verify method names, and guessing at
    //         them would produce code that silently returns nothing. This
    //         computes the same answer from the game state directly, so it
    //         needs no third-party plugin at all.
    //
    //         Three sources are summed per rune type:
    //           inventory     ordinary item counts
    //           rune pouch    varbits 29/1622/1623 (type) and 1624/1625/1626
    //                         (quantity). Divine pouch's fourth slot is read
    //                         too where the varbits exist.
    //           equipment     a staff granting unlimited runes reports
    //                         rune_<type>_unlimited=true and a count of
    //                         999999, so a consumer dividing by a
    //                         requirement gets a sensible large number
    //                         rather than needing a special case.
    //
    //         COMBINATION RUNES are counted toward BOTH their elements, so
    //         lava runes add to both fire and earth. That matches how the
    //         game spends them, but note the two totals are not independent:
    //         a spell needing both fire AND earth cannot use one lava rune
    //         twice. Where that matters, rune_<type>_pure gives the count
    //         excluding combination runes.
    //
    //         SPELL REQUIREMENTS ARE NOT SHIPPED. A table of every spell's
    //         runes would be large, would rot as spells change, and would
    //         silently mislabel when it did. Instead configure the spell you
    //         are actually casting:
    //             cast=fire:5,air:4
    //         and rune_cast_remaining is the number of casts available, the
    //         limiting rune named in rune_cast_limiter.
    //  2.48 — Four additions.
    //
    //         (1) BANK FILTER (bank_*). A named list of items to report
    //             quantities for, rather than dumping the whole bank:
    //                 logs=Logs, air=Air rune, 995
    //             Quantities come from the BANK item container, which the
    //             client keeps populated after the bank has been opened once
    //             this session — so counts remain readable while away from a
    //             bank, with bank_stale saying whether the bank is open now.
    //
    //         (2) SHORTEST PATH TRANSPORTS (path_transport_*). The reason a
    //             path step sometimes sits on the tile you are stood on is
    //             that the step is a TELEPORT, not a walk. The plugin knows
    //             this and renders it as text; that text is now exposed.
    //             transportsForEdge(step, next) is public and returns the
    //             candidate transports for the current edge, each with a
    //             display info string such as the jewellery box letter to
    //             press. Parsed into item name and key where a trailing
    //             letter or digit is present, so a consumer does not have to
    //             pattern-match the sentence itself.
    //
    //         (3) INCOMING ATTACK STYLE (att_*). Not the projectile-override
    //             plugin, which only recolours existing projectiles and
    //             cannot classify them. Instead, projectiles aimed at the
    //             local player are captured as they spawn: a projectile at
    //             all means ranged or magic, and its id distinguishes which
    //             once you have noted it. An attacker in range with no
    //             projectile is melee. Style is reported as a best guess with
    //             att_confidence, never as a certainty, because the client
    //             does not label attack styles.
    //
    //         (4) ACTIVE PRAYERS (pray_*). Straight from Client.isPrayerActive
    //             over the Prayer enum, so overheads and boosts are listed by
    //             name with no varbit table needed here.
    //  2.49 — SAILING integration (sail_*), reading the Sailing plugin's own
    //         boat model rather than hardcoding object ids.
    //
    //         That plugin's Boat class is @Data annotated, so hull, sailMast,
    //         helm, cargoHold and the salvagingHooks set are all reachable
    //         through public getters, along with getCargoCapacity(),
    //         getHullTier(), getSizeClass() and getSalvagingHookTiers().
    //         BoatTracker.getBoat() is public and returns the boat the local
    //         player is currently aboard.
    //
    //         DELIBERATELY NO HARDCODED SAILING OBJECT IDS. The constants the
    //         Sailing plugin uses for the crystal extractor, shipwrecks and
    //         hooks (SAILING_CRYSTAL_EXTRACTOR_ACTIVATED,
    //         SAILING_SMALL_SHIPWRECK_STUMP and so on) do not exist in the
    //         RuneLite API this plugin builds against — they arrive with a
    //         newer client. Inventing them would produce silent mismatches.
    //         Facilities come from the boat model instead, and anything not
    //         in that model is reachable through the ordinary go_ scenery
    //         filter, where the "*" wildcard reveals the real ids.
    //
    //         CREW OCCUPANCY. Each salvaging hook reports whether a player or
    //         NPC is standing on or beside it, which is the "is the left hook
    //         taken" question. Occupancy is by proximity to the facility's
    //         tile, so a crewmate walking past momentarily reads as occupied;
    //         sail_hook_N_occupant_dist lets a consumer be stricter.
    //
    //         CRYSTAL EXTRACTOR AND SALVAGE STATE both work the way live and
    //         depleted rocks do: activated and deactivated are DIFFERENT
    //         OBJECT IDS, as are a shipwreck and its stump. So an anchored
    //         wildcard in the scenery filter already answers "is it ready"
    //         and "is the salvage up or down" without any new field —
    //         extractor=*@x:y then watch go_extractor_id change.
    //  2.50 — CARGO HOLD FULLNESS. V2.49 reported capacity but not the
    //         current count, on the grounds that CargoHoldTracker keeps it in
    //         a private map. That was over-cautious: the plugin renders the
    //         figure as an overlay, so it is computed, and its private
    //         usedCapacity() / maxCapacity() methods are reachable with
    //         setAccessible.
    //
    //         Calling those is better than reimplementing them, because they
    //         already handle the parts that are easy to get wrong — per-boat
    //         slots keyed off SAILING_LAST_PERSONAL_BOAT_BOARDED, stackable
    //         versus unstackable items, and a config-persisted cache that
    //         survives being away from the boat.
    //
    //         sail_cargo_used is -1 when the plugin itself does not know,
    //         which is exactly when its overlay shows "???" — that is not an
    //         error, it means the hold has not been observed yet this
    //         session. sail_cargo_percent is only computed when both numbers
    //         are known.
    //  2.51 — Build fix: onProjectileMoved carried two @Subscribe
    //         annotations. The V2.48 patch inserted the new subscriber above
    //         onGameTick without accounting for the @Subscribe already
    //         sitting there, leaving both stacked on the inserted method.
    //  2.52 — SAILING LINK NEVER ESTABLISHED. sail_plugin_found was false
    //         even sat on a boat with the Sailing plugin running. Not a
    //         moved package, as the message first suggested — an ACCESS
    //         problem:
    //           "cannot access a member of class
    //            com.google.inject.internal.InjectorImpl with modifiers
    //            public"
    //
    //         getInstance was being looked up on injector.getClass(), which
    //         is Guice's internal InjectorImpl. That class is not accessible
    //         from this package, so although the method itself is public,
    //         invoking it threw. The fix is to take the method from the
    //         field's DECLARED type — the public com.google.inject.Injector
    //         interface — which is both accessible and immune to Guice
    //         swapping its implementation class.
    //
    //         Worth remembering generally: a public method on a
    //         package-private implementation class is not callable by
    //         reflection. Always reflect against the interface.
    //
    //         Adds sail_link_error, because sail_plugin_found=false on its
    //         own is a dead end — the reason existed only in the client log,
    //         so diagnosing this at all meant going and finding that log.
    //  2.53 — SCENE SCANS USED THE WRONG WORLD VIEW ABOARD A BOAT.
    //         Sailing puts the boat in its own WorldView. Everything here
    //         went through client.getTopLevelWorldView(), which aboard a
    //         boat still describes the MAINLAND: base 2416,2120 reported
    //         while the player stood at 15555,4423. Every scene scan -
    //         scenery, npcs, ground items - swept the wrong arrays, so a
    //         correctly written filter matched nothing and go_count was 0.
    //
    //         The sailing hooks appeared anyway, which is what made this
    //         confusing: those come from the Sailing plugin's own boat
    //         model rather than from a scene scan, so they were right
    //         while everything scanned was empty.
    //
    //         Now takes the LOCAL PLAYER's world view, falling back to the
    //         top level one. scene_wv_source says which was used, because
    //         "base nowhere near the player" is the symptom and there was
    //         nothing naming the cause.
    //  2.55 — ROOFTOP OBSTACLE REPORTED not_loaded WHILE IN PLAIN SIGHT.
    //         emitRooftopObstacle only ever resolved a box when the
    //         Rooftop plugin's getTileObject() returned a TileObject. The
    //         world-point fallback existed but sat INSIDE that branch, so
    //         an obstacle whose tile object was empty reported
    //         "not_loaded" with no box, even though its location was
    //         known all along in the obstacle's own `locations` field.
    //
    //         Observed at the Pollnivneach course start: rooftop_next_0
    //         not_loaded, count 1, plugin found, while this plugin's own
    //         agility_obstacle_0 (the same Basket, id 14935) reported
    //         state=ok at 5 tiles with a click point. One feed usable,
    //         the other not, for the same object on the same tick.
    //
    //         The fallback now runs whenever the clickbox route produced
    //         no box, not only when a TileObject happened to exist.
    //  2.56 — MARKS OF GRACE NOW NAME THE OBSTACLE THEY SIT BESIDE.
    //         agility_mark_N_near_obstacle_id / _slot / _dist. Answers
    //         "is this mark on my roof or the next one" without the
    //         caller cross-referencing two tables by eye.
    //
    //         Matched against agility_obstacle_* rather than the Rooftop
    //         feed, because those entries carry a real WorldPoint.
    //         The match includes PLANE: chebyshev is 2D, so without it a
    //         mark on a roof binds to whatever is directly below it.
    //
    //         The ID is the durable half. The slot is a distance rank
    //         that reshuffles as the player moves, so it is for reading,
    //         not for identity - a caller wanting a course position maps
    //         the id through its own configured order.
    //  2.57 — THE RUNNING VERSION IS NOW VISIBLE IN THE CLIENT.
    //         plugin_output_version has only ever been readable by
    //         fetching :8081/state, which is no help at all when the
    //         question is "did my rebuild actually pick up the change".
    //         Rebuilding and having no way to tell is how an already
    //         fixed bug gets hunted twice.
    //
    //         The side panel header now reads "GE Visual Aid v2.57" and
    //         the sidebar icon tooltip matches, both from the same
    //         constant, so they cannot drift from what is running.
    //         startUp() also logs it, which is the fastest check when
    //         running from IntelliJ or gradlew where the console is
    //         already in front of you.
    //  2.58 — AGILITY OBSTACLE LIST WAS CAPPED AT 8, NEAREST FIRST.
    //         A rooftop course has nine obstacles or more. The list is
    //         sorted nearest first and then truncated, so the entry
    //         dropped was always the one furthest away - which is the
    //         one being walked towards. Pollnivneach (nine obstacles,
    //         14935..14945) could therefore never publish the whole
    //         course, and the caller stalled for twelve minutes on a
    //         roof reporting "step 8 not in view" with the obstacle in
    //         plain sight in front of it.
    //
    //         Raised to 24. This list comes from the Agility plugin's
    //         own scene map rather than a radius search, so it is
    //         bounded by the course itself and cannot run away.
    //
    //         The general trap, already recorded for entity filters:
    //         one distance-sorted pool plus a cap means near things
    //         crowd out far ones, and "far" is usually "next".
    //  2.59 — MULTI-TILE ROOFTOP OBSTACLES RESOLVE ON ANY OF THEIR TILES.
    //         Verified against the Rooftop Agility Improved source
    //         (v0.6.2) rather than inferred: an Obstacle carries a LIST
    //         of world points and several are multi-tile - Pollnivneach
    //         14944 is {3359,2996},{3360,2996},{3361,2996}. We resolved
    //         locations.get(0) and nothing else, so one corner off
    //         screen or behind scenery reported the whole obstacle
    //         offscreen while two good tiles sat in plain view.
    //
    //         Now: distance and bearing come from the NEAREST location,
    //         and the box search walks every location nearest-first and
    //         takes the first that resolves.
    //
    //         Also confirmed correct against that source, so nobody
    //         re-derives it: getNextObstacles (plural), getCurrentObstacle,
    //         isDoingObstacle, id, obstacles on Course; id, locations,
    //         minLevel, maxLevel, getTileObject on Obstacle; getCourse,
    //         getMarksOfGraces, isStoppingObstacle(int) on CoursesManager,
    //         reached through the private `coursesManager` field. Every
    //         name this plugin reflects exists and matches.
    //
    //         Two behaviours of theirs that look like faults and are not:
    //         getNextObstacles returns EMPTY at the last obstacle of a
    //         lap, which is why rooftop_next_count goes to 0 at the end
    //         of every lap; and with no current obstacle it returns
    //         obstacles[0], so a fresh course correctly reports step 1.
    //         isStoppingObstacle is additionally gated on their own
    //         "mark_of_grace_stop" config option, which defaults on.
    //  2.60 — CONFIG PANEL SPLIT INTO SECTIONS THAT MEAN SOMETHING.
    //         "Scene & Tiles" had become a 24-item dumping ground -
    //         camera geometry, agility, Blast Furnace, runes, bank,
    //         prayers, widgets and NPC aggression in one flat list -
    //         so finding a setting meant reading all of it. Split into
    //         Scene & Tiles (master switch plus geometry), Movement &
    //         Pathing, Skilling, Magic & Runes, Player & Combat, Bank,
    //         and Interface & Widgets.
    //
    //         Every keyName is unchanged, so no saved setting is lost -
    //         only where it appears in the panel moves.
    //
    //         The two agility entries are renamed and rewritten, because
    //         "Agility course order" read as the place to set the course
    //         order when Rooftop Agility Improved makes it unnecessary.
    //         It is now "Agility course order (fallback only)" and says
    //         to leave it empty when that plugin is installed.
    //  2.61 — THE CLICK BOX CAME FROM THE TILE THE PLAYER STOOD ON.
    //         Field report: with the Rooftop feed finally driving, the
    //         script reached step 3 and then clicked the player's own
    //         coordinates over and over.
    //
    //         Cause, read from the Rooftop source: an Obstacle's
    //         `locations` are the tiles the PLAYER STANDS ON to use it,
    //         not the obstacle object's tile - their isNearNextObstacle()
    //         checks player.distanceTo(location) <= 1 to decide the
    //         obstacle has been started. V2.55 started resolving those
    //         into a click box when getTileObject() was empty, and V2.59
    //         made it reliably wrong by choosing the NEAREST of them:
    //         standing on the start tile, the nearest is the ground under
    //         your own feet. Clicking there does nothing, forever.
    //
    //         Box sources are now, in order: the Rooftop plugin's own
    //         TileObject; then the same obstacle ID taken from RuneLite's
    //         Agility plugin list, which is a real scene object with a
    //         real clickbox and is what V2.55 should have reached for.
    //         If neither has it there is NO box and the caller turns or
    //         walks. `locations` is used for distance and bearing only.
    //
    //         New field rooftop_*_box_source: rooftop_object,
    //         agility_plugin or none. A wrong click is now traceable to
    //         which feed produced the target.
    //  2.71 - AN ENTITY SET CAN CARRY ITS OWN WAYPOINTS.
    //         Josh: "can we not move these custom waypoint bundles into
    //         the entity sets. so i dont have a blast furnace entity set
    //         and blast furnace waypoints?"
    //
    //         Right: an activity is one thing, and having its scenery in
    //         a set while its positioning lives in a separately-toggled
    //         bundle means two switches that must agree. Each set slot
    //         gains a Waypoints field, merged into the same parse as the
    //         always-on list and the enabled bundles.
    //
    //         THE BUNDLES ARE UNTOUCHED and still work. This is another
    //         source, not a replacement, so nothing anyone has already
    //         configured moves or breaks. A name defined in both still
    //         resolves first-wins with the collision reported in
    //         waypoint_name_conflicts, exactly as 2.25 established.
    //
    //         Set waypoints are attributed to the SET's name in
    //         wp_<name>_bundle, so the output says where a waypoint came
    //         from without anyone having to remember.
    //  2.70 - THE MERGED FILTERS ARE NEVER CACHED. STALENESS MADE
    //         STRUCTURALLY IMPOSSIBLE.
    //         Josh, having hit 2.68's bug from the other side: "i delete
    //         all of the carried items and it still shows them in the
    //         plugin state."
    //
    //         2.68 fixed the guard by adding the missing inputs to it.
    //         That was the wrong shape of fix: it leaves a rule that has
    //         to keep being obeyed every time a field is added, and the
    //         penalty for forgetting is silent stale output rather than a
    //         failure. Two rounds were lost to it already.
    //
    //         The guard was also nearly worthless. It BUILT its comparison
    //         string by reading every config field, so the reads it was
    //         meant to avoid happened anyway; all it saved was four string
    //         splits and some assignments.
    //
    //         So the merged strings are now assigned UNCONDITIONALLY every
    //         time. The only thing still guarded is the conflict scan and
    //         its log line, which exist purely to avoid logging identical
    //         text every tick -- and if that guard is ever wrong the worst
    //         case is a stale WARNING, never stale data.
    //
    //         esSpecRaw therefore no longer gates output, and the
    //         invalidation in applyPendingConfig is no longer load-bearing.
    //  2.69 - /filter SHOWS WHAT EACH SLOT ACTUALLY HOLDS.
    //         Two rounds were spent guessing whether a filter had been typed
    //         into an entity set's field or the always-on box, because
    //         nothing exposed either. The state output reports what a filter
    //         FOUND; it never reported what the filter WAS.
    //
    //         GET /filter with no parameters now also dumps, per slot, the
    //         name, the on/off flag and all four filter strings exactly as
    //         stored, plus the four MERGED strings the scan really uses.
    //         Comparing "what I typed" with "what is merged" settles in one
    //         command what was previously inference.
    //
    //         Diagnostic only. On /filter, not in the per-tick state, so it
    //         costs nothing in the hot path.
    //
    //         Three fixes to 2.66 that its first real reading exposed:
    //
    //         (1) AN ITEM IN THE INVENTORY WITH THE TAB CLOSED READ AS
    //             not_found. It is not missing, it is not on screen, and
    //             those are different instructions: one says give up, the
    //             other says open the inventory. Now not_visible.
    //
    //         (2) id AND name WERE -1 AND THE LOWERCASED FILTER TEXT unless
    //             a widget happened to be visible, so an equipped item could
    //             not be identified at all. Both are now resolved from
    //             whichever container holds it.
    //
    //         (3) bank_qty=0 MEANT BOTH "the bank holds none" AND "the bank
    //             has never been opened this session". New ib_bank_known
    //             separates them, matching bank_available in the bank family.
    //  2.68 - EDITING A SET'S FILTER TEXT DID NOTHING UNTIL A RESTART.
    //         A bug introduced by 2.65 itself. rebuildEntitySets guards on a
    //         combined "spec" string so nothing re-parses until the config
    //         really changes -- but the spec was built from the always-on
    //         boxes plus the NAMES of the enabled sets, and never the sets'
    //         own filter text. So typing a filter into an active set changed
    //         nothing the guard could see: it returned early and the merged
    //         string kept its previous value indefinitely.
    //
    //         rebuildWaypoints has always appended "<name>=<list>", the
    //         actual content, for precisely this reason. 2.65 deviated from
    //         the model it claimed to copy and this is what that cost.
    //
    //         The spec now carries every slot's name, its on/off state and
    //         all four filter strings. Renaming a DISABLED set is included
    //         too, since that changes entity_sets_available.
    //
    //         Symptom was "carried true (0)" with a correct filter in an
    //         active set -- indistinguishable from a filter matching
    //         nothing, which is the same class of fault 2.67 addressed and
    //         the reason that diagnostic did not catch this one.
    //  2.67 - A CONFIGURED FILTER WITH ITS FAMILY SWITCHED OFF SAYS SO.
    //         Josh had a correct carried-item filter in an active entity
    //         set and got nothing, because the master "Track carried items"
    //         box was unticked. Every reading was individually honest -
    //         ib_enabled=false, ib_count=0 - and the combination still
    //         looked exactly like "the filter matches nothing", which is
    //         the failure this codebase exists to avoid.
    //
    //         New filters_configured_but_off, listing any family that has
    //         filter text but is switched off. Empty is the good case. It
    //         covers all four families, because scenery, npcs and items
    //         have had the same trap since 2.27 and nobody had been bitten
    //         by it yet only because those boxes were ticked years ago.
    //  2.66 - CARRIED ITEMS: CLICK BOXES FOR INVENTORY AND BANK ITEMS.
    //         Josh: "can we add like item ids (for example ice gloves) and it
    //         give screen coordinates for those in my inventory and in the
    //         bank. i know we can look up the inventory using http. but the
    //         bank we dont, but the plugins always manage to outline them."
    //
    //         New ib_<label>_* family, filtered exactly like the scenery and
    //         NPC families (label=, id or exact name, * to discover) and
    //         carried in entity sets alongside them.
    //
    //         PRESENCE AND POSITION ARE DELIBERATELY SEPARATE, and this is
    //         the whole design:
    //           - the COUNTS (ib_*_inv_qty, _bank_qty, _worn) come from the
    //             ItemContainer, so they are right even when the bank is
    //             scrolled elsewhere, on another tab, or shut.
    //           - the CLICK BOX comes from the widget, and is only emitted
    //             when the item is really on screen.
    //         Collapsing the two would produce the two worst readings we
    //         could ship: "you have none" when the bank simply scrolled, and
    //         a box to click when the item is not visible.
    //
    //         A SCROLLED-OUT BANK CHILD KEEPS A RECTANGLE. It is not hidden
    //         and its bounds are not empty -- they are just somewhere else,
    //         possibly over the game world. Clicking that would walk the
    //         player. So a child's bounds must INTERSECT ITS CONTAINER before
    //         the box is emitted; otherwise state is scrolled_out, meaning
    //         "it is definitely there, scroll or search for it". findItemWidget
    //         (the Blast Furnace ItemStep source) now goes through the same
    //         check and inherits the fix.
    //
    //         Bank placeholders are skipped for counting, as 2.63 established,
    //         but reported in ib_*_bank_placeholder as 2.64 established.
    //  2.65 - ENTITY SETS: THE FILTERS STOP OVERWRITING EACH OTHER.
    //         Josh: "this blast furnace scenery names etc, overwrites over
    //         scenery names ive got. for sailing, and coal rocks etc. can we
    //         have a scenery names, item names, npc names etc, global and
    //         maybe a separate tickable box for each plugin".
    //
    //         Scenery / NPC / ground item were ONE GLOBAL STRING EACH, so
    //         configuring one activity destroyed another's setup. Worse, the
    //         /filter endpoint persists to the RuneLite profile, so the loss
    //         survived a restart and looked like the plugin forgetting.
    //
    //         Ten named ENTITY SETS, deliberately on the SAME model as the
    //         waypoint bundles of 2.25 rather than a second, different idea:
    //         each slot has Enabled / Name / Scenery / NPCs / Items, and an
    //         enabled set is MERGED WITH the always-on boxes, never replaces
    //         them. So the always-on boxes keep whatever is genuinely common
    //         and each activity owns its own slot.
    //
    //         Labels are NOT prefixed with the set name, matching 2.25's
    //         reasoning: a consumer's key must not change because a set was
    //         reorganised. Duplicate labels are a real hazard here, because
    //         selectResults() skips a label it has already emitted -- the
    //         second entry is silently DROPPED, matching nothing forever
    //         while looking configured. They are now reported in
    //         entity_set_conflicts. The 24-entry cap in parseEntityFilter is
    //         reported the same way rather than quietly truncating.
    //
    //         GET /filter?entityset=<name> switches one set on and every
    //         other set off, which is what a script wants at startup. An
    //         unknown name CHANGES NOTHING and says so: turning everything
    //         off on a typo would leave the script running blind.
    //         /filter?entityset=none is the explicit way to clear.
    //
    //         New output: entity_sets_available, entity_sets_active,
    //         entity_set_conflicts.
    //  2.64 - A PLACEHOLDER IS EVIDENCE, NOT JUST NOISE.
    //         2.63 stopped counting placeholders, which made every
    //         bank_<name> honest. It also threw the placeholder away
    //         entirely, and that discarded the one thing that can tell
    //         two identical-looking readings apart.
    //
    //         bank_<name>=0 means either "the bank is empty" or "this
    //         watch entry matches nothing" - a typo like Gold_ore
    //         instead of gold ore reports 0 forever. Those are the same
    //         number, and a consumer cannot act on the first without
    //         risking the second. The Skilling Copilot's out-of-ore stop
    //         had to spend a real withdraw click to break the tie: only
    //         an empty bank can produce "count says 0" AND "the withdraw
    //         landed nothing".
    //
    //         But a placeholder CARRIES THE REAL ITEM'S NAME - that is
    //         exactly why it inflated the counts in the first place. So
    //         its presence proves the watch entry names a real item that
    //         the bank has a slot reserved for. New field per entry:
    //
    //             bank_<name>_placeholder=true|false
    //
    //         0 with placeholder=true is an EMPTY BANK, provably.
    //         0 with placeholder=false is a bank that never held it, or
    //         a mistyped entry - still ambiguous, still worth a second
    //         witness.
    //
    //         Id-based watch entries resolve to their item NAME once per
    //         entry, because a placeholder has a DIFFERENT id from the
    //         real item and could never be recognised by id.
    //
    //         Every bank_<name> consumer gains this, not just the Blast
    //         Furnace - the mining and gather bank targets read the same
    //         family of fields.
    //  2.63 - BANK PLACEHOLDERS NO LONGER COUNT AS ONE ITEM.
    //         Field report, and the experiment that settled it: 10 gold
    //         ore read 10, 5 read 5, an EMPTY slot left as a placeholder
    //         read 1, and releasing the placeholder read 0.
    //
    //         A placeholder is a distinct item id carrying the real
    //         item NAME, so the exact-name match counted it, and it
    //         contributed 1. That made "the bank is empty" and "one
    //         left" the same number, which is unusable for any consumer
    //         that wants to act on exhaustion - the Skilling Copilot
    //         out-of-ore stop had to treat 1 as 0 to work at all, and
    //         then could not be used on an item genuinely down to its
    //         last one.
    //
    //         ItemComposition.getPlaceholderTemplateId() is -1 on a real
    //         item and the template id on a placeholder. Verified
    //         present in runelite-api 1.12.35 with javap rather than
    //         taken from the wiki. Placeholders are now skipped, so an
    //         emptied slot reads 0 whether or not the placeholder was
    //         released.
    //
    //         This makes every bank_<name> count honest, not just the
    //         Blast Furnace one - the mining and gather bank targets
    //         read the same field and had the same off-by-one.
    //  2.62 — LAST-RESORT BOX FROM THE OBSTACLE'S OWN TILE.
    //         Field report: standing ONE tile from the Pollnivneach
    //         basket, the caller reported it could not find the obstacle
    //         and span the camera looking for it. Neither clickbox
    //         resolved, and V2.61 had deliberately removed every tile
    //         fallback.
    //
    //         V2.61 was right that `locations` must never produce a
    //         click box - those are the tiles the PLAYER stands on - but
    //         wrong to take this one with it. The Agility plugin entry
    //         carries the OBSTACLE's own WorldPoint, read off a real
    //         scene object. Aiming at the tile an obstacle occupies is
    //         sound; aiming at the tile you are standing on never was.
    //
    //         Box source order is now: rooftop_object, agility_plugin
    //         (clickbox), agility_tile (the object's own tile), none.
    static final String PLUGIN_OUTPUT_VERSION = "2.76";   // package-visible: the panel shows it

    // Refreshed by every GameStateChanged event — lets the .txt report the
    // precise client state (LOGIN_SCREEN, LOGGING_IN, LOADING, LOGGED_IN,
    // CONNECTION_LOST, HOPPING, LOGIN_SCREEN_AUTHENTICATOR, STARTING, UNKNOWN)
    // even when GameTick isn't firing (connection dropped, game world unloaded).
    private volatile GameState lastGameState = GameState.UNKNOWN;

    // Last system / engine / welcome / broadcast chat message, captured by
    // onChatMessage. Useful for the AHK side to detect update countdowns,
    // disconnect notices, welcome banners, etc. without its own scanning.
    private String lastSystemMessage   = "";
    private long   lastSystemMessageMs = 0;

    // Plugin v2.2 — Tracks the most recent in-game player name. After
    // logout, client.getLocalPlayer() returns null and the generic
    // ge_visual_aid.txt would normally be the only file written. We
    // keep this so writeRaw can ALSO write to the player-named file
    // (e.g. Gump12_ge_visual_aid.txt) after logout, preventing the
    // stale-file scenario where consumers monitoring the named file
    // see logged_in=true indefinitely after the player has logged out.
    // v2.15 — No longer used: output collapsed to a single generic file.

    // Plugin v2.4 — ScheduledExecutorService for 1Hz idle writes when not
    // LOGGED_IN. Replaces the v2.3 ClientTick approach which empirically
    // didn't fire while logged out in this RuneLite version. The scheduler
    // is created in startUp() and cancelled cleanly in shutDown() so the
    // plugin doesn't leak threads when disabled.
    private ScheduledExecutorService idleWriteScheduler = null;
    private ScheduledFuture<?>       idleWriteTask      = null;

    // Plugin v2.13 — Local HTTP server serving the latest state string at
    // http://127.0.0.1:<port>/state. latestState is written by writeRaw on
    // the game/scheduler thread and read by the HTTP thread; volatile gives
    // safe publication so the endpoint never serves a half-written buffer.
    private HttpServer      httpServer  = null;
    private volatile String latestState = "status=starting\n";

    // Plugin v2.21 — Scene/tile/camera block. Computed on the client thread
    // in onGameTick and published here; buildUiState() and baseIdleHeader()
    // only read the cached string, so the 1Hz idle writer thread (and the
    // HTTP handler behind it) never touch Perspective or the scene, neither
    // of which is safe off the client thread.
    private volatile String sceneStateBlock = "";
    private volatile long   sceneStateMs    = 0;

    // Parsed form of config.waypointList(), rebuilt only when the raw config
    // string changes so we are not re-parsing text every tick.
    private String             waypointSpecRaw = null;
    private final List<String> wpNames  = new ArrayList<>();
    // {x1, y1, x2, y2, plane, isCluster}. A single tile has x2==x1, y2==y1.
    private final List<int[]>  wpCoords = new ArrayList<>();
    private final List<String> wpBundle = new ArrayList<>();   // owning bundle, "" = always-on list
    private final List<String> wpConflicts = new ArrayList<>();
    private String             wpActiveBundles = "";
    private static final int   WP_BUNDLE_SLOTS = 10;

    // Plugin v2.65 — Entity sets. The always-on filter box for each family
    // merged with every ENABLED set, rebuilt only when the configuration
    // actually changes rather than on every tick.
    private static final int   ES_SLOTS      = 10;
    private static final int   ES_FILTER_CAP = 24;   // parseEntityFilter's own limit
    private String             esSpecRaw     = null;
    private String             esScenery     = "";
    private String             esNpcs        = "";
    private String             esItems       = "";
    private String             esActive      = "";
    private String             esAvailable   = "";
    private String             esConflicts   = "";
    private String             esBoxes       = "";   // v2.66 carried items
    // V2.28: id -> scenery name. Only non-morphing objects are cached; an
    // impostor's name depends on game state and must be resolved each time.
    private final Map<Integer, String> objNameCache = new HashMap<>();

    // V2.33 — Shortest Path bridge. pendingPath is written by the HTTP
    // handler thread and consumed on the client thread in onGameTick.
    private volatile int[]  pendingPath   = null;   // {x, y, plane} or {} to clear
    private int[]           pathTarget    = null;   // last target actually applied
    private Object          spPlugin      = null;   // cached ShortestPathPlugin instance
    private java.lang.reflect.Method spRestart   = null;
    private java.lang.reflect.Field  spPathfield = null;
    private java.lang.reflect.Method spGetPath   = null;
    private java.lang.reflect.Method spPacked    = null;
    private java.lang.reflect.Method wpuPack     = null;
    private java.lang.reflect.Method wpuX        = null;
    private java.lang.reflect.Method wpuY        = null;
    private java.lang.reflect.Method wpuPlane    = null;
    private java.lang.reflect.Method spTransports = null;
    private java.lang.reflect.Method spTrInfo     = null;
    private java.lang.reflect.Method spTrType     = null;
    private boolean         spLinkTried   = false;

    // V2.36 — Remote config writes, queued by the HTTP thread and applied on
    // the client thread. Short name -> {configKey, type} where type is
    // s(tring), i(nt) or b(oolean).
    private final List<String[]> pendingConfig = new ArrayList<>();

    // V2.37 — world hop request, queued by the HTTP thread.
    // V2.38 — player activity tracking, updated on the client thread.
    private int  actAnimation      = -1;
    private long actAnimChangedMs  = 0;
    private int  actLastAnimation  = -1;
    private long actLastNonIdleMs  = 0;
    private WorldPoint actLastLoc  = null;
    private boolean    actMoved    = false;

    private volatile int    pendingHopWorld = -1;

    // 2.72: remote plugin control. Josh: "'rooftop agility improved' never
    // seems to load, i have to turn it off then on. Then it works."
    //
    // A RESTART IS TWO PHASES ON PURPOSE - stopped now, started a few ticks
    // later - because that is what fixes it by hand. A same-tick stop and
    // start gives the plugin no chance to notice the region again, which is
    // the whole reason for doing it.
    //
    // The listing is rebuilt on the CLIENT THREAD each tick and read from
    // the volatile snapshot by the HTTP thread, the same discipline the
    // scene block uses. Plugin metadata is not scene data, but there is no
    // reason to have two rules.
    // 2.73: NOT on the client thread, and not on the HTTP thread either.
    // Starting or stopping a plugin registers and unregisters EventBus
    // subscribers. Doing that from inside onGameTick means mutating the
    // subscriber list while the bus is still dispatching that very tick,
    // which throws - caught, logged, and the plugin never actually
    // cycled. That is why 2.72 queued the restart, said "queued", and
    // nothing happened. RuneLite's own plugin panel uses a separate
    // executor for exactly this reason, so this does too.
    private volatile String pluginActionStatus  = "idle";
    private volatile String pluginListSnapshot  = "";
    private ExecutorService pluginExec = null;
    private Object          hopTarget       = null;   // net.runelite.api.World
    private int             hopTicksLeft    = 0;
    private int             hopLastRequested = -1;
    private String          hopStatus       = "idle";

    // V2.40 — Agility plugin reflection handles.
    private Object  agPlugin        = null;
    private boolean agLinkTried     = false;
    private java.lang.reflect.Method agObstacles = null;
    private java.lang.reflect.Method agMarks     = null;
    private java.lang.reflect.Method agLevel     = null;
    private java.lang.reflect.Method agSession   = null;
    private java.lang.reflect.Method agStick     = null;
    private java.lang.reflect.Method agShortcut  = null;
    private java.util.Set<?>         agTrapIds   = null;

    // V2.43 — Rooftop Agility Improved reflection handles.
    private Object  rtPlugin      = null;
    private Object  rtManager     = null;
    private boolean rtLinkTried   = false;
    private java.lang.reflect.Method rtGetCourse   = null;
    private java.lang.reflect.Method rtGetMarks    = null;
    private java.lang.reflect.Method rtIsStopping  = null;
    private java.lang.reflect.Method rtNextObs     = null;
    private java.lang.reflect.Method rtCurObs      = null;
    private java.lang.reflect.Method rtDoing       = null;
    private java.lang.reflect.Field  rtCourseId    = null;
    private java.lang.reflect.Field  rtCourseObs   = null;
    private java.lang.reflect.Field  rtObsId       = null;
    private java.lang.reflect.Field  rtObsLocs     = null;
    private java.lang.reflect.Field  rtObsMin      = null;
    private java.lang.reflect.Field  rtObsMax      = null;
    private java.lang.reflect.Method rtObsTile     = null;

    // V2.49 — Sailing plugin reflection handles.
    private Object  slTracker     = null;
    private boolean slLinkTried   = false;
    // V2.51 — WHY the link failed. sail_plugin_found=false on its own is a
    // dead end: the reason existed only in the client log, so diagnosing it
    // meant going and finding that log. Reported in the state instead.
    private String  slLinkError   = "";
    private java.lang.reflect.Method slGetBoat  = null;
    private java.lang.reflect.Method slHull     = null;
    private java.lang.reflect.Method slHelm     = null;
    private java.lang.reflect.Method slMast     = null;
    private java.lang.reflect.Method slCargo    = null;
    private java.lang.reflect.Method slHooks    = null;
    private java.lang.reflect.Method slCapacity = null;
    private java.lang.reflect.Method slHullTier = null;
    private java.lang.reflect.Method slSizeCls  = null;
    private java.lang.reflect.Method slHookTier = null;
    // V2.50 — CargoHoldTracker's own private capacity calculations.
    private Object slCargoTracker = null;
    private java.lang.reflect.Method slUsedCap = null;
    private java.lang.reflect.Method slMaxCap  = null;

    // V2.48 — Incoming projectile tracking. Written from the event thread,
    // read on the client thread, so kept volatile and primitive.
    private volatile int  attLastProjectile = -1;
    private volatile long attLastProjectileMs = 0;
    private volatile int  attProjectileCount  = 0;

    // V2.48 — parsed bank watch list.
    private String             bankSpecRaw = null;
    private final List<String> bankNames   = new ArrayList<>();
    private final List<String> bankMatch   = new ArrayList<>();   // lowercase name, or "" if id
    private final List<Integer> bankIds    = new ArrayList<>();   // -1 if name

    // V2.47 — Rune item ids (from ItemID). Elemental and combination runes
    // first, then the rest. Combination runes list the two elements they
    // satisfy so they can be credited to both.
    private static final String[][] RUNE_TYPES = {
            { "air",     "556"   }, { "water",  "555"   },
            { "earth",   "557"   }, { "fire",   "554"   },
            { "mind",    "558"   }, { "body",   "559"   },
            { "cosmic",  "564"   }, { "chaos",  "562"   },
            { "nature",  "561"   }, { "law",    "563"   },
            { "death",   "560"   }, { "astral", "9075"  },
            { "blood",   "565"   }, { "soul",   "566"   },
            { "wrath",   "21880" }, { "sunfire","28929" }
    };
    // { itemId, elementA, elementB }
    private static final String[][] RUNE_COMBOS = {
            { "4695", "mist",  "air",   "water" },
            { "4696", "dust",  "air",   "earth" },
            { "4698", "mud",   "water", "earth" },
            { "4697", "smoke", "air",   "fire"  },
            { "4694", "steam", "water", "fire"  },
            { "4699", "lava",  "earth", "fire"  }
    };
    private static final int RP_TYPE_1 = 29,   RP_TYPE_2 = 1622, RP_TYPE_3 = 1623;
    private static final int RP_QTY_1  = 1624, RP_QTY_2  = 1625, RP_QTY_3  = 1626;

    // V2.46 — Blast Furnace varbits (from VarbitID). {name, varbit} pairs,
    // ores first then bars, matching the order the game lists them.
    private static final String[][] BF_CONTENTS = {
            { "copper_ore",      "959"   }, { "tin_ore",         "950"   },
            { "iron_ore",        "951"   }, { "coal",            "949"   },
            { "mithril_ore",     "952"   }, { "adamantite_ore",  "953"   },
            { "runite_ore",      "954"   }, { "silver_ore",      "956"   },
            { "gold_ore",        "955"   }, { "lead_ore",        "18167" },
            { "nickel_ore",      "18168" },
            { "bronze_bar",      "941"   }, { "iron_bar",        "942"   },
            { "steel_bar",       "943"   }, { "mithril_bar",     "944"   },
            { "adamantite_bar",  "945"   }, { "runite_bar",      "946"   },
            { "silver_bar",      "948"   }, { "gold_bar",        "947"   },
            { "lead_bar",        "18169" }, { "cupronickel_bar", "18170" }
    };
    private static final int BF_VB_COFFER      = 5357;
    private static final int BF_VB_BARS_HOT    = 936;
    private static final int BF_VB_COAL_NEEDED = 940;
    private static final int BF_VB_TEMPERATURE = 937;
    private static final int BF_VB_BROKEN_PIPE = 938;
    private static final int BF_VB_FUEL_LOW    = 939;
    // RuneLite's coffer overlay uses this rate, so our countdown matches it.
    private static final float BF_COST_PER_HOUR = 72000.0f;

    // V2.45 — Easy Blast Furnace reflection handles.
    private Object  bfPlugin      = null;
    private Object  bfHandler     = null;
    private Object  bfObjects     = null;
    private boolean bfLinkTried   = false;
    private java.lang.reflect.Method bfGetSteps   = null;
    private java.lang.reflect.Method bfGetMethod  = null;
    private java.lang.reflect.Method bfTooltip    = null;
    private java.lang.reflect.Method bfObjGet     = null;
    private java.lang.reflect.Method bfEnabled    = null;
    private java.lang.reflect.Method bfItemIds    = null;
    private java.lang.reflect.Method bfObjectId   = null;
    private java.lang.reflect.Method bfWidgetId   = null;
    private java.lang.reflect.Method bfWorldPoint = null;

    // V2.43 — parsed widget list.
    private String            wgSpecRaw = null;
    private final List<String> wgNames  = new ArrayList<>();
    private final List<int[]>  wgIds    = new ArrayList<>();   // {group, child, index}

    // V2.41 — ordered course and where we have got to.
    private String            agCourseRaw   = null;
    private final List<int[]> agCourse      = new ArrayList<>();   // {x, y, plane}
    private int               agProgress    = 0;
    private volatile int      pendingAgStep = Integer.MIN_VALUE;

    // V2.37 — Runite Rocks reflection handles.
    private Object spRunite        = null;
    private boolean runiteLinkTried = false;
    private java.lang.reflect.Method rrWorldMap   = null;
    private java.lang.reflect.Method rrTrkWorld   = null;
    private java.lang.reflect.Method rrTrkRocks   = null;
    private java.lang.reflect.Method rrRockAvail  = null;
    private java.lang.reflect.Method rrRockRespawn= null;
    private java.lang.reflect.Method rrRockSeen   = null;
    private java.lang.reflect.Method rrRockAcc    = null;
    private java.lang.reflect.Method rrRockRock   = null;
    private java.lang.reflect.Method rrRockName   = null;
    private java.lang.reflect.Method rrRockLoc    = null;
    private java.lang.reflect.Method rrRockPoint  = null;
    private static final String[][] REMOTE_KEYS = {
            { "items",       "groundItemFilter",       "s" },
            { "npcs",        "npcFilter",              "s" },
            { "scenery",     "gameObjectFilter",       "s" },
            { "waypoints",   "waypointList",           "s" },
            { "radius",      "objectSearchRadius",     "i" },
            { "max",         "objectMaxResults",       "i" },
            { "tolerance",   "objectAnchorTolerance",  "i" },
            { "itemson",     "groundItemsEnabled",     "b" },
            { "npcson",      "npcTrackingEnabled",     "b" },
            { "sceneryon",   "gameObjectsEnabled",     "b" },
            { "carried",     "itemBoxFilter",          "s" },   // v2.66
            { "carriedon",   "itemBoxesEnabled",       "b" },
            { "waypointson", "waypointsEnabled",       "b" },
            { "hoveron",     "hoverTileEnabled",       "b" },
            { "cameraon",    "cameraStateEnabled",     "b" },
            { "canvason",    "canvasGeometryEnabled",  "b" },
            { "loadon",      "loadingLinesEnabled",    "b" },
            { "moveon",      "movementFlagsEnabled",   "b" },
            { "pathon",      "pathTrackingEnabled",    "b" },
            { "aggroon",     "aggroTimerEnabled",      "b" },
            { "sceneon",     "sceneTrackingEnabled",   "b" }
    };
    private static final int   WP_MAX_CLUSTER_TILES = 400;

    // Plugin v2.21 — NPC aggression tracking. anchorA is the older of the two
    // tiles the server remembers, anchorB the newer. aggroKnown stays false
    // until we have seen a movement large enough to guarantee both anchors
    // were re-seated under the player (teleport / dungeon / loading zone),
    // because before that the real anchors are unknowable client-side.
    private static final int  AGGRO_SAFE_RADIUS = 10;
    private static final long AGGRO_DURATION_MS = 600_000L;   // 10 minutes
    private WorldPoint aggroAnchorA = null;
    private WorldPoint aggroAnchorB = null;
    private WorldPoint aggroLastLoc = null;
    private long       aggroStartMs = 0;
    private boolean    aggroKnown   = false;

    // Plugin v2.22 — Set once an AggressionTimer infobox has been seen this
    // login. RuneLite culls that infobox the moment it expires, so without
    // this flag a missing infobox is ambiguous between "timer ran out" and
    // "the NPC Aggression Timer plugin is not enabled".
    private boolean    aggroSawRlTimer = false;

    // Plugin v2.22 — Emitted when the whole scene block is switched off, so
    // consumers still get an unambiguous answer instead of missing keys.
    private static final String SCENE_DISABLED_BLOCK = "scene_enabled=false\n";

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Provides
    GEVisualAidConfig provideConfig(ConfigManager cm)
    {
        return cm.getConfig(GEVisualAidConfig.class);
    }

    // -----------------------------------------------------------------------
    // Start / Stop
    // -----------------------------------------------------------------------
    @Override
    protected void startUp()
    {
        log.info("GEVisualAid v{} starting up", PLUGIN_OUTPUT_VERSION);
        for (int i = 0; i < 8; i++)  slots[i]          = new SlotState();
        for (int i = 0; i < 28; i++) inventorySlots[i]  = new InventorySlot();
        session.load();
        bondTracker.load();
        overlayManager.add(overlay);
        linkToCopilot();
        linkToApm();
        startHttpServer();

        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0, 200, 255));
        g.fillOval(1, 5, 14, 7);
        g.setColor(new Color(0, 80, 140));
        g.fillOval(5, 6, 6, 5);
        g.setColor(Color.WHITE);
        g.fillOval(7, 7, 2, 2);
        g.dispose();

        navButton = NavigationButton.builder()
                .tooltip("GE Visual Aid v" + PLUGIN_OUTPUT_VERSION)
                .icon(icon)
                .priority(10)
                .panel(panel)
                .build();

        clientToolbar.addNavigation(navButton);
        panel.setOnReset(() -> { session.reset(); refreshSessionPanel(); });
        panel.setOnResetBonds(() ->
        {
            bondTracker.reset();
            panel.updateBonds(0, 0, 0, bondTracker);
        });
        // Populate bond panel with persisted values on startup
        panel.updateBonds(bondTracker.getBondCount(),
                bondTracker.getBondTotalGp(),
                bondTracker.getBondLastGp(),
                bondTracker);
        // Seed lastGameState so the first .txt write reflects the current
        // client state even before any GameStateChanged event fires.
        try { lastGameState = client.getGameState(); } catch (Throwable t) { /* keep UNKNOWN */ }

        // Plugin v2.4 — Schedule a 1Hz idle-write task that runs on a
        // dedicated thread, independent of any RuneLite event loop. While
        // LOGGED_IN it returns immediately (GameTick already handles
        // in-game writes). When not LOGGED_IN it writes the idle snapshot
        // so the .txt timestamp keeps ticking, preventing AHK consumers
        // from flipping their staleness gates during long off-game windows
        // (logout, login screen, disconnect, hopping, authenticator).
        try
        {
            idleWriteScheduler = Executors.newSingleThreadScheduledExecutor(r ->
            {
                Thread t = new Thread(r, "GEVisualAid-IdleWriter");
                t.setDaemon(true);
                return t;
            });
            idleWriteTask = idleWriteScheduler.scheduleAtFixedRate(() ->
            {
                try
                {
                    GameState gs = lastGameState;
                    if (gs == GameState.LOGGED_IN || gs == GameState.LOADING)
                    {
                        return;  // GameTick handles in-game writes
                    }
                    writeLoggedOut();
                }
                catch (Throwable t)
                {
                    log.warn("GEVisualAid idle-write task error: {}", t.getMessage());
                }
            }, 1000, 1000, TimeUnit.MILLISECONDS);
            log.info("GEVisualAid v2.4 idle-write scheduler started (1Hz)");
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid could not start idle-write scheduler: {}", t.getMessage());
        }

        writeIdle();
    }

    @Override
    protected void shutDown()
    {
        // Plugin v2.4 — Stop the idle-write scheduler cleanly so the
        // daemon thread doesn't outlive the plugin being disabled.
        try
        {
            if (idleWriteTask != null)
            {
                idleWriteTask.cancel(false);
                idleWriteTask = null;
            }
            if (idleWriteScheduler != null)
            {
                idleWriteScheduler.shutdown();
                idleWriteScheduler = null;
            }
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid idle-write scheduler shutdown error: {}", t.getMessage());
        }

        // Plugin v2.13 — Stop the HTTP server cleanly.
        stopHttpServer();

        overlayManager.remove(overlay);
        clientToolbar.removeNavigation(navButton);
        overlay.clearHighlight();
        session.save();
        bondTracker.save();
        writeIdle();
        suggestionManager            = null;
        accountStatusManager         = null;
        suggestionPreferencesManager = null;
        apmPlugin                    = null;
        profitCalculator             = null;
    }

    // -----------------------------------------------------------------------
    // Game state change
    // -----------------------------------------------------------------------
    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState gs = event.getGameState();
        lastGameState = gs;

        // Any non-LOGGED_IN, non-LOADING state means we're not playing.
        // Write a fresh idle/logged-out file IMMEDIATELY so the AHK side
        // sees the new game_state without waiting for the next GameTick
        // (GameTick stops firing when the connection drops, so without
        // this nudge the .txt would stay stale).
        // LOADING is excluded — it's a brief mid-login / world-swap state
        // and treating it as logged-out would cause flicker. lastGameState
        // is still updated so the next tick reports the LOADING value.
        if (gs != GameState.LOGGED_IN && gs != GameState.LOADING)
        {
            overlay.clearHighlight();
            // V2.21: drop the cached scene geometry before writing, so the
            // payload never carries screen pixels from before the logout.
            // Aggression anchors are unknowable after a login, so clear them.
            aggroReset();
            try
            {
                sceneStateBlock = config.sceneTrackingEnabled()
                        ? buildSceneBlock(false) : SCENE_DISABLED_BLOCK;
            }
            catch (Throwable ignored) { sceneStateBlock = SCENE_DISABLED_BLOCK; }
            sceneStateMs    = System.currentTimeMillis();
            writeLoggedOut();
        }
    }

    // -----------------------------------------------------------------------
    // System chat capture — feeds last_system_message in the .txt output
    // so the AHK side can detect update countdowns, disconnect notices,
    // welcome banners, ban/mute warnings, etc. without scanning the chat
    // box visually. Filter to system-y message types only (no player chat).
    // -----------------------------------------------------------------------
    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        ChatMessageType type = event.getType();
        if (type == ChatMessageType.GAMEMESSAGE
                || type == ChatMessageType.ENGINE
                || type == ChatMessageType.WELCOME
                || type == ChatMessageType.BROADCAST
                || type == ChatMessageType.LOGINLOGOUTNOTIFICATION)
        {
            String msg = event.getMessage();
            if (msg != null && !msg.isEmpty())
            {
                // Strip newlines so it stays on a single .txt line, and
                // strip RuneScape <col=...> markup for clean consumption.
                String clean = msg.replaceAll("<[^>]+>", "")
                        .replace('\n', ' ')
                        .replace('\r', ' ');
                if (clean.length() > 400) clean = clean.substring(0, 400);
                lastSystemMessage   = clean;
                lastSystemMessageMs = System.currentTimeMillis();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Item container tracking — inventory (93), bank (95), equipment (94)
    // -----------------------------------------------------------------------
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        switch (event.getContainerId())
        {
            case 93: updateInventory(event.getItemContainer()); break;
            case 95: updateBank(event.getItemContainer());      break;
            case 94: updateEquipment(event.getItemContainer()); break;
        }
    }

    private void updateInventory(ItemContainer container)
    {
        if (container == null) return;
        Item[] items = container.getItems();
        long totalValue = 0;
        for (int i = 0; i < 28; i++)
        {
            InventorySlot slot = inventorySlots[i];
            if (i >= items.length || items[i].getId() <= 0)
            {
                slot.setItemId(-1);
                slot.setItemName("");
                slot.setQuantity(0);
                slot.setValueEach(0);
                continue;
            }
            Item item  = items[i];
            int  id    = item.getId();
            int  qty   = item.getQuantity();
            int  price = itemManager.getItemPrice(id);
            String name;
            try { name = itemManager.getItemComposition(id).getName(); }
            catch (Exception e) { name = "Unknown"; }
            slot.setItemId(id);
            slot.setItemName(name);
            slot.setQuantity(qty);
            slot.setValueEach(price);
            totalValue += (long) price * qty;
        }
        inventoryValueGp = totalValue;
    }

    private void updateBank(ItemContainer container)
    {
        if (container == null) return;
        long totalValue = 0;
        for (Item item : container.getItems())
        {
            if (item.getId() <= 0) continue;
            totalValue += (long) itemManager.getItemPrice(item.getId()) * item.getQuantity();
        }
        bankValueGp = totalValue;
    }

    private void updateEquipment(ItemContainer container)
    {
        if (container == null) return;
        long totalValue = 0;
        for (Item item : container.getItems())
        {
            if (item.getId() <= 0) continue;
            totalValue += (long) itemManager.getItemPrice(item.getId()) * item.getQuantity();
        }
        equipmentValueGp = totalValue;
    }

    // -----------------------------------------------------------------------
    // GrandExchangeOfferChanged
    // -----------------------------------------------------------------------
    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
    {
        int                slotIndex  = event.getSlot();
        GrandExchangeOffer offer      = event.getOffer();
        SlotState          s          = slots[slotIndex];
        String             prevStatus = s.getStatus();

        s.setQuantityDone(offer.getQuantitySold());
        s.setQuantityTotal(offer.getTotalQuantity());
        s.setPriceEach(offer.getPrice());
        s.setLastChangedMs(System.currentTimeMillis());

        GrandExchangeOfferState state = offer.getState();

        if (offer.getItemId() > 0)
        {
            s.setItemId(offer.getItemId());
            try { s.setItemName(itemManager.getItemComposition(offer.getItemId()).getName()); }
            catch (Exception e) { s.setItemName("Unknown"); }
        }

        switch (state)
        {
            case BUYING: case BOUGHT:   s.setOfferType("buy");  break;
            case SELLING: case SOLD:    s.setOfferType("sell"); break;
            default: break;
        }

        switch (state)
        {
            case EMPTY:
                s.setStatus("empty");
                s.setItemId(-1); s.setItemName("");
                s.setQuantityDone(0); s.setQuantityTotal(0);
                s.setCopilotProfitGp(0);
                break;
            case BUYING:
                s.setStatus("buying");
                if (config.profitTrackingEnabled())
                    session.recordBuy(s.getItemId(), offer.getPrice());
                break;
            case BOUGHT:
                s.setStatus("complete");
                handleOfferComplete(slotIndex, s, prevStatus);
                break;
            case SELLING:
                s.setStatus("selling");
                // Re-apply any profit we already saw from the tooltip for this item
                s.setCopilotProfitGp(getCopilotProfit(s.getItemName()));
                break;
            case SOLD:
                s.setStatus("complete");
                handleOfferComplete(slotIndex, s, prevStatus);
                break;
            case CANCELLED_BUY: case CANCELLED_SELL:
            s.setStatus("cancelled");
            s.setCopilotProfitGp(0);
            break;
        }

        panel.updateSlot(slotIndex, s.getStatus(), s.getItemName(),
                s.getQuantityDone(), s.getQuantityTotal());
        checkGEFull();
    }


    /**
     * Ask Copilot's ProfitCalculator for the profit-per-item for a given item name.
     * Returns 0 if Copilot is not loaded or the item is not tracked.
     */
    private long getCopilotProfit(String itemName)
    {
        if (profitCalculator == null || itemName == null || itemName.isEmpty()) return 0;
        try
        {
            return (long) invoke(profitCalculator, "getProfitByItemName", itemName);
        }
        catch (Exception e)
        {
            log.debug("GEVisualAid: getProfitByItemName({}) failed: {}", itemName, e.getMessage());
            return 0;
        }
    }

    private void handleOfferComplete(int slotIndex, SlotState s, String prevStatus)
    {
        if ("complete".equals(prevStatus)) return;
        long profit = 0;
        if (config.profitTrackingEnabled() && "sell".equals(s.getOfferType()))
            profit = session.recordSell(s.getItemId(), s.getPriceEach(), s.getQuantityDone());

        // Track bond purchases (buy offers only; item ID checked inside)
        if ("buy".equals(s.getOfferType()))
        {
            bondTracker.onOfferComplete(s);
            panel.updateBonds(bondTracker.getBondCount(),
                    bondTracker.getBondTotalGp(),
                    bondTracker.getBondLastGp(),
                    bondTracker);
        }

        refreshSessionPanel();
        sound.playOfferComplete();
        discord.sendOfferComplete(s.getItemName(), s.getOfferType(),
                s.getQuantityDone(), s.getPriceEach(), profit);

        if (config.pushoverEnabled() && config.pushoverNotifyOfferComplete())
        {
            String msg = s.getOfferType() + " " + s.getItemName()
                    + " x" + s.getQuantityDone()
                    + " @ " + String.format("%,d", s.getPriceEach()) + "gp"
                    + (profit != 0 ? " | Profit: " + session.formatGp(profit) : "");
            pushover.send("Offer Complete", msg, false);
        }
    }

    private void checkGEFull()
    {
        if (!config.geFullAlertEnabled()) return;
        boolean full = true;
        for (SlotState s : slots)
            if ("empty".equals(s.getStatus())) { full = false; break; }
        if (full)
        {
            discord.sendGEFull();
            if (config.pushoverEnabled())
                pushover.send("GE Full", "All 8 slots are occupied.", false);
        }
    }

    private void refreshSessionPanel()
    {
        if (config.sessionSummaryEnabled())
            panel.updateSession(session.getTotalProfit(), session.getTotalFlips(),
                    session.getBestFlip(), session);
    }

    // -----------------------------------------------------------------------
    // Game tick
    // -----------------------------------------------------------------------
    @Subscribe
    public void onProjectileMoved(ProjectileMoved event)
    {
        // V2.48: only projectiles aimed at the local player matter here. A
        // projectile fires once per attack but moves every tick, so the
        // start cycle is used to count distinct attacks rather than frames.
        try
        {
            Projectile p = event.getProjectile();
            if (p == null || client.getLocalPlayer() == null) return;
            if (p.getInteracting() != client.getLocalPlayer()) return;
            if (p.getRemainingCycles() <= 0) return;

            int id = p.getId();
            long now = System.currentTimeMillis();
            if (id != attLastProjectile || now - attLastProjectileMs > 1200)
                attProjectileCount++;
            attLastProjectile   = id;
            attLastProjectileMs = now;
        }
        catch (Throwable ignored) { }
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        // V2.21: FIRST, and outside the copilot gate — the scene/camera block
        // must keep refreshing even when Flipping Copilot is not linked, and
        // it must be computed here because this is the client thread.
        updateSceneState();

        if (suggestionManager == null)
        {
            linkToCopilot();
            if (suggestionManager == null)
            {
                panel.setDisconnected();
                overlay.clearHighlight();
                writeError("copilot_not_found");
                return;
            }
        }

        checkStuckOffers();

        try { resolveAndWrite(); }
        catch (Exception e)
        {
            // V2.19: full stack trace (was e.getMessage() only), and ALWAYS
            // still write — a throw here used to leave the .txt frozen, which
            // the AHK script reads as authoritative and hangs on.
            log.warn("GEVisualAid resolve error", e);
            writeResolveFailureState(e);
        }
    }

    // V2.19: Last-resort state write. Called when resolveAndWrite() throws, so
    // the state file NEVER freezes — a frozen file is indistinguishable from a
    // hung plugin to the AHK script, which then sits inert until the account
    // disconnects. Each section is rebuilt independently and guarded, so one
    // broken section cannot suppress the rest.
    private void writeResolveFailureState(Exception cause)
    {
        String ui = "", slotStr = "", invStr = "";
        try { ui      = buildUiState();        } catch (Exception ignored) { }
        try { slotStr = buildSlotState();      } catch (Exception ignored) { }
        try { invStr  = buildInventoryState(); } catch (Exception ignored) { }

        try
        {
            overlay.clearHighlight();
            panel.updateStatus("idle", "", false, false);
        }
        catch (Exception ignored) { }

        String msg = (cause == null)
                ? "unknown"
                : cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage());
        msg = msg.replace('\n', ' ').replace('\r', ' ');

        try { writeRaw(ui + slotStr + invStr + idleFields() + "resolve_error=" + msg + "\n"); }
        catch (Exception ignored) { }
    }

    private void checkStuckOffers()
    {
        if (!config.offerStuckEnabled()) return;
        long now       = System.currentTimeMillis();
        long threshold = config.offerStuckMinutes() * 60_000L;
        for (int i = 0; i < 8; i++)
        {
            SlotState s = slots[i];
            if (!"buying".equals(s.getStatus()) && !"selling".equals(s.getStatus())) continue;
            if (s.getQuantityDone() == 0) continue;
            if (s.getQuantityDone() >= s.getQuantityTotal()) continue;
            if (now - s.getLastChangedMs() > threshold)
            {
                discord.sendOfferStuck(s.getItemName(), i);
                if (config.pushoverEnabled())
                    pushover.send("Offer Stuck",
                            "Slot " + (i + 1) + " (" + s.getItemName() + ") may be stuck.", false);
                s.setLastChangedMs(now);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Core resolver
    // -----------------------------------------------------------------------
    private void resolveAndWrite() throws Exception
    {
        String  ui      = buildUiState();
        String  slotStr = buildSlotState();
        String  invStr  = buildInventoryState();
        boolean geOpen  = isVisible(465, 7) || isVisible(465, 26) || isVisible(465, 4);

        if (!geOpen)
        {
            overlay.clearHighlight();
            panel.updateStatus("idle", "", false, false);
            writeRaw(ui + slotStr + invStr + idleFields());
            return;
        }

        Object suggestion = invoke(suggestionManager, "getSuggestion");
        Object error      = invoke(suggestionManager, "getSuggestionError");

        if (suggestion == null || error != null || isWait(suggestion))
        {
            overlay.clearHighlight();
            checkIdleAlert("");
            panel.updateStatus("idle", "", false, false);
            writeRaw(ui + slotStr + invStr + idleFields());
            return;
        }

        String  itemName    = getStringSafe(suggestion, "getName");
        int     itemId      = getIntSafe(suggestion, "getItemId");
        String  offerType   = getStringSafe(suggestion, "offerType");
        int     targetPrice = getIntSafe(suggestion, "getPrice");
        int     targetQty   = getIntSafe(suggestion, "getQuantity");
        boolean dumpAlert   = getBoolSafe(suggestion, "isDumpAlert");

        String suggestionKey = itemName + offerType + targetPrice + targetQty + dumpAlert;
        if (!suggestionKey.equals(lastSuggestionKey))
        {
            lastSuggestionKey      = suggestionKey;
            lastSuggestionChangeMs = System.currentTimeMillis();
        }

        String sugMeta = "item_name=" + itemName + "\n"
                + "item_id=" + itemId + "\n"
                + "offer_type=" + offerType + "\n"
                + "target_price=" + targetPrice + "\n"
                + "target_quantity=" + targetQty + "\n"
                + "is_dump_alert=" + dumpAlert + "\n";

        boolean slotOpen = getOpenSlot() != -1;
        if (!slotOpen)
            resolveHomeScreen(suggestion, ui, slotStr, invStr, sugMeta, itemName, dumpAlert);
        else
            resolveOfferScreen(suggestion, ui, slotStr, invStr, sugMeta, itemName, dumpAlert);
    }

    private void resolveHomeScreen(Object sug, String ui, String slotStr, String invStr,
                                   String sugMeta, String itemName,
                                   boolean dumpAlert) throws Exception
    {
        Object  accountStatus = invoke(accountStatusManager, "getAccountStatus");
        Widget  confirmWidget = getOfferChild(58);
        boolean setupOpen     = confirmWidget != null && !confirmWidget.isHidden();
        boolean collectNeeded = (boolean) invoke(accountStatus, "isCollectNeeded", sug, setupOpen);

        if (collectNeeded)
        {
            Widget topBar = client.getWidget(465, 6);
            if (topBar != null)
            {
                Widget btn = topBar.getChild(2);
                if (btn != null)
                {
                    emit("master_collect", "collect", btn,
                            new Rectangle(2, 1, 81, 18), null, null,
                            ui, slotStr, invStr, sugMeta, itemName, dumpAlert,
                            new String[]{"master_collect"});
                    discord.sendCollectNeeded();
                    if (config.pushoverEnabled())
                        pushover.send("Collect Needed",
                                "Items ready to collect from the GE.", false);
                    return;
                }
            }
        }
        else if (isAbort(sug))
        {
            int    boxId = (int) invoke(sug, "getBoxId");
            Widget slot  = client.getWidget(465, 7 + boxId);
            if (slot != null)
            {
                emit("abort_slot_" + (boxId + 1), "abort", slot, fullBounds(slot),
                        null, null,
                        ui, slotStr, invStr, sugMeta, itemName, dumpAlert,
                        new String[]{"abort_slot_" + (boxId + 1)});
                return;
            }
        }
        else if (isModify(sug))
        {
            int    boxId = (int) invoke(sug, "getBoxId");
            Widget slot  = client.getWidget(465, 7 + boxId);
            if (slot != null && !slot.isHidden())
            {
                emit("modify_slot_" + (boxId + 1), "modify", slot, fullBounds(slot),
                        null, null,
                        ui, slotStr, invStr, sugMeta, itemName, dumpAlert,
                        new String[]{"modify_slot_" + (boxId + 1)});
                return;
            }
        }
        else if (isBuy(sug))
        {
            int slotId = (int) invoke(accountStatus, "findEmptySlot");
            if (slotId != -1)
            {
                Widget slotWidget = client.getWidget(465, 7 + slotId);
                if (slotWidget != null)
                {
                    Widget buyBtn = slotWidget.getChild(0);
                    if (buyBtn != null && !buyBtn.isHidden())
                    {
                        emit("buy_slot_" + (slotId + 1), "normal", buyBtn,
                                new Rectangle(0, 0, 45, 44), null, null,
                                ui, slotStr, invStr, sugMeta, itemName, dumpAlert,
                                new String[]{"buy_slot_" + (slotId + 1)});
                        return;
                    }
                }
            }
        }
        else if (isSell(sug))
        {
            int    itemId = (int) invoke(sug, "getItemId");
            Widget inv    = client.getWidget(467, 0);
            if (inv == null) inv = client.getWidget(149, 0);
            if (inv != null)
            {
                Widget item = findInventoryItem(inv, itemId);
                if (item != null && !item.isHidden())
                {
                    emit("inventory_slot_" + (item.getIndex() + 1), "normal", item,
                            new Rectangle(0, 0, 34, 32), null, null,
                            ui, slotStr, invStr, sugMeta, itemName, dumpAlert,
                            new String[]{"inventory_slot_" + (item.getIndex() + 1)});
                    return;
                }
            }
        }

        overlay.clearHighlight();
        panel.updateStatus("idle", itemName, false, false);
        writeRaw(ui + slotStr + invStr
                + "action_required=false\naction=idle\ncopilot_status=idle\n"
                + "pending_actions=\n"
                + "x1=0\ny1=0\nx2=0\ny2=0\n"
                + "action2=\nx1_2=0\ny1_2=0\nx2_2=0\ny2_2=0\n"
                + sugMeta);
    }

    private void resolveOfferScreen(Object sug, String ui, String slotStr, String invStr,
                                    String sugMeta, String itemName,
                                    boolean dumpAlert) throws Exception
    {
        String  offerType     = client.getVarbitValue(4397) == 1 ? "sell" : "buy";
        int     currentItemId = client.getVarpValue(1151);
        int     offerPrice    = client.getVarbitValue(4398);
        int     offerQuantity = client.getVarbitValue(4396);
        boolean searchOpen    = client.getWidget(10616884) != null
                && !client.getWidget(10616884).isHidden();

        // V2.19: were raw invoke() — a single renamed getter threw, killed
        // resolveAndWrite(), and froze the state file entirely. These are the
        // same getters resolveAndWrite() already reads via the safe wrappers.
        String  sugType   = getStringSafe(sug, "offerType");
        int     sugItemId = getIntSafe(sug, "getItemId");
        int     sugPrice  = getIntSafe(sug, "getPrice");
        int     sugQty    = getIntSafe(sug, "getQuantity");

        boolean typeMatches = offerType.equals(sugType);
        boolean itemMatches = currentItemId == sugItemId;

        List<String> pending = new ArrayList<>();

        if (typeMatches && itemMatches)
        {
            if (offerPrice != sugPrice)  pending.add("set_price");
            if (offerQuantity != sugQty) pending.add("set_qty");
            if (pending.isEmpty())       pending.add("confirm");
        }
        else if (typeMatches && currentItemId == -1 && searchOpen)
        {
            pending.add("search_item");
        }
        else
        {
            pending.add("back");
        }

        // Resolve widget for second action if two pending
        Widget secondWidget = null;
        Rectangle secondRel = null;
        if (pending.size() >= 2)
        {
            // If both set_price and set_qty are pending,
            // primary = set_price, secondary = set_qty
            Widget inv = client.getWidget(467, 0);
            if (inv == null) inv = client.getWidget(149, 0);
            boolean useAll = inv != null && inventoryCount(inv, sugItemId) == sugQty;
            secondWidget = useAll ? getOfferChild(50) : getOfferChild(51);
            secondRel    = new Rectangle(1, 6, 33, 23);
        }

        String[] pendingArr = pending.toArray(new String[0]);

        if (typeMatches && itemMatches && offerPrice == sugPrice && offerQuantity == sugQty)
        {
            Widget confirm = getOfferChild(58);
            if (confirm != null)
            {
                emit("confirm", "normal", confirm, new Rectangle(1, 1, 150, 38),
                        null, null,
                        ui, slotStr, invStr, sugMeta, itemName, dumpAlert, pendingArr);
                return;
            }
        }

        if (typeMatches && itemMatches)
        {
            if (offerPrice != sugPrice)
            {
                Widget priceBtn = getOfferChild(54);
                if (priceBtn != null)
                {
                    emit("set_price", "normal", priceBtn, new Rectangle(1, 6, 33, 23),
                            secondWidget, secondRel,
                            ui, slotStr, invStr, sugMeta, itemName, dumpAlert, pendingArr);
                    return;
                }
            }
            if (offerQuantity != sugQty)
            {
                Widget  inv    = client.getWidget(467, 0);
                if (inv == null) inv = client.getWidget(149, 0);
                boolean useAll = inv != null && inventoryCount(inv, sugItemId) == sugQty;
                Widget  qtyBtn = useAll ? getOfferChild(50) : getOfferChild(51);
                if (qtyBtn != null)
                {
                    String actionName = useAll ? "qty_all" : "set_qty";
                    emit(actionName, "normal", qtyBtn, new Rectangle(1, 6, 33, 23),
                            null, null,
                            ui, slotStr, invStr, sugMeta, itemName, dumpAlert, pendingArr);
                    return;
                }
            }
        }
        else if (typeMatches && currentItemId == -1 && searchOpen)
        {
            Widget results = client.getWidget(10616884);
            if (results != null)
            {
                String name = (String) invoke(sug, "getName");
                for (Widget w : results.getDynamicChildren())
                {
                    if (w.getName().equals("<col=ff9040>" + name + "</col>"))
                    {
                        emit("search_item", "normal", w, fullBounds(w),
                                null, null,
                                ui, slotStr, invStr, sugMeta, itemName, dumpAlert, pendingArr);
                        return;
                    }
                }
                Widget first = results.getChild(3);
                if (first != null && first.getItemId() == sugItemId)
                {
                    emit("search_item", "normal", first, fullBounds(first),
                            null, null,
                            ui, slotStr, invStr, sugMeta, itemName, dumpAlert, pendingArr);
                    return;
                }
            }
        }
        else
        {
            Widget back = client.getWidget(465, 4);
            if (back != null)
            {
                emit("back", "normal", back, fullBounds(back),
                        null, null,
                        ui, slotStr, invStr, sugMeta, itemName, dumpAlert, pendingArr);
                return;
            }
        }

        overlay.clearHighlight();
        panel.updateStatus("idle", itemName, false, false);
        writeRaw(ui + slotStr + invStr
                + "action_required=false\naction=idle\ncopilot_status=idle\n"
                + "pending_actions=\n"
                + "x1=0\ny1=0\nx2=0\ny2=0\n"
                + "action2=\nx1_2=0\ny1_2=0\nx2_2=0\ny2_2=0\n"
                + sugMeta);
    }

    // -----------------------------------------------------------------------
    // Idle alert
    // -----------------------------------------------------------------------
    private void checkIdleAlert(String itemName)
    {
        if (!config.idleAlertEnabled()) return;
        if (actionSinceMs == 0) return;
        long elapsed = System.currentTimeMillis() - actionSinceMs;
        if (elapsed > config.idleAlertSeconds() * 1000L)
        {
            discord.sendIdleAlert(itemName);
            if (config.pushoverEnabled())
                pushover.send("Action Pending",
                        "Still waiting for action" +
                                (itemName.isEmpty() ? "." : " on " + itemName + "."), false);
            actionSinceMs = 0;
        }
    }

    // -----------------------------------------------------------------------
    // Emit
    // -----------------------------------------------------------------------
    private void emit(String action, String actionType,
                      Widget w, Rectangle rel,
                      Widget w2, Rectangle rel2,
                      String ui, String slotStr, String invStr, String sugMeta,
                      String itemName, boolean dumpAlert, String[] pendingActions)
    {
        Rectangle b = w.getBounds();
        if (b == null)
        {
            overlay.clearHighlight();
            writeRaw(ui + slotStr + invStr
                    + "action_required=false\naction=idle\ncopilot_status=idle\n"
                    + "pending_actions=\n"
                    + "x1=0\ny1=0\nx2=0\ny2=0\n"
                    + "action2=\nx1_2=0\ny1_2=0\nx2_2=0\ny2_2=0\n"
                    + sugMeta);
            return;
        }

        overlay.setHighlight(
                new Rectangle(b.x + rel.x, b.y + rel.y, rel.width, rel.height),
                dumpAlert ? "dump" : actionType
        );

        java.awt.Canvas            canvas = client.getCanvas();
        java.awt.Point             loc;
        try { loc = canvas.getLocationOnScreen(); }
        catch (java.awt.IllegalComponentStateException e) { loc = new java.awt.Point(0, 0); }
        java.awt.GraphicsConfiguration gc = canvas.getGraphicsConfiguration();
        double sx = gc != null ? gc.getDefaultTransform().getScaleX() : 1.0;
        double sy = gc != null ? gc.getDefaultTransform().getScaleY() : 1.0;

        int x1 = (int)((loc.x + b.x + rel.x)             * sx);
        int y1 = (int)((loc.y + b.y + rel.y)             * sy);
        int x2 = (int)((loc.x + b.x + rel.x + rel.width) * sx);
        int y2 = (int)((loc.y + b.y + rel.y + rel.height)* sy);

        // Second action coordinates
        int    x1_2 = 0, y1_2 = 0, x2_2 = 0, y2_2 = 0;
        String action2 = "";
        if (w2 != null && rel2 != null && pendingActions.length >= 2)
        {
            Rectangle b2 = w2.getBounds();
            if (b2 != null)
            {
                x1_2   = (int)((loc.x + b2.x + rel2.x)              * sx);
                y1_2   = (int)((loc.y + b2.y + rel2.y)              * sy);
                x2_2   = (int)((loc.x + b2.x + rel2.x + rel2.width) * sx);
                y2_2   = (int)((loc.y + b2.y + rel2.y + rel2.height)* sy);
                action2 = pendingActions[1];
            }
        }

        if (!action.equals(lastAction) || dumpAlert != lastDumpAlert
                || !itemName.equals(lastItemName))
        {
            lastAction    = action;
            lastItemName  = itemName;
            lastDumpAlert = dumpAlert;
            actionSinceMs = System.currentTimeMillis();

            if (dumpAlert)
            {
                sound.playDumpAlert();
                if (config.discordNotifyDumpAlert())
                    discord.sendActionRequired(action, itemName, true);
                if (config.pushoverEnabled() && config.pushoverNotifyDumpAlert())
                    pushover.send("DUMP ALERT", itemName, true);
            }
            else
            {
                sound.playAction();
                discord.sendActionRequired(action, itemName, false);
                if (config.pushoverEnabled() && config.pushoverNotifyActionRequired())
                    pushover.send("Action Required",
                            action.replace("_", " ") +
                                    (itemName.isEmpty() ? "" : " — " + itemName), false);
            }
        }

        panel.updateStatus(action, itemName, true, dumpAlert);
        if (config.sessionSummaryEnabled())
            panel.updateSession(session.getTotalProfit(), session.getTotalFlips(),
                    session.getBestFlip(), session);

        if (config.fileOutputEnabled())
        {
            String pendingStr = String.join(",", pendingActions);
            writeRaw(ui + slotStr + invStr
                    + "action_required=true\n"
                    + "action=" + action + "\n"
                    + "pending_actions=" + pendingStr + "\n"
                    + "copilot_status=active\n"
                    + sugMeta
                    + "x1=" + x1 + "\n"
                    + "y1=" + y1 + "\n"
                    + "x2=" + x2 + "\n"
                    + "y2=" + y2 + "\n"
                    + "action2=" + action2 + "\n"
                    + "x1_2=" + x1_2 + "\n"
                    + "y1_2=" + y1_2 + "\n"
                    + "x2_2=" + x2_2 + "\n"
                    + "y2_2=" + y2_2 + "\n");
        }
    }

    // -----------------------------------------------------------------------
    // UI state builder
    // -----------------------------------------------------------------------
    private boolean isVisible(int id, int child)
    {
        Widget w = client.getWidget(id, child);
        return w != null && !w.isHidden();
    }

    // -----------------------------------------------------------------------
    // Login / connection helpers — feed the new login-state fields in
    // buildUiState() and baseIdleHeader(). All defensive: any client API
    // call that could throw during teardown/startup is wrapped.
    // -----------------------------------------------------------------------

    // Welcome / "Click here to play" overlay — shown after authentication
    // but before the player dismisses it to enter the world.
    //
    // EMPIRICAL FINDING (Gump12 test, 2026-05-27): during the entire
    // "Click here to play" window, GameState reports LOGGING_IN (NOT
    // LOGGED_IN as initially assumed). RuneLite holds LOGGING_IN from
    // successful auth through to the moment the player clicks Play.
    // This is API-driven and rock solid — no widget ID guessing needed.
    //
    // Widget 378 scan kept as a backup for the rare case where Welcome
    // content lingers into LOGGED_IN state on certain login flows.
    private boolean isWelcomeScreenVisible()
    {
        // Primary signal: API-driven, no widget IDs involved.
        if (lastGameState == GameState.LOGGING_IN) return true;

        // Backup: widget visibility scan. Only counts when we have a
        // local player (post-load), to avoid false-positiving on the
        // credentials screen which also lives in widget group 378.
        try
        {
            if (lastGameState == GameState.LOGGED_IN
                    && client.getLocalPlayer() != null)
            {
                for (int child = 0; child <= 50; child++)
                {
                    Widget w = client.getWidget(378, child);
                    if (w != null && !w.isHidden()) return true;
                }
            }
        }
        catch (Throwable t) { /* ignore */ }
        return false;
    }

    // Plugin v2.13 — Reported game_state. When the welcome ("Click here to
    // play") screen is up, isWelcomeScreenVisible() returns true even though
    // RuneLite still reports LOGGED_IN. In that case we override the emitted
    // game_state to WELCOME_SCREEN so a single field tells the AHK side it is
    // NOT yet in-world. welcome_screen_visible is still emitted separately and
    // unchanged. Once clicked through, this falls back to the real state name
    // (LOGGED_IN, etc.).
    private String gameStateString()
    {
        if (isWelcomeScreenVisible()) return "WELCOME_SCREEN";
        return lastGameState.name();
    }

    // Diagnostic: list visible widgets in a small set of candidate groups
    // (the ones that host login / welcome / world-select content). When
    // we're not LOGGED_IN, the AHK side can read this to learn exactly
    // which widget IDs fired for a given screen — useful for adding new
    // specific detectors over time without further empirical rounds.
    // Returns "" once we have a local player to avoid in-game overhead.
    private String getVisibleLoginWidgets()
    {
        if (lastGameState == GameState.LOGGED_IN
                && client.getLocalPlayer() != null) return "";
        StringBuilder sb = new StringBuilder();
        int[] groups = {378, 24, 25, 69, 162, 164, 165, 549, 596};
        try
        {
            for (int g : groups)
            {
                for (int c = 0; c <= 30; c++)
                {
                    Widget w = client.getWidget(g, c);
                    if (w != null && !w.isHidden())
                    {
                        if (sb.length() > 0) sb.append(",");
                        sb.append(g).append(".").append(c);
                        if (sb.length() > 300) return sb.toString();
                    }
                }
            }
        }
        catch (Throwable t) { /* ignore */ }
        return sb.toString();
    }

    // World Selector — shown when clicking "World Select" on the login
    // screen or logout panel. Widget group 69 in modern OSRS.
    private boolean isWorldSelectVisible()
    {
        try { if (isVisible(69, 0)) return true; }
        catch (Throwable t) { /* ignore */ }
        return false;
    }

    // Best-effort scrape of any text shown on the login screen widget
    // (group 378). Captures things like:
    //   "Your client needs to be updated. Please reload this page."
    //   "Connection lost. Please wait — attempting to re-establish."
    //   "Login server offline."
    //   "Error connecting to server."
    // Returns "" when LOGGED_IN so it doesn't leak into in-game writes.
    private String getLoginScreenMessage()
    {
        if (lastGameState == GameState.LOGGED_IN) return "";
        StringBuilder sb = new StringBuilder();
        try
        {
            // Widget group 378 hosts both the Welcome and Login screens.
            // Iterate children 0..30 collecting any visible text.
            for (int child = 0; child <= 30; child++)
            {
                Widget w = client.getWidget(378, child);
                if (w == null || w.isHidden()) continue;
                String t = w.getText();
                if (t == null || t.isEmpty()) continue;
                // Strip <col=...></col> markup and collapse whitespace
                t = t.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
                if (t.isEmpty()) continue;
                if (sb.length() > 0) sb.append(" | ");
                sb.append(t);
                if (sb.length() > 400) break;
            }
        }
        catch (Throwable t) { /* ignore */ }
        return sb.toString().replace('\n', ' ').replace('\r', ' ');
    }

    // Heuristic: did the server kick us back with "client needs update" or
    // a revision-mismatch message? Driven by login screen text + the last
    // system chat message (which sometimes contains the update notice
    // before the connection drops).
    private boolean isUpdateRequired()
    {
        String haystack = (getLoginScreenMessage() + " " + lastSystemMessage).toLowerCase();
        if (haystack.contains("update") && (haystack.contains("client") || haystack.contains("reload"))) return true;
        if (haystack.contains("revision") && haystack.contains("change")) return true;
        if (haystack.contains("game has been updated")) return true;
        return false;
    }

    // Safe wrappers — used in baseIdleHeader where the client may be in a
    // transitional state (CONNECTION_LOST, STARTING) and bare API calls
    // could in principle return junk or throw. Cheap defensive layer.
    private int safeLoginIndex() { try { return client.getLoginIndex(); } catch (Throwable t) { return -1; } }
    private int safeWorld()      { try { return client.getWorld(); }      catch (Throwable t) { return 0; } }
    private int safeRevision()   { try { return client.getRevision(); }   catch (Throwable t) { return 0; } }

    // Plugin v2.14 — Human-readable interpretation of getLoginIndex().
    // The OSRS login screen reuses a small set of state indices. The
    // notice/message pages (server-update notice, "client out of date",
    // etc.) are painted directly on the login canvas and are NOT widgets,
    // so their text cannot be read via the widget tree — getLoginIndex()
    // is the only reliable plugin-side discriminator. Confirmed mappings:
    //   2  = NORMAL        ordinary logged-out login state (RuneLite API
    //                       doc calls 2 the username/password form; this
    //                       Jagex-launcher client never shows that form)
    //   10 = NORMAL        ordinary logged-out state on this client
    //                       (revision 238) — covers BOTH the "Play Now /
    //                       <account>" login screen AND the welcome screen,
    //                       so it is screen-agnostic; use game_state /
    //                       welcome_screen_visible to tell those two apart.
    //                       Confirmed from Gump dumps 2026-06-16.
    //   4  = AUTHENTICATOR  6-digit authenticator form (RuneLite API doc;
    //                       not seen on this launcher setup)
    //   9  = CLIENT_UPDATE  "RuneScape has been updated / please restart
    //                       RuneLite" notice box — appears AFTER clicking OK
    //                       on a 24 server-update box when the client is now
    //                       out of date. Requires a RuneLite restart, NOT a
    //                       retry. Confirmed from Gump12 dump 2026-06-17.
    //   24 = SERVER_MESSAGE centre-screen notice box + OK
    //                       (e.g. "the game servers are currently being
    //                       updated") — confirmed from Gump12 dump 2026-06-16
    // Any other value is emitted as INDEX_<n> so a newly-encountered
    // screen can be identified the first time it appears, without another
    // code change. When you next hit the "RuneScape has been updated /
    // restart RuneLite" screen, read login_index off the .txt and tell me
    // the number so I can give it a named label here.
    private String loginStateLabel()
    {
        if (lastGameState == GameState.LOGGED_IN) return "IN_GAME";
        int idx = safeLoginIndex();
        switch (idx)
        {
            case -1: return "UNKNOWN";
            case 2:  return "NORMAL";
            case 10: return "NORMAL";
            case 4:  return "AUTHENTICATOR";
            case 9:  return "CLIENT_UPDATE";
            case 24: return "SERVER_MESSAGE";
            default: return "INDEX_" + idx;
        }
    }

    // True when the login screen is showing a notice/message box (a popup
    // that is NOT the normal logged-out / authenticator / world-select
    // flow). This is the "it is not just a plain login screen" flag the
    // AHK side can gate on. Confirmed notice indices: 24 = server-update
    // box (recoverable: click OK + retry), 9 = client-out-of-date box
    // (needs a RuneLite restart). They use DIFFERENT indices, so
    // login_state_label (SERVER_MESSAGE vs CLIENT_UPDATE) distinguishes the
    // recoverable case from the restart case on its own — no pixel check
    // needed for that distinction anymore.
    private boolean isLoginNoticeVisible()
    {
        if (lastGameState == GameState.LOGGED_IN) return false;
        int idx = safeLoginIndex();
        return idx == 9 || idx == 24;
    }

    // Age (seconds) of the last captured system chat message, or -1 if none.
    private long getLastSystemMessageAgeSeconds()
    {
        if (lastSystemMessageMs == 0) return -1;
        return (System.currentTimeMillis() - lastSystemMessageMs) / 1000L;
    }

    private long getPlayerIdleSeconds()
    {
        long mouseTicks = client.getMouseIdleTicks();
        long keyTicks   = client.getKeyboardIdleTicks();

        if (mouseTicks < lastMouseTicks || keyTicks < lastKeyTicks)
        {
            lastInputMs = System.currentTimeMillis();
        }

        lastMouseTicks = mouseTicks;
        lastKeyTicks   = keyTicks;

        return (System.currentTimeMillis() - lastInputMs) / 1000L;
    }

    private long getCopilotIdleSeconds()
    {
        if (lastSuggestionChangeMs == 0) return 0;
        return (System.currentTimeMillis() - lastSuggestionChangeMs) / 1000;
    }

    private long getGeSlotsTotalValue()
    {
        long v = 0;
        for (SlotState s : slots)
            if (!"empty".equals(s.getStatus()) && !"cancelled".equals(s.getStatus()))
                v += (long) s.getPriceEach() * s.getQuantityTotal();
        return v;
    }

    private int getServerRestartSeconds()
    {
        // Widget 229,1 contains the server restart countdown text
        Widget w = client.getWidget(229, 1);
        if (w == null || w.isHidden()) return -1;
        try
        {
            String text = w.getText();
            if (text == null) return -1;
            // Text format is typically "System update in: X minutes, Y seconds"
            text = text.replaceAll("[^0-9:]", " ").trim();
            String[] parts = text.trim().split("\\s+");
            if (parts.length >= 2)
            {
                int mins = Integer.parseInt(parts[0]);
                int secs = Integer.parseInt(parts[1]);
                return mins * 60 + secs;
            }
            else if (parts.length == 1)
            {
                return Integer.parseInt(parts[0]);
            }
        }
        catch (Exception e) { /* ignore parse errors */ }
        return -1;
    }

    private String buildUiState()
    {
        boolean loggedIn      = client.getGameState() == GameState.LOGGED_IN;
        boolean geMainPage    = isVisible(465, 7);
        // V2.18: was isVisible(465, 26) only. That widget child index moved and
        // the check silently went false while the offer screen was open, which
        // froze action= on the last suggestion and hung the AHK script. Varbit
        // 4439 = the GE slot currently being configured (0 = none) — the same
        // signal getOpenSlot() already uses, and stable across widget changes.
        // Widget check kept as a secondary so nothing regresses if it returns.
        int     geOpenSlotVb  = client.getVarbitValue(4439);
        boolean geOfferScreen = geOpenSlotVb > 0 || isVisible(465, 26);
        boolean geHistoryOpen = isVisible(383, 0);
        boolean bankOpen      = isVisible(12, 0);
        boolean bankPinOpen   = isVisible(213, 0);

        boolean invStandalone = isVisible(149, 0) && !geMainPage && !bankOpen;
        boolean invGE         = isVisible(467, 0);
        boolean inventoryOpen = invStandalone || invGE;

        boolean equipOpen    = isVisible(387, 0);
        boolean prayerOpen   = isVisible(541, 0);
        boolean magicOpen    = isVisible(218, 0);
        boolean combatOpen   = isVisible(593, 0);
        boolean skillsOpen   = isVisible(320, 0);
        boolean questOpen    = isVisible(399, 0);
        boolean friendsOpen  = isVisible(429, 0);
        boolean clanOpen     = isVisible(707, 0);
        boolean logoutOpen   = isVisible(182, 0);
        boolean settingsOpen = isVisible(116, 0);

        String geOfferType = "none";
        int    geSlotOpen  = 0;
        if (geOfferScreen)
        {
            geOfferType = client.getVarbitValue(4397) == 1 ? "sell" : "buy";
            // V2.18: varbit 4439 is already 1-8 (0 = none). Identical value to
            // the old getOpenSlot()+1, without the second varbit read.
            geSlotOpen  = geOpenSlotVb;
            if (geSlotOpen < 0) geSlotOpen = 0;
        }

        int    worldX = 0, worldY = 0, plane = 0;
        String playerName = "";
        if (client.getLocalPlayer() != null)
        {
            if (client.getLocalPlayer().getName() != null)
                playerName = client.getLocalPlayer().getName();
            WorldPoint wp = client.getLocalPlayer().getWorldLocation();
            worldX = wp.getX();
            worldY = wp.getY();
            plane  = wp.getPlane();
        }

        // Camera
        int cameraYaw   = client.getCameraYaw();   // 0-2047, 0=north, increases clockwise
        int cameraPitch = client.getCameraPitch();  // vertical angle
        int cameraZoom = client.getScale();

        // Convert yaw to compass degrees (0=north, 90=east etc)
        int compassDegrees = (int)((cameraYaw / 2048.0) * 360);

        long geValue    = getGeSlotsTotalValue();
        long totalWealth = inventoryValueGp + bankValueGp + equipmentValueGp + geValue;
        long playerIdle      = getPlayerIdleSeconds();
        long secsUntilLogout = Math.max(0, LOGOUT_THRESHOLD_SECONDS - playerIdle);
        long copilotIdle = getCopilotIdleSeconds();
        int  restartSecs = getServerRestartSeconds();

        return "timestamp=" + LocalDateTime.now().format(TS_FORMAT) + "\n"
                + "plugin_output_version=" + PLUGIN_OUTPUT_VERSION + "\n"
                + "account=" + playerName + "\n"
                + "logged_in=" + loggedIn + "\n"
                + "game_state=" + gameStateString() + "\n"
                + "login_index=" + safeLoginIndex() + "\n"
                + "login_state_label=" + loginStateLabel() + "\n"
                + "login_notice_visible=" + isLoginNoticeVisible() + "\n"
                + "current_world=" + safeWorld() + "\n"
                + "client_revision=" + safeRevision() + "\n"
                + "welcome_screen_visible=" + isWelcomeScreenVisible() + "\n"
                + "world_select_open=" + isWorldSelectVisible() + "\n"
                + "connection_lost=" + (lastGameState == GameState.CONNECTION_LOST) + "\n"
                + "update_required=" + isUpdateRequired() + "\n"
                + "login_screen_message=" + getLoginScreenMessage() + "\n"
                + "visible_login_widgets=" + getVisibleLoginWidgets() + "\n"
                + "last_system_message=" + lastSystemMessage + "\n"
                + "last_system_message_age_seconds=" + getLastSystemMessageAgeSeconds() + "\n"
                + "world_x=" + worldX + "\n"
                + "world_y=" + worldY + "\n"
                + "plane=" + plane + "\n"
                + "camera_yaw=" + cameraYaw + "\n"
                + "camera_pitch=" + cameraPitch + "\n"
                + "camera_zoom=" + cameraZoom + "\n"
                + "compass_degrees=" + compassDegrees + "\n"
                + "player_idle_seconds=" + playerIdle + "\n"
                + "logout_in_seconds=" + secsUntilLogout + "\n"
                + "copilot_idle_seconds=" + copilotIdle + "\n"
                + "server_restart_seconds=" + restartSecs + "\n"
                + "ge_main_page=" + geMainPage + "\n"
                + "ge_offer_screen=" + geOfferScreen + "\n"
                + "ge_offer_type=" + geOfferType + "\n"
                + "ge_slot_open=" + geSlotOpen + "\n"
                + "ge_history_open=" + geHistoryOpen + "\n"
                + "bank_open=" + bankOpen + "\n"
                + "bank_pin_open=" + bankPinOpen + "\n"
                + "inventory_open=" + inventoryOpen + "\n"
                + "equipment_open=" + equipOpen + "\n"
                + "prayer_open=" + prayerOpen + "\n"
                + "magic_open=" + magicOpen + "\n"
                + "combat_options_open=" + combatOpen + "\n"
                + "skills_open=" + skillsOpen + "\n"
                + "quest_list_open=" + questOpen + "\n"
                + "friends_open=" + friendsOpen + "\n"
                + "clan_open=" + clanOpen + "\n"
                + "logout_open=" + logoutOpen + "\n"
                + "settings_open=" + settingsOpen + "\n"
                + "inventory_value_gp=" + inventoryValueGp + "\n"
                + "bank_value_gp=" + bankValueGp + "\n"
                + "equipment_value_gp=" + equipmentValueGp + "\n"
                + "ge_slots_value_gp=" + geValue + "\n"
                + "total_wealth_gp=" + totalWealth + "\n"
                + buildPortfolioState()
                + buildClerkState()
                + buildCopilotPreferencesState()
                + buildApmAndMembershipState()
                + buildSceneState();
    }

    private String buildSlotState()
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++)
        {
            SlotState s = slots[i];
            sb.append("slot_").append(i+1).append("_status=").append(s.getStatus()).append("\n");
            sb.append("slot_").append(i+1).append("_item=").append(s.getItemName()).append("\n");
            sb.append("slot_").append(i+1).append("_type=").append(s.getOfferType()).append("\n");
            sb.append("slot_").append(i+1).append("_done=").append(s.getQuantityDone()).append("\n");
            sb.append("slot_").append(i+1).append("_total=").append(s.getQuantityTotal()).append("\n");
            sb.append("slot_").append(i+1).append("_price=").append(s.getPriceEach()).append("\n");
            // Copilot estimated profit per item (0 = unknown / buying slot)
            long cp = getCopilotProfit(s.getItemName());
            sb.append("slot_").append(i+1).append("_copilot_profit=").append(cp).append("\n");
            sb.append("slot_").append(i+1).append("_copilot_profit_fmt=")
                    .append(cp != 0 ? session.formatGp(cp) : "").append("\n");
        }
        return sb.toString();
    }

    private String buildInventoryState()
    {
        StringBuilder sb = new StringBuilder();
        int itemCount = 0, freeSlots = 0;
        for (int i = 0; i < 28; i++)
        {
            InventorySlot s = inventorySlots[i];
            sb.append("inv_slot_").append(i+1).append("_id=").append(s.getItemId()).append("\n");
            sb.append("inv_slot_").append(i+1).append("_item=").append(s.getItemName()).append("\n");
            sb.append("inv_slot_").append(i+1).append("_qty=").append(s.getQuantity()).append("\n");
            sb.append("inv_slot_").append(i+1).append("_value=").append(s.getValueEach()).append("\n");
            if (s.getItemId() > 0) itemCount++;
            else freeSlots++;
        }
        sb.append("inv_total_items=").append(itemCount).append("\n");
        sb.append("inv_free_slots=").append(freeSlots).append("\n");
        sb.append(buildUntrackedInventoryState());  // v2.6 — alert for items not in FC portfolio
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // File output helpers
    // -----------------------------------------------------------------------
    private String idleFields()
    {
        String copilotStatus = suggestionManager == null ? "not_found" : "idle";
        return "action_required=false\n"
                + "action=idle\n"
                + "pending_actions=\n"
                + "copilot_status=" + copilotStatus + "\n"
                + "item_name=\nitem_id=\noffer_type=\ntarget_price=\ntarget_quantity=\nis_dump_alert=false\n"
                + "x1=0\ny1=0\nx2=0\ny2=0\n"
                + "action2=\nx1_2=0\ny1_2=0\nx2_2=0\ny2_2=0\n";
    }

    private String baseIdleHeader()
    {
        long geValue     = getGeSlotsTotalValue();
        long totalWealth = inventoryValueGp + bankValueGp + equipmentValueGp + geValue;
        return "timestamp=" + LocalDateTime.now().format(TS_FORMAT) + "\n"
                + "plugin_output_version=" + PLUGIN_OUTPUT_VERSION + "\n"
                + "account=\n"
                + "logged_in=false\n"
                + "game_state=" + gameStateString() + "\n"
                + "login_index=" + safeLoginIndex() + "\n"
                + "login_state_label=" + loginStateLabel() + "\n"
                + "login_notice_visible=" + isLoginNoticeVisible() + "\n"
                + "current_world=" + safeWorld() + "\n"
                + "client_revision=" + safeRevision() + "\n"
                + "welcome_screen_visible=" + isWelcomeScreenVisible() + "\n"
                + "world_select_open=" + isWorldSelectVisible() + "\n"
                + "connection_lost=" + (lastGameState == GameState.CONNECTION_LOST) + "\n"
                + "update_required=" + isUpdateRequired() + "\n"
                + "login_screen_message=" + getLoginScreenMessage() + "\n"
                + "visible_login_widgets=" + getVisibleLoginWidgets() + "\n"
                + "last_system_message=" + lastSystemMessage + "\n"
                + "last_system_message_age_seconds=" + getLastSystemMessageAgeSeconds() + "\n"
                + "world_x=0\nworld_y=0\nplane=0\n"
                + "camera_yaw=0\ncamera_pitch=0\ncamera_zoom=0\ncompass_degrees=0\n"
                + "player_idle_seconds=0\ncopilot_idle_seconds=0\n"
                + "server_restart_seconds=-1\n"
                + "ge_main_page=false\nge_offer_screen=false\nge_offer_type=none\nge_slot_open=0\n"
                + "ge_history_open=false\nbank_open=false\nbank_pin_open=false\n"
                + "inventory_open=false\nequipment_open=false\nprayer_open=false\n"
                + "magic_open=false\ncombat_options_open=false\nskills_open=false\n"
                + "quest_list_open=false\nfriends_open=false\nclan_open=false\n"
                + "logout_open=false\nsettings_open=false\n"
                + "inventory_value_gp=0\nbank_value_gp=0\nequipment_value_gp=0\n"
                + "ge_slots_value_gp=0\ntotal_wealth_gp=0\n"
                + buildPortfolioState()
                + buildClerkState()
                + buildCopilotPreferencesState()
                + buildApmAndMembershipState()
                + buildSceneState();
    }

    private String buildClerkState()
    {
        java.awt.Canvas canvas = client.getCanvas();
        if (canvas == null)
            return "clerk_x1=0\nclerk_y1=0\nclerk_x2=0\nclerk_y2=0\n";

        java.awt.Point loc;
        try { loc = canvas.getLocationOnScreen(); }
        catch (java.awt.IllegalComponentStateException e)
        { return "clerk_x1=0\nclerk_y1=0\nclerk_x2=0\nclerk_y2=0\n"; }
        java.awt.GraphicsConfiguration gc = canvas.getGraphicsConfiguration();
        double sx = gc != null ? gc.getDefaultTransform().getScaleX() : 1.0;
        double sy = gc != null ? gc.getDefaultTransform().getScaleY() : 1.0;

        for (NPC npc : client.getTopLevelWorldView().npcs())
        {
            if (npc == null || npc.getId() != 2148) continue;

            Shape hull = npc.getConvexHull();
            if (hull == null) continue;

            Rectangle b = hull.getBounds();
            int x1 = (int) ((loc.x + b.x)              * sx);
            int y1 = (int) ((loc.y + b.y)              * sy);
            int x2 = (int) ((loc.x + b.x + b.width)   * sx);
            int y2 = (int) ((loc.y + b.y + b.height)  * sy);

            return "clerk_x1=" + x1 + "\n"
                    + "clerk_y1=" + y1 + "\n"
                    + "clerk_x2=" + x2 + "\n"
                    + "clerk_y2=" + y2 + "\n";
        }

        return "clerk_x1=0\nclerk_y1=0\nclerk_x2=0\nclerk_y2=0\n";
    }

    private String buildCopilotPreferencesState()
    {
        if (suggestionPreferencesManager == null)
        {
            return "copilot_sell_only=false\n"
                    + "copilot_risk_level=\n"
                    + "copilot_timeframe_minutes=\n"
                    + "copilot_reserved_slots=\n"
                    + "copilot_min_predicted_profit=\n"
                    + "copilot_dump_mode=false\n"
                    + "copilot_dump_min_profit=\n"
                    + "copilot_f2p_only=false\n"
                    + "copilot_blocked_items_count=\n"
                    + "copilot_profile=\n";
        }
        try
        {
            // sellOnlyMode is a volatile field — read directly
            boolean sellOnly      = (boolean) getFieldValue(suggestionPreferencesManager, "sellOnlyMode");

            // all other prefs are via public synchronized methods
            Object  riskLevel     = invoke(suggestionPreferencesManager, "getRiskLevel");
            int     timeframe     = (int)     invoke(suggestionPreferencesManager, "getTimeframe");
            Integer reservedSlots = (Integer) invoke(suggestionPreferencesManager, "getReservedSlots");
            Integer minProfit     = (Integer) invoke(suggestionPreferencesManager, "getMinPredictedProfit");
            boolean dumpMode      = (boolean) invoke(suggestionPreferencesManager, "isReceiveDumpSuggestions");
            Integer dumpMinProfit = (Integer) invoke(suggestionPreferencesManager, "getDumpMinPredictedProfit");
            boolean f2pOnly       = (boolean) invoke(suggestionPreferencesManager, "isF2pOnlyMode");
            Object  blockedItems  = invoke(suggestionPreferencesManager, "blockedItems");
            String  profile       = (String)  invoke(suggestionPreferencesManager, "getCurrentProfile");

            int blockedCount = blockedItems instanceof java.util.List
                    ? ((java.util.List<?>) blockedItems).size() : 0;

            // RiskLevel enum — use toApiValue() to get "low"/"medium"/"high"
            // matching what Copilot itself sends to its API
            String riskStr = "medium";
            if (riskLevel != null)
            {
                try { riskStr = (String) riskLevel.getClass().getMethod("toApiValue").invoke(riskLevel); }
                catch (Exception ex) { riskStr = riskLevel.getClass().getMethod("name").invoke(riskLevel).toString().toLowerCase(); }
            }

            return "copilot_sell_only=" + sellOnly + "\n"
                    + "copilot_risk_level=" + riskStr + "\n"
                    + "copilot_timeframe_minutes=" + timeframe + "\n"
                    + "copilot_reserved_slots=" + (reservedSlots != null ? reservedSlots : "auto") + "\n"
                    + "copilot_min_predicted_profit=" + (minProfit != null ? minProfit : "auto") + "\n"
                    + "copilot_dump_mode=" + dumpMode + "\n"
                    + "copilot_dump_min_profit=" + (dumpMinProfit != null ? dumpMinProfit : "auto") + "\n"
                    + "copilot_f2p_only=" + f2pOnly + "\n"
                    + "copilot_blocked_items_count=" + blockedCount + "\n"
                    + "copilot_profile=" + (profile != null ? profile : "") + "\n";
        }
        catch (Exception e)
        {
            log.warn("GEVisualAid: copilot prefs read error: {}", e.getMessage());
            return "copilot_sell_only=\n"
                    + "copilot_risk_level=\n"
                    + "copilot_timeframe_minutes=\n"
                    + "copilot_reserved_slots=\n"
                    + "copilot_min_predicted_profit=\n"
                    + "copilot_dump_mode=\n"
                    + "copilot_dump_min_profit=\n"
                    + "copilot_f2p_only=\n"
                    + "copilot_blocked_items_count=\n"
                    + "copilot_profile=\n";
        }
    }

    private String buildApmAndMembershipState()
    {
        // APM
        int[] apm        = getApmValues();
        int   apmLastMin = apm[0];
        int   apmSession = apm[1];

        // Membership — VarPlayer 1780, whole days only (Jagex server-side granularity)
        int    membershipDays   = client.getVarpValue(1780);
        String membershipExpiry;
        if (membershipDays > 0)
            membershipExpiry = LocalDate.now().plusDays(membershipDays).toString(); // YYYY-MM-DD
        else
            membershipExpiry = "none";

        return "apm_last_minute=" + apmLastMin + "\n"
                + "apm_session_avg=" + apmSession + "\n"
                + "membership_days_remaining=" + membershipDays + "\n"
                + "membership_expiry_date=" + membershipExpiry + "\n";
    }

    // -----------------------------------------------------------------------
    // Scene / tile / camera state (Plugin v2.21)
    //
    // Everything in here reads the scene, Perspective or the canvas, none of
    // which is safe off the client thread. updateSceneState() is therefore
    // called from onGameTick() only, and publishes a finished string that
    // buildSceneState() hands out to whichever thread is writing.
    // -----------------------------------------------------------------------
    private void updateSceneState()
    {
        try
        {
            // V2.22: master gate. Off means off — no client reads at all,
            // not merely suppressed output.
            if (!config.sceneTrackingEnabled())
            {
                sceneStateBlock = SCENE_DISABLED_BLOCK;
                sceneStateMs    = System.currentTimeMillis();
                return;
            }

            boolean online = client.getGameState() == GameState.LOGGED_IN;
            // V2.33: apply any queued Shortest Path request here, on the
            // client thread, never in the HTTP handler.
            applyPendingConfig();
            if (online) applyPendingHop();
            // 2.73: the plugin ACTION no longer runs here - see the note on
            // pluginExec. Only the listing is built on the client thread.
            refreshPluginListSnapshot();
            if (online) updatePlayerActivity(); else resetPlayerActivity();
            int agReq = pendingAgStep;
            if (agReq != Integer.MIN_VALUE)
            {
                pendingAgStep = Integer.MIN_VALUE;
                agProgress = agReq < 0 ? 0 : agReq;
                log.info("GEVisualAid agility progress set to {}", agProgress);
            }
            if (online) applyPendingPath();
            if (online) updateAggroTimer(); else aggroReset();
            sceneStateBlock = buildSceneBlock(online);
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid scene state error: {}", t.getMessage());
            try
            {
                sceneStateBlock = buildSceneBlock(false)
                        + "scene_error=" + t.getClass().getSimpleName() + "\n";
            }
            catch (Throwable ignored) { sceneStateBlock = "scene_error=fatal\n"; }
        }
        sceneStateMs = System.currentTimeMillis();
    }

    // Read by the file/HTTP writers on any thread. scene_age_ms is stamped at
    // emit time rather than at compute time, so a consumer can see instantly
    // whether the geometry below is live or a cached leftover.
    private String buildSceneState()
    {
        long age = sceneStateMs == 0 ? -1 : (System.currentTimeMillis() - sceneStateMs);
        return "scene_age_ms=" + age + "\n" + sceneStateBlock;
    }

    private String buildSceneBlock(boolean online)
    {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("scene_enabled=true\n");
        sb.append("scene_online=").append(online).append("\n");

        boolean wantCamera    = cfgFlag("camera");
        boolean wantCanvas    = cfgFlag("canvas");
        boolean wantHover     = cfgFlag("hover");
        boolean wantWaypoints = cfgFlag("waypoints");
        boolean wantAggro     = cfgFlag("aggro");

        // Waypoints are expressed in desktop pixels, which cannot be produced
        // without the canvas origin — so asking for waypoints implies canvas.
        if (wantWaypoints) wantCanvas = true;

        sb.append("scene_camera_enabled=").append(wantCamera).append("\n");
        sb.append("scene_canvas_enabled=").append(wantCanvas).append("\n");
        sb.append("scene_hover_enabled=").append(wantHover).append("\n");
        sb.append("scene_waypoints_enabled=").append(wantWaypoints).append("\n");
        sb.append("scene_aggro_enabled=").append(wantAggro).append("\n");

        // ---- Camera / detached camera (features A and D) -------------------
        int  orbState = 0;
        int  camX = 0, camY = 0, camZ = 0;
        if (online && wantCamera)
        {
            try { orbState = client.getOculusOrbState(); } catch (Throwable ignored) { }
            try { camX = client.getCameraX(); camY = client.getCameraY(); camZ = client.getCameraZ(); }
            catch (Throwable ignored) { }
        }
        sb.append("detached_camera=").append(orbState == 1).append("\n");
        sb.append("oculus_orb_state=").append(orbState).append("\n");
        sb.append("camera_world_x=").append(camX).append("\n");
        sb.append("camera_world_y=").append(camY).append("\n");
        sb.append("camera_world_z=").append(camZ).append("\n");

        // ---- Canvas geometry ----------------------------------------------
        // Same maths buildClerkState() already uses in production: the canvas
        // origin on the desktop plus the display's DPI transform. Emitted in
        // full so a mismatch (stretched mode, a DPI change, a moved window)
        // is visible in the payload instead of silently skewing every pixel.
        java.awt.Canvas canvas   = null;
        boolean         canvasOk = false;
        int             ox = 0, oy = 0, cw = 0, ch = 0;
        int             dispW = 0, dispH = 0;
        double          dsx = 1.0, dsy = 1.0;
        try
        {
            canvas = wantCanvas ? client.getCanvas() : null;
            if (canvas != null)
            {
                cw = canvas.getWidth();
                ch = canvas.getHeight();
                java.awt.Point loc = canvas.getLocationOnScreen();
                ox = loc.x;
                oy = loc.y;
                java.awt.GraphicsConfiguration gc = canvas.getGraphicsConfiguration();
                dsx = gc != null ? gc.getDefaultTransform().getScaleX() : 1.0;
                dsy = gc != null ? gc.getDefaultTransform().getScaleY() : 1.0;
                if (gc != null)
                {
                    // V2.23: physical desktop resolution. getBounds() is in
                    // AWT's logical space, so it needs the same DPI factor
                    // applied as everything else here.
                    Rectangle db = gc.getBounds();
                    dispW = (int) (db.width  * dsx);
                    dispH = (int) (db.height * dsy);
                }
                canvasOk = true;
            }
        }
        catch (Throwable ignored) { canvasOk = false; }

        sb.append("canvas_ok=").append(canvasOk).append("\n");
        sb.append("canvas_screen_x=").append((int) (ox * dsx)).append("\n");
        sb.append("canvas_screen_y=").append((int) (oy * dsy)).append("\n");
        sb.append("canvas_w=").append(cw).append("\n");
        sb.append("canvas_h=").append(ch).append("\n");
        sb.append("canvas_screen_w=").append((int) (cw * dsx)).append("\n");
        sb.append("canvas_screen_h=").append((int) (ch * dsy)).append("\n");
        sb.append("canvas_dpi_scale_x=").append(dsx).append("\n");
        sb.append("canvas_dpi_scale_y=").append(dsy).append("\n");
        sb.append("display_w=").append(dispW).append("\n");
        sb.append("display_h=").append(dispH).append("\n");

        int vpX = 0, vpY = 0, vpW = 0, vpH = 0;
        if (online && wantCanvas)
        {
            try
            {
                vpX = client.getViewportXOffset();
                vpY = client.getViewportYOffset();
                vpW = client.getViewportWidth();
                vpH = client.getViewportHeight();
            }
            catch (Throwable ignored) { }
        }
        // V2.26: the rectangle every click box must stay inside. Zero-size
        // means the viewport is unavailable, in which case clipping is
        // skipped rather than rejecting everything.
        Rectangle vpRect = (vpW > 0 && vpH > 0) ? new Rectangle(vpX, vpY, vpW, vpH) : null;

        sb.append("viewport_x=").append(vpX).append("\n");
        sb.append("viewport_y=").append(vpY).append("\n");
        sb.append("viewport_w=").append(vpW).append("\n");
        sb.append("viewport_h=").append(vpH).append("\n");

        // ---- Scene identity -------------------------------------------------
        WorldView wv = null;
        int  baseX = -1, baseY = -1, scenePlane = -1;
        boolean instanced = false;
        String wvSource = "none";
        if (online && (wantHover || wantWaypoints || wantCanvas))
        {
            // V2.53: THE PLAYER'S world view, not the top level one. Sailing
            // puts the boat in its own WorldView, so aboard a boat the top
            // level view still described the mainland: base 2416,2120 while
            // the player stood at 15555,4423. Every scene scan - scenery,
            // npcs, ground items - swept the wrong arrays and reported zero
            // matches with a filter that was set correctly. The sailing
            // hooks appeared anyway only because those come from the Sailing
            // plugin's own boat model rather than from a scene scan, which
            // is what made the two disagree so visibly.
            try
            {
                Player lp = client.getLocalPlayer();
                if (lp != null)
                {
                    wv = lp.getWorldView();
                    if (wv != null) wvSource = "player";
                }
            }
            catch (Throwable ignored) { wv = null; }
            if (wv == null)
            {
                try
                {
                    wv = client.getTopLevelWorldView();
                    if (wv != null) wvSource = "toplevel";
                }
                catch (Throwable ignored) { wv = null; }
            }
            try
            {
                if (wv != null)
                {
                    baseX      = wv.getBaseX();
                    baseY      = wv.getBaseY();
                    scenePlane = wv.getPlane();
                    instanced  = wv.isInstance();
                }
            }
            catch (Throwable ignored) { }
        }
        sb.append("scene_base_x=").append(baseX).append("\n");
        sb.append("scene_base_y=").append(baseY).append("\n");
        sb.append("scene_plane=").append(scenePlane).append("\n");
        sb.append("scene_instanced=").append(instanced).append("\n");
        // Which view the scans are actually using. A base nowhere near the
        // player is the symptom; this names the cause without guesswork.
        sb.append("scene_wv_source=").append(wvSource).append("\n");

        // ---- Hovered tile (feature B) ---------------------------------------
        // The heaviest item in this block by a wide margin — it sweeps the
        // scene plane every tick. Off by default for that reason.
        int[] hover = null;
        int   mouseX = -1, mouseY = -1;
        if (online && wantHover && wv != null)
        {
            try
            {
                net.runelite.api.Point m = client.getMouseCanvasPosition();
                if (m != null) { mouseX = m.getX(); mouseY = m.getY(); }
                hover = findHoverTile(wv, mouseX, mouseY);
            }
            catch (Throwable ignored) { hover = null; }
        }
        sb.append("mouse_canvas_x=").append(mouseX).append("\n");
        sb.append("mouse_canvas_y=").append(mouseY).append("\n");
        sb.append("hover_valid=").append(hover != null).append("\n");
        sb.append("hover_x=").append(hover != null ? hover[0] : -1).append("\n");
        sb.append("hover_y=").append(hover != null ? hover[1] : -1).append("\n");
        sb.append("hover_plane=").append(hover != null ? hover[2] : -1).append("\n");
        sb.append("hover_canvas_x=").append(hover != null ? hover[3] : -1).append("\n");
        sb.append("hover_canvas_y=").append(hover != null ? hover[4] : -1).append("\n");

        // V2.23: the same values in real desktop pixels, so a consumer can
        // click them directly instead of repeating the DPI maths. Requires
        // the canvas origin, so -1 when canvas geometry is unavailable or
        // switched off rather than a misleading raw canvas value.
        sb.append("mouse_screen_x=").append(canvasOk && mouseX >= 0 ? toScreenX(mouseX, ox, dsx) : -1).append("\n");
        sb.append("mouse_screen_y=").append(canvasOk && mouseY >= 0 ? toScreenY(mouseY, oy, dsy) : -1).append("\n");
        sb.append("hover_screen_x=").append(canvasOk && hover != null ? toScreenX(hover[3], ox, dsx) : -1).append("\n");
        sb.append("hover_screen_y=").append(canvasOk && hover != null ? toScreenY(hover[4], oy, dsy) : -1).append("\n");

        // ---- Entity sets (v2.65) -------------------------------------------
        // Before the three entity appenders below, which read the merged
        // strings this produces.
        try { rebuildEntitySets(); } catch (Throwable ignored) { }
        sb.append("entity_sets_available=").append(esAvailable).append("\n");
        sb.append("entity_sets_active=").append(esActive).append("\n");
        sb.append("entity_set_conflicts=").append(esConflicts).append("\n");

        // v2.67: filter text present, family switched off. Each half reads
        // honestly on its own and the pair still looks like "matched
        // nothing", so the combination is called out explicitly.
        StringBuilder offCfg = new StringBuilder();
        try { if (!config.gameObjectsEnabled()   && !esScenery.trim().isEmpty())
                  esOff(offCfg, "scenery"); }  catch (Throwable ignored) { }
        try { if (!config.npcTrackingEnabled()   && !esNpcs.trim().isEmpty())
                  esOff(offCfg, "npcs"); }     catch (Throwable ignored) { }
        try { if (!config.groundItemsEnabled()   && !esItems.trim().isEmpty())
                  esOff(offCfg, "items"); }    catch (Throwable ignored) { }
        try { if (!config.itemBoxesEnabled()     && !esBoxes.trim().isEmpty())
                  esOff(offCfg, "carried"); }  catch (Throwable ignored) { }
        sb.append("filters_configured_but_off=").append(offCfg).append("\n");

        // ---- Named waypoints, world tile -> absolute desktop pixels (C) -----
        if (wantWaypoints)
        {
            try { rebuildWaypoints(); } catch (Throwable ignored) { }
        }
        else if (!wpNames.isEmpty())
        {
            wpNames.clear();
            wpCoords.clear();
            wpBundle.clear();
            wpConflicts.clear();
            wpActiveBundles = "";
            waypointSpecRaw = null;
        }

        // V2.24: player position and camera yaw, read once for the bearing
        // fields below rather than per waypoint.
        WorldPoint playerLoc = null;
        int        playerYaw = 0;
        boolean needPlayer = wantWaypoints;
        try { needPlayer = needPlayer || config.loadingLinesEnabled()
                                     || config.movementFlagsEnabled(); }
        catch (Throwable ignored) { }
        if (online && needPlayer)
        {
            try
            {
                if (client.getLocalPlayer() != null)
                    playerLoc = client.getLocalPlayer().getWorldLocation();
                playerYaw = client.getCameraYaw();
            }
            catch (Throwable ignored) { }
        }

        StringBuilder names = new StringBuilder();
        for (int i = 0; i < wpNames.size(); i++)
        {
            if (names.length() > 0) names.append(",");
            names.append(wpNames.get(i));
        }
        StringBuilder conflicts = new StringBuilder();
        for (int i = 0; i < wpConflicts.size(); i++)
        {
            if (conflicts.length() > 0) conflicts.append(",");
            conflicts.append(wpConflicts.get(i));
        }
        sb.append("waypoint_count=").append(wpNames.size()).append("\n");
        sb.append("waypoint_names=").append(names).append("\n");
        sb.append("waypoint_bundles_active=").append(wpActiveBundles).append("\n");
        sb.append("waypoint_name_conflicts=").append(conflicts).append("\n");

        for (int i = 0; i < wpNames.size(); i++)
        {
            String name    = wpNames.get(i);
            int[]  c       = wpCoords.get(i);
            int    wx      = c[0];
            int    wy      = c[1];
            int    wx2     = c[2];
            int    wy2     = c[3];
            int    wp      = c[4] < 0 ? (scenePlane < 0 ? 0 : scenePlane) : c[4];
            boolean isClus = c[5] == 1;

            String state = "offline";
            int[]  box   = null;
            int[]  counts = new int[]{ isClus ? (wx2 - wx + 1) * (wy2 - wy + 1) : 1, 0 };
            if (online && wv != null)
            {
                if (instanced) state = "instanced";
                else if (isClus)
                {
                    Object[] r = resolveCluster(wv, wx, wy, wx2, wy2, wp,
                            canvasOk, ox, oy, dsx, dsy, counts, vpRect);
                    state = (String) r[0];
                    box   = (int[])  r[1];
                }
                else
                {
                    Object[] r = resolveTile(wv, wx, wy, wp, canvasOk, ox, oy, dsx, dsy, vpRect);
                    state = (String) r[0];
                    box   = (int[])  r[1];
                    counts[1] = box != null ? 1 : 0;
                }
            }

            // V2.24: bearing is computed for every waypoint whatever its
            // state — an offscreen tile still has a known world position, and
            // this is what tells the consumer which way to turn the camera.
            // For a cluster it is measured to the centre of the area.
            int bearX = isClus ? (wx + wx2) / 2 : wx;
            int bearY = isClus ? (wy + wy2) / 2 : wy;
            Object[] bear = online
                    ? bearingTo(playerLoc, bearX, bearY, playerYaw)
                    : new Object[]{ -1, -1, -999, "UNKNOWN" };

            String k = "wp_" + name + "_";
            sb.append(k).append("type=").append(isClus ? "cluster" : "tile").append("\n");
            sb.append(k).append("bundle=").append(wpBundle.get(i)).append("\n");
            sb.append(k).append("world_x=").append(wx).append("\n");
            sb.append(k).append("world_y=").append(wy).append("\n");
            if (isClus)
            {
                sb.append(k).append("world_x2=").append(wx2).append("\n");
                sb.append(k).append("world_y2=").append(wy2).append("\n");
                sb.append(k).append("tiles_total=").append(counts[0]).append("\n");
                sb.append(k).append("tiles_visible=").append(counts[1]).append("\n");
            }
            sb.append(k).append("plane=").append(wp).append("\n");
            sb.append(k).append("state=").append(state).append("\n");
            sb.append(k).append("visible=").append(box != null).append("\n");
            sb.append(k).append("screen_x=").append(box != null ? box[0] : -1).append("\n");
            sb.append(k).append("screen_y=").append(box != null ? box[1] : -1).append("\n");
            sb.append(k).append("x1=").append(box != null ? box[2] : -1).append("\n");
            sb.append(k).append("y1=").append(box != null ? box[3] : -1).append("\n");
            sb.append(k).append("x2=").append(box != null ? box[4] : -1).append("\n");
            sb.append(k).append("y2=").append(box != null ? box[5] : -1).append("\n");
            sb.append(k).append("click_x1=").append(box != null ? box[6] : -1).append("\n");
            sb.append(k).append("click_y1=").append(box != null ? box[7] : -1).append("\n");
            sb.append(k).append("click_x2=").append(box != null ? box[8] : -1).append("\n");
            sb.append(k).append("click_y2=").append(box != null ? box[9] : -1).append("\n");
            sb.append(k).append("dist_tiles=").append(bear[0]).append("\n");
            sb.append(k).append("bearing_deg=").append(bear[1]).append("\n");
            sb.append(k).append("rel_bearing_deg=").append(bear[2]).append("\n");
            sb.append(k).append("direction=").append(bear[3]).append("\n");
        }

        // ---- Ground items and NPCs (V2.27) ----------------------------------
        boolean wantItems = false, wantNpcs = false;
        try { wantItems = config.groundItemsEnabled(); } catch (Throwable ignored) { }
        try { wantNpcs  = config.npcTrackingEnabled(); } catch (Throwable ignored) { }
        sb.append("gi_enabled=").append(wantItems).append("\n");
        sb.append("npc_enabled=").append(wantNpcs).append("\n");

        if (online && wantItems && wv != null && !instanced)
            appendGroundItems(sb, wv, playerLoc, playerYaw, scenePlane,
                    canvasOk, ox, oy, dsx, dsy, vpRect);
        else
            sb.append("gi_count=0\n");

        if (online && wantNpcs && wv != null)
            appendNpcs(sb, wv, playerLoc, playerYaw, canvasOk, ox, oy, dsx, dsy, vpRect);
        else
            sb.append("npc_count=0\n");

        // ---- Carried items (v2.66) ------------------------------------------
        boolean wantBoxes = false;
        try { wantBoxes = config.itemBoxesEnabled(); } catch (Throwable ignored) { }
        sb.append("ib_enabled=").append(wantBoxes).append("\n");
        if (online && wantBoxes)
            appendItemBoxes(sb, canvasOk, ox, oy, dsx, dsy);
        else
            sb.append("ib_count=0\n");

        boolean wantObjs = false;
        try { wantObjs = config.gameObjectsEnabled(); } catch (Throwable ignored) { }
        sb.append("go_enabled=").append(wantObjs).append("\n");
        if (online && wantObjs && wv != null)
            appendGameObjects(sb, wv, playerLoc, playerYaw, scenePlane,
                    canvasOk, ox, oy, dsx, dsy, vpRect);
        else
            sb.append("go_count=0\n");

        // ---- Loading lines and movement flags (V2.32) -----------------------
        boolean wantLoad = false, wantMove = false;
        try { wantLoad = config.loadingLinesEnabled(); } catch (Throwable ignored) { }
        try { wantMove = config.movementFlagsEnabled(); } catch (Throwable ignored) { }
        sb.append("load_enabled=").append(wantLoad).append("\n");
        sb.append("move_enabled=").append(wantMove).append("\n");

        if (online && wantLoad && wv != null && playerLoc != null)
            appendLoadingLines(sb, wv, playerLoc, scenePlane, canvasOk, ox, oy, dsx, dsy, vpRect);

        if (online && wantMove && wv != null && playerLoc != null)
            appendMovementFlags(sb, wv, playerLoc, hover, scenePlane,
                    canvasOk, ox, oy, dsx, dsy, vpRect);

        // ---- Vitals and widgets (V2.43) -------------------------------------
        boolean wantVitals = false, wantWidgets = false;
        try { wantVitals  = config.vitalsEnabled();  } catch (Throwable ignored) { }
        try { wantWidgets = config.widgetsEnabled(); } catch (Throwable ignored) { }
        sb.append("vit_enabled=").append(wantVitals).append("\n");
        sb.append("wg_enabled=").append(wantWidgets).append("\n");
        if (online && wantVitals)  appendVitals(sb);
        if (online && wantWidgets) appendWidgets(sb, canvasOk, ox, oy, dsx, dsy);

        // ---- Sailing (V2.49) ------------------------------------------------
        boolean wantSail = false;
        try { wantSail = config.sailingEnabled(); } catch (Throwable ignored) { }
        sb.append("sail_enabled=").append(wantSail).append("\n");
        if (online && wantSail)
            appendSailing(sb, wv, playerLoc, playerYaw, canvasOk, ox, oy, dsx, dsy, vpRect);

        // ---- Bank, attack style, prayers (V2.48) ----------------------------
        boolean wantBank = false, wantAtt = false, wantPray = false;
        try { wantBank = config.bankTrackingEnabled();   } catch (Throwable ignored) { }
        try { wantAtt  = config.attackStyleEnabled();    } catch (Throwable ignored) { }
        try { wantPray = config.prayerTrackingEnabled(); } catch (Throwable ignored) { }
        sb.append("bank_enabled=").append(wantBank).append("\n");
        sb.append("att_enabled=").append(wantAtt).append("\n");
        sb.append("pray_enabled=").append(wantPray).append("\n");
        if (online && wantBank) appendBank(sb);
        if (online && wantAtt)  appendAttackStyle(sb);
        if (online && wantPray) appendPrayers(sb);

        // ---- Runes and remaining casts (V2.47) ------------------------------
        boolean wantRunes = false;
        try { wantRunes = config.runeTrackingEnabled(); } catch (Throwable ignored) { }
        sb.append("rune_enabled=").append(wantRunes).append("\n");
        if (online && wantRunes) appendRunes(sb);

        // ---- Easy Blast Furnace (V2.45) -------------------------------------
        boolean wantBf = false;
        try { wantBf = config.blastFurnaceEnabled(); } catch (Throwable ignored) { }
        sb.append("bf_enabled=").append(wantBf).append("\n");
        if (online && wantBf)
        {
            appendBlastFurnaceState(sb);
            appendBlastFurnace(sb, wv, playerLoc, playerYaw, canvasOk, ox, oy, dsx, dsy, vpRect);
        }
        else
        {
            sb.append("bf_step_count=0\n");
        }

        // ---- Agility (V2.40) ------------------------------------------------
        boolean wantAgility = false;
        try { wantAgility = config.agilityTrackingEnabled(); } catch (Throwable ignored) { }
        sb.append("agility_enabled=").append(wantAgility).append("\n");
        if (online && wantAgility && wv != null)
            appendAgility(sb, wv, playerLoc, playerYaw, scenePlane,
                    canvasOk, ox, oy, dsx, dsy, vpRect);
        else
            sb.append("agility_obstacle_count=0\nagility_mark_count=0\n");

        // ---- Player activity (V2.38) ----------------------------------------
        boolean wantAct = false;
        try { wantAct = config.playerActivityEnabled(); } catch (Throwable ignored) { }
        sb.append("player_act_enabled=").append(wantAct).append("\n");
        if (online && wantAct) appendPlayerActivity(sb);

        // ---- Runite rocks and world hop (V2.37) -----------------------------
        boolean wantRunite = false;
        try { wantRunite = config.runiteTrackingEnabled(); } catch (Throwable ignored) { }
        sb.append("runite_enabled=").append(wantRunite).append("\n");
        sb.append("hop_status=").append(hopStatus).append("\n");
        sb.append("hop_requested_world=").append(hopLastRequested).append("\n");
        sb.append("current_world=").append(safeInt(() -> client.getWorld())).append("\n");
        if (online && wantRunite) appendRuniteRocks(sb);
        else                      sb.append("runite_count=0\n");

        // ---- Shortest Path route (V2.33) ------------------------------------
        boolean wantPath = false;
        try { wantPath = config.pathTrackingEnabled(); } catch (Throwable ignored) { }
        sb.append("path_enabled=").append(wantPath).append("\n");
        if (online && wantPath)
            appendPath(sb, wv, playerLoc, scenePlane, canvasOk, ox, oy, dsx, dsy, vpRect);
        else
            sb.append("path_state=disabled\npath_length=0\n");

        // ---- NPC aggression timer -------------------------------------------
        // V2.22: RuneLite's own NPC Aggression Timer is preferred over our
        // estimate whenever it is running. Its infobox carries the exact
        // end instant, calibrated by their logic, so there is nothing to
        // re-derive. The v2.21 estimate stays as the fallback for when that
        // plugin is disabled.
        boolean wildy = false;
        if (online && wantAggro)
        {
            // VarBit 5963 — IN_WILDERNESS. Raw id to match the existing style
            // in this file (4439 / 4397 are used the same way).
            try { wildy = client.getVarbitValue(5963) > 0; } catch (Throwable ignored) { }
        }

        String  aggroState  = "UNKNOWN";
        String  aggroSource = "disabled";
        long    aggroLeft   = -1;
        boolean useRl       = true;
        try { useRl = config.aggroUseRuneLitePlugin(); } catch (Throwable ignored) { }

        if (online && wantAggro)
        {
            aggroSource = "none";

            if (useRl)
            {
                long[] rl = readRuneLiteAggroTimer();
                if (rl[1] == 1)                 // live AggressionTimer infobox
                {
                    aggroLeft   = rl[0];
                    aggroSource = "runelite";
                    aggroSawRlTimer = true;
                }
                else if (rl[2] == 1)            // UncalibratedInfobox showing
                {
                    aggroSource = "uncalibrated";
                }
                else if (aggroSawRlTimer)       // was running, now culled
                {
                    aggroLeft   = 0;
                    aggroSource = "runelite_expired";
                }
            }

            // Fall back to the internal estimate only when RuneLite gave us
            // nothing at all — never override a real reading with a guess.
            if (aggroLeft < 0 && !"uncalibrated".equals(aggroSource) && aggroKnown)
            {
                long elapsed = System.currentTimeMillis() - aggroStartMs;
                aggroLeft    = Math.max(0, (AGGRO_DURATION_MS - elapsed) / 1000L);
                aggroSource  = "internal";
            }

            if (wildy)             aggroState = "ALWAYS_AGGRESSIVE";
            else if (aggroLeft < 0) aggroState = "UNKNOWN";
            else                    aggroState = aggroLeft > 0 ? "AGGRESSIVE" : "UNAGGRESSIVE";
        }

        boolean internalSrc = "internal".equals(aggroSource);
        sb.append("aggro_state=").append(aggroState).append("\n");
        sb.append("aggro_seconds_remaining=").append(aggroLeft).append("\n");
        sb.append("aggro_source=").append(aggroSource).append("\n");
        sb.append("aggro_known=").append(aggroLeft >= 0).append("\n");
        sb.append("aggro_in_wilderness=").append(wildy).append("\n");
        sb.append("aggro_anchor_a_x=").append(internalSrc && aggroAnchorA != null ? aggroAnchorA.getX() : -1).append("\n");
        sb.append("aggro_anchor_a_y=").append(internalSrc && aggroAnchorA != null ? aggroAnchorA.getY() : -1).append("\n");
        sb.append("aggro_anchor_b_x=").append(internalSrc && aggroAnchorB != null ? aggroAnchorB.getX() : -1).append("\n");
        sb.append("aggro_anchor_b_y=").append(internalSrc && aggroAnchorB != null ? aggroAnchorB.getY() : -1).append("\n");

        return sb.toString();
    }

    // Canvas space -> desktop pixels. Deliberately the same expression used
    // by resolveTile() for the waypoints and by buildClerkState() for the GE
    // boxes: the canvas origin and the canvas offset are both in AWT logical
    // units, so the DPI factor applies once to their sum. There is no
    // resolution branch here by design — the factor is read from the live
    // display, so the answer is already correct for whatever mode is running.
    private int toScreenX(int canvasX, int originX, double scaleX)
    {
        return (int) ((originX + canvasX) * scaleX);
    }

    private int toScreenY(int canvasY, int originY, double scaleY)
    {
        return (int) ((originY + canvasY) * scaleY);
    }

    // Small guarded config reader so one throwing accessor cannot take the
    // whole scene block down with it.
    private boolean cfgFlag(String which)
    {
        try
        {
            switch (which)
            {
                case "camera":    return config.cameraStateEnabled();
                case "canvas":    return config.canvasGeometryEnabled();
                case "hover":     return config.hoverTileEnabled();
                case "waypoints": return config.waypointsEnabled();
                case "aggro":     return config.aggroTimerEnabled();
                default:          return false;
            }
        }
        catch (Throwable t) { return false; }
    }

    // -----------------------------------------------------------------------
    // Read RuneLite's own NPC Aggression Timer (plugin package
    // npcunaggroarea). Its AggressionTimer extends the public Timer infobox
    // class, so getEndTime() gives the exact instant it is counting down to
    // — the number displayed on screen, with RuneLite's calibration already
    // applied. Matched by class simple name rather than by type, because
    // AggressionTimer itself is package-private.
    //
    // Returns { secondsRemaining, foundFlag, uncalibratedFlag }.
    // -----------------------------------------------------------------------
    private long[] readRuneLiteAggroTimer()
    {
        long[] result = new long[]{ -1, 0, 0 };
        try
        {
            if (infoBoxManager == null) return result;
            List<InfoBox> boxes = infoBoxManager.getInfoBoxes();
            if (boxes == null) return result;

            for (InfoBox ib : boxes)
            {
                if (ib == null) continue;
                String cn = ib.getClass().getSimpleName();

                if ("UncalibratedInfobox".equals(cn))
                {
                    result[2] = 1;
                    continue;
                }
                if ("AggressionTimer".equals(cn) && ib instanceof Timer)
                {
                    Instant end = ((Timer) ib).getEndTime();
                    if (end == null) continue;
                    long secs = Duration.between(Instant.now(), end).getSeconds();
                    result[0] = Math.max(0, secs);
                    result[1] = 1;
                }
            }
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid aggro infobox read error: {}", t.getMessage());
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Waypoint config parsing.
    //
    //   single tile : name:x:y[:plane]
    //   cluster     : name:x1:y1-x2:y2[:plane]
    //
    // Separated by commas, semicolons or newlines. Plane may be omitted to
    // mean "the plane the player is currently on". Names are sanitised to
    // [A-Za-z0-9_] so they can be used directly as key prefixes.
    //
    // The dash is what marks a cluster: name:x:y:plane already occupies four
    // colon-separated fields, so a fifth could not be told apart otherwise.
    //
    // V2.25: sources are the always-on list plus every ENABLED bundle. The
    // combined spec is compared as a whole, so nothing re-parses until the
    // configuration actually changes.
    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    // Plugin v2.65 — Entity sets.
    //
    // Merges the always-on filter box for each family with every enabled
    // set. Guarded on the combined spec exactly as rebuildWaypoints is, so
    // nothing re-parses until the configuration really changes.
    //
    // MERGED, NOT REPLACED. The always-on boxes are the first chunk, so an
    // entry that is genuinely common stays in one place and a duplicate
    // label in a set loses to it rather than shadowing it.
    // -----------------------------------------------------------------------
    private void rebuildEntitySets()
    {
        StringBuilder spec  = new StringBuilder();
        StringBuilder scen  = new StringBuilder();
        StringBuilder npcs  = new StringBuilder();
        StringBuilder itms  = new StringBuilder();
        StringBuilder act   = new StringBuilder();
        StringBuilder avail = new StringBuilder();

        StringBuilder boxs = new StringBuilder();
        try { esAppend(scen, config.gameObjectFilter()); } catch (Throwable ignored) { }
        try { esAppend(npcs, config.npcFilter());        } catch (Throwable ignored) { }
        try { esAppend(itms, config.groundItemFilter()); } catch (Throwable ignored) { }
        try { esAppend(boxs, config.itemBoxFilter());    } catch (Throwable ignored) { }
        spec.append(scen).append("|").append(npcs).append("|").append(itms)
            .append("|").append(boxs);

        for (int i = 1; i <= ES_SLOTS; i++)
        {
            try
            {
                String nm = sanitiseKey(setName(i));
                if (nm.isEmpty()) nm = "set" + i;

                boolean on = setEnabled(i);
                if (avail.length() > 0) avail.append(",");
                avail.append(nm).append(on ? ":on" : ":off");

                // 2.68: the guard below compares this spec, so EVERY input
                // that can change the output has to appear in it. A name and
                // an on/off flag for every slot, enabled or not, because a
                // rename shows up in entity_sets_available.
                spec.append("|").append(nm).append(on ? ":on" : ":off");
                if (!on) continue;

                String sc = setScenery(i);
                String np = setNpcs(i);
                String im = setItems(i);
                String bx = setBoxes(i);
                String wp = setWaypoints(i);   // 2.71

                // 2.68: the CONTENT, not just the name. Without this, editing
                // a set's filter text changes nothing the guard can see, so
                // the merge never rebuilds and the edit silently never takes
                // effect until something unrelated happens to differ.
                spec.append("=").append(sc).append("&").append(np)
                    .append("&").append(im).append("&").append(bx)
                    .append("&").append(wp);

                esAppend(scen, sc);
                esAppend(npcs, np);
                esAppend(itms, im);
                esAppend(boxs, bx);

                if (act.length() > 0) act.append(",");
                act.append(nm);
            }
            catch (Throwable ignored) { }
        }

        // 2.70: ASSIGNED UNCONDITIONALLY. There is deliberately no early
        // return above this point.
        //
        // 2.65 guarded these on a spec string and forgot to put the sets'
        // filter text in it, so edits did not take. 2.68 added the missing
        // inputs, which fixes the instance and leaves the trap: any field
        // added later has to be remembered, and forgetting produces silently
        // stale output instead of an error.
        //
        // The guard bought almost nothing anyway - it read every config
        // field to build its own comparison string, so the reads it was
        // meant to skip happened regardless.
        esScenery   = scen.toString();
        esNpcs      = npcs.toString();
        esItems     = itms.toString();
        esBoxes     = boxs.toString();
        esActive    = act.toString();
        esAvailable = avail.toString();

        // A duplicate label matches nothing forever while looking configured,
        // because selectResults() skips a label it has already emitted. Same
        // for anything past parseEntityFilter's 24-entry cap. Both are
        // reported rather than left to look like an empty area.
        //
        // 2.70: this half IS still guarded, because it exists to write a log
        // line and repeating that every tick would bury everything else. The
        // asymmetry is the point - if this guard is ever wrong the cost is a
        // stale warning, never stale data.
        String raw = spec.toString();
        if (raw.equals(esSpecRaw)) return;
        esSpecRaw = raw;

        StringBuilder bad = new StringBuilder();
        esCheck(bad, "scenery", esScenery);
        esCheck(bad, "npc",     esNpcs);
        esCheck(bad, "item",    esItems);
        esCheck(bad, "carried", esBoxes);
        esConflicts = bad.toString();

        log.info("GEVisualAid v{} entity sets active [{}]", PLUGIN_OUTPUT_VERSION, esActive);
        if (!esConflicts.isEmpty())
            log.warn("GEVisualAid entity set problems: {}", esConflicts);
    }

    private void esOff(StringBuilder sb, String family)
    {
        if (sb.length() > 0) sb.append(",");
        sb.append(family);
    }

    private void esAppend(StringBuilder sb, String chunk)
    {
        if (chunk == null || chunk.trim().isEmpty()) return;
        if (sb.length() > 0) sb.append(",");
        sb.append(chunk.trim());
    }

    // Labels are read the same way parseEntityFilter reads them, so what is
    // reported here is what that method will actually do.
    private void esCheck(StringBuilder bad, String family, String merged)
    {
        List<String> seen = new ArrayList<>();
        int n = 0;
        for (String tok : merged.split("[,;\r\n]+"))
        {
            tok = tok.trim();
            if (tok.isEmpty()) continue;
            n++;
            int eq = tok.indexOf('=');
            if (eq <= 0) continue;
            String lab = tok.substring(0, eq).trim();
            int colon = lab.lastIndexOf(':');
            if (colon > 0 && colon < lab.length() - 1
                    && lab.substring(colon + 1).trim().matches("\\d+"))
                lab = lab.substring(0, colon).trim();
            lab = sanitiseKey(lab);
            if (lab.isEmpty()) continue;
            if (seen.contains(lab))
            {
                if (bad.length() > 0) bad.append(",");
                bad.append(family).append("_duplicate_label:").append(lab);
            }
            else seen.add(lab);
        }
        if (n > ES_FILTER_CAP)
        {
            if (bad.length() > 0) bad.append(",");
            bad.append(family).append("_overflow:").append(n).append("/").append(ES_FILTER_CAP);
        }
    }

    // Config accessors for the ten entity set slots. Static declarations mean
    // a switch rather than a loop, exactly as with the waypoint bundles.
    private boolean setEnabled(int i)
    {
        switch (i)
        {
            case 1:  return config.set1Enabled();
            case 2:  return config.set2Enabled();
            case 3:  return config.set3Enabled();
            case 4:  return config.set4Enabled();
            case 5:  return config.set5Enabled();
            case 6:  return config.set6Enabled();
            case 7:  return config.set7Enabled();
            case 8:  return config.set8Enabled();
            case 9:  return config.set9Enabled();
            case 10: return config.set10Enabled();
        }
        return false;
    }

    private String setName(int i)
    {
        switch (i)
        {
            case 1:  return config.set1Name();
            case 2:  return config.set2Name();
            case 3:  return config.set3Name();
            case 4:  return config.set4Name();
            case 5:  return config.set5Name();
            case 6:  return config.set6Name();
            case 7:  return config.set7Name();
            case 8:  return config.set8Name();
            case 9:  return config.set9Name();
            case 10: return config.set10Name();
        }
        return "";
    }

    private String setScenery(int i)
    {
        switch (i)
        {
            case 1:  return config.set1Scenery();
            case 2:  return config.set2Scenery();
            case 3:  return config.set3Scenery();
            case 4:  return config.set4Scenery();
            case 5:  return config.set5Scenery();
            case 6:  return config.set6Scenery();
            case 7:  return config.set7Scenery();
            case 8:  return config.set8Scenery();
            case 9:  return config.set9Scenery();
            case 10: return config.set10Scenery();
        }
        return "";
    }

    private String setNpcs(int i)
    {
        switch (i)
        {
            case 1:  return config.set1Npcs();
            case 2:  return config.set2Npcs();
            case 3:  return config.set3Npcs();
            case 4:  return config.set4Npcs();
            case 5:  return config.set5Npcs();
            case 6:  return config.set6Npcs();
            case 7:  return config.set7Npcs();
            case 8:  return config.set8Npcs();
            case 9:  return config.set9Npcs();
            case 10: return config.set10Npcs();
        }
        return "";
    }

    private String setItems(int i)
    {
        switch (i)
        {
            case 1:  return config.set1Items();
            case 2:  return config.set2Items();
            case 3:  return config.set3Items();
            case 4:  return config.set4Items();
            case 5:  return config.set5Items();
            case 6:  return config.set6Items();
            case 7:  return config.set7Items();
            case 8:  return config.set8Items();
            case 9:  return config.set9Items();
            case 10: return config.set10Items();
        }
        return "";
    }

    private String setWaypoints(int i)
    {
        switch (i)
        {
            case 1:  return config.set1Waypoints();
            case 2:  return config.set2Waypoints();
            case 3:  return config.set3Waypoints();
            case 4:  return config.set4Waypoints();
            case 5:  return config.set5Waypoints();
            case 6:  return config.set6Waypoints();
            case 7:  return config.set7Waypoints();
            case 8:  return config.set8Waypoints();
            case 9:  return config.set9Waypoints();
            case 10: return config.set10Waypoints();
        }
        return "";
    }

    private String setBoxes(int i)
    {
        switch (i)
        {
            case 1:  return config.set1Boxes();
            case 2:  return config.set2Boxes();
            case 3:  return config.set3Boxes();
            case 4:  return config.set4Boxes();
            case 5:  return config.set5Boxes();
            case 6:  return config.set6Boxes();
            case 7:  return config.set7Boxes();
            case 8:  return config.set8Boxes();
            case 9:  return config.set9Boxes();
            case 10: return config.set10Boxes();
        }
        return "";
    }

    // Plugin v2.65 — exclusive activation by name, for a script's startup.
    //
    // An unknown name changes NOTHING. Turning every set off because of a
    // typo would leave the caller scanning for nothing at all, which is the
    // failure this whole feature exists to prevent.
    private String activateEntitySet(String want)
    {
        String w = sanitiseKey(want == null ? "" : want.trim()).toLowerCase();

        List<String> names = new ArrayList<>();
        int hit = -1;
        for (int i = 1; i <= ES_SLOTS; i++)
        {
            String nm;
            try { nm = sanitiseKey(setName(i)); } catch (Throwable t) { nm = ""; }
            if (nm.isEmpty()) nm = "set" + i;
            names.add(nm);
            if (hit < 0 && nm.toLowerCase().equals(w)) hit = i;
        }

        boolean clear = w.isEmpty() || w.equals("none") || w.equals("off");
        if (!clear && hit < 0)
            return "no entity set named '" + want + "' - nothing changed. available: "
                    + String.join(",", names) + "\n";

        synchronized (pendingConfig)
        {
            for (int i = 1; i <= ES_SLOTS; i++)
                pendingConfig.add(new String[]{ "set" + i + "Enabled",
                        (!clear && i == hit) ? "true" : "false" });
        }
        return clear ? "queued entityset=none - all sets off\n"
                     : "queued entityset=" + names.get(hit - 1)
                         + " (slot " + hit + "), all other sets off\n";
    }

    private void rebuildWaypoints()
    {
        StringBuilder combined = new StringBuilder();
        StringBuilder active   = new StringBuilder();
        List<String>  sources  = new ArrayList<>();   // parallel: bundle name per chunk
        List<String>  chunks   = new ArrayList<>();

        try
        {
            String always = config.waypointList();
            if (always != null && !always.trim().isEmpty())
            {
                chunks.add(always);
                sources.add("");
                combined.append("|").append(always);
            }
        }
        catch (Throwable ignored) { }

        for (int i = 1; i <= WP_BUNDLE_SLOTS; i++)
        {
            try
            {
                if (!bundleEnabled(i)) continue;
                String list = bundleWaypoints(i);
                if (list == null || list.trim().isEmpty()) continue;
                String bname = sanitiseKey(bundleName(i));
                if (bname.isEmpty()) bname = "bundle" + i;
                chunks.add(list);
                sources.add(bname);
                combined.append("|").append(bname).append("=").append(list);
                if (active.length() > 0) active.append(",");
                active.append(bname);
            }
            catch (Throwable ignored) { }
        }

        // 2.71: an ENABLED ENTITY SET is a third source, so an activity's
        // positioning can live with its scenery instead of in a separately
        // toggled bundle that has to be kept in agreement. Bundles above
        // are untouched; this adds to them rather than replacing them, and
        // a name defined twice still resolves first-wins with the clash
        // reported in waypoint_name_conflicts.
        for (int i = 1; i <= ES_SLOTS; i++)
        {
            try
            {
                if (!setEnabled(i)) continue;
                String list = setWaypoints(i);
                if (list == null || list.trim().isEmpty()) continue;
                String sname = sanitiseKey(setName(i));
                if (sname.isEmpty()) sname = "set" + i;
                chunks.add(list);
                sources.add(sname);
                combined.append("|set:").append(sname).append("=").append(list);
                if (active.length() > 0) active.append(",");
                active.append(sname);
            }
            catch (Throwable ignored) { }
        }

        String spec = combined.toString();
        if (spec.equals(waypointSpecRaw)) return;
        waypointSpecRaw = spec;

        wpNames.clear();
        wpCoords.clear();
        wpBundle.clear();
        wpConflicts.clear();
        wpActiveBundles = active.toString();

        for (int ci = 0; ci < chunks.size(); ci++)
            parseChunk(chunks.get(ci), sources.get(ci));

        log.info("GEVisualAid v2.25 parsed {} waypoint(s) from bundles [{}]: {}",
                wpNames.size(), wpActiveBundles, wpNames);
        if (!wpConflicts.isEmpty())
            log.warn("GEVisualAid waypoint name collisions (first wins): {}", wpConflicts);
    }

    private void parseChunk(String spec, String bundle)
    {
        for (String tok : spec.split("[,;\r\n]+"))
        {
            tok = tok.trim();
            if (tok.isEmpty()) continue;
            String[] p = tok.split(":");
            if (p.length < 3) continue;
            try
            {
                String name = sanitiseKey(p[0].trim());
                if (name.isEmpty()) continue;
                if (wpNames.contains(name))
                {
                    if (!wpConflicts.contains(name)) wpConflicts.add(name);
                    continue;                       // first definition wins
                }

                int x1, y1, x2, y2, pl = -1;
                String midField = p[2].trim();

                if (midField.indexOf('-') > 0 && p.length >= 4)
                {
                    // cluster: name:x1:y1-x2:y2[:plane]
                    int dash = midField.indexOf('-');
                    x1 = Integer.parseInt(p[1].trim());
                    y1 = Integer.parseInt(midField.substring(0, dash).trim());
                    x2 = Integer.parseInt(midField.substring(dash + 1).trim());
                    y2 = Integer.parseInt(p[3].trim());
                    if (p.length >= 5 && !p[4].trim().isEmpty())
                        pl = Integer.parseInt(p[4].trim());

                    int lox = Math.min(x1, x2), hix = Math.max(x1, x2);
                    int loy = Math.min(y1, y2), hiy = Math.max(y1, y2);
                    long area = (long) (hix - lox + 1) * (hiy - loy + 1);
                    if (area > WP_MAX_CLUSTER_TILES)
                    {
                        log.warn("GEVisualAid cluster '{}' is {} tiles, cap is {} — skipped",
                                name, area, WP_MAX_CLUSTER_TILES);
                        continue;
                    }
                    wpNames.add(name);
                    wpCoords.add(new int[]{ lox, loy, hix, hiy, pl, 1 });
                    wpBundle.add(bundle);
                }
                else
                {
                    // single tile: name:x:y[:plane]
                    x1 = Integer.parseInt(p[1].trim());
                    y1 = Integer.parseInt(midField);
                    if (p.length >= 4 && !p[3].trim().isEmpty())
                        pl = Integer.parseInt(p[3].trim());
                    wpNames.add(name);
                    wpCoords.add(new int[]{ x1, y1, x1, y1, pl, 0 });
                    wpBundle.add(bundle);
                }
            }
            catch (Exception ignored) { }
            if (wpNames.size() >= 64) break;         // sanity cap
        }
    }

    // Config accessors for the ten bundle slots. Static declarations mean a
    // switch rather than anything dynamic.
    private boolean bundleEnabled(int i)
    {
        switch (i)
        {
            case 1:  return config.bundle1Enabled();
            case 2:  return config.bundle2Enabled();
            case 3:  return config.bundle3Enabled();
            case 4:  return config.bundle4Enabled();
            case 5:  return config.bundle5Enabled();
            case 6:  return config.bundle6Enabled();
            case 7:  return config.bundle7Enabled();
            case 8:  return config.bundle8Enabled();
            case 9:  return config.bundle9Enabled();
            case 10: return config.bundle10Enabled();
            default: return false;
        }
    }

    private String bundleName(int i)
    {
        switch (i)
        {
            case 1:  return config.bundle1Name();
            case 2:  return config.bundle2Name();
            case 3:  return config.bundle3Name();
            case 4:  return config.bundle4Name();
            case 5:  return config.bundle5Name();
            case 6:  return config.bundle6Name();
            case 7:  return config.bundle7Name();
            case 8:  return config.bundle8Name();
            case 9:  return config.bundle9Name();
            case 10: return config.bundle10Name();
            default: return "";
        }
    }

    private String bundleWaypoints(int i)
    {
        switch (i)
        {
            case 1:  return config.bundle1Waypoints();
            case 2:  return config.bundle2Waypoints();
            case 3:  return config.bundle3Waypoints();
            case 4:  return config.bundle4Waypoints();
            case 5:  return config.bundle5Waypoints();
            case 6:  return config.bundle6Waypoints();
            case 7:  return config.bundle7Waypoints();
            case 8:  return config.bundle8Waypoints();
            case 9:  return config.bundle9Waypoints();
            case 10: return config.bundle10Waypoints();
            default: return "";
        }
    }

    private String sanitiseKey(String s)
    {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_')
                out.append(c);
            else if (c == ' ' || c == '-')
                out.append('_');
        }
        return out.toString();
    }

    // -----------------------------------------------------------------------
    // World tile -> absolute desktop pixels.
    //
    // Returns { state, box } where box is null unless state is "ok", and
    // box is { centreX, centreY, x1, y1, x2, y2 } in real screen pixels.
    // The LocalPoint is taken from the Tile object rather than constructed,
    // which keeps this working across the WorldView API changes, and the
    // scene bounds are read from the tile array itself rather than assuming
    // 104x104 (extended scenes change that).
    // -----------------------------------------------------------------------
    private Object[] resolveTile(WorldView wv, int worldX, int worldY, int plane,
                                 boolean canvasOk, int ox, int oy, double dsx, double dsy,
                                 Rectangle clip)
    {
        return resolveTile(wv, worldX, worldY, plane, canvasOk, ox, oy, dsx, dsy, clip, false);
    }

    // 2.76: liftToItems raises the box to where the ITEM is actually drawn.
    //
    // Josh, on the Pollnivneach mark that sits on a table: "not the mark, a
    // section of the green ground highlighted by the agility plugin below
    // it... most of them are fine and work well, but this one is a table so
    // it keeps clicking under it."
    //
    // A tile polygon is at FLOOR level. An item resting on a table renders
    // a long way above its own tile - his screenshot has the reported click
    // at 2140,1059 with the mark sprite about two hundred pixels higher. The
    // box was never wrong about WHICH tile, only about where on the screen
    // that tile's contents are.
    //
    // Tile.getItemLayer().getHeight() is the offset the client itself draws
    // the item at, so localToCanvas with that height gives the real point.
    // The whole box is shifted by the difference, which keeps the tile's
    // shape and size and only moves it up.
    //
    // OFF by default. Only callers clicking something LYING on a tile want
    // this; an obstacle's box must not jump because a bone happens to be on
    // the same square.
    private Object[] resolveTile(WorldView wv, int worldX, int worldY, int plane,
                                 boolean canvasOk, int ox, int oy, double dsx, double dsy,
                                 Rectangle clip, boolean liftToItems)
    {
        Tile[][][] tiles;
        try { tiles = wv.getScene().getTiles(); }
        catch (Throwable t) { return new Object[]{ "no_scene", null }; }
        if (tiles == null || plane < 0 || plane >= tiles.length)
            return new Object[]{ "offscene", null };

        int sx = worldX - wv.getBaseX();
        int sy = worldY - wv.getBaseY();
        if (sx < 0 || sx >= tiles[plane].length)      return new Object[]{ "offscene", null };
        if (sy < 0 || sy >= tiles[plane][sx].length)  return new Object[]{ "offscene", null };

        Tile t = tiles[plane][sx][sy];
        if (t == null) return new Object[]{ "not_loaded", null };

        LocalPoint lp = t.getLocalLocation();
        if (lp == null) return new Object[]{ "not_loaded", null };

        net.runelite.api.Point c = Perspective.localToCanvas(client, lp, plane);
        if (c == null) return new Object[]{ "offscreen", null };
        if (!canvasOk) return new Object[]{ "no_canvas", null };

        // 2.76: how far ABOVE the floor the item on this tile is drawn.
        // Zero for anything at ground level, which is nearly everything -
        // so this changes nothing except where it matters.
        int lift = 0;
        if (liftToItems)
        {
            try
            {
                net.runelite.api.ItemLayer il = t.getItemLayer();
                if (il != null && il.getHeight() != 0)
                {
                    net.runelite.api.Point ch =
                            Perspective.localToCanvas(client, lp, plane, il.getHeight());
                    if (ch != null) lift = ch.getY() - c.getY();
                }
            }
            catch (Throwable ignored) { }   // no lift is the safe answer
        }

        int cy = c.getY() + lift;

        // V2.26: localToCanvas happily returns coordinates outside the
        // window, so an explicit viewport test is what actually decides
        // whether this tile is on screen. 2.76: tested at the LIFTED point,
        // because that is the one we are going to click.
        if (clip != null && !clip.contains(c.getX(), cy))
            return new Object[]{ "offscreen", null };

        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        Rectangle r  = poly != null
                ? poly.getBounds()
                : new Rectangle(c.getX() - 16, c.getY() - 16, 32, 32);
        if (lift != 0) r = new Rectangle(r.x, r.y + lift, r.width, r.height);

        // V2.24: largest axis-aligned rectangle that fits inside the tile
        // polygon, computed in canvas space then converted like everything
        // else. Null when the tile is too small on screen to hold one.
        int[] inner = inscribedBox(poly, c.getX(), cy, r, clip);

        int[] box = new int[]{
                (int) ((ox + c.getX())            * dsx),
                (int) ((oy + cy)                  * dsy),
                (int) ((ox + r.x)                 * dsx),
                (int) ((oy + r.y)                 * dsy),
                (int) ((ox + r.x + r.width)       * dsx),
                (int) ((oy + r.y + r.height)      * dsy),
                inner != null ? (int) ((ox + inner[0]) * dsx) : -1,
                inner != null ? (int) ((oy + inner[1]) * dsy) : -1,
                inner != null ? (int) ((ox + inner[2]) * dsx) : -1,
                inner != null ? (int) ((oy + inner[3]) * dsy) : -1
        };
        return new Object[]{ "ok", box };
    }

    // -----------------------------------------------------------------------
    // Agility integration (V2.40).
    // -----------------------------------------------------------------------
    private boolean linkAgility()
    {
        if (agPlugin != null && agObstacles != null) return true;
        if (agLinkTried && agPlugin == null) return false;
        agLinkTried = true;

        try
        {
            for (Plugin p : pluginManager.getPlugins())
            {
                if (p == null) continue;
                if (!"AgilityPlugin".equals(p.getClass().getSimpleName())) continue;
                agPlugin = p;
                break;
            }
            if (agPlugin == null)
            {
                log.info("GEVisualAid: Agility plugin not found");
                return false;
            }

            Class<?> pc = agPlugin.getClass();
            agObstacles = pc.getMethod("getObstacles");
            agMarks     = pc.getMethod("getMarksOfGrace");

            try { agLevel   = pc.getMethod("getAgilityLevel"); } catch (Throwable ignored) { }
            try { agSession = pc.getMethod("getSession");      } catch (Throwable ignored) { }
            // Package-private getter — the only member here that needs it.
            try { agStick = pc.getDeclaredMethod("getStickTile"); agStick.setAccessible(true); }
            catch (Throwable ignored) { }

            ClassLoader cl = pc.getClassLoader();
            try
            {
                Class<?> ob = cl.loadClass("net.runelite.client.plugins.agility.Obstacle");
                agShortcut = ob.getDeclaredMethod("getShortcut");
                agShortcut.setAccessible(true);
            }
            catch (Throwable ignored) { }

            try
            {
                Class<?> obs = cl.loadClass("net.runelite.client.plugins.agility.Obstacles");
                java.lang.reflect.Field f = obs.getDeclaredField("TRAP_OBSTACLE_IDS");
                f.setAccessible(true);
                Object v = f.get(null);
                if (v instanceof java.util.Set) agTrapIds = (java.util.Set<?>) v;
            }
            catch (Throwable ignored) { }

            log.info("GEVisualAid: linked to Agility plugin");
            return true;
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid Agility link failed: {}", t.getMessage());
            agPlugin = null;
            return false;
        }
    }

    // Shape (object clickbox / convex hull) -> the standard ten-int box in
    // desktop pixels, or null when it will not fit on screen.
    private int[] shapeBox(Shape shape, boolean canvasOk, int ox, int oy,
                           double dsx, double dsy, Rectangle clip)
    {
        try
        {
            if (shape == null || !canvasOk) return null;
            Rectangle hb = shape.getBounds();
            if (hb.width <= 0 || hb.height <= 0) return null;

            int cx = hb.x + hb.width  / 2;
            int cy = hb.y + hb.height / 2;
            int[] inner = inscribedBox(shape, cx, cy, hb, clip);
            if (inner == null) return null;

            return new int[]{
                    (int) ((ox + cx)               * dsx),
                    (int) ((oy + cy)               * dsy),
                    (int) ((ox + hb.x)             * dsx),
                    (int) ((oy + hb.y)             * dsy),
                    (int) ((ox + hb.x + hb.width)  * dsx),
                    (int) ((oy + hb.y + hb.height) * dsy),
                    (int) ((ox + inner[0])         * dsx),
                    (int) ((oy + inner[1])         * dsy),
                    (int) ((ox + inner[2])         * dsx),
                    (int) ((oy + inner[3])         * dsy)
            };
        }
        catch (Throwable t) { return null; }
    }

    // -----------------------------------------------------------------------
    // Easy Blast Furnace integration (V2.45).
    // -----------------------------------------------------------------------
    private boolean linkBlastFurnace()
    {
        if (bfHandler != null && bfGetSteps != null) return true;
        if (bfLinkTried && bfPlugin == null) return false;
        bfLinkTried = true;

        try
        {
            for (Plugin p : pluginManager.getPlugins())
            {
                if (p == null) continue;
                if (!"EasyBlastFurnacePlugin".equals(p.getClass().getSimpleName())) continue;
                bfPlugin = p;
                break;
            }
            if (bfPlugin == null)
            {
                log.info("GEVisualAid: Easy Blast Furnace not installed");
                return false;
            }

            Class<?> pc = bfPlugin.getClass();
            try { bfEnabled = pc.getMethod("isEnabled"); } catch (Throwable ignored) { }

            java.lang.reflect.Field fh = pc.getDeclaredField("methodHandler");
            fh.setAccessible(true);
            bfHandler = fh.get(bfPlugin);

            java.lang.reflect.Field fo = pc.getDeclaredField("objectManager");
            fo.setAccessible(true);
            bfObjects = fo.get(bfPlugin);

            if (bfHandler == null) { bfLinkTried = false; return false; }   // not started yet

            bfGetSteps  = bfHandler.getClass().getMethod("getSteps");
            bfGetMethod = bfHandler.getClass().getMethod("getMethod");
            if (bfObjects != null)
                bfObjGet = bfObjects.getClass().getMethod("get", int.class);

            ClassLoader cl = pc.getClassLoader();
            bfTooltip = cl.loadClass("com.toofifty.easyblastfurnace.steps.MethodStep")
                    .getMethod("getTooltip");
            try { bfItemIds = cl.loadClass("com.toofifty.easyblastfurnace.steps.ItemStep")
                    .getMethod("getItemIds"); } catch (Throwable ignored) { }
            try { bfObjectId = cl.loadClass("com.toofifty.easyblastfurnace.steps.ObjectStep")
                    .getMethod("getObjectId"); } catch (Throwable ignored) { }
            try { bfWidgetId = cl.loadClass("com.toofifty.easyblastfurnace.steps.WidgetStep")
                    .getMethod("getPackedWidgetId"); } catch (Throwable ignored) { }
            try { bfWorldPoint = cl.loadClass("com.toofifty.easyblastfurnace.steps.TileStep")
                    .getMethod("getWorldPoint"); } catch (Throwable ignored) { }

            log.info("GEVisualAid: linked to Easy Blast Furnace");
            return true;
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid Blast Furnace link failed: {}", t.getMessage());
            bfPlugin = null;
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Sailing integration (V2.49).
    // -----------------------------------------------------------------------
    private boolean linkSailing()
    {
        if (slTracker != null && slGetBoat != null) return true;
        if (slLinkTried && slTracker == null) return false;
        slLinkTried = true;

        try
        {
            Object plugin = null;
            for (Plugin p : pluginManager.getPlugins())
            {
                if (p == null) continue;
                if (!"SailingPlugin".equals(p.getClass().getSimpleName())) continue;
                plugin = p;
                break;
            }
            if (plugin == null)
            {
                slLinkError = "no SailingPlugin class among the loaded plugins";
                log.info("GEVisualAid: Sailing plugin not installed");
                return false;
            }

            // BoatTracker is a Guice singleton, reached through the plugin's
            // injector rather than a field on the plugin itself.
            ClassLoader cl = plugin.getClass().getClassLoader();
            Class<?> btc = cl.loadClass("com.duckblade.osrs.sailing.features.util.BoatTracker");

            java.lang.reflect.Field inj = Plugin.class.getDeclaredField("injector");
            inj.setAccessible(true);
            Object injector = inj.get(plugin);
            if (injector == null) { slLinkTried = false; return false; }

            // V2.51: look getInstance up on the Injector INTERFACE, not on
            // the runtime class. injector.getClass() is Guice's internal
            // com.google.inject.internal.InjectorImpl, which is not
            // accessible from this package - so although getInstance is
            // public, invoking it threw:
            //   "cannot access a member of class InjectorImpl with
            //    modifiers public"
            // and the whole sailing link failed with a message that read
            // like a missing class rather than an access problem.
            // The field's declared type IS the public interface, so taking
            // the method from there is both correct and version-proof.
            java.lang.reflect.Method getInst = inj.getType()
                    .getMethod("getInstance", Class.class);
            slTracker = getInst.invoke(injector, btc);
            if (slTracker == null) { slLinkTried = false; return false; }

            slGetBoat = btc.getMethod("getBoat");

            Class<?> bc = cl.loadClass("com.duckblade.osrs.sailing.model.Boat");
            slHull     = bc.getMethod("getHull");
            slHelm     = bc.getMethod("getHelm");
            slMast     = bc.getMethod("getSailMast");
            slCargo    = bc.getMethod("getCargoHold");
            slHooks    = bc.getMethod("getSalvagingHooks");
            slCapacity = bc.getMethod("getCargoCapacity");
            slHullTier = bc.getMethod("getHullTier");
            slSizeCls  = bc.getMethod("getSizeClass");
            try { slHookTier = bc.getMethod("getSalvagingHookTiers"); } catch (Throwable ignored) { }

            // V2.50: CargoHoldTracker, for the used/max figures its overlay
            // draws. Both methods are private, hence setAccessible.
            try
            {
                Class<?> chc = cl.loadClass(
                        "com.duckblade.osrs.sailing.features.facilities.CargoHoldTracker");
                slCargoTracker = getInst.invoke(injector, chc);
                if (slCargoTracker != null)
                {
                    slUsedCap = chc.getDeclaredMethod("usedCapacity");
                    slUsedCap.setAccessible(true);
                    slMaxCap = chc.getDeclaredMethod("maxCapacity");
                    slMaxCap.setAccessible(true);
                }
            }
            catch (Throwable t)
            {
                log.info("GEVisualAid: cargo hold count unavailable: {}", t.getMessage());
                slCargoTracker = null;
            }

            slLinkError = "";
            log.info("GEVisualAid: linked to Sailing plugin");
            return true;
        }
        catch (Throwable t)
        {
            slLinkError = t.getClass().getSimpleName() + ": " + t.getMessage();
            log.warn("GEVisualAid Sailing link failed: {}", t.getMessage());
            slTracker = null;
            return false;
        }
    }

    private void appendSailing(StringBuilder sb, WorldView wv, WorldPoint playerLoc,
                               int playerYaw, boolean canvasOk,
                               int ox, int oy, double dsx, double dsy, Rectangle clip)
    {
        boolean linked = false;
        try { linked = (slTracker != null && slGetBoat != null) || linkSailing(); }
        catch (Throwable ignored) { }

        sb.append("sail_plugin_found=").append(linked).append("\n");
        // Newlines would break the key=value format the consumers parse.
        sb.append("sail_link_error=")
          .append(linked ? "" : slLinkError.replace('\n', ' ').replace('\r', ' '))
          .append("\n");
        if (!linked) { sb.append("sail_aboard=false\nsail_hook_count=0\n"); return; }

        Object boat = null;
        try { boat = slGetBoat.invoke(slTracker); } catch (Throwable ignored) { }
        sb.append("sail_aboard=").append(boat != null).append("\n");
        if (boat == null) { sb.append("sail_hook_count=0\n"); return; }

        try
        {
            sb.append("sail_hull_tier=").append(safeStr(slHullTier, boat)).append("\n");
            sb.append("sail_size_class=").append(safeStr(slSizeCls, boat)).append("\n");

            int cap = -1;
            try { cap = (Integer) slCapacity.invoke(boat); } catch (Throwable ignored) { }

            // V2.50: prefer the plugin's own maxCapacity(), falling back to
            // the boat model, then the used count from the same source.
            int used = -1;
            if (slCargoTracker != null)
            {
                try
                {
                    Object mx = slMaxCap.invoke(slCargoTracker);
                    if (mx instanceof Integer && (Integer) mx >= 0) cap = (Integer) mx;
                }
                catch (Throwable ignored) { }
                try
                {
                    Object u = slUsedCap.invoke(slCargoTracker);
                    if (u instanceof Integer) used = (Integer) u;
                }
                catch (Throwable ignored) { }
            }

            sb.append("sail_cargo_capacity=").append(cap).append("\n");
            // -1 means the plugin does not know either — its overlay shows
            // "???" in the same situation. Not an error.
            sb.append("sail_cargo_used=").append(used).append("\n");
            sb.append("sail_cargo_free=")
              .append(used >= 0 && cap >= 0 ? Math.max(0, cap - used) : -1).append("\n");
            sb.append("sail_cargo_percent=")
              .append(used >= 0 && cap > 0 ? (used * 100 / cap) : -1).append("\n");
            sb.append("sail_cargo_full=").append(used >= 0 && cap > 0 && used >= cap).append("\n");
            sb.append("sail_cargo_known=").append(used >= 0).append("\n");

            // ---- named single facilities ----
            emitSailFacility(sb, "sail_cargo",  slCargo, boat, wv, playerLoc, playerYaw,
                    canvasOk, ox, oy, dsx, dsy, clip);
            emitSailFacility(sb, "sail_helm",   slHelm,  boat, wv, playerLoc, playerYaw,
                    canvasOk, ox, oy, dsx, dsy, clip);
            emitSailFacility(sb, "sail_mast",   slMast,  boat, wv, playerLoc, playerYaw,
                    canvasOk, ox, oy, dsx, dsy, clip);
            emitSailFacility(sb, "sail_hull",   slHull,  boat, wv, playerLoc, playerYaw,
                    canvasOk, ox, oy, dsx, dsy, clip);

            // ---- salvaging hooks, with occupancy ----
            List<Object> hooks = new ArrayList<>();
            try
            {
                Object hs = slHooks.invoke(boat);
                if (hs instanceof java.util.Collection)
                    for (Object h : (java.util.Collection<?>) hs) if (h != null) hooks.add(h);
            }
            catch (Throwable ignored) { }

            // Stable left-to-right ordering by world position, so a hook does
            // not change index between ticks the way set iteration would.
            hooks.sort((a, b) ->
            {
                WorldPoint wa = objWorld(a), wbp = objWorld(b);
                if (wa == null || wbp == null) return 0;
                int c = Integer.compare(wa.getX(), wbp.getX());
                return c != 0 ? c : Integer.compare(wa.getY(), wbp.getY());
            });

            String tiers = "";
            try
            {
                if (slHookTier != null)
                {
                    Object t = slHookTier.invoke(boat);
                    if (t != null) tiers = t.toString();
                }
            }
            catch (Throwable ignored) { }
            sb.append("sail_hook_tiers=").append(tiers).append("\n");
            sb.append("sail_hook_count=").append(hooks.size()).append("\n");

            int free = 0;
            for (int i = 0; i < hooks.size() && i < 6; i++)
            {
                String k = "sail_hook_" + i + "_";
                boolean occupied = emitSailObject(sb, k, hooks.get(i), wv, playerLoc,
                        playerYaw, canvasOk, ox, oy, dsx, dsy, clip, true);
                if (!occupied) free++;
            }
            sb.append("sail_hook_free=").append(free).append("\n");
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid sailing read error: {}", t.getMessage());
        }
    }

    private String safeStr(java.lang.reflect.Method m, Object target)
    {
        try
        {
            if (m == null) return "";
            Object v = m.invoke(target);
            return v == null ? "" : v.toString();
        }
        catch (Throwable t) { return ""; }
    }

    private WorldPoint objWorld(Object o)
    {
        try { return o instanceof TileObject ? ((TileObject) o).getWorldLocation() : null; }
        catch (Throwable t) { return null; }
    }

    private void emitSailFacility(StringBuilder sb, String prefix,
                                  java.lang.reflect.Method getter, Object boat,
                                  WorldView wv, WorldPoint playerLoc, int playerYaw,
                                  boolean canvasOk, int ox, int oy,
                                  double dsx, double dsy, Rectangle clip)
    {
        Object o = null;
        try { if (getter != null) o = getter.invoke(boat); } catch (Throwable ignored) { }
        if (o == null)
        {
            sb.append(prefix).append("_present=false\n");
            sb.append(prefix).append("_state=not_found\n");
            appendBox(sb, prefix + "_", null);
            return;
        }
        sb.append(prefix).append("_present=true\n");
        emitSailObject(sb, prefix + "_", o, wv, playerLoc, playerYaw,
                canvasOk, ox, oy, dsx, dsy, clip, false);
    }

    // Emits one boat facility. Returns whether it is occupied, which is only
    // computed when wantOccupancy is set.
    private boolean emitSailObject(StringBuilder sb, String k, Object o,
                                   WorldView wv, WorldPoint playerLoc, int playerYaw,
                                   boolean canvasOk, int ox, int oy,
                                   double dsx, double dsy, Rectangle clip,
                                   boolean wantOccupancy)
    {
        boolean occupied = false;
        try
        {
            if (!(o instanceof TileObject)) { sb.append(k).append("state=error\n"); return false; }
            TileObject to = (TileObject) o;
            WorldPoint w = to.getWorldLocation();

            String state = "offscreen";
            int[]  box   = null;
            Shape shape = null;
            try { shape = to.getClickbox(); } catch (Throwable ignored) { }
            if (shape == null && to instanceof GameObject)
                try { shape = ((GameObject) to).getConvexHull(); } catch (Throwable ignored) { }
            box = shapeBox(shape, canvasOk, ox, oy, dsx, dsy, clip);
            if (box != null) state = "ok";
            else if (w != null)
            {
                Object[] rt = resolveTile(wv, w.getX(), w.getY(), w.getPlane(),
                        canvasOk, ox, oy, dsx, dsy, clip);
                box   = (int[]) rt[1];
                state = box != null ? "ok_tile" : (String) rt[0];
            }

            Object[] bear = (playerLoc != null && w != null)
                    ? bearingTo(playerLoc, w.getX(), w.getY(), playerYaw)
                    : new Object[]{ -1, -1, -999, "UNKNOWN" };

            sb.append(k).append("id=").append(to.getId()).append("\n");
            sb.append(k).append("world_x=").append(w == null ? -1 : w.getX()).append("\n");
            sb.append(k).append("world_y=").append(w == null ? -1 : w.getY()).append("\n");
            sb.append(k).append("plane=").append(w == null ? -1 : w.getPlane()).append("\n");
            sb.append(k).append("dist_tiles=").append(bear[0]).append("\n");
            sb.append(k).append("state=").append(state).append("\n");
            sb.append(k).append("visible=").append(box != null).append("\n");
            appendBox(sb, k, box);
            sb.append(k).append("direction=").append(bear[3]).append("\n");

            if (wantOccupancy && w != null)
            {
                // Nearest actor to the facility. Proximity, not a true
                // "is manning it" flag, which the client does not expose.
                String  who     = "";
                boolean isSelf  = false;
                int     bestD   = Integer.MAX_VALUE;
                try
                {
                    if (client.getLocalPlayer() != null)
                    {
                        WorldPoint me = client.getLocalPlayer().getWorldLocation();
                        if (me != null)
                        {
                            int d = chebyshev(me, w);
                            if (d <= 1) { bestD = d; who = "you"; isSelf = true; }
                        }
                    }
                    if (wv != null)
                        for (net.runelite.api.Player p : wv.players())
                        {
                            if (p == null || p == client.getLocalPlayer()) continue;
                            WorldPoint pw = p.getWorldLocation();
                            if (pw == null) continue;
                            int d = chebyshev(pw, w);
                            if (d > 1 || d >= bestD) continue;
                            bestD  = d;
                            who    = p.getName() == null ? "player" : p.getName();
                            isSelf = false;
                        }
                    if (wv != null)
                        for (NPC n : wv.npcs())
                        {
                            if (n == null) continue;
                            WorldPoint nw = n.getWorldLocation();
                            if (nw == null) continue;
                            int d = chebyshev(nw, w);
                            if (d > 1 || d >= bestD) continue;
                            bestD  = d;
                            who    = n.getName() == null ? "npc" : n.getName();
                            isSelf = false;
                        }
                }
                catch (Throwable ignored) { }

                occupied = bestD != Integer.MAX_VALUE;
                sb.append(k).append("occupied=").append(occupied).append("\n");
                sb.append(k).append("occupant=").append(who).append("\n");
                sb.append(k).append("occupant_is_you=").append(isSelf).append("\n");
                sb.append(k).append("occupant_dist=")
                  .append(occupied ? bestD : -1).append("\n");
            }
        }
        catch (Throwable ignored) { sb.append(k).append("state=error\n"); }
        return occupied;
    }

    // -----------------------------------------------------------------------
    // Bank quantities for a watch list (V2.48). Whole-bank export would be
    // far too large, so only the configured items are reported.
    // -----------------------------------------------------------------------
    private void parseBankList(String spec)
    {
        if (spec == null) spec = "";
        if (spec.equals(bankSpecRaw)) return;
        bankSpecRaw = spec;
        bankNames.clear();
        bankMatch.clear();
        bankIds.clear();

        for (String tok : spec.split("[,;\r\n]+"))
        {
            tok = tok.trim();
            if (tok.isEmpty()) continue;
            try
            {
                String label, value;
                int eq = tok.indexOf('=');
                if (eq > 0) { label = tok.substring(0, eq).trim(); value = tok.substring(eq + 1).trim(); }
                else        { label = tok;                          value = tok; }

                String key = sanitiseKey(label);
                if (key.isEmpty() || bankNames.contains(key)) continue;

                bankNames.add(key);
                if (value.matches("\\d+")) { bankIds.add(Integer.parseInt(value)); bankMatch.add(""); }
                else                       { bankIds.add(-1); bankMatch.add(value.toLowerCase()); }
            }
            catch (Exception ignored) { }
            if (bankNames.size() >= 24) break;
        }
        log.info("GEVisualAid v2.48 watching {} bank item(s): {}", bankNames.size(), bankNames);
    }

    private void appendBank(StringBuilder sb)
    {
        try { parseBankList(config.bankWatchList()); } catch (Throwable ignored) { }

        // The client keeps the bank container populated after it has been
        // opened once, so counts stay readable away from a bank.
        ItemContainer bank = null;
        try { bank = client.getItemContainer(InventoryID.BANK); } catch (Throwable ignored) { }

        boolean open = false;
        try
        {
            Widget w = client.getWidget(12, 0);
            open = w != null && !w.isHidden();
        }
        catch (Throwable ignored) { }

        sb.append("bank_available=").append(bank != null).append("\n");
        sb.append("bank_open=").append(open).append("\n");
        sb.append("bank_stale=").append(bank != null && !open).append("\n");
        sb.append("bank_watch_count=").append(bankNames.size()).append("\n");
        if (bank == null || bankNames.isEmpty()) return;

        int[]     qty = new int[bankNames.size()];
        boolean[] ph  = new boolean[bankNames.size()];   // 2.64

        // 2.64 - resolve each id-based entry to its NAME once. A placeholder
        // has a DIFFERENT id from the real item, so an id-matched entry can
        // never recognise its own placeholder by id - only by the name the
        // placeholder carries. Done here rather than in the item loop so it
        // is one lookup per watch entry, not one per bank slot.
        String[] want = new String[bankNames.size()];
        for (int i = 0; i < bankNames.size(); i++)
        {
            want[i] = bankIds.get(i) >= 0
                    ? safeItemName(bankIds.get(i)).toLowerCase()
                    : bankMatch.get(i);
        }

        try
        {
            for (Item it : bank.getItems())
            {
                if (it == null || it.getId() <= 0 || it.getQuantity() <= 0) continue;
                // 2.63 - an emptied bank slot keeps a PLACEHOLDER: a
                // distinct item id carrying the real item name, which the
                // exact-name match below happily counted as 1. That made
                // an empty bank indistinguishable from one item left.
                // 2.64 - they are still not counted, but they are no longer
                // thrown away: a placeholder is POSITIVE EVIDENCE that this
                // watch entry names a real item, because the placeholder
                // carries that item's own name. See bank_<name>_placeholder.
                boolean placeholder = isBankPlaceholder(it.getId());
                String nm = null;
                for (int i = 0; i < bankNames.size(); i++)
                {
                    if (!placeholder && bankIds.get(i) >= 0)
                    {
                        if (bankIds.get(i) == it.getId()) qty[i] += it.getQuantity();
                        continue;
                    }
                    if (nm == null) nm = safeItemName(it.getId()).toLowerCase();
                    // Exact name match, so "Logs" does not also collect
                    // "Oak logs" and "Yew logs".
                    if (!nm.equals(want[i])) continue;
                    if (placeholder) ph[i] = true;
                    else             qty[i] += it.getQuantity();
                }
            }
        }
        catch (Throwable t) { log.warn("GEVisualAid bank read error: {}", t.getMessage()); }

        StringBuilder names = new StringBuilder();
        for (int i = 0; i < bankNames.size(); i++)
        {
            if (names.length() > 0) names.append(",");
            names.append(bankNames.get(i));
            sb.append("bank_").append(bankNames.get(i)).append("=").append(qty[i]).append("\n");
            // 2.64 - "this name matches a real item, and the bank has a slot
            // reserved for it". A consumer seeing 0 alongside placeholder=true
            // knows the count is an EMPTY BANK and not a mistyped watch entry.
            // Those two are otherwise identical readings, and telling them
            // apart previously cost a wasted withdraw click.
            sb.append("bank_").append(bankNames.get(i)).append("_placeholder=").append(ph[i]).append("\n");
        }
        sb.append("bank_watch_names=").append(names).append("\n");
    }

    // -----------------------------------------------------------------------
    // Incoming attack style (V2.48).
    //
    // The client does not label attack styles, so this is inference, and it
    // is reported as such. A projectile aimed at the player means ranged or
    // magic; an attacker adjacent with no projectile means melee.
    // -----------------------------------------------------------------------
    private void appendAttackStyle(StringBuilder sb)
    {
        long now = System.currentTimeMillis();
        long since = attLastProjectileMs == 0 ? -1 : (now - attLastProjectileMs);

        sb.append("att_projectile_id=").append(attLastProjectile).append("\n");
        sb.append("att_projectile_age_ms=").append(since).append("\n");
        sb.append("att_projectile_count=").append(attProjectileCount).append("\n");

        // Who is attacking us, and how close.
        String  byName = "";
        int     byId   = -1, byDist = -1;
        boolean melee  = false;
        try
        {
            WorldPoint me = client.getLocalPlayer() == null
                    ? null : client.getLocalPlayer().getWorldLocation();
            WorldView wv = client.getTopLevelWorldView();
            if (me != null && wv != null)
            {
                for (NPC n : wv.npcs())
                {
                    if (n == null) continue;
                    if (n.getInteracting() != client.getLocalPlayer()) continue;
                    WorldPoint w = n.getWorldLocation();
                    if (w == null) continue;
                    int d = chebyshev(me, w);
                    if (byDist >= 0 && d >= byDist) continue;
                    byDist = d;
                    byId   = safeNpcId(n);
                    byName = n.getName() == null ? "" : n.getName();
                }
                // Adjacent, or within reach of a halberd-length attacker.
                melee = byDist >= 0 && byDist <= 2;
            }
        }
        catch (Throwable ignored) { }

        sb.append("att_attacker_name=").append(byName).append("\n");
        sb.append("att_attacker_id=").append(byId).append("\n");
        sb.append("att_attacker_dist=").append(byDist).append("\n");
        sb.append("att_under_attack=").append(byDist >= 0).append("\n");

        // Recent means within roughly two ticks, so a projectile from the
        // previous attack does not linger as the current style.
        boolean recentProj = since >= 0 && since <= 1500;

        String style, conf;
        if (recentProj)          { style = "RANGED_OR_MAGIC"; conf = "projectile"; }
        else if (melee)          { style = "MELEE";           conf = "adjacent_no_projectile"; }
        else if (byDist >= 0)    { style = "UNKNOWN";         conf = "attacker_out_of_melee_range"; }
        else                     { style = "NONE";            conf = "no_attacker"; }

        sb.append("att_style=").append(style).append("\n");
        sb.append("att_basis=").append(conf).append("\n");
    }

    // -----------------------------------------------------------------------
    // Active prayers (V2.48). Straight from isPrayerActive over the Prayer
    // enum, so no varbit table is needed here.
    // -----------------------------------------------------------------------
    private void appendPrayers(StringBuilder sb)
    {
        StringBuilder on = new StringBuilder();
        int count = 0;
        String overhead = "";
        try
        {
            for (Prayer p : Prayer.values())
            {
                boolean active;
                try { active = client.isPrayerActive(p); } catch (Throwable t) { continue; }
                if (!active) continue;
                count++;
                String nm = p.name();
                if (on.length() > 0) on.append(",");
                on.append(nm);
                if (nm.startsWith("PROTECT_FROM_") || nm.equals("RETRIBUTION")
                        || nm.equals("REDEMPTION") || nm.equals("SMITE"))
                    overhead = nm;
            }
        }
        catch (Throwable ignored) { }

        sb.append("pray_active_count=").append(count).append("\n");
        sb.append("pray_active=").append(on).append("\n");
        sb.append("pray_overhead=").append(overhead).append("\n");

        int quick = -1;
        try { quick = client.getVarbitValue(4102); } catch (Throwable ignored) { }
        sb.append("pray_quick_active=").append(quick == 1).append("\n");
    }

    // -----------------------------------------------------------------------
    // Rune counting and remaining casts (V2.47).
    // -----------------------------------------------------------------------
    private void appendRunes(StringBuilder sb)
    {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Integer> pure   = new HashMap<>();
        Map<String, Boolean> inf    = new HashMap<>();

        try
        {
            // ---- inventory ----
            Map<Integer, Integer> inv = new HashMap<>();
            try
            {
                ItemContainer c = client.getItemContainer(InventoryID.INV);
                if (c != null)
                    for (Item it : c.getItems())
                        if (it != null && it.getId() > 0)
                            inv.merge(it.getId(), it.getQuantity(), Integer::sum);
            }
            catch (Throwable ignored) { }

            // ---- rune pouch ----
            // Type varbits hold a rune ENUM index, not an item id, so they are
            // resolved through the client's rune enum.
            int[][] pouch = {
                    { RP_TYPE_1, RP_QTY_1 }, { RP_TYPE_2, RP_QTY_2 }, { RP_TYPE_3, RP_QTY_3 }
            };
            int pouchTotal = 0;
            for (int[] slot : pouch)
            {
                try
                {
                    int typeIdx = client.getVarbitValue(slot[0]);
                    int qty     = client.getVarbitValue(slot[1]);
                    if (typeIdx <= 0 || qty <= 0) continue;
                    int itemId = runeIdFromPouchType(typeIdx);
                    if (itemId <= 0) continue;
                    inv.merge(itemId, qty, Integer::sum);
                    pouchTotal += qty;
                }
                catch (Throwable ignored) { }
            }
            sb.append("rune_pouch_total=").append(pouchTotal).append("\n");

            // ---- tally plain runes ----
            for (String[] r : RUNE_TYPES)
            {
                int id = Integer.parseInt(r[1]);
                int n  = inv.getOrDefault(id, 0);
                counts.merge(r[0], n, Integer::sum);
                pure.merge(r[0], n, Integer::sum);
            }

            // ---- combination runes credit BOTH elements ----
            StringBuilder combos = new StringBuilder();
            for (String[] c : RUNE_COMBOS)
            {
                int n = inv.getOrDefault(Integer.parseInt(c[0]), 0);
                if (n <= 0) continue;
                counts.merge(c[2], n, Integer::sum);
                counts.merge(c[3], n, Integer::sum);
                if (combos.length() > 0) combos.append(",");
                combos.append(c[1]).append(":").append(n);
            }
            sb.append("rune_combination=").append(combos).append("\n");

            // ---- equipped staff granting unlimited runes ----
            try
            {
                ItemContainer eq = client.getItemContainer(InventoryID.WORN);
                if (eq != null)
                    for (Item it : eq.getItems())
                    {
                        if (it == null || it.getId() <= 0) continue;
                        String nm = safeItemName(it.getId()).toLowerCase();
                        if (nm.isEmpty()) continue;
                        // A staff or tome supplies its element without limit.
                        boolean supplies = nm.contains("staff") || nm.contains("tome");
                        if (!supplies) continue;
                        for (String el : new String[]{ "air", "water", "earth", "fire" })
                            if (nm.contains(el)) inf.put(el, Boolean.TRUE);
                        // Combination staves cover two elements each.
                        if (nm.contains("mist"))  { inf.put("air",   true); inf.put("water", true); }
                        if (nm.contains("dust"))  { inf.put("air",   true); inf.put("earth", true); }
                        if (nm.contains("mud"))   { inf.put("water", true); inf.put("earth", true); }
                        if (nm.contains("smoke")) { inf.put("air",   true); inf.put("fire",  true); }
                        if (nm.contains("steam")) { inf.put("water", true); inf.put("fire",  true); }
                        if (nm.contains("lava"))  { inf.put("earth", true); inf.put("fire",  true); }
                    }
            }
            catch (Throwable ignored) { }
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid rune count error: {}", t.getMessage());
        }

        // ---- emit ----
        StringBuilder held = new StringBuilder();
        for (String[] r : RUNE_TYPES)
        {
            String k = r[0];
            boolean unlimited = Boolean.TRUE.equals(inf.get(k));
            int n = unlimited ? 999999 : counts.getOrDefault(k, 0);
            if (n <= 0) continue;
            if (held.length() > 0) held.append(",");
            held.append(k);
            sb.append("rune_").append(k).append("=").append(n).append("\n");
            sb.append("rune_").append(k).append("_pure=")
              .append(pure.getOrDefault(k, 0)).append("\n");
            if (unlimited) sb.append("rune_").append(k).append("_unlimited=true\n");
        }
        sb.append("rune_held=").append(held).append("\n");

        // ---- remaining casts for the configured spell ----
        String spec = "";
        try { spec = config.castSpell(); } catch (Throwable ignored) { }
        if (spec == null) spec = "";
        spec = spec.trim();

        sb.append("rune_cast_spell=").append(spec).append("\n");
        if (spec.isEmpty()) { sb.append("rune_cast_remaining=-1\n"); return; }

        int    best    = Integer.MAX_VALUE;
        String limiter = "";
        try
        {
            for (String tok : spec.split("[,;]+"))
            {
                tok = tok.trim();
                if (tok.isEmpty()) continue;
                int c = tok.indexOf(':');
                if (c <= 0) continue;
                String type = tok.substring(0, c).trim().toLowerCase();
                int    need = Integer.parseInt(tok.substring(c + 1).trim());
                if (need <= 0) continue;

                int have = Boolean.TRUE.equals(inf.get(type))
                        ? 999999 : counts.getOrDefault(type, 0);
                int casts = have / need;
                if (casts < best) { best = casts; limiter = type; }
            }
        }
        catch (Throwable t) { best = Integer.MAX_VALUE; }

        sb.append("rune_cast_remaining=")
          .append(best == Integer.MAX_VALUE ? -1 : best).append("\n");
        sb.append("rune_cast_limiter=").append(limiter).append("\n");
    }

    // Rune pouch type varbits store an index into the game's rune enum
    // rather than an item id, so it has to be resolved.
    private int runeIdFromPouchType(int typeIdx)
    {
        try
        {
            EnumComposition e = client.getEnum(982);   // rune pouch rune enum
            if (e == null) return -1;
            return e.getIntValue(typeIdx);
        }
        catch (Throwable t) { return -1; }
    }

    // -----------------------------------------------------------------------
    // Blast Furnace game state (V2.46). Pure varbit reads — independent of
    // both the Easy Blast Furnace plugin and RuneLite's own Blast Furnace
    // plugin, since the varbits are what those read too.
    // -----------------------------------------------------------------------
    private void appendBlastFurnaceState(StringBuilder sb)
    {
        // ---- coffer ----
        int coffer = -1;
        try { coffer = client.getVarbitValue(BF_VB_COFFER); } catch (Throwable ignored) { }
        sb.append("bf_coffer_gp=").append(coffer).append("\n");
        long cofferSecs = coffer < 0 ? -1
                : (long) (coffer / BF_COST_PER_HOUR * 3600);
        sb.append("bf_coffer_seconds=").append(cofferSecs).append("\n");
        sb.append("bf_coffer_time=").append(cofferSecs < 0 ? "" : formatHms(cofferSecs)).append("\n");

        // ---- dispenser ----
        // BLAST_FURNACE_BARS_HOT: 0 idle, 1 ore cooking, 2 bars hot (ice
        // gloves needed), 3 bars cooled.
        int disp = -1;
        try { disp = client.getVarbitValue(BF_VB_BARS_HOT); } catch (Throwable ignored) { }
        String dispName;
        switch (disp)
        {
            case 0:  dispName = "empty";      break;
            case 1:  dispName = "cooking";    break;
            case 2:  dispName = "bars_hot";   break;
            case 3:  dispName = "bars_cool";  break;
            default: dispName = "unknown";    break;
        }
        sb.append("bf_dispenser_state=").append(disp).append("\n");
        sb.append("bf_dispenser_label=").append(dispName).append("\n");
        sb.append("bf_bars_ready=").append(disp == 2 || disp == 3).append("\n");
        sb.append("bf_needs_ice_gloves=").append(disp == 2).append("\n");
        sb.append("bf_belt_busy=").append(disp == 1).append("\n");

        try { sb.append("bf_coal_required=").append(client.getVarbitValue(BF_VB_COAL_NEEDED)).append("\n"); }
        catch (Throwable t) { sb.append("bf_coal_required=-1\n"); }
        try { sb.append("bf_temperature=").append(client.getVarbitValue(BF_VB_TEMPERATURE)).append("\n"); }
        catch (Throwable t) { sb.append("bf_temperature=-1\n"); }
        try { sb.append("bf_pipe_broken=").append(client.getVarbitValue(BF_VB_BROKEN_PIPE) > 0).append("\n"); }
        catch (Throwable t) { sb.append("bf_pipe_broken=false\n"); }
        try { sb.append("bf_fuel_low=").append(client.getVarbitValue(BF_VB_FUEL_LOW) > 0).append("\n"); }
        catch (Throwable t) { sb.append("bf_fuel_low=false\n"); }

        // ---- furnace contents: only non-zero entries, to keep this small ----
        StringBuilder names = new StringBuilder();
        StringBuilder lines = new StringBuilder();
        int kinds = 0, ores = 0, bars = 0;
        for (String[] c : BF_CONTENTS)
        {
            int qty;
            try { qty = client.getVarbitValue(Integer.parseInt(c[1])); }
            catch (Throwable t) { continue; }
            if (qty <= 0) continue;
            kinds++;
            if (c[0].endsWith("_ore")) ores += qty; else bars += qty;
            if (names.length() > 0) names.append(",");
            names.append(c[0]);
            lines.append("bf_contents_").append(c[0]).append("=").append(qty).append("\n");
        }
        sb.append("bf_contents_count=").append(kinds).append("\n");
        sb.append("bf_contents_names=").append(names).append("\n");
        sb.append("bf_ore_total=").append(ores).append("\n");
        sb.append("bf_bar_total=").append(bars).append("\n");
        sb.append(lines);

        // ---- foreman fee ----
        // Required under Smithing 60, lasts ten minutes, tracked by
        // RuneLite's ForemanTimer infobox.
        int smith = -1;
        try { smith = client.getRealSkillLevel(Skill.SMITHING); } catch (Throwable ignored) { }
        boolean needed = smith >= 0 && smith < 60;
        sb.append("bf_smithing_level=").append(smith).append("\n");
        sb.append("bf_foreman_needed=").append(needed).append("\n");

        long left = -1;
        try
        {
            if (infoBoxManager != null)
            {
                List<InfoBox> boxes = infoBoxManager.getInfoBoxes();
                if (boxes != null)
                    for (InfoBox ib : boxes)
                    {
                        if (ib == null) continue;
                        if (!"ForemanTimer".equals(ib.getClass().getSimpleName())) continue;
                        if (!(ib instanceof Timer)) continue;
                        Instant end = ((Timer) ib).getEndTime();
                        if (end == null) continue;
                        left = Math.max(0, Duration.between(Instant.now(), end).getSeconds());
                    }
            }
        }
        catch (Throwable ignored) { }
        sb.append("bf_foreman_seconds=").append(left).append("\n");
        // Unpaid only counts as a problem for accounts that actually need it.
        sb.append("bf_foreman_paid=").append(left > 0).append("\n");
        sb.append("bf_foreman_expired=").append(needed && left <= 0).append("\n");
    }

    private String formatHms(long seconds)
    {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h > 0 ? (h + "h " + m + "m " + s + "s") : (m + "m " + s + "s");
    }

    private void appendBlastFurnace(StringBuilder sb, WorldView wv, WorldPoint playerLoc,
                                    int playerYaw, boolean canvasOk,
                                    int ox, int oy, double dsx, double dsy, Rectangle clip)
    {
        boolean linked = false;
        try { linked = (bfHandler != null && bfGetSteps != null) || linkBlastFurnace(); }
        catch (Throwable ignored) { }

        sb.append("bf_plugin_found=").append(linked).append("\n");
        if (!linked) { sb.append("bf_step_count=0\n"); return; }

        boolean atFurnace = false;
        try { if (bfEnabled != null) atFurnace = Boolean.TRUE.equals(bfEnabled.invoke(bfPlugin)); }
        catch (Throwable ignored) { }
        sb.append("bf_at_furnace=").append(atFurnace).append("\n");

        try
        {
            Object m = bfGetMethod.invoke(bfHandler);
            sb.append("bf_method=")
              .append(m == null ? "" : m.getClass().getSimpleName()).append("\n");
        }
        catch (Throwable ignored) { sb.append("bf_method=\n"); }

        Object[] steps = null;
        try
        {
            Object s = bfGetSteps.invoke(bfHandler);
            if (s instanceof Object[]) steps = (Object[]) s;
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid blast furnace read error: {}", t.getMessage());
        }

        int n = steps == null ? 0 : steps.length;
        sb.append("bf_step_count=").append(n).append("\n");
        if (n == 0) return;

        for (int i = 0; i < n && i < 6; i++)
            emitBlastFurnaceStep(sb, "bf_step_" + i + "_", steps[i],
                    wv, playerLoc, playerYaw, canvasOk, ox, oy, dsx, dsy, clip);
    }

    private void emitBlastFurnaceStep(StringBuilder sb, String k, Object step,
                                      WorldView wv, WorldPoint playerLoc, int playerYaw,
                                      boolean canvasOk, int ox, int oy,
                                      double dsx, double dsy, Rectangle clip)
    {
        String  type    = "unknown";
        String  tooltip = "";
        String  state   = "not_found";
        String  detail  = "";
        int[]   box     = null;
        int     wx = -1, wy = -1, wp = -1;

        try
        {
            if (step == null) { sb.append(k).append("type=none\n"); return; }
            try
            {
                Object t = bfTooltip.invoke(step);
                tooltip = t == null ? "" : t.toString().replace("\n", " ");
            }
            catch (Throwable ignored) { }

            String cn = step.getClass().getSimpleName();

            if ("ObjectStep".equals(cn) && bfObjectId != null)
            {
                type = "object";
                int id = (Integer) bfObjectId.invoke(step);
                detail = Integer.toString(id);

                // Through the plugin's own ObjectManager, which caches the
                // conveyor/dispenser/chest as they spawn.
                Object go = bfObjGet == null ? null : bfObjGet.invoke(bfObjects, id);
                if (go instanceof TileObject)
                {
                    TileObject to = (TileObject) go;
                    WorldPoint w = to.getWorldLocation();
                    if (w != null) { wx = w.getX(); wy = w.getY(); wp = w.getPlane(); }

                    Shape shape = null;
                    try { shape = to.getClickbox(); } catch (Throwable ignored) { }
                    if (shape == null && to instanceof GameObject)
                        try { shape = ((GameObject) to).getConvexHull(); } catch (Throwable ignored) { }
                    box = shapeBox(shape, canvasOk, ox, oy, dsx, dsy, clip);
                    if (box != null) state = "ok";
                    else if (w != null)
                    {
                        Object[] rt = resolveTile(wv, wx, wy, wp,
                                canvasOk, ox, oy, dsx, dsy, clip);
                        box   = (int[]) rt[1];
                        state = box != null ? "ok_tile" : (String) rt[0];
                    }
                }
            }
            else if ("TileStep".equals(cn) && bfWorldPoint != null)
            {
                type = "tile";
                Object w = bfWorldPoint.invoke(step);
                if (w instanceof WorldPoint)
                {
                    wx = ((WorldPoint) w).getX();
                    wy = ((WorldPoint) w).getY();
                    wp = ((WorldPoint) w).getPlane();
                    detail = wx + ":" + wy;
                    Object[] rt = resolveTile(wv, wx, wy, wp,
                            canvasOk, ox, oy, dsx, dsy, clip);
                    box   = (int[]) rt[1];
                    state = box != null ? "ok" : (String) rt[0];
                }
            }
            else if ("WidgetStep".equals(cn) && bfWidgetId != null)
            {
                type = "widget";
                int packed = (Integer) bfWidgetId.invoke(step);
                int group  = packed >>> 16;          // packed = group << 16 | child
                int child  = packed & 0xFFFF;
                detail = group + ":" + child;
                Rectangle r = widgetBounds(client.getWidget(group, child));
                box   = boundsBox(r, canvasOk, ox, oy, dsx, dsy);
                state = box != null ? "ok" : "hidden";
            }
            else if ("ItemStep".equals(cn) && bfItemIds != null)
            {
                type = "item";
                int[] ids = (int[]) bfItemIds.invoke(step);
                StringBuilder d = new StringBuilder();
                if (ids != null)
                    for (int id : ids)
                    {
                        if (d.length() > 0) d.append("|");
                        d.append(id);
                    }
                detail = d.toString();

                Rectangle r = findItemWidget(ids);
                box   = boundsBox(r, canvasOk, ox, oy, dsx, dsy);
                state = box != null ? "ok" : "not_visible";
            }
        }
        catch (Throwable ignored) { state = "error"; }

        Object[] bear = (playerLoc != null && wx >= 0)
                ? bearingTo(playerLoc, wx, wy, playerYaw)
                : new Object[]{ -1, -1, -999, "UNKNOWN" };

        sb.append(k).append("type=").append(type).append("\n");
        sb.append(k).append("tooltip=").append(tooltip).append("\n");
        sb.append(k).append("detail=").append(detail).append("\n");
        sb.append(k).append("world_x=").append(wx).append("\n");
        sb.append(k).append("world_y=").append(wy).append("\n");
        sb.append(k).append("plane=").append(wp).append("\n");
        sb.append(k).append("state=").append(state).append("\n");
        sb.append(k).append("visible=").append(box != null).append("\n");
        appendBox(sb, k, box);
        sb.append(k).append("direction=").append(bear[3]).append("\n");
    }

    // Bounds of a widget, or null when hidden / absent.
    private Rectangle widgetBounds(Widget w)
    {
        try
        {
            if (w == null || w.isHidden()) return null;
            Rectangle r = w.getBounds();
            return (r == null || r.width <= 0 || r.height <= 0) ? null : r;
        }
        catch (Throwable t) { return null; }
    }

    // Canvas-space Rectangle -> the standard ten-int desktop-pixel box.
    private int[] boundsBox(Rectangle r, boolean canvasOk,
                            int ox, int oy, double dsx, double dsy)
    {
        if (r == null || !canvasOk) return null;
        int cx = r.x + r.width / 2;
        int cy = r.y + r.height / 2;
        // Inset a couple of pixels so a click never lands on the border.
        int ix = r.width  > 6 ? r.x + 3 : r.x;
        int iy = r.height > 6 ? r.y + 3 : r.y;
        int iw = r.width  > 6 ? r.width  - 6 : r.width;
        int ih = r.height > 6 ? r.height - 6 : r.height;
        return new int[]{
                (int) ((ox + cx)             * dsx),
                (int) ((oy + cy)             * dsy),
                (int) ((ox + r.x)            * dsx),
                (int) ((oy + r.y)            * dsy),
                (int) ((ox + r.x + r.width)  * dsx),
                (int) ((oy + r.y + r.height) * dsy),
                (int) ((ox + ix)             * dsx),
                (int) ((oy + iy)             * dsy),
                (int) ((ox + ix + iw)        * dsx),
                (int) ((oy + iy + ih)        * dsy)
        };
    }

    // Find the first widget child holding any of these item ids. Scans the
    // usual item containers: inventory, bank inventory, bank items.
    private static final int[][] BF_ITEM_CONTAINERS = {
            { 149, 0 }, { 15, 3 }, { 12, 13 }, { 467, 0 }, { 12, 12 }
    };

    // v2.66: BF_ITEM_CONTAINERS with a role attached, so a hit can say WHERE
    // it was found. Third field: 1 = bank, 0 = inventory.
    private static final int[][] IB_CONTAINERS = {
            { 149, 0, 0 },   // inventory
            { 467, 0, 0 },   // inventory, alternate interface
            { 15,  3, 0 },   // the bank's own inventory panel
            { 12, 13, 1 },   // bank items
            { 12, 12, 1 }
    };

    // v2.66: ONE pass over every item container, done once per caller rather
    // than once per filter entry -- an open bank has hundreds of children and
    // this runs on the client thread every tick.
    //
    // Returns rows of { itemId, Rectangle, where }, in container order.
    //
    // THE INTERSECTION TEST IS THE POINT. A bank child scrolled out of view
    // is NOT hidden and its bounds are NOT empty -- they are simply somewhere
    // else, and on a tall bank that can be off over the game world. Emitting
    // those bounds hands the caller a click that walks the player instead of
    // withdrawing. So the child must overlap its own container to count.
    private List<Object[]> ibVisibleItems()
    {
        List<Object[]> out = new ArrayList<>();
        try
        {
            for (int[] c : IB_CONTAINERS)
            {
                Widget cont = client.getWidget(c[0], c[1]);
                if (cont == null || cont.isHidden()) continue;

                Rectangle cb = null;
                try { cb = cont.getBounds(); } catch (Throwable ignored) { }

                Widget[] kids = cont.getDynamicChildren();
                if (kids == null) continue;

                for (Widget kid : kids)
                {
                    if (kid == null || kid.isHidden()) continue;
                    int id;
                    try { id = kid.getItemId(); } catch (Throwable t) { continue; }
                    if (id <= 0) continue;

                    Rectangle r = kid.getBounds();
                    if (r == null || r.width <= 0 || r.height <= 0) continue;
                    if (cb != null && cb.width > 0 && cb.height > 0 && !cb.intersects(r))
                        continue;   // scrolled out of its own container

                    out.add(new Object[]{ id, r, c[2] == 1 ? "bank" : "inventory" });
                }
            }
        }
        catch (Throwable ignored) { }
        return out;
    }

    private Rectangle findItemWidget(int[] ids)
    {
        if (ids == null || ids.length == 0) return null;
        for (Object[] row : ibVisibleItems())
            for (int id : ids)
                if (((Integer) row[0]) == id) return (Rectangle) row[1];
        return null;
    }

    // -----------------------------------------------------------------------
    // Carried items (v2.66).
    //
    // Counts come from the ItemContainer and are therefore honest regardless
    // of what the bank interface is showing. The click box comes from the
    // widget and is only present when the item is genuinely on screen. See
    // the 2.66 changelog note for why those two must not be merged.
    // -----------------------------------------------------------------------
    private void appendItemBoxes(StringBuilder sb, boolean canvasOk,
                                 int ox, int oy, double dsx, double dsy)
    {
        List<EntityFilter> fs = new ArrayList<>();
        try { fs = parseEntityFilter(esBoxes); } catch (Throwable ignored) { }
        if (fs.isEmpty()) { sb.append("ib_count=0\n"); return; }

        ItemContainer inv = null, bank = null, worn = null;
        try { inv  = client.getItemContainer(InventoryID.INV);  } catch (Throwable ignored) { }
        try { bank = client.getItemContainer(InventoryID.BANK); } catch (Throwable ignored) { }
        try { worn = client.getItemContainer(InventoryID.WORN); } catch (Throwable ignored) { }

        // The client keeps the bank container populated after the bank has
        // been opened once, so a bank count alone cannot tell "scrolled out
        // of view" from "nowhere near a bank". Same test appendBank uses.
        boolean bankOpen = false;
        try
        {
            Widget bw = client.getWidget(12, 0);
            bankOpen = bw != null && !bw.isHidden();
        }
        catch (Throwable ignored) { }
        sb.append("ib_bank_open=").append(bankOpen).append("\n");
        // 2.69: without this, bank_qty=0 means both "the bank holds none of
        // these" and "the bank has never been opened, so I have no idea".
        // Acting on the first when it is really the second wastes a trip.
        sb.append("ib_bank_known=").append(bank != null).append("\n");

        List<Object[]> vis = ibVisibleItems();
        Map<Integer, String> nameOf = new HashMap<>();

        List<Object[]> rows  = new ArrayList<>();
        List<String>   taken = new ArrayList<>();
        for (int i = 0; i < fs.size(); i++)
        {
            EntityFilter f = fs.get(i);
            String label = f.label.isEmpty() ? Integer.toString(rows.size()) : f.label;
            // A duplicate label would overwrite the first entry's keys. They
            // are already reported in entity_set_conflicts; drop it here so
            // the output never carries the same key twice.
            if (taken.contains(label)) continue;
            taken.add(label);

            // What are we looking for: an id, or a name?
            String want = f.id >= 0 ? safeItemName(f.id).toLowerCase() : f.name;
            boolean wild = "*".equals(want);

            // Everything on screen this entry matches, in container order.
            List<Object[]> hits = new ArrayList<>();
            for (Object[] v : vis)
            {
                int vid = (Integer) v[0];
                boolean hit;
                if (f.id >= 0) hit = f.id == vid;
                else
                {
                    String nm = nameOf.get(vid);
                    if (nm == null) { nm = safeItemName(vid).toLowerCase(); nameOf.put(vid, nm); }
                    hit = !nm.isEmpty() && (wild || nm.equals(want));
                }
                if (hit) hits.add(v);
            }

            int n = f.count <= 1 ? 1 : f.count;
            for (int r = 0; r < n; r++)
            {
                Object[] hit = r < hits.size() ? hits.get(r) : null;
                String   key = n > 1 ? label + "_" + r : label;

                int    foundId = hit != null ? (Integer) hit[0] : (f.id >= 0 ? f.id : -1);
                String where   = "none";
                int[]  box     = null;
                String state;

                // A wildcard row counts the item it actually landed on;
                // a named row counts by its own name, so notes and charges
                // of the same item still add up.
                String forCount = wild
                        ? (foundId >= 0 ? safeItemName(foundId).toLowerCase() : null)
                        : want;

                int invQty  = ibCount(inv,  f, forCount, false);
                int bankQty = ibCount(bank, f, forCount, false);
                int wornQty = ibCount(worn, f, forCount, false);
                boolean ph  = ibCount(bank, f, forCount, true) > 0;

                // 2.69: ordered by what the caller would have to DO about it.
                // Every branch that knows the item exists also resolves its
                // real id, so id and name are meaningful even with no box.
                if (hit != null)
                {
                    where = (String) hit[2];
                    box   = boundsBox((Rectangle) hit[1], canvasOk, ox, oy, dsx, dsy);
                    state = box != null ? "ok" : "no_canvas";
                }
                else if (invQty > 0)
                {
                    // In the inventory but no widget: the tab is not showing.
                    // That is "open the inventory", not "you do not have it".
                    where   = "inventory";
                    state   = "not_visible";
                    foundId = ibFindId(inv, f, forCount);
                }
                else if (wornQty > 0)
                {
                    where   = "equipped";
                    state   = "equipped";
                    foundId = ibFindId(worn, f, forCount);
                }
                else if (bankQty > 0)
                {
                    // Provably present, just not on screen. Never call this
                    // missing - that reading costs a pointless withdraw.
                    // scrolled_out means "open the right tab or scroll";
                    // bank_closed means "this is a remembered count, go to a
                    // bank". Conflating them would have the caller scrolling
                    // an interface that is not on screen.
                    where   = "bank";
                    state   = bankOpen ? "scrolled_out" : "bank_closed";
                    foundId = ibFindId(bank, f, forCount);
                }
                else state = "not_found";

                String nm = foundId >= 0 ? safeItemName(foundId) : "";
                if (nm.isEmpty() && want != null && !wild) nm = want;

                rows.add(new Object[]{ key, foundId, nm, where, state, box,
                        invQty, bankQty, wornQty, ph });
            }
        }

        sb.append("ib_count=").append(rows.size()).append("\n");
        StringBuilder names = new StringBuilder();
        for (Object[] r : rows)
        {
            String k = "ib_" + r[0] + "_";
            if (names.length() > 0) names.append(",");
            names.append(r[0]);
            sb.append(k).append("id=").append(r[1]).append("\n");
            sb.append(k).append("name=").append(r[2]).append("\n");
            sb.append(k).append("where=").append(r[3]).append("\n");
            sb.append(k).append("state=").append(r[4]).append("\n");
            sb.append(k).append("visible=").append(r[5] != null).append("\n");
            sb.append(k).append("inv_qty=").append(r[6]).append("\n");
            sb.append(k).append("bank_qty=").append(r[7]).append("\n");
            sb.append(k).append("worn=").append(((Integer) r[8]) > 0).append("\n");
            sb.append(k).append("bank_placeholder=").append(r[9]).append("\n");
            appendBox(sb, k, (int[]) r[5]);
        }
        sb.append("ib_names=").append(names).append("\n");
    }

    // 2.69: the id of the first real item in this container matching the
    // entry, or -1. Lets an item that has no widget on screen still be
    // identified, which is the difference between "worn=true" and knowing
    // WHAT is worn.
    private int ibFindId(ItemContainer c, EntityFilter f, String wantName)
    {
        if (c == null) return -1;
        try
        {
            for (Item it : c.getItems())
            {
                if (it == null || it.getId() <= 0 || it.getQuantity() <= 0) continue;
                if (isBankPlaceholder(it.getId())) continue;
                if (f.id >= 0)
                {
                    if (f.id == it.getId()) return it.getId();
                    continue;
                }
                if (wantName == null) continue;
                String nm = safeItemName(it.getId()).toLowerCase();
                if (!nm.isEmpty() && (wantName.equals("*") || nm.equals(wantName)))
                    return it.getId();
            }
        }
        catch (Throwable ignored) { }
        return -1;
    }

    // Counts one filter entry against a container. Placeholders are excluded
    // from the count (2.63) but findable on their own (2.64), because a
    // placeholder proves the name is real and the bank slot is reserved.
    private int ibCount(ItemContainer c, EntityFilter f, String wantName,
                        boolean placeholdersOnly)
    {
        if (c == null) return 0;
        int total = 0;
        try
        {
            for (Item it : c.getItems())
            {
                if (it == null || it.getId() <= 0 || it.getQuantity() <= 0) continue;
                boolean ph = isBankPlaceholder(it.getId());
                if (ph != placeholdersOnly) continue;

                boolean hit;
                if (f.id >= 0 && !ph)
                {
                    hit = f.id == it.getId();
                }
                else if (wantName == null)
                {
                    hit = false;
                }
                else
                {
                    String nm = safeItemName(it.getId()).toLowerCase();
                    hit = !nm.isEmpty() && nm.equals(wantName);
                }
                if (hit) total += placeholdersOnly ? 1 : it.getQuantity();
            }
        }
        catch (Throwable ignored) { }
        return total;
    }

    // -----------------------------------------------------------------------
    // Vitals (V2.43).
    // -----------------------------------------------------------------------
    private void appendVitals(StringBuilder sb)
    {
        int hp = -1, hpMax = -1, pray = -1, prayMax = -1;
        try { hp      = client.getBoostedSkillLevel(Skill.HITPOINTS); } catch (Throwable ignored) { }
        try { hpMax   = client.getRealSkillLevel(Skill.HITPOINTS);    } catch (Throwable ignored) { }
        try { pray    = client.getBoostedSkillLevel(Skill.PRAYER);    } catch (Throwable ignored) { }
        try { prayMax = client.getRealSkillLevel(Skill.PRAYER);       } catch (Throwable ignored) { }

        sb.append("vit_hp=").append(hp).append("\n");
        sb.append("vit_hp_max=").append(hpMax).append("\n");
        sb.append("vit_prayer=").append(pray).append("\n");
        sb.append("vit_prayer_max=").append(prayMax).append("\n");

        // Run energy scale changed between client versions: newer reports
        // 0-10000, older 0-100. Emit the raw value and derive the percent
        // from whichever scale is actually in use.
        int rawEnergy = -1;
        try { rawEnergy = client.getEnergy(); } catch (Throwable ignored) { }
        int runPct = rawEnergy < 0 ? -1 : (rawEnergy > 100 ? rawEnergy / 100 : rawEnergy);
        sb.append("vit_run_raw=").append(rawEnergy).append("\n");
        sb.append("vit_run_percent=").append(runPct).append("\n");

        // VarPlayer 300 holds special attack energy as PERCENT x10.
        int specRaw = -1;
        try { specRaw = client.getVarpValue(300); } catch (Throwable ignored) { }
        sb.append("vit_spec_raw=").append(specRaw).append("\n");
        sb.append("vit_spec_percent=").append(specRaw < 0 ? -1 : specRaw / 10).append("\n");

        boolean specOn = false;
        try { specOn = client.getVarpValue(301) == 1; } catch (Throwable ignored) { }
        sb.append("vit_spec_enabled=").append(specOn).append("\n");

        int runOn = -1;
        try { runOn = client.getVarpValue(173); } catch (Throwable ignored) { }
        sb.append("vit_run_enabled=").append(runOn == 1).append("\n");
    }

    // -----------------------------------------------------------------------
    // Widget coordinates (V2.43).  label=group:child[:index]
    //
    // Generic on purpose. Widget ids move between RuneLite versions, so a
    // hardcoded list of orbs would rot silently. Capture the ids you need
    // with RuneLite's Widget Inspector (Developer Tools) and put them here.
    // -----------------------------------------------------------------------
    private void parseWidgets(String spec)
    {
        if (spec == null) spec = "";
        if (spec.equals(wgSpecRaw)) return;
        wgSpecRaw = spec;
        wgNames.clear();
        wgIds.clear();

        for (String tok : spec.split("[,;\r\n]+"))
        {
            tok = tok.trim();
            if (tok.isEmpty()) continue;
            try
            {
                int eq = tok.indexOf('=');
                if (eq <= 0) continue;
                String name = sanitiseKey(tok.substring(0, eq).trim());
                if (name.isEmpty() || wgNames.contains(name)) continue;

                String[] p = tok.substring(eq + 1).trim().split(":");
                if (p.length < 2) continue;
                int g = Integer.parseInt(p[0].trim());
                int c = Integer.parseInt(p[1].trim());
                int i = p.length >= 3 && !p[2].trim().isEmpty()
                        ? Integer.parseInt(p[2].trim()) : -1;
                wgNames.add(name);
                wgIds.add(new int[]{ g, c, i });
            }
            catch (Exception ignored) { }
            if (wgNames.size() >= 24) break;
        }
        log.info("GEVisualAid v2.43 parsed {} widget(s): {}", wgNames.size(), wgNames);
    }

    private void appendWidgets(StringBuilder sb, boolean canvasOk,
                               int ox, int oy, double dsx, double dsy)
    {
        try { parseWidgets(config.widgetList()); } catch (Throwable ignored) { }

        StringBuilder names = new StringBuilder();
        for (int i = 0; i < wgNames.size(); i++)
        {
            if (names.length() > 0) names.append(",");
            names.append(wgNames.get(i));
        }
        sb.append("wg_count=").append(wgNames.size()).append("\n");
        sb.append("wg_names=").append(names).append("\n");

        for (int i = 0; i < wgNames.size(); i++)
        {
            int[]  id = wgIds.get(i);
            String k  = "wg_" + wgNames.get(i) + "_";

            String  state  = "not_found";
            boolean hidden = true;
            String  text   = "";
            Rectangle r    = null;
            int sprite = -1, opacity = -1, itemId = -1, itemQty = -1;
            try
            {
                Widget w = client.getWidget(id[0], id[1]);
                if (w != null && id[2] >= 0)
                {
                    Widget[] kids = w.getDynamicChildren();
                    if (kids != null && id[2] < kids.length) w = kids[id[2]];
                    else w = w.getChild(id[2]);
                }
                if (w != null)
                {
                    hidden = w.isHidden();
                    try { text = w.getText() == null ? "" : w.getText().replace("\n", " "); }
                    catch (Throwable ignored) { }
                    // V2.44: state, not just geometry. Each guarded on its
                    // own so one missing accessor cannot lose the rest.
                    try { sprite  = w.getSpriteId();      } catch (Throwable ignored) { }
                    try { opacity = w.getOpacity();       } catch (Throwable ignored) { }
                    try { itemId  = w.getItemId();        } catch (Throwable ignored) { }
                    try { itemQty = w.getItemQuantity();  } catch (Throwable ignored) { }
                    r = w.getBounds();
                    state = hidden ? "hidden" : (r == null ? "no_bounds" : "ok");
                }
            }
            catch (Throwable ignored) { state = "error"; }

            sb.append(k).append("group=").append(id[0]).append("\n");
            sb.append(k).append("child=").append(id[1]).append("\n");
            sb.append(k).append("index=").append(id[2]).append("\n");
            sb.append(k).append("state=").append(state).append("\n");
            sb.append(k).append("hidden=").append(hidden).append("\n");
            sb.append(k).append("text=").append(text).append("\n");
            sb.append(k).append("sprite=").append(sprite).append("\n");
            sb.append(k).append("opacity=").append(opacity).append("\n");
            sb.append(k).append("item_id=").append(itemId).append("\n");
            sb.append(k).append("item_qty=").append(itemQty).append("\n");

            boolean ok = "ok".equals(state) && r != null && canvasOk
                    && r.width > 0 && r.height > 0;
            sb.append(k).append("visible=").append(ok).append("\n");
            sb.append(k).append("screen_x=").append(ok ? (int) ((ox + r.x + r.width / 2)  * dsx) : -1).append("\n");
            sb.append(k).append("screen_y=").append(ok ? (int) ((oy + r.y + r.height / 2) * dsy) : -1).append("\n");
            sb.append(k).append("x1=").append(ok ? (int) ((ox + r.x)            * dsx) : -1).append("\n");
            sb.append(k).append("y1=").append(ok ? (int) ((oy + r.y)            * dsy) : -1).append("\n");
            sb.append(k).append("x2=").append(ok ? (int) ((ox + r.x + r.width)  * dsx) : -1).append("\n");
            sb.append(k).append("y2=").append(ok ? (int) ((oy + r.y + r.height) * dsy) : -1).append("\n");
        }
    }

    // -----------------------------------------------------------------------
    // Rooftop Agility Improved integration (V2.43).
    //
    // That plugin knows the real course sequence, including branches and
    // level gates, and which obstacle is a "stop" because a Mark of grace is
    // still waiting behind it. Everything here is a public method except the
    // coursesManager field itself.
    // -----------------------------------------------------------------------
    // 2.75: the plugin instance survives a stop/start but its
    // coursesManager does not. Re-read it, and if it has been replaced,
    // re-derive the method handles from the new one - they come off the
    // manager's class, which could in principle differ after a reload.
    //
    // Anything unexpected leaves the existing link alone: a failed
    // refresh must never be worse than not refreshing.
    private void refreshRooftopManager()
    {
        try
        {
            if (rtPlugin == null) return;
            java.lang.reflect.Field f = rtPlugin.getClass().getDeclaredField("coursesManager");
            f.setAccessible(true);
            Object live = f.get(rtPlugin);
            if (live == null || live == rtManager) return;

            rtManager    = live;
            Class<?> cm  = live.getClass();
            rtGetCourse  = cm.getMethod("getCourse");
            rtGetMarks   = cm.getMethod("getMarksOfGraces");
            rtIsStopping = cm.getMethod("isStoppingObstacle", int.class);
            log.info("GEVisualAid: Rooftop coursesManager was replaced - relinked");
        }
        catch (Throwable ignored) { }
    }

    private boolean linkRooftops()
    {
        if (rtManager != null && rtGetCourse != null) return true;
        if (rtLinkTried && rtManager == null) return false;
        rtLinkTried = true;

        try
        {
            for (Plugin p : pluginManager.getPlugins())
            {
                if (p == null) continue;
                if (!"TicTac7xRooftopsPlugin".equals(p.getClass().getSimpleName())) continue;
                rtPlugin = p;
                break;
            }
            if (rtPlugin == null)
            {
                log.info("GEVisualAid: Rooftop Agility Improved not installed");
                return false;
            }

            java.lang.reflect.Field f = rtPlugin.getClass().getDeclaredField("coursesManager");
            f.setAccessible(true);
            rtManager = f.get(rtPlugin);
            if (rtManager == null) { rtLinkTried = false; return false; }   // not started yet

            Class<?> cm = rtManager.getClass();
            rtGetCourse  = cm.getMethod("getCourse");
            rtGetMarks   = cm.getMethod("getMarksOfGraces");
            rtIsStopping = cm.getMethod("isStoppingObstacle", int.class);

            ClassLoader cl = rtPlugin.getClass().getClassLoader();
            Class<?> co = cl.loadClass("tictac7x.rooftops.course.Course");
            rtNextObs   = co.getMethod("getNextObstacles");
            rtCurObs    = co.getMethod("getCurrentObstacle");
            rtDoing     = co.getMethod("isDoingObstacle");
            rtCourseId  = co.getField("id");
            rtCourseObs = co.getField("obstacles");

            Class<?> ob = cl.loadClass("tictac7x.rooftops.course.Obstacle");
            rtObsId   = ob.getField("id");
            rtObsLocs = ob.getField("locations");
            rtObsMin  = ob.getField("minLevel");
            rtObsMax  = ob.getField("maxLevel");
            rtObsTile = ob.getMethod("getTileObject");

            log.info("GEVisualAid: linked to Rooftop Agility Improved");
            return true;
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid Rooftops link failed: {}", t.getMessage());
            rtManager = null;
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void appendRooftops(StringBuilder sb, WorldView wv, WorldPoint playerLoc,
                                int playerYaw, boolean canvasOk,
                                int ox, int oy, double dsx, double dsy, Rectangle clip,
                                List<Object[]> agObs)
    {
        // 2.75: RE-READ coursesManager EVERY TIME. Josh restarted Rooftop
        // (by hand, and later through /plugin), the overlays came back,
        // and rooftop_course stayed empty forever.
        //
        // Cause: startUp() builds a NEW coursesManager, and we cached the
        // old one at link time. RuneLite keeps the same Plugin object
        // across a stop/start, so nothing here looked broken - we simply
        // kept asking an object nobody was updating any more. A cached
        // handle that outlives what it points at, which is the same
        // shape as the entity-set cache and the bank placeholders.
        //
        // The field read is one reflective get per tick. The classes and
        // methods below it are stable and stay cached.
        refreshRooftopManager();

        boolean linked = false;
        try { linked = (rtManager != null && rtGetCourse != null) || linkRooftops(); }
        catch (Throwable ignored) { }

        sb.append("rooftop_plugin_found=").append(linked).append("\n");
        if (!linked)
        {
            sb.append("rooftop_course=\nrooftop_obstacle_count=0\nrooftop_next_count=0\n");
            return;
        }

        try
        {
            Object opt = rtGetCourse.invoke(rtManager);
            Object course = optValue(opt);
            if (course == null)
            {
                sb.append("rooftop_course=\nrooftop_obstacle_count=0\nrooftop_next_count=0\n");
                return;
            }

            sb.append("rooftop_course=").append(rtCourseId.get(course)).append("\n");
            sb.append("rooftop_doing_obstacle=").append(rtDoing.invoke(course)).append("\n");

            Object cur = optValue(rtCurObs.invoke(course));
            sb.append("rooftop_current_id=")
              .append(cur == null ? -1 : rtObsId.getInt(cur)).append("\n");

            // Which ids are "next" — a list, because some courses branch.
            List<Object> next = new ArrayList<>();
            Object nextOpt = optValue(rtNextObs.invoke(course));
            if (nextOpt instanceof List) next.addAll((List<Object>) nextOpt);

            int level = -1;
            try { level = client.getBoostedSkillLevel(Skill.AGILITY); } catch (Throwable ignored) { }

            sb.append("rooftop_next_count=").append(next.size()).append("\n");
            for (int i = 0; i < next.size() && i < 4; i++)
                emitRooftopObstacle(sb, "rooftop_next_" + i + "_", next.get(i), true,
                        wv, playerLoc, playerYaw, canvasOk, ox, oy, dsx, dsy, clip, level, agObs);

            // Every obstacle on the course, in course order.
            Object arr = rtCourseObs.get(course);
            int n = arr == null ? 0 : java.lang.reflect.Array.getLength(arr);
            sb.append("rooftop_obstacle_count=").append(n).append("\n");
            for (int i = 0; i < n && i < 20; i++)
            {
                Object o = java.lang.reflect.Array.get(arr, i);
                boolean isNext = false;
                for (int j = 0; j < next.size(); j++)
                    if (rtObsId.getInt(next.get(j)) == rtObsId.getInt(o)) { isNext = true; break; }
                emitRooftopObstacle(sb, "rooftop_obstacle_" + i + "_", o, isNext,
                        wv, playerLoc, playerYaw, canvasOk, ox, oy, dsx, dsy, clip, level, agObs);
            }

            // Marks of grace the plugin is tracking.
            Object marks = rtGetMarks.invoke(rtManager);
            List<WorldPoint> mpts = new ArrayList<>();
            if (marks instanceof List)
                for (Object t : (List<?>) marks)
                    if (t instanceof Tile)
                    {
                        WorldPoint w = ((Tile) t).getWorldLocation();
                        if (w != null) mpts.add(w);
                    }
            if (playerLoc != null)
            {
                final WorldPoint me = playerLoc;
                mpts.sort((a, b) -> Integer.compare(chebyshev(me, a), chebyshev(me, b)));
            }
            sb.append("rooftop_mark_count=").append(mpts.size()).append("\n");
            for (int i = 0; i < mpts.size() && i < 6; i++)
            {
                WorldPoint w = mpts.get(i);
                String k = "rooftop_mark_" + i + "_";
                String state = "offscreen";
                int[]  box   = null;
                try
                {
                    Object[] rt = resolveTile(wv, w.getX(), w.getY(), w.getPlane(),
                            canvasOk, ox, oy, dsx, dsy, clip);
                    state = (String) rt[0];
                    box   = (int[])  rt[1];
                }
                catch (Throwable ignored) { state = "error"; }
                Object[] bear = playerLoc != null
                        ? bearingTo(playerLoc, w.getX(), w.getY(), playerYaw)
                        : new Object[]{ -1, -1, -999, "UNKNOWN" };
                sb.append(k).append("world_x=").append(w.getX()).append("\n");
                sb.append(k).append("world_y=").append(w.getY()).append("\n");
                sb.append(k).append("plane=").append(w.getPlane()).append("\n");
                sb.append(k).append("dist_tiles=").append(bear[0]).append("\n");
                sb.append(k).append("state=").append(state).append("\n");
                sb.append(k).append("visible=").append(box != null).append("\n");
                appendBox(sb, k, box);
                sb.append(k).append("direction=").append(bear[3]).append("\n");
            }
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid rooftops read error: {}", t.getMessage());
            sb.append("rooftop_obstacle_count=0\nrooftop_next_count=0\n");
        }
    }

    private void emitRooftopObstacle(StringBuilder sb, String k, Object obs, boolean isNext,
                                     WorldView wv, WorldPoint playerLoc, int playerYaw,
                                     boolean canvasOk, int ox, int oy,
                                     double dsx, double dsy, Rectangle clip, int level,
                                     List<Object[]> agObs)
    {
        try
        {
            int id = rtObsId.getInt(obs);

            // "Stop" means do not use this yet — a Mark of grace is still on
            // the ground behind it and using this obstacle would skip it.
            boolean stop = false;
            try { stop = Boolean.TRUE.equals(rtIsStopping.invoke(rtManager, id)); }
            catch (Throwable ignored) { }

            // Level gating, so an obstacle you cannot use is not offered.
            boolean gated = false;
            try
            {
                Object mn = optValue(rtObsMin.get(obs));
                Object mx = optValue(rtObsMax.get(obs));
                if (mn instanceof Integer && level >= 0 && level < (Integer) mn) gated = true;
                if (mx instanceof Integer && level >= 0 && level > (Integer) mx) gated = true;
            }
            catch (Throwable ignored) { }
            // V2.59 — keep ALL the obstacle's locations, not just the
            // first. Read from the Rooftop source: an Obstacle carries a
            // list of world points, and several are multi-tile - the
            // Pollnivneach gap 14944 is {3359,2996},{3360,2996},
            // {3361,2996}. Resolving only locations.get(0) meant that if
            // that one corner happened to be off screen or behind
            // scenery the whole obstacle reported offscreen while two
            // perfectly good tiles of it sat in plain view.
            WorldPoint w = null;
            List<WorldPoint> wAll = new ArrayList<>();
            try
            {
                Object locs = rtObsLocs.get(obs);
                if (locs instanceof List)
                {
                    for (Object p : (List<?>) locs)
                        if (p instanceof WorldPoint) wAll.add((WorldPoint) p);
                }
            }
            catch (Throwable ignored) { }
            // Nearest location is the one to report distance and bearing
            // against - it is the part of the obstacle being walked to.
            if (!wAll.isEmpty())
            {
                w = wAll.get(0);
                if (playerLoc != null)
                    for (WorldPoint c : wAll)
                        if (chebyshev(playerLoc, c) < chebyshev(playerLoc, w)) w = c;
            }

            // V2.61 — WHERE THE CLICK BOX COMES FROM. This is the fix for
            // "it kept clicking the tile I was standing on".
            //
            // Read from the Rooftop source: an Obstacle's `locations` are
            // the tiles the PLAYER STANDS ON to use it, not the obstacle
            // object's own tile - their isNearNextObstacle() checks
            // player.distanceTo(location) <= 1 to decide the obstacle has
            // been started. So resolving `locations` into a click box,
            // which V2.55 introduced and V2.59 made worse by choosing the
            // NEAREST of them, aimed at the ground under the player's
            // feet the moment they were standing on the start tile.
            // Clicking there does nothing at all, forever.
            //
            // Order now: the Rooftop plugin's own TileObject, then the
            // same obstacle ID from RuneLite's Agility plugin list, which
            // is a real scene object with a real clickbox and is what
            // V2.55 should have fallen back to. If neither has it, we
            // report no box and let the caller turn or walk - `locations`
            // is used for distance and bearing ONLY, never to click.
            String state  = "not_loaded";
            String boxSrc = "none";
            int[]  box    = null;
            try
            {
                Object to = optValue(rtObsTile.invoke(obs));
                if (to instanceof TileObject)
                {
                    TileObject t = (TileObject) to;
                    Shape shape = null;
                    try { shape = t.getClickbox(); } catch (Throwable ignored) { }
                    if (shape == null && t instanceof GameObject)
                        try { shape = ((GameObject) t).getConvexHull(); } catch (Throwable ignored) { }
                    box = shapeBox(shape, canvasOk, ox, oy, dsx, dsy, clip);
                    if (box != null) { state = "ok"; boxSrc = "rooftop_object"; }
                    else state = "offscreen";
                }

                if (box == null && agObs != null)
                {
                    for (Object[] r : agObs)
                    {
                        if (!(r[1] instanceof TileObject)) continue;
                        TileObject t = (TileObject) r[1];
                        if (t.getId() != id) continue;
                        Shape shape = null;
                        try { shape = t.getClickbox(); } catch (Throwable ignored) { }
                        if (shape == null && t instanceof GameObject)
                            try { shape = ((GameObject) t).getConvexHull(); } catch (Throwable ignored) { }
                        int[] b2 = shapeBox(shape, canvasOk, ox, oy, dsx, dsy, clip);
                        if (b2 != null)
                        {
                            box    = b2;
                            state  = "ok_agility";
                            boxSrc = "agility_plugin";
                            break;
                        }
                    }
                }
                // V2.62 — last resort: the AGILITY PLUGIN object's own
                // world tile. Standing one tile from the Pollnivneach
                // basket the caller reported "cannot find it" and span
                // the camera, because neither clickbox resolved and
                // V2.61 had removed every tile fallback on purpose. That
                // purge was right about `locations` - those are the
                // tiles the PLAYER stands on - but wrong to take this
                // one with it: r[2] is the OBSTACLE's own position, read
                // off a real scene object. Aiming at the tile an
                // obstacle occupies is sound; aiming at the tile you are
                // standing on never was.
                if (box == null && agObs != null)
                {
                    for (Object[] r : agObs)
                    {
                        if (!(r[1] instanceof TileObject)) continue;
                        if (((TileObject) r[1]).getId() != id) continue;
                        if (!(r[2] instanceof WorldPoint)) continue;
                        WorldPoint ow = (WorldPoint) r[2];
                        Object[] rt = resolveTile(wv, ow.getX(), ow.getY(), ow.getPlane(),
                                canvasOk, ox, oy, dsx, dsy, clip);
                        if (rt[1] != null)
                        {
                            box    = (int[]) rt[1];
                            state  = "ok_agility_tile";
                            boxSrc = "agility_tile";
                            break;
                        }
                        if ("not_loaded".equals(state)) state = (String) rt[0];
                    }
                }
            }
            catch (Throwable ignored) { state = "error"; }

            Object[] bear = (playerLoc != null && w != null)
                    ? bearingTo(playerLoc, w.getX(), w.getY(), playerYaw)
                    : new Object[]{ -1, -1, -999, "UNKNOWN" };

            sb.append(k).append("id=").append(id).append("\n");
            sb.append(k).append("is_next=").append(isNext).append("\n");
            sb.append(k).append("stop=").append(stop).append("\n");
            sb.append(k).append("level_locked=").append(gated).append("\n");
            sb.append(k).append("world_x=").append(w == null ? -1 : w.getX()).append("\n");
            sb.append(k).append("world_y=").append(w == null ? -1 : w.getY()).append("\n");
            sb.append(k).append("plane=").append(w == null ? -1 : w.getPlane()).append("\n");
            sb.append(k).append("dist_tiles=").append(bear[0]).append("\n");
            sb.append(k).append("state=").append(state).append("\n");
            sb.append(k).append("box_source=").append(boxSrc).append("\n");
            sb.append(k).append("visible=").append(box != null).append("\n");
            appendBox(sb, k, box);
            sb.append(k).append("rel_bearing_deg=").append(bear[2]).append("\n");
            sb.append(k).append("direction=").append(bear[3]).append("\n");
        }
        catch (Throwable ignored) { }
    }

    // Optional.get() without importing their Optional flavour, and null-safe.
    private Object optValue(Object opt)
    {
        try
        {
            if (opt == null) return null;
            if (!(opt instanceof java.util.Optional)) return opt;
            java.util.Optional<?> o = (java.util.Optional<?>) opt;
            return o.isPresent() ? o.get() : null;
        }
        catch (Throwable t) { return null; }
    }

    // GET /agility?reset=1   or   /agility?next=3
    private void handleAgilityRequest(HttpExchange ex)
    {
        String reply;
        try
        {
            String q = ex.getRequestURI().getRawQuery();
            Map<String, String> p = new HashMap<>();
            if (q != null)
                for (String kv : q.split("&"))
                {
                    int e = kv.indexOf('=');
                    if (e > 0) p.put(kv.substring(0, e).toLowerCase(), kv.substring(e + 1));
                }

            if (p.containsKey("reset")) { pendingAgStep = 0; reply = "progress reset to 0"; }
            else if (p.containsKey("next"))
            {
                int n = Integer.parseInt(p.get("next").trim());
                pendingAgStep = Math.max(0, n);
                reply = "progress set to " + Math.max(0, n);
            }
            else reply = "usage: /agility?reset=1  or  /agility?next=3";
        }
        catch (Throwable t) { reply = "err " + t.getMessage(); }

        try
        {
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.getResponseBody().close();
        }
        catch (Throwable ignored) { }
    }

    // Parse the ordered course list: x:y[:plane], comma or newline separated.
    private void parseAgilityCourse(String spec)
    {
        if (spec == null) spec = "";
        if (spec.equals(agCourseRaw)) return;
        agCourseRaw = spec;
        agCourse.clear();
        agProgress = 0;

        for (String tok : spec.split("[,;\r\n]+"))
        {
            tok = tok.trim();
            if (tok.isEmpty()) continue;
            try
            {
                String[] p = tok.split(":");
                if (p.length < 2) continue;
                int x  = Integer.parseInt(p[0].trim());
                int y  = Integer.parseInt(p[1].trim());
                int pl = p.length >= 3 && !p[2].trim().isEmpty()
                        ? Integer.parseInt(p[2].trim()) : -1;
                agCourse.add(new int[]{ x, y, pl });
            }
            catch (Exception ignored) { }
            if (agCourse.size() >= 24) break;
        }
        log.info("GEVisualAid v2.41 parsed {} agility step(s)", agCourse.size());
    }

    // Emits agility_step_N_* for every configured step, tracks progression,
    // and mirrors the chosen step as agility_next_*.
    private void appendAgilityCourse(StringBuilder sb, WorldView wv, WorldPoint playerLoc,
                                     int playerYaw, int scenePlane, boolean canvasOk,
                                     int ox, int oy, double dsx, double dsy, Rectangle clip,
                                     List<Object[]> liveObstacles)
    {
        try { parseAgilityCourse(config.agilityCourseOrder()); }
        catch (Throwable ignored) { }

        sb.append("agility_step_count=").append(agCourse.size()).append("\n");
        if (agCourse.isEmpty())
        {
            sb.append("agility_next_index=-1\nagility_progress_index=-1\n");
            return;
        }

        int tol = anchorTolerance();

        // Resolve every configured step: prefer a live highlighted obstacle
        // at that position, fall back to the bare tile.
        String[] states = new String[agCourse.size()];
        int[][]  boxes  = new int[agCourse.size()][];
        int[]    dists  = new int[agCourse.size()];

        for (int i = 0; i < agCourse.size(); i++)
        {
            int[] c  = agCourse.get(i);
            int   pl = c[2] < 0 ? (scenePlane < 0 ? 0 : scenePlane) : c[2];
            dists[i] = playerLoc == null ? Integer.MAX_VALUE
                     : Math.max(Math.abs(c[0] - playerLoc.getX()),
                                Math.abs(c[1] - playerLoc.getY()));

            TileObject live = null;
            for (int j = 0; j < liveObstacles.size(); j++)
            {
                WorldPoint w = (WorldPoint) liveObstacles.get(j)[2];
                if (Math.abs(w.getX() - c[0]) <= tol && Math.abs(w.getY() - c[1]) <= tol)
                { live = (TileObject) liveObstacles.get(j)[1]; break; }
            }

            String state = "not_found";
            int[]  box   = null;
            if (live != null)
            {
                Shape shape = null;
                try { shape = live.getClickbox(); } catch (Throwable ignored) { }
                if (shape == null && live instanceof GameObject)
                    try { shape = ((GameObject) live).getConvexHull(); } catch (Throwable ignored) { }
                box = shapeBox(shape, canvasOk, ox, oy, dsx, dsy, clip);
                if (box != null) state = "ok";
            }
            if (box == null)
            {
                try
                {
                    Object[] rt = resolveTile(wv, c[0], c[1], pl,
                            canvasOk, ox, oy, dsx, dsy, clip);
                    box   = (int[]) rt[1];
                    state = box != null ? (live != null ? "ok_tile" : "tile_only")
                                        : (String) rt[0];
                }
                catch (Throwable ignored) { state = "error"; }
            }
            states[i] = state;
            boxes[i]  = box;
        }

        // ---- progression ----
        // Nearest RESOLVABLE step decides. One step back is ignored, because
        // that is the obstacle just completed still sitting in view. Two or
        // more back means a fall or a fresh lap, so we follow it.
        int best = -1, bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < agCourse.size(); i++)
        {
            if (boxes[i] == null && !"tile_only".equals(states[i])) continue;
            if (dists[i] < bestDist) { bestDist = dists[i]; best = i; }
        }

        if (best >= 0)
        {
            if (best >= agProgress)            agProgress = best;
            else if (agProgress - best >= 2)   agProgress = best;
            // else: exactly one behind — the completed obstacle. Ignore it.
        }
        if (agProgress >= agCourse.size()) agProgress = 0;   // lap complete

        sb.append("agility_progress_index=").append(agProgress).append("\n");
        sb.append("agility_nearest_index=").append(best).append("\n");

        for (int i = 0; i < agCourse.size(); i++)
        {
            int[] c = agCourse.get(i);
            String k = "agility_step_" + i + "_";
            Object[] bear = playerLoc != null
                    ? bearingTo(playerLoc, c[0], c[1], playerYaw)
                    : new Object[]{ -1, -1, -999, "UNKNOWN" };

            sb.append(k).append("world_x=").append(c[0]).append("\n");
            sb.append(k).append("world_y=").append(c[1]).append("\n");
            sb.append(k).append("plane=").append(c[2]).append("\n");
            sb.append(k).append("dist_tiles=").append(bear[0]).append("\n");
            sb.append(k).append("state=").append(states[i]).append("\n");
            sb.append(k).append("visible=").append(boxes[i] != null).append("\n");
            appendBox(sb, k, boxes[i]);
            sb.append(k).append("rel_bearing_deg=").append(bear[2]).append("\n");
            sb.append(k).append("direction=").append(bear[3]).append("\n");
        }

        // ---- the recommendation ----
        int n = agProgress;
        sb.append("agility_next_index=").append(n).append("\n");
        int[] c = agCourse.get(n);
        Object[] bear = playerLoc != null
                ? bearingTo(playerLoc, c[0], c[1], playerYaw)
                : new Object[]{ -1, -1, -999, "UNKNOWN" };
        sb.append("agility_next_world_x=").append(c[0]).append("\n");
        sb.append("agility_next_world_y=").append(c[1]).append("\n");
        sb.append("agility_next_plane=").append(c[2]).append("\n");
        sb.append("agility_next_dist_tiles=").append(bear[0]).append("\n");
        sb.append("agility_next_state=").append(states[n]).append("\n");
        sb.append("agility_next_visible=").append(boxes[n] != null).append("\n");
        appendBox(sb, "agility_next_", boxes[n]);
        sb.append("agility_next_rel_bearing_deg=").append(bear[2]).append("\n");
        sb.append("agility_next_direction=").append(bear[3]).append("\n");
    }

    private void appendAgility(StringBuilder sb, WorldView wv, WorldPoint playerLoc,
                               int playerYaw, int scenePlane, boolean canvasOk,
                               int ox, int oy, double dsx, double dsy, Rectangle clip)
    {
        boolean linked = false;
        try { linked = (agPlugin != null && agObstacles != null) || linkAgility(); }
        catch (Throwable ignored) { }

        sb.append("agility_plugin_found=").append(linked).append("\n");
        if (!linked)
        {
            sb.append("agility_obstacle_count=0\nagility_mark_count=0\n");
            sb.append("agility_step_count=0\nagility_next_index=-1\n");
            sb.append("agility_progress_index=-1\n");
            return;
        }

        // ---- lap / level info ----
        int level = -1, laps = -1, lapsHr = -1;
        try { if (agLevel != null) level = (Integer) agLevel.invoke(agPlugin); }
        catch (Throwable ignored) { }
        try
        {
            if (agSession != null)
            {
                Object s = agSession.invoke(agPlugin);
                if (s != null)
                {
                    try { laps = (Integer) s.getClass().getMethod("getTotalLaps").invoke(s); }
                    catch (Throwable ignored) { }
                    try { lapsHr = (Integer) s.getClass().getMethod("getLapsPerHour").invoke(s); }
                    catch (Throwable ignored) { }
                }
            }
        }
        catch (Throwable ignored) { }
        sb.append("agility_level=").append(level).append("\n");
        sb.append("agility_laps=").append(laps).append("\n");
        sb.append("agility_laps_per_hour=").append(lapsHr).append("\n");

        // ---- obstacles ----
        List<Object[]> obs = new ArrayList<>();
        try
        {
            Object m = agObstacles.invoke(agPlugin);
            if (m instanceof Map)
            {
                for (Object e : ((Map<?, ?>) m).entrySet())
                {
                    Map.Entry<?, ?> en = (Map.Entry<?, ?>) e;
                    if (!(en.getKey() instanceof TileObject)) continue;
                    TileObject to = (TileObject) en.getKey();

                    WorldPoint w = to.getWorldLocation();
                    if (w == null) continue;
                    int dist = playerLoc == null ? -1 : chebyshev(playerLoc, w);

                    String shortcut = "";
                    try
                    {
                        if (agShortcut != null && en.getValue() != null)
                        {
                            Object sc = agShortcut.invoke(en.getValue());
                            if (sc != null) shortcut = sc.toString();
                        }
                    }
                    catch (Throwable ignored) { }

                    boolean trap = agTrapIds != null && agTrapIds.contains(to.getId());
                    obs.add(new Object[]{ dist, to, w, shortcut, trap });
                }
            }
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid agility obstacle read error: {}", t.getMessage());
        }

        obs.sort((a, b) -> Integer.compare((Integer) a[0], (Integer) b[0]));
        // V2.58 — was 8. A rooftop course has nine obstacles or more, and
        // this list is sorted NEAREST FIRST, so the cap dropped precisely the
        // one being walked towards. The caller then reported the next step as
        // "not in view" and stalled with the obstacle in plain sight. The
        // source is the Agility plugin's own scene map, so this is bounded by
        // the course, not by a search radius.
        while (obs.size() > 24) obs.remove(obs.size() - 1);

        sb.append("agility_obstacle_count=").append(obs.size()).append("\n");
        for (int i = 0; i < obs.size(); i++)
        {
            Object[]   r  = obs.get(i);
            TileObject to = (TileObject) r[1];
            WorldPoint w  = (WorldPoint) r[2];
            String k = "agility_obstacle_" + i + "_";

            String state = "offscreen";
            int[]  box   = null;
            try
            {
                Shape shape = null;
                try { shape = to.getClickbox(); } catch (Throwable ignored) { }
                if (shape == null && to instanceof GameObject)
                    try { shape = ((GameObject) to).getConvexHull(); } catch (Throwable ignored) { }

                box = shapeBox(shape, canvasOk, ox, oy, dsx, dsy, clip);
                if (box != null) state = "ok";
                else
                {
                    Object[] rt = resolveTile(wv, w.getX(), w.getY(), w.getPlane(),
                            canvasOk, ox, oy, dsx, dsy, clip);
                    box   = (int[]) rt[1];
                    state = box != null ? "ok_tile" : (String) rt[0];
                }
            }
            catch (Throwable ignored) { state = "error"; }

            Object[] bear = playerLoc != null
                    ? bearingTo(playerLoc, w.getX(), w.getY(), playerYaw)
                    : new Object[]{ -1, -1, -999, "UNKNOWN" };

            String[] info = objectInfo(to.getId());
            sb.append(k).append("id=").append(to.getId()).append("\n");
            sb.append(k).append("name=").append(info[0]).append("\n");
            sb.append(k).append("actions=").append(info[1]).append("\n");
            sb.append(k).append("trap=").append(r[4]).append("\n");
            sb.append(k).append("shortcut=").append(r[3]).append("\n");
            sb.append(k).append("world_x=").append(w.getX()).append("\n");
            sb.append(k).append("world_y=").append(w.getY()).append("\n");
            sb.append(k).append("plane=").append(w.getPlane()).append("\n");
            sb.append(k).append("dist_tiles=").append(r[0]).append("\n");
            sb.append(k).append("state=").append(state).append("\n");
            sb.append(k).append("visible=").append(box != null).append("\n");
            appendBox(sb, k, box);
            sb.append(k).append("rel_bearing_deg=").append(bear[2]).append("\n");
            sb.append(k).append("direction=").append(bear[3]).append("\n");
        }

        // ---- Rooftop Agility Improved (V2.43) ----
        appendRooftops(sb, wv, playerLoc, playerYaw, canvasOk, ox, oy, dsx, dsy, clip, obs);

        // ---- ordered course (V2.41) ----
        appendAgilityCourse(sb, wv, playerLoc, playerYaw, scenePlane,
                canvasOk, ox, oy, dsx, dsy, clip, obs);

        // ---- marks of grace ----
        // V2.56: obs is handed over so each mark can name the obstacle it
        // sits beside. The stick gets null - there is nothing to say.
        appendAgilityTiles(sb, "agility_mark", agMarks, wv, playerLoc, playerYaw,
                canvasOk, ox, oy, dsx, dsy, clip, true, obs);

        // ---- werewolf stick ----
        appendAgilityTiles(sb, "agility_stick", agStick, wv, playerLoc, playerYaw,
                canvasOk, ox, oy, dsx, dsy, clip, false, null);
    }

    // Shared emitter for the plugin's Tile lists — marks of grace (a List)
    // and the Werewolf stick (a single Tile). Both resolve as tile click
    // boxes, nearest first.
    private void appendAgilityTiles(StringBuilder sb, String prefix,
                                    java.lang.reflect.Method getter,
                                    WorldView wv, WorldPoint playerLoc, int playerYaw,
                                    boolean canvasOk, int ox, int oy,
                                    double dsx, double dsy, Rectangle clip,
                                    boolean isList, List<Object[]> obs)
    {
        List<WorldPoint> pts = new ArrayList<>();
        try
        {
            if (getter != null)
            {
                Object v = getter.invoke(agPlugin);
                if (isList && v instanceof List)
                {
                    for (Object o : (List<?>) v)
                        if (o instanceof Tile)
                        {
                            WorldPoint w = ((Tile) o).getWorldLocation();
                            if (w != null) pts.add(w);
                        }
                }
                else if (!isList && v instanceof Tile)
                {
                    WorldPoint w = ((Tile) v).getWorldLocation();
                    if (w != null) pts.add(w);
                }
            }
        }
        catch (Throwable ignored) { }

        if (playerLoc != null)
        {
            final WorldPoint me = playerLoc;
            pts.sort((a, b) -> Integer.compare(chebyshev(me, a), chebyshev(me, b)));
        }
        while (pts.size() > 8) pts.remove(pts.size() - 1);

        sb.append(prefix).append("_count=").append(pts.size()).append("\n");
        for (int i = 0; i < pts.size(); i++)
        {
            WorldPoint w = pts.get(i);
            String k = prefix + "_" + i + "_";

            String state = "offscreen";
            int[]  box   = null;
            try
            {
                // 2.76: lifted. A mark of grace is an ITEM lying on the tile,
                // and at Pollnivneach one of them sits on a table.
                Object[] rt = resolveTile(wv, w.getX(), w.getY(), w.getPlane(),
                        canvasOk, ox, oy, dsx, dsy, clip, true);
                state = (String) rt[0];
                box   = (int[])  rt[1];
            }
            catch (Throwable ignored) { state = "error"; }

            Object[] bear = playerLoc != null
                    ? bearingTo(playerLoc, w.getX(), w.getY(), playerYaw)
                    : new Object[]{ -1, -1, -999, "UNKNOWN" };

            sb.append(k).append("world_x=").append(w.getX()).append("\n");
            sb.append(k).append("world_y=").append(w.getY()).append("\n");
            sb.append(k).append("plane=").append(w.getPlane()).append("\n");
            sb.append(k).append("dist_tiles=").append(bear[0]).append("\n");
            sb.append(k).append("state=").append(state).append("\n");
            sb.append(k).append("visible=").append(box != null).append("\n");
            appendBox(sb, k, box);
            sb.append(k).append("rel_bearing_deg=").append(bear[2]).append("\n");
            sb.append(k).append("direction=").append(bear[3]).append("\n");

            // V2.56: which obstacle is this mark beside? Answers "is this
            // one on my roof or the next roof" without the caller having
            // to cross-reference two tables by hand.
            //
            // Matched against agility_obstacle_*, NOT the Rooftop feed:
            // these entries carry a real WorldPoint, while the Rooftop
            // ones derive theirs from locations.get(0) and are the feed
            // that reports not_loaded in the field.
            //
            // PLANE IS PART OF THE MATCH. chebyshev is 2D, so without it
            // a mark on a roof binds to whatever sits directly below it -
            // which is exactly the "two roofs away" case this is for.
            //
            // The id is the durable half of the answer. The slot number
            // is a DISTANCE RANK that reshuffles as you move, so it is
            // emitted for reading, not for identity; callers wanting a
            // course position should map the id through their own order.
            if (obs != null)
            {
                int nearSlot = -1;
                int nearDist = Integer.MAX_VALUE;
                int nearId   = -1;
                for (int j = 0; j < obs.size(); j++)
                {
                    try
                    {
                        WorldPoint ow = (WorldPoint) obs.get(j)[2];
                        if (ow == null || ow.getPlane() != w.getPlane()) continue;
                        int od = chebyshev(w, ow);
                        if (od < nearDist)
                        {
                            nearDist = od;
                            nearSlot = j;
                            nearId   = ((TileObject) obs.get(j)[1]).getId();
                        }
                    }
                    catch (Throwable ignored) { }
                }
                sb.append(k).append("near_obstacle_slot=").append(nearSlot).append("\n");
                sb.append(k).append("near_obstacle_id=").append(nearId).append("\n");
                sb.append(k).append("near_obstacle_dist=")
                  .append(nearSlot < 0 ? -1 : nearDist).append("\n");
            }
        }
    }

    // -----------------------------------------------------------------------
    // Player activity (V2.38). Client thread only.
    //
    // Tracks when the animation last CHANGED and when a non-idle animation
    // was last seen. Those two timestamps are what turn a raw animation id
    // into something actionable without knowing which skill it belongs to.
    // -----------------------------------------------------------------------
    private void updatePlayerActivity()
    {
        try
        {
            if (client.getLocalPlayer() == null) return;
            long now = System.currentTimeMillis();

            int anim = -1;
            try { anim = client.getLocalPlayer().getAnimation(); } catch (Throwable ignored) { }

            if (anim != actAnimation)
            {
                actAnimation     = anim;
                actAnimChangedMs = now;
            }
            if (anim != -1)
            {
                actLastAnimation = anim;
                actLastNonIdleMs = now;
            }

            WorldPoint here = client.getLocalPlayer().getWorldLocation();
            actMoved = (here != null && actLastLoc != null && !here.equals(actLastLoc));
            if (here != null) actLastLoc = here;
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid player activity error: {}", t.getMessage());
        }
    }

    private void resetPlayerActivity()
    {
        actAnimation     = -1;
        actAnimChangedMs = 0;
        actLastAnimation = -1;
        actLastNonIdleMs = 0;
        actLastLoc       = null;
        actMoved         = false;
    }

    private void appendPlayerActivity(StringBuilder sb)
    {
        long now = System.currentTimeMillis();

        int pose = -1, idlePose = -1;
        try { pose     = client.getLocalPlayer().getPoseAnimation(); }     catch (Throwable ignored) { }
        try { idlePose = client.getLocalPlayer().getIdlePoseAnimation(); } catch (Throwable ignored) { }

        sb.append("player_act_animation=").append(actAnimation).append("\n");
        sb.append("player_act_last_animation=").append(actLastAnimation).append("\n");
        sb.append("player_act_busy=").append(actAnimation != -1).append("\n");
        sb.append("player_act_anim_seconds=")
          .append(actAnimChangedMs == 0 ? -1 : (now - actAnimChangedMs) / 1000.0).append("\n");
        sb.append("player_act_idle_seconds=")
          .append(actLastNonIdleMs == 0 ? -1 : (now - actLastNonIdleMs) / 1000.0).append("\n");

        sb.append("player_act_pose=").append(pose).append("\n");
        sb.append("player_act_idle_pose=").append(idlePose).append("\n");
        // Two independent movement signals: the pose differing from the idle
        // pose, and the tile actually changing. Either alone can lie for a
        // tick, so both are emitted.
        sb.append("player_act_pose_moving=")
          .append(pose != -1 && idlePose != -1 && pose != idlePose).append("\n");
        sb.append("player_act_tile_moved=").append(actMoved).append("\n");

        // What the player is interacting with — the target of combat, or the
        // NPC being talked to. Null for scenery, which the client does not
        // report as an interaction.
        String  tName = "";
        int     tIdx  = -1;
        boolean tNpc  = false;
        boolean inter = false;
        try
        {
            Object t = client.getLocalPlayer().getInteracting();
            if (t != null)
            {
                inter = true;
                if (t instanceof NPC)
                {
                    tNpc = true;
                    NPC n = (NPC) t;
                    tName = n.getName() == null ? "" : n.getName();
                    tIdx  = safeNpcIndex(n);
                }
                else if (t instanceof Actor)
                {
                    Object nm = ((Actor) t).getName();
                    tName = nm == null ? "" : nm.toString();
                }
            }
        }
        catch (Throwable ignored) { }

        sb.append("player_act_interacting=").append(inter).append("\n");
        sb.append("player_act_target_name=").append(tName).append("\n");
        sb.append("player_act_target_index=").append(tIdx).append("\n");
        sb.append("player_act_target_is_npc=").append(tNpc).append("\n");
    }

    // -----------------------------------------------------------------------
    // World hopping (V2.37).  GET /hop?world=100
    //
    // HTTP thread: validate and queue only.
    // -----------------------------------------------------------------------
    private void handleHopRequest(HttpExchange ex)
    {
        String reply;
        try
        {
            String q = ex.getRequestURI().getRawQuery();
            int world = -1;
            if (q != null)
                for (String kv : q.split("&"))
                {
                    int e = kv.indexOf('=');
                    if (e > 0 && kv.substring(0, e).trim().equalsIgnoreCase("world"))
                        world = Integer.parseInt(urlDecode(kv.substring(e + 1)).trim());
                }

            if (world <= 0) reply = "usage: /hop?world=100";
            else            { pendingHopWorld = world; reply = "queued hop to " + world; }
        }
        catch (Throwable t) { reply = "err " + t.getMessage(); }

        try
        {
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.getResponseBody().close();
        }
        catch (Throwable ignored) { }
    }

    // 2.72: exact name wins; otherwise a substring must match exactly ONE
    // plugin. Two matches is REFUSED rather than guessed - the duplicate
    // filter label taught that lesson, and "restarted the wrong plugin" is
    // a worse outcome than "did nothing and said so".
    private Plugin findPluginByName(String want)
    {
        if (want == null) return null;
        String w = want.trim().toLowerCase();
        if (w.isEmpty()) return null;
        Plugin sub = null;
        int subs = 0;
        for (Plugin p : pluginManager.getPlugins())
        {
            String n = p.getName();
            if (n == null) continue;
            if (n.equalsIgnoreCase(want.trim())) return p;
            if (n.toLowerCase().contains(w)) { sub = p; subs++; }
        }
        return subs == 1 ? sub : null;
    }

    // Client thread. Both states are published because they DISAGREE in
    // exactly the case this endpoint exists for: enabled in the config but
    // not actually running, which is what "never seems to load" looks like
    // from the outside.
    private void refreshPluginListSnapshot()
    {
        try
        {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (Plugin p : pluginManager.getPlugins())
            {
                String n = p.getName();
                if (n == null || n.trim().isEmpty()) continue;
                names.add(n
                        + "|" + (pluginManager.isPluginEnabled(p) ? "enabled" : "disabled")
                        + "|" + (pluginManager.isPluginActive(p)  ? "active"  : "inactive"));
            }
            java.util.Collections.sort(names);
            StringBuilder sb = new StringBuilder();
            sb.append("plugin_count=").append(names.size()).append("\n");
            for (String n : names) sb.append("plugin=").append(n).append("\n");
            pluginListSnapshot = sb.toString();
        }
        catch (Throwable ignored) { }
    }

    // 2.74: THE SWING EVENT DISPATCH THREAD, and nowhere else.
    //
    // startPlugin and stopPlugin both open with
    //     assert SwingUtilities.isEventDispatchThread();
    // - confirmed with javap against the resolved client-1.12.36.jar, not
    // guessed. RuneLite runs with assertions ON, so calling them from any
    // other thread throws AssertionError. That is what 2.72 did from the
    // client thread and what 2.73 did from its own executor, and it is
    // the "error restart Rooftop Agility Improved: java.lang.AssertionError"
    // in Josh's log.
    //
    // setPluginEnabled has NO such assertion, which is the cruel part: the
    // config flag flipped every time while nothing ever started or
    // stopped. Half a job that reads like a whole one.
    private String onEdt(Runnable r)
    {
        try { SwingUtilities.invokeAndWait(r); return ""; }
        catch (java.lang.reflect.InvocationTargetException e)
        {
            Throwable c = e.getCause() == null ? e : e.getCause();
            return c.toString();
        }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return "interrupted"; }
        catch (Throwable t) { return t.toString(); }
    }

    // Runs on pluginExec. The two PluginManager calls hop to the EDT; the
    // PAUSE between them deliberately does not, because blocking the EDT
    // for 2.5s would freeze the whole client.
    private void runPluginAction(String want, String act)
    {
        final Plugin p = findPluginByName(want);
        if (p == null)
        {
            // No match AND ambiguous both land here on purpose: either way
            // there is nothing safe to act on. /plugin with no name lists
            // the real names.
            pluginActionStatus = "no_single_match_for " + want;
            log.warn("GEVisualAid /plugin: no single match for '{}'", want);
            return;
        }
        final String nm = p.getName();

        if (act.equals("on") || act.equals("off"))
        {
            final boolean on = act.equals("on");
            String err = onEdt(() ->
            {
                try
                {
                    pluginManager.setPluginEnabled(p, on);
                    if (on) pluginManager.startPlugin(p); else pluginManager.stopPlugin(p);
                }
                catch (Throwable t) { throw new RuntimeException(t); }
            });
            pluginActionStatus = err.isEmpty()
                    ? (on ? "started " : "stopped ") + nm + activeSuffix(nm)
                    : (on ? "start_failed " : "stop_failed ") + nm + ": " + err;
            log.info("GEVisualAid {} {} -> {}", act, nm, pluginActionStatus);
            return;
        }

        // 2.75: whatever we had reflected into this plugin is about to be
        // stale. Dropping the link costs one re-link on the next tick;
        // keeping it costs a feed that never reports again.
        forgetPluginLinks(nm);

        // restart: off, pause, on.
        pluginActionStatus = "restarting " + nm;
        log.info("GEVisualAid restarting plugin {}", nm);

        String e1 = onEdt(() ->
        {
            try { pluginManager.setPluginEnabled(p, false); pluginManager.stopPlugin(p); }
            catch (Throwable t) { throw new RuntimeException(t); }
        });
        if (!e1.isEmpty())
        {
            pluginActionStatus = "restart_stop_failed " + nm + ": " + e1;
            log.warn("GEVisualAid could not stop {}: {}", nm, e1);
            return;
        }

        try { Thread.sleep(2500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        // Resolved again BY NAME: the Plugin instance is not guaranteed to
        // be the same object after a stop.
        final Plugin again = findPluginByName(nm);
        if (again == null)
        {
            pluginActionStatus = "restart_failed_gone " + nm;
            log.warn("GEVisualAid restart: {} vanished after stop", nm);
            return;
        }
        String e2 = onEdt(() ->
        {
            try { pluginManager.setPluginEnabled(again, true); pluginManager.startPlugin(again); }
            catch (Throwable t) { throw new RuntimeException(t); }
        });
        pluginActionStatus = e2.isEmpty()
                ? "restarted " + nm + activeSuffix(nm)
                : "restart_start_failed " + nm + ": " + e2;
        log.info("GEVisualAid restart {} -> {}", nm, pluginActionStatus);
    }

    // 2.75: a plugin we reflect into has been stopped or started, so
    // every handle we hold for it is suspect. Only the links for THAT
    // plugin are dropped - re-linking is cheap, but doing it to
    // everything on every action would be noise.
    private void forgetPluginLinks(String nm)
    {
        if (nm == null) return;
        String n = nm.toLowerCase();
        if (n.contains("rooftop"))
        {
            rtPlugin = null; rtManager = null; rtGetCourse = null;
            rtGetMarks = null; rtIsStopping = null; rtLinkTried = false;
            log.info("GEVisualAid: dropped the Rooftop link ahead of a restart");
        }
    }

    // 2.73: the outcome, not the intention. "restarted X active" and
    // "restarted X INACTIVE" are different facts and only one of them is
    // worth celebrating.
    private String activeSuffix(String nm)
    {
        try
        {
            Plugin p = findPluginByName(nm);
            if (p == null) return " gone";
            return pluginManager.isPluginActive(p) ? " active" : " INACTIVE";
        }
        catch (Throwable t) { return " unknown"; }
    }

    // One plugin's line, for verifying a restart landed.
    private String pluginStatusLine(String want)
    {
        Plugin p = findPluginByName(want);
        if (p == null) return "match=none\n";
        return "match=" + p.getName()
             + "\nenabled=" + pluginManager.isPluginEnabled(p)
             + "\nactive="  + pluginManager.isPluginActive(p) + "\n";
    }

    // 2.72: /plugin
    //   no name        -> list every plugin with enabled and active state
    //   name + action  -> queue on|off|restart for the client thread
    private void handlePluginRequest(HttpExchange ex)
    {
        StringBuilder reply = new StringBuilder();
        try
        {
            String q = ex.getRequestURI().getRawQuery();
            String name = "", action = "";
            if (q != null)
                for (String kv : q.split("&"))
                {
                    int e = kv.indexOf('=');
                    if (e <= 0) continue;
                    String k = kv.substring(0, e).trim();
                    String v = urlDecode(kv.substring(e + 1)).trim();
                    if (k.equalsIgnoreCase("name"))   name   = v;
                    if (k.equalsIgnoreCase("action")) action = v.toLowerCase();
                }

            reply.append("last_action=").append(pluginActionStatus).append("\n");

            if (name.isEmpty())
            {
                reply.append(pluginListSnapshot);
                reply.append("usage=/plugin?name=<part of the name>&action=on|off|restart\n");
            }
            else if (action.isEmpty())
            {
                // 2.73: a name with NO action is a READ. This is how a
                // caller verifies a restart actually landed, which 2.72
                // gave it no way to do.
                reply.append(pluginStatusLine(name));
            }
            else if (!action.equals("on") && !action.equals("off") && !action.equals("restart"))
            {
                reply.append("usage=/plugin?name=").append(name)
                     .append("&action=on|off|restart\n");
            }
            else if (pluginExec == null)
            {
                reply.append("err=plugin executor not running\n");
            }
            else
            {
                final String fn = name, fa = action;
                pluginActionStatus = "queued " + fa + " " + fn;
                pluginExec.submit(() -> runPluginAction(fn, fa));
                reply.append("queued=").append(action).append(" ").append(name).append("\n");
                reply.append("note=a restart takes about 3s; read /plugin?name=")
                     .append(name).append(" to see whether it came back active\n");
            }
        }
        catch (Throwable t) { reply.append("err ").append(t.getMessage()).append("\n"); }

        try
        {
            byte[] out = reply.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.getResponseBody().close();
        }
        catch (Throwable ignored) { }
    }

    // Client thread. Hopping needs the world switcher open before
    // hopToWorld() takes effect, so this opens it and retries for a few
    // ticks. No widget id lookup — the retry is robust across interface
    // changes, which the constants are not.
    private void applyPendingHop()
    {
        int req = pendingHopWorld;
        if (req > 0)
        {
            pendingHopWorld  = -1;
            hopLastRequested = req;
            hopTarget        = null;
            hopTicksLeft     = 0;

            try
            {
                if (req == client.getWorld())
                {
                    hopStatus = "already_there";
                    return;
                }

                WorldResult wr = worldService.getWorlds();
                World w = wr == null ? null : wr.findWorld(req);
                if (w == null) { hopStatus = "unknown_world"; return; }

                net.runelite.api.World rs = client.createWorld();
                rs.setActivity(w.getActivity());
                rs.setAddress(w.getAddress());
                rs.setId(w.getId());
                rs.setPlayerCount(w.getPlayers());
                rs.setLocation(w.getLocation());
                rs.setTypes(WorldUtil.toWorldTypes(w.getTypes()));

                hopTarget    = rs;
                hopTicksLeft = 12;
                hopStatus    = "hopping";
                client.openWorldHopper();
            }
            catch (Throwable t)
            {
                hopStatus = "error";
                log.warn("GEVisualAid hop request failed: {}", t.getMessage());
            }
            return;
        }

        if (hopTarget == null || hopTicksLeft <= 0) return;

        hopTicksLeft--;
        try
        {
            client.hopToWorld((net.runelite.api.World) hopTarget);
            if (client.getWorld() == hopLastRequested)
            {
                hopTarget = null;
                hopStatus = "done";
            }
        }
        catch (Throwable ignored) { }

        if (hopTicksLeft <= 0 && hopTarget != null)
        {
            hopTarget = null;
            hopStatus = client.getWorld() == hopLastRequested ? "done" : "timeout";
        }
    }

    // -----------------------------------------------------------------------
    // Runite Rocks mirror (V2.37). Everything read through public getters, so
    // no setAccessible and graceful absence if the plugin is not installed.
    // -----------------------------------------------------------------------
    private boolean linkRunite()
    {
        if (spRunite != null && rrWorldMap != null) return true;
        if (runiteLinkTried && spRunite == null) return false;
        runiteLinkTried = true;

        try
        {
            for (Plugin p : pluginManager.getPlugins())
            {
                if (p == null) continue;
                if (!"RuniteRocksPlugin".equals(p.getClass().getSimpleName())) continue;
                spRunite = p;
                break;
            }
            if (spRunite == null)
            {
                log.info("GEVisualAid: Runite Rocks plugin not installed");
                return false;
            }

            rrWorldMap = spRunite.getClass().getMethod("getWorldMap");
            ClassLoader cl = spRunite.getClass().getClassLoader();

            Class<?> trk = cl.loadClass("thestonedturtle.runiterocks.WorldTracker");
            rrTrkWorld = trk.getMethod("getWorld");
            rrTrkRocks = trk.getMethod("getRuniteRocks");

            Class<?> rr = cl.loadClass("thestonedturtle.runiterocks.RuniteRock");
            rrRockAvail   = rr.getMethod("isAvailable");
            rrRockRespawn = rr.getMethod("getRespawnTime");
            rrRockSeen    = rr.getMethod("getLastSeenAt");
            rrRockAcc     = rr.getMethod("hasWitnessedDepletion");
            rrRockRock    = rr.getMethod("getRock");

            Class<?> rk = cl.loadClass("thestonedturtle.runiterocks.Rock");
            rrRockName  = rk.getMethod("getName");
            rrRockLoc   = rk.getMethod("getLocation");
            rrRockPoint = rk.getMethod("getWorldPoint");

            log.info("GEVisualAid: linked to Runite Rocks plugin");
            return true;
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid Runite Rocks link failed: {}", t.getMessage());
            spRunite = null;
            return false;
        }
    }

    private void appendRuniteRocks(StringBuilder sb)
    {
        boolean linked = false;
        try { linked = (spRunite != null && rrWorldMap != null) || linkRunite(); }
        catch (Throwable ignored) { }
        sb.append("runite_plugin_found=").append(linked).append("\n");
        if (!linked) { sb.append("runite_count=0\n"); return; }

        List<Object[]> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        try
        {
            Object mapObj = rrWorldMap.invoke(spRunite);
            if (!(mapObj instanceof Map)) { sb.append("runite_count=0\n"); return; }

            for (Object entry : ((Map<?, ?>) mapObj).entrySet())
            {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) entry;
                Object trk = e.getValue();
                if (trk == null) continue;

                int worldId = -1, players = -1;
                try
                {
                    Object w = rrTrkWorld.invoke(trk);
                    if (w instanceof World)
                    {
                        worldId = ((World) w).getId();
                        players = ((World) w).getPlayers();
                    }
                }
                catch (Throwable ignored) { }
                if (worldId <= 0 && e.getKey() instanceof Integer) worldId = (Integer) e.getKey();

                Object rocksObj = rrTrkRocks.invoke(trk);
                if (!(rocksObj instanceof java.util.Collection)) continue;

                for (Object rock : (java.util.Collection<?>) rocksObj)
                {
                    if (rock == null) continue;
                    boolean avail = Boolean.TRUE.equals(rrRockAvail.invoke(rock));
                    boolean acc   = Boolean.TRUE.equals(rrRockAcc.invoke(rock));

                    long respawnIn = -1;
                    try
                    {
                        Object inst = rrRockRespawn.invoke(rock);
                        if (inst instanceof Instant)
                            respawnIn = Math.max(0,
                                    (((Instant) inst).toEpochMilli() - now) / 1000L);
                    }
                    catch (Throwable ignored) { }

                    long seenAgo = -1;
                    try
                    {
                        Object inst = rrRockSeen.invoke(rock);
                        if (inst instanceof Instant)
                            seenAgo = Math.max(0, (now - ((Instant) inst).toEpochMilli()) / 1000L);
                    }
                    catch (Throwable ignored) { }

                    String name = "", loc = "";
                    int rx = -1, ry = -1, rp = -1;
                    try
                    {
                        Object rk = rrRockRock.invoke(rock);
                        if (rk != null)
                        {
                            Object n = rrRockName.invoke(rk);
                            Object l = rrRockLoc.invoke(rk);
                            name = n == null ? "" : n.toString();
                            loc  = l == null ? "" : l.toString();
                            Object wp = rrRockPoint.invoke(rk);
                            if (wp instanceof WorldPoint)
                            {
                                rx = ((WorldPoint) wp).getX();
                                ry = ((WorldPoint) wp).getY();
                                rp = ((WorldPoint) wp).getPlane();
                            }
                        }
                    }
                    catch (Throwable ignored) { }

                    rows.add(new Object[]{ avail, respawnIn, worldId, name, loc,
                            acc, seenAgo, players, rx, ry, rp });
                }
            }
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid runite read error: {}", t.getMessage());
            sb.append("runite_count=0\n");
            return;
        }

        // Most actionable first: available rocks, then soonest respawn.
        rows.sort((a, b) ->
        {
            boolean aa = (Boolean) a[0], ba = (Boolean) b[0];
            if (aa != ba) return aa ? -1 : 1;
            long ar = (Long) a[1], br = (Long) b[1];
            if (ar < 0) ar = Long.MAX_VALUE;
            if (br < 0) br = Long.MAX_VALUE;
            return Long.compare(ar, br);
        });

        int max = 12;
        while (rows.size() > max) rows.remove(rows.size() - 1);

        sb.append("runite_count=").append(rows.size()).append("\n");
        sb.append("runite_best_world=")
          .append(rows.isEmpty() ? -1 : rows.get(0)[2]).append("\n");
        sb.append("runite_best_available=")
          .append(!rows.isEmpty() && Boolean.TRUE.equals(rows.get(0)[0])).append("\n");

        for (int i = 0; i < rows.size(); i++)
        {
            Object[] r = rows.get(i);
            String k = "runite_" + i + "_";
            sb.append(k).append("available=").append(r[0]).append("\n");
            sb.append(k).append("respawn_seconds=").append(r[1]).append("\n");
            sb.append(k).append("world=").append(r[2]).append("\n");
            sb.append(k).append("rock=").append(r[3]).append("\n");
            sb.append(k).append("location=").append(r[4]).append("\n");
            sb.append(k).append("accurate=").append(r[5]).append("\n");
            sb.append(k).append("last_seen_seconds=").append(r[6]).append("\n");
            sb.append(k).append("players=").append(r[7]).append("\n");
            sb.append(k).append("world_x=").append(r[8]).append("\n");
            sb.append(k).append("world_y=").append(r[9]).append("\n");
            sb.append(k).append("plane=").append(r[10]).append("\n");
        }
    }

    // -----------------------------------------------------------------------
    // Remote configuration (V2.36).
    //
    //   GET /filter                      read every supported value
    //   GET /filter?scenery=Coal%20rocks set one or more
    //
    // Runs on an HTTP thread, so it only validates and queues. The actual
    // ConfigManager write happens on the client thread in onGameTick.
    // -----------------------------------------------------------------------
    private void handleFilterRequest(HttpExchange ex)
    {
        StringBuilder reply = new StringBuilder();
        try
        {
            String q = ex.getRequestURI().getRawQuery();

            if (q == null || q.trim().isEmpty())
            {
                // No parameters: report current values so a consumer can
                // verify a write landed, or discover what is configurable.
                for (String[] k : REMOTE_KEYS)
                    reply.append(k[0]).append("=").append(readRemoteKey(k)).append("\n");
                // v2.65: the sets are not single config keys, so report them
                // here or a reader cannot tell which one is live.
                reply.append("entityset_available=").append(esAvailable).append("\n");
                reply.append("entityset_active=").append(esActive).append("\n");

                // v2.69: what is actually STORED, per slot, and what the scan
                // actually USES. The state output only ever said what a
                // filter found, never what it was, which made "typed into the
                // wrong box" and "matched nothing" indistinguishable.
                for (int i = 1; i <= ES_SLOTS; i++)
                {
                    try
                    {
                        String nm = setName(i);
                        String sc = setScenery(i), np = setNpcs(i);
                        String im = setItems(i),   bx = setBoxes(i);
                        boolean on = setEnabled(i);
                        if (!on && nm.trim().isEmpty() && sc.trim().isEmpty()
                                && np.trim().isEmpty() && im.trim().isEmpty()
                                && bx.trim().isEmpty())
                            continue;   // untouched slot, nothing to say
                        reply.append("set").append(i).append("=")
                             .append(nm).append(on ? " :ON" : " :off")
                             .append(" | scenery[").append(sc).append("]")
                             .append(" | npcs[").append(np).append("]")
                             .append(" | items[").append(im).append("]")
                             .append(" | carried[").append(bx).append("]")
                             .append("\n");
                    }
                    catch (Throwable ignored) { }
                }
                reply.append("merged_scenery=").append(esScenery).append("\n");
                reply.append("merged_npcs=").append(esNpcs).append("\n");
                reply.append("merged_items=").append(esItems).append("\n");
                reply.append("merged_carried=").append(esBoxes).append("\n");
            }
            else
            {
                for (String kv : q.split("&"))
                {
                    int e = kv.indexOf('=');
                    if (e <= 0) continue;
                    String name = kv.substring(0, e).trim().toLowerCase();
                    String val  = urlDecode(kv.substring(e + 1));

                    // v2.65: entityset is not a single config key - it turns
                    // one slot on and every other slot off.
                    if (name.equals("entityset"))
                    {
                        reply.append(activateEntitySet(val));
                        continue;
                    }

                    String[] key = null;
                    for (String[] k : REMOTE_KEYS)
                        if (k[0].equals(name)) { key = k; break; }

                    if (key == null)
                    {
                        reply.append("unknown key: ").append(name).append("\n");
                        continue;
                    }

                    String normalised = normaliseRemoteValue(key[2], val);
                    if (normalised == null)
                    {
                        reply.append("bad value for ").append(name)
                             .append(": ").append(val).append("\n");
                        continue;
                    }

                    synchronized (pendingConfig)
                    {
                        pendingConfig.add(new String[]{ key[1], normalised });
                    }
                    reply.append("queued ").append(name).append("=")
                         .append(normalised).append("\n");
                }
            }
        }
        catch (Throwable t) { reply.append("err ").append(t.getMessage()).append("\n"); }

        try
        {
            byte[] out = reply.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.getResponseBody().close();
        }
        catch (Throwable ignored) { }
    }

    // Booleans accept 1/0, true/false, on/off, yes/no. Ints must parse.
    // Strings pass through. null means reject.
    private String normaliseRemoteValue(String type, String val)
    {
        try
        {
            if ("b".equals(type))
            {
                String v = val.trim().toLowerCase();
                if (v.equals("1") || v.equals("true")  || v.equals("on")  || v.equals("yes"))
                    return "true";
                if (v.equals("0") || v.equals("false") || v.equals("off") || v.equals("no"))
                    return "false";
                return null;
            }
            if ("i".equals(type))
            {
                return Integer.toString(Integer.parseInt(val.trim()));
            }
            return val;
        }
        catch (Throwable t) { return null; }
    }

    private String readRemoteKey(String[] k)
    {
        try
        {
            String v = configManager.getConfiguration("ge-visual-aid", k[1]);
            return v == null ? "" : v;
        }
        catch (Throwable t) { return ""; }
    }

    private String urlDecode(String s)
    {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); }
        catch (Throwable t) { return s; }
    }

    // Client thread. Applies queued writes. These are ordinary config writes
    // and persist to the RuneLite profile, exactly as if typed into the panel.
    private void applyPendingConfig()
    {
        List<String[]> batch;
        synchronized (pendingConfig)
        {
            if (pendingConfig.isEmpty()) return;
            batch = new ArrayList<>(pendingConfig);
            pendingConfig.clear();
        }

        for (String[] kv : batch)
        {
            try
            {
                configManager.setConfiguration("ge-visual-aid", kv[0], kv[1]);
                log.info("GEVisualAid remote config: {} = {}", kv[0], kv[1]);
            }
            catch (Throwable t)
            {
                log.warn("GEVisualAid remote config failed for {}: {}", kv[0], t.getMessage());
            }
        }

        // Force the filters and waypoints to re-parse on the next build
        // rather than waiting for a config string to happen to differ.
        waypointSpecRaw = null;
        esSpecRaw       = null;   // v2.65
    }

    // -----------------------------------------------------------------------
    // Shortest Path bridge (V2.33).
    //
    // GET /path?x=..&y=..[&plane=..]   set destination
    // GET /path?clear=1                cancel
    //
    // Runs on an HTTP thread, so it only records the request. Nothing here
    // touches the client.
    // -----------------------------------------------------------------------
    private void handlePathRequest(HttpExchange ex)
    {
        String reply = "err";
        try
        {
            String q = ex.getRequestURI().getRawQuery();
            Map<String, String> p = new HashMap<>();
            if (q != null)
                for (String kv : q.split("&"))
                {
                    int e = kv.indexOf('=');
                    if (e > 0) p.put(kv.substring(0, e).toLowerCase(), kv.substring(e + 1));
                }

            if (p.containsKey("clear"))
            {
                pendingPath = new int[0];
                reply = "cleared";
            }
            else if (p.containsKey("x") && p.containsKey("y"))
            {
                int x  = Integer.parseInt(p.get("x").trim());
                int y  = Integer.parseInt(p.get("y").trim());
                int pl = p.containsKey("plane") ? Integer.parseInt(p.get("plane").trim()) : 0;
                pendingPath = new int[]{ x, y, pl };
                reply = "queued " + x + "," + y + "," + pl;
            }
            else
            {
                reply = "usage: /path?x=X&y=Y[&plane=P]  or  /path?clear=1";
            }
        }
        catch (Throwable t) { reply = "err " + t.getMessage(); }

        try
        {
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.getResponseBody().close();
        }
        catch (Throwable ignored) { }
    }

    // Client thread. Links to Shortest Path lazily and applies the queued
    // request. Everything reflective and everything optional.
    private void applyPendingPath()
    {
        int[] req = pendingPath;
        if (req == null) return;
        pendingPath = null;

        if (!linkShortestPath()) return;

        try
        {
            if (req.length == 0)
            {
                spRestart.invoke(spPlugin, -1, new HashSet<Integer>());   // empty = cancel
                pathTarget = null;
                log.info("GEVisualAid: shortest path cleared");
                return;
            }

            if (client.getLocalPlayer() == null) return;
            WorldPoint me = client.getLocalPlayer().getWorldLocation();
            if (me == null) return;

            int start  = (Integer) wpuPack.invoke(null, me.getX(), me.getY(), me.getPlane());
            int target = (Integer) wpuPack.invoke(null, req[0], req[1], req[2]);

            HashSet<Integer> ends = new HashSet<>();
            ends.add(Integer.valueOf(target));
            spRestart.invoke(spPlugin, Integer.valueOf(start), ends);

            pathTarget = req;
            log.info("GEVisualAid: shortest path set to {},{},{}", req[0], req[1], req[2]);
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid shortest path set error: {}", t.getMessage());
        }
    }

    // Locate ShortestPathPlugin and cache every reflective handle. Tried once
    // per session unless it succeeds.
    private boolean linkShortestPath()
    {
        if (spPlugin != null && spRestart != null) return true;
        if (spLinkTried && spPlugin == null) return false;
        spLinkTried = true;

        try
        {
            for (Plugin p : pluginManager.getPlugins())
            {
                if (p == null) continue;
                if (!"ShortestPathPlugin".equals(p.getClass().getSimpleName())) continue;
                spPlugin = p;
                break;
            }
            if (spPlugin == null)
            {
                log.info("GEVisualAid: Shortest Path plugin not installed");
                return false;
            }

            Class<?> pc = spPlugin.getClass();
            spRestart = pc.getMethod("restartPathfinding", int.class, java.util.Set.class);

            spPathfield = pc.getDeclaredField("pathfinder");
            spPathfield.setAccessible(true);

            ClassLoader cl = pc.getClassLoader();
            Class<?> wpu = cl.loadClass("shortestpath.WorldPointUtil");
            wpuPack  = wpu.getMethod("packWorldPoint", int.class, int.class, int.class);
            wpuX     = wpu.getMethod("unpackWorldX", int.class);
            wpuY     = wpu.getMethod("unpackWorldY", int.class);
            wpuPlane = wpu.getMethod("unpackWorldPlane", int.class);

            Class<?> step = cl.loadClass("shortestpath.pathfinder.PathStep");
            spPacked = step.getMethod("getPackedPosition");

            log.info("GEVisualAid: linked to Shortest Path plugin");
            return true;
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid Shortest Path link failed: {}", t.getMessage());
            spPlugin = null;
            return false;
        }
    }

    // Reads the current route and emits the step to click: chosen from the
    // last quarter of the run that is currently on screen, so each click
    // makes as much progress as possible without leaving the viewport.
    private void appendPath(StringBuilder sb, WorldView wv, WorldPoint playerLoc,
                            int scenePlane, boolean canvasOk,
                            int ox, int oy, double dsx, double dsy, Rectangle clip)
    {
        boolean linked = false;
        try { linked = (spPlugin != null && spRestart != null) || linkShortestPath(); }
        catch (Throwable ignored) { }

        sb.append("path_plugin_found=").append(linked).append("\n");
        sb.append("path_target_x=").append(pathTarget != null ? pathTarget[0] : -1).append("\n");
        sb.append("path_target_y=").append(pathTarget != null ? pathTarget[1] : -1).append("\n");
        sb.append("path_target_plane=").append(pathTarget != null ? pathTarget[2] : -1).append("\n");

        if (!linked)      { sb.append("path_state=no_plugin\npath_length=0\n"); return; }
        if (pathTarget == null) { sb.append("path_state=no_target\npath_length=0\n"); return; }

        List<int[]> pts = new ArrayList<>();
        try
        {
            Object pf = spPathfield.get(spPlugin);
            if (pf == null) { sb.append("path_state=calculating\npath_length=0\n"); return; }
            if (spGetPath == null) spGetPath = pf.getClass().getMethod("getPath");

            Object raw = spGetPath.invoke(pf);
            if (!(raw instanceof List)) { sb.append("path_state=calculating\npath_length=0\n"); return; }

            for (Object o : (List<?>) raw)
            {
                if (o == null) continue;
                int packed = (Integer) spPacked.invoke(o);
                pts.add(new int[]{
                        (Integer) wpuX.invoke(null, packed),
                        (Integer) wpuY.invoke(null, packed),
                        (Integer) wpuPlane.invoke(null, packed) });
            }
        }
        catch (Throwable t)
        {
            sb.append("path_state=error\npath_length=0\n");
            log.warn("GEVisualAid path read error: {}", t.getMessage());
            return;
        }

        sb.append("path_length=").append(pts.size()).append("\n");
        if (pts.isEmpty()) { sb.append("path_state=no_path\n"); return; }

        // Walk the path and keep the run of steps that resolve on screen.
        int firstVis = -1, lastVis = -1;
        for (int i = 0; i < pts.size(); i++)
        {
            int[] q = pts.get(i);
            try
            {
                Object[] r = resolveTile(wv, q[0], q[1], q[2], canvasOk, ox, oy, dsx, dsy, clip);
                if (r[1] != null) { if (firstVis < 0) firstVis = i; lastVis = i; }
            }
            catch (Throwable ignored) { }
        }

        sb.append("path_visible_count=").append(lastVis < 0 ? 0 : (lastVis - firstVis + 1)).append("\n");
        if (lastVis < 0)
        {
            sb.append("path_state=offscreen\n");
            appendBox(sb, "path_step_", null);
            return;
        }

        // Last quarter of the visible run, inclusive of its far end.
        int span  = lastVis - firstVis + 1;
        int qStart = lastVis - Math.max(0, (span / 4) - 1);
        if (qStart < firstVis) qStart = firstVis;
        int pick = qStart + (qStart >= lastVis ? 0 : (int) (Math.random() * (lastVis - qStart + 1)));
        if (pick > lastVis) pick = lastVis;

        int[] chosen = pts.get(pick);
        String state = "offscreen";
        int[]  box   = null;
        try
        {
            Object[] r = resolveTile(wv, chosen[0], chosen[1], chosen[2],
                    canvasOk, ox, oy, dsx, dsy, clip);
            state = (String) r[0];
            box   = (int[])  r[1];
        }
        catch (Throwable ignored) { state = "error"; }

        // V2.48: transports for the edge the player is currently on. This is
        // why a path step can sit on the tile you are stood on — the step is
        // a teleport, not a walk, and this says which one and which key.
        appendPathTransports(sb, pts);

        sb.append("path_state=").append(box != null ? "ok" : state).append("\n");
        sb.append("path_step_index=").append(pick).append("\n");
        sb.append("path_remaining=").append(pts.size() - pick).append("\n");
        sb.append("path_step_world_x=").append(chosen[0]).append("\n");
        sb.append("path_step_world_y=").append(chosen[1]).append("\n");
        sb.append("path_step_plane=").append(chosen[2]).append("\n");
        appendBox(sb, "path_step_", box);
    }

    // -----------------------------------------------------------------------
    // Shortest Path transports for the current edge (V2.48).
    //
    // transportsForEdge(currentStep, nextStep) is public on the plugin and
    // returns the candidate transports between two path steps, each carrying
    // a display info string — "Teleport to house", "Jewellery box (C)" and so
    // on. Emitted whole, plus a parsed key where a trailing letter or digit
    // is present, so the consumer need not pattern-match the sentence.
    // -----------------------------------------------------------------------
    private void appendPathTransports(StringBuilder sb, List<int[]> pts)
    {
        int count = 0;
        try
        {
            if (spPathfield == null || spPlugin == null || pts.size() < 2)
            {
                sb.append("path_transport_count=0\n");
                return;
            }

            Object pf = spPathfield.get(spPlugin);
            if (pf == null) { sb.append("path_transport_count=0\n"); return; }
            Object raw = spGetPath.invoke(pf);
            if (!(raw instanceof List)) { sb.append("path_transport_count=0\n"); return; }
            List<?> steps = (List<?>) raw;
            if (steps.size() < 2) { sb.append("path_transport_count=0\n"); return; }

            // Find where the player is on the path, and look at that edge.
            int at = 0;
            WorldPoint me = client.getLocalPlayer() == null
                    ? null : client.getLocalPlayer().getWorldLocation();
            if (me != null)
            {
                int best = Integer.MAX_VALUE;
                for (int i = 0; i < pts.size(); i++)
                {
                    int[] q = pts.get(i);
                    int d = Math.max(Math.abs(q[0] - me.getX()), Math.abs(q[1] - me.getY()));
                    if (d < best) { best = d; at = i; }
                }
            }
            if (at >= steps.size() - 1) at = steps.size() - 2;

            if (spTransports == null)
                spTransports = spPlugin.getClass().getMethod("transportsForEdge",
                        steps.get(0).getClass(), steps.get(0).getClass());

            Object res = spTransports.invoke(spPlugin, steps.get(at), steps.get(at + 1));
            if (!(res instanceof java.util.Collection))
            {
                sb.append("path_transport_count=0\n");
                return;
            }

            StringBuilder body = new StringBuilder();
            for (Object t : (java.util.Collection<?>) res)
            {
                if (t == null || count >= 4) continue;
                String info = "";
                try
                {
                    if (spTrInfo == null)
                        spTrInfo = t.getClass().getMethod("getDisplayInfo");
                    Object o = spTrInfo.invoke(t);
                    info = o == null ? "" : o.toString().replace("\n", " ").trim();
                }
                catch (Throwable ignored) { }

                String type = "";
                try
                {
                    if (spTrType == null) spTrType = t.getClass().getMethod("getType");
                    Object o = spTrType.invoke(t);
                    type = o == null ? "" : o.toString();
                }
                catch (Throwable ignored) { }

                String k = "path_transport_" + count + "_";
                body.append(k).append("info=").append(info).append("\n");
                body.append(k).append("type=").append(type).append("\n");
                body.append(k).append("key=").append(trailingKey(info)).append("\n");
                count++;
            }
            sb.append("path_transport_count=").append(count).append("\n");
            sb.append(body);
            return;
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid transport read error: {}", t.getMessage());
        }
        sb.append("path_transport_count=").append(count).append("\n");
    }

    // Pull a trailing option key out of a display string — the letter or
    // number to press, as in "Jewellery box (C)" or "Spirit tree 3".
    private String trailingKey(String info)
    {
        try
        {
            if (info == null || info.isEmpty()) return "";
            String s = info.trim();
            if (s.endsWith(")"))
            {
                int open = s.lastIndexOf('(');
                if (open >= 0)
                {
                    String inner = s.substring(open + 1, s.length() - 1).trim();
                    if (inner.length() <= 2) return inner;
                }
            }
            int sp = s.lastIndexOf(' ');
            if (sp > 0 && sp < s.length() - 1)
            {
                String last = s.substring(sp + 1);
                if (last.length() <= 2 && last.matches("[A-Za-z0-9]+")) return last;
            }
        }
        catch (Throwable ignored) { }
        return "";
    }

    // -----------------------------------------------------------------------
    // Loading lines. The scene reloads when the player crosses scene
    // coordinate 16 or 88 — the same boundary RuneLite devtools draws. In
    // world terms that is base+16 and base+88 on each axis.
    //
    // Worth knowing in advance rather than after: a scene reload shifts
    // scene_base_x/y and invalidates every cached screen coordinate.
    // -----------------------------------------------------------------------
    private static final int LOAD_LINE_NEAR = 16;
    private static final int LOAD_LINE_FAR  = 88;

    private void appendLoadingLines(StringBuilder sb, WorldView wv, WorldPoint playerLoc,
                                    int scenePlane, boolean canvasOk,
                                    int ox, int oy, double dsx, double dsy, Rectangle clip)
    {
        try
        {
            int baseX = wv.getBaseX();
            int baseY = wv.getBaseY();
            int plane = scenePlane < 0 ? 0 : scenePlane;

            int west  = baseX + LOAD_LINE_NEAR;
            int east  = baseX + LOAD_LINE_FAR;
            int south = baseY + LOAD_LINE_NEAR;
            int north = baseY + LOAD_LINE_FAR;

            int dW = playerLoc.getX() - west;
            int dE = east  - playerLoc.getX();
            int dS = playerLoc.getY() - south;
            int dN = north - playerLoc.getY();

            sb.append("load_west_x=").append(west).append("\n");
            sb.append("load_east_x=").append(east).append("\n");
            sb.append("load_south_y=").append(south).append("\n");
            sb.append("load_north_y=").append(north).append("\n");
            sb.append("load_dist_west=").append(dW).append("\n");
            sb.append("load_dist_east=").append(dE).append("\n");
            sb.append("load_dist_south=").append(dS).append("\n");
            sb.append("load_dist_north=").append(dN).append("\n");

            // Nearest boundary, and the point on it straight out from the
            // player — negative means already past it, which happens briefly
            // before the reload lands.
            String dir = "west";
            int    best = dW;
            if (dE < best) { best = dE; dir = "east";  }
            if (dS < best) { best = dS; dir = "south"; }
            if (dN < best) { best = dN; dir = "north"; }

            int tx, ty;
            if ("west".equals(dir))       { tx = west;  ty = playerLoc.getY(); }
            else if ("east".equals(dir))  { tx = east;  ty = playerLoc.getY(); }
            else if ("south".equals(dir)) { tx = playerLoc.getX(); ty = south; }
            else                          { tx = playerLoc.getX(); ty = north; }

            sb.append("load_nearest=").append(dir).append("\n");
            sb.append("load_nearest_dist=").append(best).append("\n");
            sb.append("load_nearest_world_x=").append(tx).append("\n");
            sb.append("load_nearest_world_y=").append(ty).append("\n");

            String state = "offline";
            int[]  box   = null;
            try
            {
                Object[] r = resolveTile(wv, tx, ty, plane, canvasOk, ox, oy, dsx, dsy, clip);
                state = (String) r[0];
                box   = (int[])  r[1];
            }
            catch (Throwable ignored) { state = "error"; }
            sb.append("load_nearest_state=").append(state).append("\n");
            appendBox(sb, "load_nearest_", box);
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid loading line error: {}", t.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Movement flags from WorldView.getCollisionMaps() — the same data
    // devtools' "Valid movement" overlay uses.
    //
    // A step is walkable when the tile being left does not block that
    // direction AND the tile being entered is not fully blocked. Raw flag
    // ints are emitted alongside so anything the booleans do not cover can
    // still be decoded by the consumer.
    // -----------------------------------------------------------------------
    private static final String[] MOVE_DIRS  = { "n", "ne", "e", "se", "s", "sw", "w", "nw" };
    private static final int[]    MOVE_DX    = {   0,    1,   1,    1,   0,   -1,  -1,   -1 };
    private static final int[]    MOVE_DY    = {   1,    1,   0,   -1,  -1,   -1,   0,    1 };
    // CollisionDataFlag: NW 0x1, N 0x2, NE 0x4, E 0x8, SE 0x10, S 0x20, SW 0x40, W 0x80
    private static final int[]    MOVE_BLOCK = { 0x2, 0x4, 0x8, 0x10, 0x20, 0x40, 0x80, 0x1 };
    // OBJECT 0x100 | FLOOR_DECORATION 0x40000 | FLOOR 0x200000 (eg. water)
    private static final int      BLOCK_FULL = 0x100 | 0x40000 | 0x200000;

    private void appendMovementFlags(StringBuilder sb, WorldView wv, WorldPoint playerLoc,
                                     int[] hover, int scenePlane, boolean canvasOk,
                                     int ox, int oy, double dsx, double dsy, Rectangle clip)
    {
        try
        {
            int plane = scenePlane < 0 ? 0 : scenePlane;
            int[][] flags = null;
            try
            {
                CollisionData[] maps = wv.getCollisionMaps();
                if (maps != null && plane >= 0 && plane < maps.length && maps[plane] != null)
                    flags = maps[plane].getFlags();
            }
            catch (Throwable ignored) { }

            sb.append("move_flags_available=").append(flags != null).append("\n");

            int pf = tileFlags(flags, wv, playerLoc.getX(), playerLoc.getY());
            sb.append("move_flags_player=").append(pf).append("\n");
            sb.append("move_flags_hover=")
              .append(hover != null ? tileFlags(flags, wv, hover[0], hover[1]) : -1)
              .append("\n");

            for (int i = 0; i < MOVE_DIRS.length; i++)
            {
                int nx = playerLoc.getX() + MOVE_DX[i];
                int ny = playerLoc.getY() + MOVE_DY[i];
                int nf = tileFlags(flags, wv, nx, ny);

                boolean walkable = flags != null
                        && pf >= 0 && nf >= 0
                        && (pf & MOVE_BLOCK[i]) == 0
                        && (nf & BLOCK_FULL)    == 0;

                String state = "offline";
                int[]  box   = null;
                try
                {
                    Object[] r = resolveTile(wv, nx, ny, plane, canvasOk, ox, oy, dsx, dsy, clip);
                    state = (String) r[0];
                    box   = (int[])  r[1];
                }
                catch (Throwable ignored) { state = "error"; }

                String k = "move_" + MOVE_DIRS[i] + "_";
                sb.append(k).append("walkable=").append(walkable).append("\n");
                sb.append(k).append("flags=").append(nf).append("\n");
                sb.append(k).append("world_x=").append(nx).append("\n");
                sb.append(k).append("world_y=").append(ny).append("\n");
                sb.append(k).append("state=").append(state).append("\n");
                sb.append(k).append("click_x1=").append(box != null ? box[6] : -1).append("\n");
                sb.append(k).append("click_y1=").append(box != null ? box[7] : -1).append("\n");
                sb.append(k).append("click_x2=").append(box != null ? box[8] : -1).append("\n");
                sb.append(k).append("click_y2=").append(box != null ? box[9] : -1).append("\n");
            }
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid movement flag error: {}", t.getMessage());
        }
    }

    // Raw collision flags for a world tile, or -1 when outside the scene.
    private int tileFlags(int[][] flags, WorldView wv, int wx, int wy)
    {
        try
        {
            if (flags == null) return -1;
            int sx = wx - wv.getBaseX();
            int sy = wy - wv.getBaseY();
            if (sx < 0 || sx >= flags.length)     return -1;
            if (sy < 0 || sy >= flags[sx].length) return -1;
            return flags[sx][sy];
        }
        catch (Throwable t) { return -1; }
    }

    // -----------------------------------------------------------------------
    // Ground items within a radius of the player, matching a filter of names
    // and/or item IDs. Sorted nearest first and capped.
    //
    // The scan is bounded by radius rather than sweeping the scene, so it is
    // far cheaper than the hover scan; item name lookups only happen at all
    // if the filter actually contains a non-numeric token.
    // -----------------------------------------------------------------------
    private void appendGroundItems(StringBuilder sb, WorldView wv, WorldPoint playerLoc,
                                   int playerYaw, int scenePlane, boolean canvasOk,
                                   int ox, int oy, double dsx, double dsy, Rectangle clip)
    {
        List<Object[]>     found     = new ArrayList<>();
        List<EntityFilter> giFilters = new ArrayList<>();
        try
        {
            if (playerLoc == null) { sb.append("gi_count=0\n"); return; }

            giFilters = parseEntityFilter(esItems);   // v2.65: always-on + enabled sets
            if (giFilters.isEmpty()) { sb.append("gi_count=0\n"); return; }

            int gRadius = clampInt(config.objectSearchRadius(), 1, 52);
            int radius  = maxFilterRadius(giFilters, gRadius, playerLoc);
            int max     = clampInt(config.objectMaxResults(),   1, 12);
            int tol     = anchorTolerance();
            int plane  = scenePlane < 0 ? 0 : scenePlane;
            int tick   = client.getTickCount();

            Tile[][][] tiles = wv.getScene().getTiles();
            if (tiles == null || plane >= tiles.length) { sb.append("gi_count=0\n"); return; }

            for (int wx = playerLoc.getX() - radius; wx <= playerLoc.getX() + radius; wx++)
            {
                for (int wy = playerLoc.getY() - radius; wy <= playerLoc.getY() + radius; wy++)
                {
                    int sx = wx - wv.getBaseX();
                    int sy = wy - wv.getBaseY();
                    if (sx < 0 || sx >= tiles[plane].length)     continue;
                    if (sy < 0 || sy >= tiles[plane][sx].length) continue;

                    Tile t = tiles[plane][sx][sy];
                    if (t == null) continue;
                    List<TileItem> items = t.getGroundItems();
                    if (items == null || items.isEmpty()) continue;

                    int ddx = wx - playerLoc.getX();
                    int ddy = wy - playerLoc.getY();
                    int tdist = (int) Math.round(Math.sqrt((double) ddx * ddx + (double) ddy * ddy));

                    for (TileItem it : items)
                    {
                        if (it == null) continue;
                        // V2.30: id/anchor pass first, name only if it could help.
                        // V2.39: and only within this entry's own radius.
                        String iname = "";
                        EntityFilter mf = matchFilter(giFilters, it.getId(), "", "",
                                wx, wy, plane, tol, tdist, gRadius);
                        if (mf == null && needsName(giFilters, wx, wy, plane, tol, tdist, gRadius))
                        {
                            iname = safeItemName(it.getId());
                            mf = matchFilter(giFilters, it.getId(), iname, "",
                                    wx, wy, plane, tol, tdist, gRadius);
                        }
                        if (mf == null) continue;
                        if (iname.isEmpty()) iname = safeItemName(it.getId());

                        // getDespawnTime() is a server TICK, not a duration.
                        int  dt   = it.getDespawnTime();
                        long left = (long) dt - tick;
                        if (left < 0 || left > 30000) left = -1;   // unknown / not witnessed

                        int dist = tdist;

                        found.add(new Object[]{ dist, it.getId(), iname, it.getQuantity(),
                                wx, wy, plane, left, mf });
                    }
                }
            }

            found.sort((a, b) -> Integer.compare((Integer) a[0], (Integer) b[0]));
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid ground item scan error: {}", t.getMessage());
        }

        int giMax = 12;
        try { giMax = clampInt(config.objectMaxResults(), 1, 12); } catch (Throwable ignored) { }
        List<Object[]> sel = selectResults(giFilters, found, 8, giMax);

        sb.append("gi_count=").append(sel.size()).append("\n");
        appendLabelCounts(sb, "gi", giFilters);
        for (int i = 0; i < sel.size(); i++)
        {
            String   key = (String)   sel.get(i)[0];
            Object[] f   = (Object[]) sel.get(i)[1];
            String   k   = "gi_" + key + "_";

            if (f == null)
            {
                sb.append(k).append("state=not_found\n");
                sb.append(k).append("visible=false\n");
                appendBox(sb, k, null);
                continue;
            }

            int      wx = (Integer) f[4];
            int      wy = (Integer) f[5];
            int      pl = (Integer) f[6];
            long     lf = (Long)    f[7];

            String state = "offline";
            int[]  box   = null;
            try
            {
                // 2.76: lifted - these are items ON the tile, not the tile.
                Object[] r = resolveTile(wv, wx, wy, pl, canvasOk, ox, oy, dsx, dsy, clip, true);
                state = (String) r[0];
                box   = (int[])  r[1];
            }
            catch (Throwable ignored) { state = "error"; }

            Object[] bear = bearingTo(playerLoc, wx, wy, playerYaw);

            sb.append(k).append("id=").append(f[1]).append("\n");
            sb.append(k).append("name=").append(f[2]).append("\n");
            sb.append(k).append("qty=").append(f[3]).append("\n");
            sb.append(k).append("world_x=").append(wx).append("\n");
            sb.append(k).append("world_y=").append(wy).append("\n");
            sb.append(k).append("plane=").append(pl).append("\n");
            sb.append(k).append("dist_tiles=").append(f[0]).append("\n");
            sb.append(k).append("state=").append(state).append("\n");
            sb.append(k).append("visible=").append(box != null).append("\n");
            appendBox(sb, k, box);
            sb.append(k).append("despawn_ticks=").append(lf).append("\n");
            sb.append(k).append("despawn_seconds=").append(lf < 0 ? -1 : Math.round(lf * 0.6)).append("\n");
            sb.append(k).append("rel_bearing_deg=").append(bear[2]).append("\n");
            sb.append(k).append("direction=").append(bear[3]).append("\n");
        }
    }

    // -----------------------------------------------------------------------
    // Live NPCs matching a filter of IDs and/or names, nearest first.
    //
    // The click box comes from the NPC's own convex hull where available —
    // that is the real model clickbox, which is what the game itself hit-tests
    // — rather than the tile underneath, which is wrong for large or moving
    // NPCs. Falls back to the tile polygon when the hull is not built.
    // -----------------------------------------------------------------------
    private void appendNpcs(StringBuilder sb, WorldView wv, WorldPoint playerLoc,
                            int playerYaw, boolean canvasOk,
                            int ox, int oy, double dsx, double dsy, Rectangle clip)
    {
        List<Object[]>     found      = new ArrayList<>();
        List<EntityFilter> npcFilters = new ArrayList<>();
        try
        {
            npcFilters = parseEntityFilter(esNpcs);   // v2.65: always-on + enabled sets
            if (npcFilters.isEmpty()) { sb.append("npc_count=0\n"); return; }
            int tol     = anchorTolerance();
            // V2.39: NPCs are not swept by radius, but a per-entry radius
            // still constrains which matches are accepted. Default is 52,
            // i.e. no constraint, matching pre-2.39 behaviour.
            int gRadius = 52;

            for (NPC n : wv.npcs())
            {
                if (n == null) continue;
                String nm = n.getName();
                WorldPoint w = n.getWorldLocation();
                int nwx = w != null ? w.getX() : Integer.MIN_VALUE;
                int nwy = w != null ? w.getY() : Integer.MIN_VALUE;
                int npl = w != null ? w.getPlane() : -1;
                int ndist = -1;
                if (playerLoc != null && w != null)
                {
                    int ndx = nwx - playerLoc.getX();
                    int ndy = nwy - playerLoc.getY();
                    ndist = (int) Math.round(Math.sqrt((double) ndx * ndx + (double) ndy * ndy));
                }
                EntityFilter mf = matchFilter(npcFilters, n.getId(), nm == null ? "" : nm, "",
                        nwx, nwy, npl, tol, ndist, gRadius);
                if (mf == null) continue;
                found.add(new Object[]{ npcDist(playerLoc, n), n, mf });
            }

            found.sort((a, b) -> Integer.compare((Integer) a[0], (Integer) b[0]));
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid npc scan error: {}", t.getMessage());
        }

        int npcMax = 12;
        try { npcMax = clampInt(config.objectMaxResults(), 1, 12); } catch (Throwable ignored) { }
        List<Object[]> sel = selectResults(npcFilters, found, 2, npcMax);

        sb.append("npc_count=").append(sel.size()).append("\n");
        appendLabelCounts(sb, "npc", npcFilters);
        for (int i = 0; i < sel.size(); i++)
        {
            String   key = (String)   sel.get(i)[0];
            Object[] row = (Object[]) sel.get(i)[1];
            String   k   = "npc_" + key + "_";

            if (row == null)
            {
                sb.append(k).append("state=not_found\n");
                sb.append(k).append("visible=false\n");
                appendBox(sb, k, null);
                continue;
            }
            final NPC n = (NPC) row[1];

            int wx = -1, wy = -1, pl = -1;
            String state = "offscreen";
            int[]  box   = null;
            try
            {
                WorldPoint w = n.getWorldLocation();
                if (w != null) { wx = w.getX(); wy = w.getY(); pl = w.getPlane(); }

                Shape hull = n.getConvexHull();
                if (hull != null && canvasOk)
                {
                    Rectangle hb = hull.getBounds();
                    if (hb.width > 0 && hb.height > 0)
                    {
                        int cx = hb.x + hb.width  / 2;
                        int cy = hb.y + hb.height / 2;
                        int[] inner = inscribedBox(hull, cx, cy, hb, clip);
                        if (inner != null)
                        {
                            box = new int[]{
                                    (int) ((ox + cx)                 * dsx),
                                    (int) ((oy + cy)                 * dsy),
                                    (int) ((ox + hb.x)               * dsx),
                                    (int) ((oy + hb.y)               * dsy),
                                    (int) ((ox + hb.x + hb.width)    * dsx),
                                    (int) ((oy + hb.y + hb.height)   * dsy),
                                    (int) ((ox + inner[0])           * dsx),
                                    (int) ((oy + inner[1])           * dsy),
                                    (int) ((ox + inner[2])           * dsx),
                                    (int) ((oy + inner[3])           * dsy)
                            };
                            state = "ok";
                        }
                    }
                }
                if (box == null && wx >= 0)
                {
                    // Fallback: the tile the NPC stands on.
                    Object[] r = resolveTile(wv, wx, wy, pl, canvasOk, ox, oy, dsx, dsy, clip);
                    String st = (String) r[0];
                    box = (int[]) r[1];
                    state = box != null ? "ok_tile" : st;
                }
            }
            catch (Throwable ignored) { state = "error"; }

            Object[] bear = (playerLoc != null && wx >= 0)
                    ? bearingTo(playerLoc, wx, wy, playerYaw)
                    : new Object[]{ -1, -1, -999, "UNKNOWN" };

            sb.append(k).append("id=").append(safeNpcId(n)).append("\n");
            sb.append(k).append("name=").append(n.getName() == null ? "" : n.getName()).append("\n");
            sb.append(k).append("index=").append(safeNpcIndex(n)).append("\n");
            sb.append(k).append("world_x=").append(wx).append("\n");
            sb.append(k).append("world_y=").append(wy).append("\n");
            sb.append(k).append("plane=").append(pl).append("\n");
            sb.append(k).append("dist_tiles=").append(bear[0]).append("\n");
            sb.append(k).append("state=").append(state).append("\n");
            sb.append(k).append("visible=").append(box != null).append("\n");
            appendBox(sb, k, box);
            sb.append(k).append("health_ratio=").append(safeInt(() -> n.getHealthRatio())).append("\n");
            sb.append(k).append("health_scale=").append(safeInt(() -> n.getHealthScale())).append("\n");
            sb.append(k).append("animation=").append(safeInt(() -> n.getAnimation())).append("\n");
            sb.append(k).append("interacting=").append(safeInteracting(n)).append("\n");
            sb.append(k).append("rel_bearing_deg=").append(bear[2]).append("\n");
            sb.append(k).append("direction=").append(bear[3]).append("\n");
        }
    }

    // -----------------------------------------------------------------------
    // Scenery (cave entrances, doors, ladders, banks, trees...) within the
    // search radius, matching a filter of names and/or object IDs.
    //
    // Covers all four scenery layers. Deduplicated on TileObject.getHash()
    // because a multi-tile object is reported by every tile it covers.
    // -----------------------------------------------------------------------
    private void appendGameObjects(StringBuilder sb, WorldView wv, WorldPoint playerLoc,
                                   int playerYaw, int scenePlane, boolean canvasOk,
                                   int ox, int oy, double dsx, double dsy, Rectangle clip)
    {
        List<Object[]>     found     = new ArrayList<>();
        List<EntityFilter> goFilters = new ArrayList<>();
        try
        {
            if (playerLoc == null) { sb.append("go_count=0\n"); return; }

            goFilters = parseEntityFilter(esScenery);   // v2.65: always-on + enabled sets
            if (goFilters.isEmpty()) { sb.append("go_count=0\n"); return; }

            int gRadius = clampInt(config.objectSearchRadius(), 1, 52);
            int radius  = maxFilterRadius(goFilters, gRadius, playerLoc);
            int tol     = anchorTolerance();
            int plane   = scenePlane < 0 ? 0 : scenePlane;

            Tile[][][] tiles = wv.getScene().getTiles();
            if (tiles == null || plane >= tiles.length) { sb.append("go_count=0\n"); return; }

            HashSet<Long> seen = new HashSet<>();

            for (int wx = playerLoc.getX() - radius; wx <= playerLoc.getX() + radius; wx++)
            {
                for (int wy = playerLoc.getY() - radius; wy <= playerLoc.getY() + radius; wy++)
                {
                    int sx = wx - wv.getBaseX();
                    int sy = wy - wv.getBaseY();
                    if (sx < 0 || sx >= tiles[plane].length)     continue;
                    if (sy < 0 || sy >= tiles[plane][sx].length) continue;

                    Tile t = tiles[plane][sx][sy];
                    if (t == null) continue;

                    GameObject[] gos = t.getGameObjects();
                    if (gos != null)
                        for (GameObject g : gos)
                            considerObject(g, "game", goFilters, tol, gRadius, seen, found, playerLoc);

                    considerObject(t.getWallObject(),       "wall",       goFilters, tol, gRadius, seen, found, playerLoc);
                    considerObject(t.getDecorativeObject(), "decorative", goFilters, tol, gRadius, seen, found, playerLoc);
                    considerObject(t.getGroundObject(),     "ground",     goFilters, tol, gRadius, seen, found, playerLoc);
                }
            }

            found.sort((a, b) -> Integer.compare((Integer) a[0], (Integer) b[0]));
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid game object scan error: {}", t.getMessage());
        }

        int goMax = 12;
        try { goMax = clampInt(config.objectMaxResults(), 1, 12); } catch (Throwable ignored) { }
        List<Object[]> sel = selectResults(goFilters, found, 8, goMax);

        sb.append("go_count=").append(sel.size()).append("\n");
        appendLabelCounts(sb, "go", goFilters);
        for (int i = 0; i < sel.size(); i++)
        {
            String   key = (String)   sel.get(i)[0];
            Object[] f   = (Object[]) sel.get(i)[1];
            String   k   = "go_" + key + "_";

            if (f == null)
            {
                sb.append(k).append("state=not_found\n");
                sb.append(k).append("visible=false\n");
                appendBox(sb, k, null);
                continue;
            }

            TileObject to  = (TileObject) f[4];
            int        wx  = (Integer) f[5];
            int        wy  = (Integer) f[6];
            int        pl  = (Integer) f[7];

            String state = "offscreen";
            int[]  box   = null;
            try
            {
                Shape shape = null;
                try { shape = to.getClickbox(); } catch (Throwable ignored) { }
                if (shape == null && to instanceof GameObject)
                {
                    try { shape = ((GameObject) to).getConvexHull(); } catch (Throwable ignored) { }
                }

                if (shape != null && canvasOk)
                {
                    Rectangle hb = shape.getBounds();
                    if (hb.width > 0 && hb.height > 0)
                    {
                        int cx = hb.x + hb.width  / 2;
                        int cy = hb.y + hb.height / 2;
                        int[] inner = inscribedBox(shape, cx, cy, hb, clip);
                        if (inner != null)
                        {
                            box = new int[]{
                                    (int) ((ox + cx)               * dsx),
                                    (int) ((oy + cy)               * dsy),
                                    (int) ((ox + hb.x)             * dsx),
                                    (int) ((oy + hb.y)             * dsy),
                                    (int) ((ox + hb.x + hb.width)  * dsx),
                                    (int) ((oy + hb.y + hb.height) * dsy),
                                    (int) ((ox + inner[0])         * dsx),
                                    (int) ((oy + inner[1])         * dsy),
                                    (int) ((ox + inner[2])         * dsx),
                                    (int) ((oy + inner[3])         * dsy)
                            };
                            state = "ok";
                        }
                    }
                }
                if (box == null)
                {
                    // Fallback: the tile the object is anchored to.
                    Object[] r = resolveTile(wv, wx, wy, pl, canvasOk, ox, oy, dsx, dsy, clip);
                    String st = (String) r[0];
                    box   = (int[]) r[1];
                    state = box != null ? "ok_tile" : st;
                }
            }
            catch (Throwable ignored) { state = "error"; }

            Object[] bear = bearingTo(playerLoc, wx, wy, playerYaw);

            sb.append(k).append("id=").append(f[1]).append("\n");
            sb.append(k).append("name=").append(f[2]).append("\n");
            sb.append(k).append("type=").append(f[3]).append("\n");
            sb.append(k).append("actions=").append(f[9]).append("\n");
            sb.append(k).append("world_x=").append(wx).append("\n");
            sb.append(k).append("world_y=").append(wy).append("\n");
            sb.append(k).append("plane=").append(pl).append("\n");
            sb.append(k).append("dist_tiles=").append(f[0]).append("\n");
            sb.append(k).append("state=").append(state).append("\n");
            sb.append(k).append("visible=").append(box != null).append("\n");
            appendBox(sb, k, box);
            sb.append(k).append("animation=").append(objectAnimation(to)).append("\n");
            sb.append(k).append("rel_bearing_deg=").append(bear[2]).append("\n");
            sb.append(k).append("direction=").append(bear[3]).append("\n");
        }
    }

    // V2.54: animation id of an animated scenery object, or -1.
    //
    // A static GameObject has a Model renderable and never animates. One that
    // does animate carries a DynamicObject instead, and that is the only place
    // the current animation id lives -- ObjectComposition has no such field.
    // Needed to tell apart states of one object id, e.g. a crystal extractor
    // mid-charge from one that is charged and ready to click.
    private int objectAnimation(TileObject to)
    {
        try
        {
            if (to instanceof GameObject)
            {
                Renderable r = ((GameObject) to).getRenderable();
                if (r instanceof DynamicObject)
                {
                    Animation a = ((DynamicObject) r).getAnimation();
                    if (a != null) return a.getId();
                }
            }
        }
        catch (Throwable ignored) { }
        return -1;
    }

    // Test one scenery object against the filter and add it if it matches and
    // has not already been seen. Multi-tile objects appear on every tile they
    // cover, hence the hash set.
    private void considerObject(TileObject o, String type,
                                List<EntityFilter> filters, int tol, int gRadius,
                                HashSet<Long> seen, List<Object[]> out,
                                WorldPoint playerLoc)
    {
        try
        {
            if (o == null) return;
            long hash = o.getHash();
            if (!seen.add(hash)) return;

            WorldPoint w = o.getWorldLocation();
            if (w == null) return;

            int id = o.getId();
            int wx = w.getX(), wy = w.getY(), wp = w.getPlane();

            int odx = wx - playerLoc.getX();
            int ody = wy - playerLoc.getY();
            int dist = (int) Math.round(Math.sqrt((double) odx * odx + (double) ody * ody));

            // V2.30: id and anchor first — both free. Only resolve the name
            // when a name or wildcard filter could still match at this spot,
            // which keeps a full radius scan cheap.
            // V2.39: and only when the entry's own radius reaches this far.
            String name    = "";
            String actions = "";
            EntityFilter mf = matchFilter(filters, id, "", "", wx, wy, wp, tol, dist, gRadius);
            if (mf == null && needsName(filters, wx, wy, wp, tol, dist, gRadius))
            {
                String[] info = objectInfo(id);
                name    = info[0];
                actions = info[1];
                mf = matchFilter(filters, id, name, actions, wx, wy, wp, tol, dist, gRadius);
            }
            if (mf == null) return;
            if (name.isEmpty() && actions.isEmpty())
            {
                String[] info = objectInfo(id);
                name    = info[0];
                actions = info[1];
            }

            out.add(new Object[]{ dist, id, name, type, o, w.getX(), w.getY(), w.getPlane(),
                    mf, actions });
        }
        catch (Throwable ignored) { }
    }

    // Scenery name AND action list from a single composition lookup,
    // resolving varbit impostors. Doors, quest-state cave entrances and
    // depleted-vs-live rocks report different things on the base id versus
    // the impostor, so the impostor is what the player actually sees.
    // Impostor results change with game state and are deliberately NOT
    // cached; everything else is, which is what keeps a name or action
    // filter affordable across a full radius scan.
    //
    // Returns { name, pipeJoinedActions }, both possibly empty.
    private String[] objectInfo(int id)
    {
        try
        {
            Integer key    = Integer.valueOf(id);
            String  cached = objNameCache.get(key);
            if (cached != null) return splitCached(cached);

            ObjectComposition comp = client.getObjectDefinition(id);
            if (comp == null) return new String[]{ "", "" };

            boolean morphs = false;
            try { morphs = comp.getImpostorIds() != null; } catch (Throwable ignored) { }
            if (morphs)
            {
                try
                {
                    ObjectComposition imp = comp.getImpostor();
                    if (imp != null) comp = imp;
                }
                catch (Throwable ignored) { }
            }

            String n = comp.getName();
            if (n == null || "null".equals(n)) n = "";

            StringBuilder acts = new StringBuilder();
            try
            {
                String[] a = comp.getActions();
                if (a != null)
                    for (String s : a)
                    {
                        if (s == null || s.isEmpty() || "null".equals(s)) continue;
                        if (acts.length() > 0) acts.append("|");
                        acts.append(s);
                    }
            }
            catch (Throwable ignored) { }

            String actions = acts.toString();
            if (!morphs) objNameCache.put(key, n + "\u0001" + actions);
            return new String[]{ n, actions };
        }
        catch (Throwable t) { return new String[]{ "", "" }; }
    }

    private String[] splitCached(String cached)
    {
        int sep = cached.indexOf('\u0001');
        if (sep < 0) return new String[]{ cached, "" };
        return new String[]{ cached.substring(0, sep), cached.substring(sep + 1) };
    }

    // ---- small shared helpers for the two scans above ----------------------

    private void appendBox(StringBuilder sb, String k, int[] box)
    {
        sb.append(k).append("screen_x=").append(box != null ? box[0] : -1).append("\n");
        sb.append(k).append("screen_y=").append(box != null ? box[1] : -1).append("\n");
        sb.append(k).append("x1=").append(box != null ? box[2] : -1).append("\n");
        sb.append(k).append("y1=").append(box != null ? box[3] : -1).append("\n");
        sb.append(k).append("x2=").append(box != null ? box[4] : -1).append("\n");
        sb.append(k).append("y2=").append(box != null ? box[5] : -1).append("\n");
        sb.append(k).append("click_x1=").append(box != null ? box[6] : -1).append("\n");
        sb.append(k).append("click_y1=").append(box != null ? box[7] : -1).append("\n");
        sb.append(k).append("click_x2=").append(box != null ? box[8] : -1).append("\n");
        sb.append(k).append("click_y2=").append(box != null ? box[9] : -1).append("\n");
    }

    // -----------------------------------------------------------------------
    // Entity filter entries (V2.29), shared by ground items, NPCs and scenery.
    //
    //   Name                          match by name, unlabelled
    //   1613                          match by id, unlabelled
    //   label=Name                    match by name, stable key
    //   label=Name@x:y[:plane]        ...and pinned to a position
    //   label=1613@x:y                id form, pinned
    //
    // Anchoring is what separates two objects that share an id and a name,
    // such as the two mouths of the same tunnel.
    // -----------------------------------------------------------------------
    private static final class EntityFilter
    {
        String  label    = "";     // "" = unlabelled, emitted by index
        String  name     = "";     // lowercase; empty when matching by id or action
        String  action   = "";     // lowercase right-click action, scenery only
        int     id       = -1;     // -1 when matching by name or action
        boolean anchored = false;
        int     ax = 0, ay = 0, ap = -1;
        boolean boxed    = false;                    // @x1:y1-x2:y2 bounds
        int     bx1 = 0, by1 = 0, bx2 = 0, by2 = 0;
        int     count    = 1;      // how many matches this entry may return
        int     radius   = -1;     // -1 = use the global search radius
    }

    private List<EntityFilter> parseEntityFilter(String raw)
    {
        List<EntityFilter> out = new ArrayList<>();
        if (raw == null) return out;

        for (String tok : raw.split("[,;\r\n]+"))
        {
            tok = tok.trim();
            if (tok.isEmpty()) continue;
            try
            {
                EntityFilter f = new EntityFilter();

                int eq = tok.indexOf('=');
                if (eq > 0)
                {
                    String lab = tok.substring(0, eq).trim();
                    // V2.34: "label:N" asks for up to N matches under that
                    // label. The colon is safe here because anchors live
                    // after the '@', which is stripped below.
                    int colon = lab.lastIndexOf(':');
                    if (colon > 0 && colon < lab.length() - 1)
                    {
                        String n = lab.substring(colon + 1).trim();
                        if (n.matches("\\d+"))
                        {
                            f.count = clampInt(Integer.parseInt(n), 1, 12);
                            lab     = lab.substring(0, colon).trim();
                        }
                    }
                    f.label = sanitiseKey(lab);
                    tok     = tok.substring(eq + 1).trim();
                }

                // V2.39: "~R" sets this entry's own search radius.
                int tilde = tok.lastIndexOf('~');
                if (tilde > 0)
                {
                    String r = tok.substring(tilde + 1).trim();
                    if (r.matches("\\d+"))
                    {
                        f.radius = clampInt(Integer.parseInt(r), 1, 52);
                        tok      = tok.substring(0, tilde).trim();
                    }
                }

                int at = tok.indexOf('@');
                if (at >= 0)
                {
                    String anchor = tok.substring(at + 1).trim();
                    tok = tok.substring(0, at).trim();

                    int dash = anchor.indexOf('-');
                    if (dash > 0)
                    {
                        // V2.42: rectangular bounds x1:y1-x2:y2[:plane]
                        String[] l = anchor.substring(0, dash).trim().split(":");
                        String[] r = anchor.substring(dash + 1).trim().split(":");
                        if (l.length >= 2 && r.length >= 2)
                        {
                            int x1 = Integer.parseInt(l[0].trim());
                            int y1 = Integer.parseInt(l[1].trim());
                            int x2 = Integer.parseInt(r[0].trim());
                            int y2 = Integer.parseInt(r[1].trim());
                            f.bx1 = Math.min(x1, x2);   // corners in any order
                            f.by1 = Math.min(y1, y2);
                            f.bx2 = Math.max(x1, x2);
                            f.by2 = Math.max(y1, y2);
                            if (r.length >= 3 && !r[2].trim().isEmpty())
                                f.ap = Integer.parseInt(r[2].trim());
                            f.boxed = true;
                        }
                    }
                    else
                    {
                        String[] a = anchor.split(":");
                        if (a.length >= 2)
                        {
                            f.ax = Integer.parseInt(a[0].trim());
                            f.ay = Integer.parseInt(a[1].trim());
                            if (a.length >= 3 && !a[2].trim().isEmpty())
                                f.ap = Integer.parseInt(a[2].trim());
                            f.anchored = true;
                        }
                    }
                }

                if (tok.isEmpty()) continue;
                if (tok.charAt(0) == '#')
                    f.action = tok.substring(1).trim().toLowerCase();   // #Mine
                else if (tok.matches("\\d+"))
                    f.id = Integer.parseInt(tok);
                else
                    f.name = tok.toLowerCase();                          // "*" = any named

                out.add(f);
            }
            catch (Exception ignored) { }
            if (out.size() >= 24) break;
        }
        return out;
    }

    // Returns the first filter entry this entity satisfies, or null.
    //
    // V2.30: the anchor is tested FIRST, because position is free while a
    // name may cost a composition lookup. Pass an empty name to run an
    // id-only pass; if that returns null, ask needsName() whether resolving
    // the name could still produce a match at this position before paying
    // for it.
    private EntityFilter matchFilter(List<EntityFilter> filters, int id, String name,
                                     int wx, int wy, int plane, int tol)
    {
        return matchFilter(filters, id, name, "", wx, wy, plane, tol, -1, 52);
    }

    private EntityFilter matchFilter(List<EntityFilter> filters, int id, String name,
                                     String actions, int wx, int wy, int plane, int tol)
    {
        return matchFilter(filters, id, name, actions, wx, wy, plane, tol, -1, 52);
    }

    // actions is the pipe-joined lowercase action list, or "" when unresolved
    // or not applicable (NPCs and ground items).
    private EntityFilter matchFilter(List<EntityFilter> filters, int id, String name,
                                     String actions, int wx, int wy, int plane, int tol,
                                     int dist, int globalRadius)
    {
        String lower = (name == null) ? "" : name.toLowerCase();
        for (int i = 0; i < filters.size(); i++)
        {
            EntityFilter f = filters.get(i);
            if (!anchorOk(f, wx, wy, plane, tol)) continue;
            if (!radiusOk(f, dist, globalRadius)) continue;

            if (!f.action.isEmpty())
            {
                if (!hasAction(actions, f.action)) continue;
            }
            else if (f.id >= 0)
            {
                if (f.id != id) continue;
            }
            else if ("*".equals(f.name))
            {
                if (lower.isEmpty()) continue;      // wildcards skip unnamed objects
            }
            else
            {
                if (lower.isEmpty() || !lower.equals(f.name)) continue;
            }
            return f;
        }
        return null;
    }

    // Exact match against one entry of a pipe-joined action list, so "Mine"
    // does not accidentally match "Mine-thing".
    private boolean hasAction(String actions, String wanted)
    {
        if (actions == null || actions.isEmpty()) return false;
        int from = 0;
        while (from <= actions.length())
        {
            int bar = actions.indexOf('|', from);
            String part = bar < 0 ? actions.substring(from) : actions.substring(from, bar);
            // V2.32: case-insensitive. The filter is lowercased at parse
            // time but getActions() returns original case ("Mine"), so an
            // exact-case compare here could never match.
            if (part.equalsIgnoreCase(wanted)) return true;
            if (bar < 0) break;
            from = bar + 1;
        }
        return false;
    }

    // An entry only matches inside its own radius. dist < 0 means "distance
    // not known here", which skips the check rather than rejecting.
    private boolean radiusOk(EntityFilter f, int dist, int globalRadius)
    {
        // V2.42: a box already says exactly where matches may be, so the
        // radius is not applied on top of it.
        if (f.boxed) return true;
        if (dist < 0) return true;
        int r = f.radius > 0 ? f.radius : globalRadius;
        return dist <= r;
    }

    // The sweep has to cover the largest radius anyone asked for.
    private int maxFilterRadius(List<EntityFilter> filters, int globalRadius,
                                WorldPoint playerLoc)
    {
        int max = globalRadius;
        for (int i = 0; i < filters.size(); i++)
        {
            EntityFilter f = filters.get(i);
            if (f.radius > max) max = f.radius;

            // V2.42: a bounded entry needs the sweep to reach its far corner,
            // otherwise the box could sit entirely outside the scan.
            if (f.boxed && playerLoc != null)
            {
                int need = Math.max(
                        Math.max(Math.abs(f.bx1 - playerLoc.getX()),
                                 Math.abs(f.bx2 - playerLoc.getX())),
                        Math.max(Math.abs(f.by1 - playerLoc.getY()),
                                 Math.abs(f.by2 - playerLoc.getY())));
                if (need > max) max = need;
            }
        }
        return clampInt(max, 1, 52);
    }

    private boolean anchorOk(EntityFilter f, int wx, int wy, int plane, int tol)
    {
        // V2.42: a box is an exact bound, so no tolerance is applied to it —
        // the point anchor keeps its tolerance because a multi-tile object
        // reports its base tile rather than the tile you would aim at.
        if (f.boxed)
        {
            if (f.ap >= 0 && f.ap != plane) return false;
            return wx >= f.bx1 && wx <= f.bx2 && wy >= f.by1 && wy <= f.by2;
        }
        if (!f.anchored) return true;
        if (f.ap >= 0 && f.ap != plane) return false;
        return Math.abs(wx - f.ax) <= tol && Math.abs(wy - f.ay) <= tol;
    }

    // Would resolving this entity's name let any filter match here? Used to
    // avoid composition lookups that cannot possibly pay off.
    private boolean needsName(List<EntityFilter> filters, int wx, int wy, int plane, int tol)
    {
        return needsName(filters, wx, wy, plane, tol, -1, 52);
    }

    private boolean needsName(List<EntityFilter> filters, int wx, int wy, int plane, int tol,
                              int dist, int globalRadius)
    {
        for (int i = 0; i < filters.size(); i++)
        {
            EntityFilter f = filters.get(i);
            // Action filters need the composition too, so they count here.
            if (f.id >= 0 && f.action.isEmpty()) continue;
            if (!radiusOk(f, dist, globalRadius)) continue;
            if (anchorOk(f, wx, wy, plane, tol)) return true;
        }
        return false;
    }

    // Builds the emission order: every labelled filter entry first, in config
    // order, each paired with its nearest match or null; then the unlabelled
    // matches nearest first, capped. Entries are Object[]{ keySuffix, match }.
    //
    // "found" must be sorted nearest first and carry its filter label at
    // index labelIdx.
    //
    // V2.34: "found" carries the EntityFilter that matched at labelIdx, not
    // just its label, so results can be attributed back to their entry.
    //
    // Labelled entries come first in config order, each taking up to its
    // count, and are exempt from max. Unlabelled entries are then allocated
    // ROUND ROBIN — one each, then a second each, and so on — so a distant
    // filter is never starved by a nearer one flooding the cap.
    private List<Object[]> selectResults(List<EntityFilter> filters, List<Object[]> found,
                                         int labelIdx, int max)
    {
        List<Object[]> out  = new ArrayList<>();
        List<String>   done = new ArrayList<>();

        // ---- labelled ----
        for (int i = 0; i < filters.size(); i++)
        {
            EntityFilter f = filters.get(i);
            if (f.label.isEmpty() || done.contains(f.label)) continue;
            done.add(f.label);

            List<Object[]> mine = matchesFor(found, labelIdx, f);
            if (f.count <= 1)
            {
                out.add(new Object[]{ f.label, mine.isEmpty() ? null : mine.get(0) });
            }
            else
            {
                for (int n = 0; n < f.count; n++)
                    out.add(new Object[]{ f.label + "_" + n,
                            n < mine.size() ? mine.get(n) : null });
            }
        }

        // ---- unlabelled, round robin ----
        List<List<Object[]>> pools = new ArrayList<>();
        for (int i = 0; i < filters.size(); i++)
        {
            EntityFilter f = filters.get(i);
            if (!f.label.isEmpty()) continue;
            pools.add(matchesFor(found, labelIdx, f));
        }

        int idx = 0, round = 0;
        boolean any = true;
        while (idx < max && any)
        {
            any = false;
            for (int p = 0; p < pools.size() && idx < max; p++)
            {
                List<Object[]> pool = pools.get(p);
                if (round >= pool.size()) continue;
                out.add(new Object[]{ Integer.toString(idx), pool.get(round) });
                idx++;
                any = true;
            }
            round++;
        }
        return out;
    }

    // All results attributed to one filter entry, preserving the nearest
    // first ordering of the source list.
    private List<Object[]> matchesFor(List<Object[]> found, int labelIdx, EntityFilter f)
    {
        List<Object[]> out = new ArrayList<>();
        for (int j = 0; j < found.size(); j++)
            if (found.get(j)[labelIdx] == f) out.add(found.get(j));
        return out;
    }

    // Emits <prefix>_<label>_count for every counted label, so a consumer
    // knows how many slots to read before reading them.
    private void appendLabelCounts(StringBuilder sb, String prefix, List<EntityFilter> filters)
    {
        List<String> done = new ArrayList<>();
        for (int i = 0; i < filters.size(); i++)
        {
            EntityFilter f = filters.get(i);
            if (f.label.isEmpty() || f.count <= 1 || done.contains(f.label)) continue;
            done.add(f.label);
            sb.append(prefix).append("_").append(f.label).append("_count=")
              .append(f.count).append("\n");
        }
    }

    private int anchorTolerance()
    {
        try { return clampInt(config.objectAnchorTolerance(), 0, 10); }
        catch (Throwable t) { return 2; }
    }

    // 2.63 - is this id a bank placeholder rather than a real item?
    // getPlaceholderTemplateId() is -1 on a real item and the template id
    // on a placeholder. Verified present in runelite-api 1.12.35 with
    // javap; an absent method would compile away to nothing useful.
    // Fails CLOSED - an unreadable composition is treated as a real item,
    // so a lookup failure can never silently zero a genuine count.
    private boolean isBankPlaceholder(int id)
    {
        try
        {
            if (itemManager == null) return false;
            return itemManager.getItemComposition(id).getPlaceholderTemplateId() > -1;
        }
        catch (Throwable t) { return false; }
    }

    private String safeItemName(int id)
    {
        try
        {
            if (itemManager == null) return "";
            String n = itemManager.getItemComposition(id).getName();
            return n == null ? "" : n;
        }
        catch (Throwable t) { return ""; }
    }

    private int npcDist(WorldPoint player, NPC n)
    {
        try
        {
            if (player == null || n == null) return Integer.MAX_VALUE;
            WorldPoint w = n.getWorldLocation();
            if (w == null) return Integer.MAX_VALUE;
            int dx = w.getX() - player.getX();
            int dy = w.getY() - player.getY();
            return dx * dx + dy * dy;
        }
        catch (Throwable t) { return Integer.MAX_VALUE; }
    }

    private int clampInt(int v, int lo, int hi)
    {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private interface IntSupplierX { int get(); }

    private int safeInt(IntSupplierX s)
    {
        try { return s.get(); } catch (Throwable t) { return -1; }
    }

    private int safeNpcId(NPC n)
    {
        try { return n.getId(); } catch (Throwable t) { return -1; }
    }

    private int safeNpcIndex(NPC n)
    {
        try { return n.getIndex(); } catch (Throwable t) { return -1; }
    }

    private boolean safeInteracting(NPC n)
    {
        try { return n.getInteracting() != null; } catch (Throwable t) { return false; }
    }

    // -----------------------------------------------------------------------
    // Cluster area -> one clickable box.
    //
    // A rectangular world area projects to a quadrilateral, not a rectangle,
    // so the union of its tiles' bounding boxes would once again cover ground
    // that is not part of the area. Instead we collect the vertices of every
    // tile polygon in the area, take their convex hull — which is the
    // projected outline of the area itself — and inscribe a box in that.
    //
    // The hull is taken over every tile rather than just the four corner
    // tiles because ground height varies per tile, so a rise in the middle
    // can project outside the quad its corners describe.
    //
    // Returns { state, box } with box laid out exactly as resolveTile():
    // { centreX, centreY, x1, y1, x2, y2, clickX1, clickY1, clickX2, clickY2 }.
    // -----------------------------------------------------------------------
    private Object[] resolveCluster(WorldView wv, int wx1, int wy1, int wx2, int wy2,
                                    int plane, boolean canvasOk,
                                    int ox, int oy, double dsx, double dsy,
                                    int[] tileCounts, Rectangle clip)
    {
        Tile[][][] tiles;
        try { tiles = wv.getScene().getTiles(); }
        catch (Throwable t) { return new Object[]{ "no_scene", null }; }
        if (tiles == null || plane < 0 || plane >= tiles.length)
            return new Object[]{ "offscene", null };

        List<int[]> pts    = new ArrayList<>();
        int total = 0, inScene = 0, visible = 0;
        // V2.26: anchor on the centroid of the tile centres that are actually
        // on screen, so a cluster hanging half off the edge still anchors
        // somewhere valid instead of at an off-window hull centroid.
        long anchorSumX = 0, anchorSumY = 0;
        int  anchorCount = 0;

        for (int wx = wx1; wx <= wx2; wx++)
        {
            for (int wy = wy1; wy <= wy2; wy++)
            {
                total++;
                int sx = wx - wv.getBaseX();
                int sy = wy - wv.getBaseY();
                if (sx < 0 || sx >= tiles[plane].length)     continue;
                if (sy < 0 || sy >= tiles[plane][sx].length) continue;
                inScene++;

                Tile t = tiles[plane][sx][sy];
                if (t == null) continue;
                LocalPoint lp = t.getLocalLocation();
                if (lp == null) continue;

                Polygon poly = Perspective.getCanvasTilePoly(client, lp);
                if (poly == null || poly.npoints < 3) continue;

                // V2.26: skip tiles that project entirely off screen. Without
                // this they would drag the hull outside the window.
                if (clip != null && !poly.intersects(clip)) continue;

                visible++;
                for (int i = 0; i < poly.npoints; i++)
                    pts.add(new int[]{ poly.xpoints[i], poly.ypoints[i] });

                net.runelite.api.Point tc = Perspective.localToCanvas(client, lp, plane);
                if (tc != null && (clip == null || clip.contains(tc.getX(), tc.getY())))
                {
                    anchorSumX += tc.getX();
                    anchorSumY += tc.getY();
                    anchorCount++;
                }
            }
        }

        tileCounts[0] = total;
        tileCounts[1] = visible;

        if (visible == 0)
            return new Object[]{ inScene == 0 ? "offscene" : "offscreen", null };
        if (!canvasOk)
            return new Object[]{ "no_canvas", null };

        Polygon hull = convexHull(pts);
        if (hull == null || hull.npoints < 3) return new Object[]{ "offscreen", null };

        Rectangle r = hull.getBounds();

        // Anchor: prefer the centroid of the on-screen tile centres (V2.26),
        // falling back to the hull's vertex centroid, which is guaranteed
        // interior for a convex polygon.
        int cx, cy;
        if (anchorCount > 0)
        {
            cx = (int) (anchorSumX / anchorCount);
            cy = (int) (anchorSumY / anchorCount);
        }
        else
        {
            long ax = 0, ay = 0;
            for (int i = 0; i < hull.npoints; i++) { ax += hull.xpoints[i]; ay += hull.ypoints[i]; }
            cx = (int) (ax / hull.npoints);
            cy = (int) (ay / hull.npoints);
        }

        int[] inner = inscribedBox(hull, cx, cy, r, clip);

        int[] box = new int[]{
                (int) ((ox + cx)                  * dsx),
                (int) ((oy + cy)                  * dsy),
                (int) ((ox + r.x)                 * dsx),
                (int) ((oy + r.y)                 * dsy),
                (int) ((ox + r.x + r.width)       * dsx),
                (int) ((oy + r.y + r.height)      * dsy),
                inner != null ? (int) ((ox + inner[0]) * dsx) : -1,
                inner != null ? (int) ((oy + inner[1]) * dsy) : -1,
                inner != null ? (int) ((ox + inner[2]) * dsx) : -1,
                inner != null ? (int) ((oy + inner[3]) * dsy) : -1
        };
        return new Object[]{ visible == total ? "ok" : "partial", box };
    }

    // -----------------------------------------------------------------------
    // Convex hull (Andrew's monotone chain). Input is canvas-space points,
    // output the enclosing polygon. O(n log n), and n is at most four times
    // the cluster tile count.
    // -----------------------------------------------------------------------
    private Polygon convexHull(List<int[]> pts)
    {
        if (pts == null || pts.size() < 3) return null;

        int[][] a = pts.toArray(new int[0][]);
        java.util.Arrays.sort(a, (p, q) -> p[0] != q[0] ? Integer.compare(p[0], q[0])
                                                        : Integer.compare(p[1], q[1]));
        int n = a.length;
        int[][] h = new int[2 * n][];
        int k = 0;

        for (int i = 0; i < n; i++)
        {
            while (k >= 2 && cross(h[k - 2], h[k - 1], a[i]) <= 0) k--;
            h[k++] = a[i];
        }
        int lower = k + 1;
        for (int i = n - 2; i >= 0; i--)
        {
            while (k >= lower && cross(h[k - 2], h[k - 1], a[i]) <= 0) k--;
            h[k++] = a[i];
        }

        int count = Math.max(0, k - 1);
        if (count < 3) return null;

        Polygon p = new Polygon();
        for (int i = 0; i < count; i++) p.addPoint(h[i][0], h[i][1]);
        return p;
    }

    private long cross(int[] o, int[] a, int[] b)
    {
        return (long) (a[0] - o[0]) * (b[1] - o[1]) - (long) (a[1] - o[1]) * (b[0] - o[0]);
    }

    // -----------------------------------------------------------------------
    // Largest axis-aligned rectangle inscribed in the tile polygon.
    //
    // A tile renders as a squashed diamond, and a diamond covers only half of
    // its own bounding box — so sampling the bounding box puts roughly half of
    // all clicks on a neighbouring tile. This returns a box every point of
    // which is on the tile, so the consumer can sample it naively.
    //
    // Binary search on a scale factor rather than the closed form for a
    // symmetric rhombus, because sloped ground projects tiles as irregular
    // quads where the closed form is wrong. 14 iterations is far past pixel
    // resolution and costs 56 Polygon.contains() calls, which is nothing at
    // one tick per waypoint.
    //
    // Coordinates in and out are canvas space.
    // -----------------------------------------------------------------------
    private int[] inscribedBox(Shape poly, int cx, int cy, Rectangle bounds, Rectangle clip)
    {
        if (poly == null || bounds == null) return null;
        if (!poly.contains(cx, cy)) return null;   // centre outside = degenerate
        // V2.26: an anchor outside the viewport can never yield a safe box.
        if (clip != null && !clip.contains(cx, cy)) return null;

        double halfW = bounds.width  / 2.0;
        double halfH = bounds.height / 2.0;
        if (halfW < 2 || halfH < 2) return null;   // too small on screen to matter

        double lo = 0.0, hi = 1.0;
        for (int i = 0; i < 14; i++)
        {
            double mid = (lo + hi) / 2.0;
            double w   = halfW * mid;
            double h   = halfH * mid;
            boolean fits = poly.contains(cx - w, cy - h)
                        && poly.contains(cx + w, cy - h)
                        && poly.contains(cx - w, cy + h)
                        && poly.contains(cx + w, cy + h)
                        // V2.28: edge midpoints too. Object clickboxes are
                        // composite shapes and may be non-convex, where four
                        // corners inside does not imply the box is inside.
                        // Redundant but harmless for convex shapes.
                        && poly.contains(cx,     cy - h)
                        && poly.contains(cx,     cy + h)
                        && poly.contains(cx - w, cy)
                        && poly.contains(cx + w, cy);
            // V2.26: and it must stay on screen. Both shapes are convex, so
            // testing the four corners is sufficient for each.
            if (fits && clip != null)
                fits = clip.contains(cx - w, cy - h) && clip.contains(cx + w, cy + h);
            if (fits) lo = mid; else hi = mid;
        }

        double w = halfW * lo;
        double h = halfH * lo;
        if (w < 1.0 || h < 1.0) return null;

        return new int[]{
                (int) Math.round(cx - w), (int) Math.round(cy - h),
                (int) Math.round(cx + w), (int) Math.round(cy + h)
        };
    }

    // -----------------------------------------------------------------------
    // Where a tile lies relative to the player and the current camera facing.
    //
    // Emitted for every waypoint whatever its state, because this is exactly
    // what an offscreen or offscene tile needs to report: it cannot be
    // projected, but its world position is known, so "which way do I turn"
    // always has an answer.
    //
    // Yaw convention matches compass_degrees: 0-2047, 0 = north, clockwise.
    // Returns { distTiles, bearingDeg, relBearingDeg, direction }.
    // -----------------------------------------------------------------------
    private Object[] bearingTo(WorldPoint player, int wx, int wy, int cameraYaw)
    {
        if (player == null) return new Object[]{ -1, -1, -999, "UNKNOWN" };

        int dx = wx - player.getX();          // +east
        int dy = wy - player.getY();          // +north
        int dist = (int) Math.round(Math.sqrt((double) dx * dx + (double) dy * dy));

        if (dx == 0 && dy == 0)
            return new Object[]{ 0, -1, 0, "HERE" };

        // atan2(east, north) gives 0 = due north, increasing clockwise.
        double bearing = Math.toDegrees(Math.atan2(dx, dy));
        if (bearing < 0) bearing += 360.0;

        double camDeg = (cameraYaw / 2048.0) * 360.0;
        double rel    = bearing - camDeg;
        while (rel > 180.0)  rel -= 360.0;
        while (rel < -180.0) rel += 360.0;

        String dir;
        double a = Math.abs(rel);
        if (a <= 30.0)       dir = "AHEAD";
        else if (a >= 150.0) dir = "BEHIND";
        else                 dir = rel > 0 ? "RIGHT" : "LEFT";

        return new Object[]{ dist, (int) Math.round(bearing), (int) Math.round(rel), dir };
    }

    // -----------------------------------------------------------------------
    // Tile under the mouse. Scans the current plane testing canvas tile
    // polygons, exactly as the world-location plugin does — deliberately not
    // getSelectedSceneTile(), which is the last right-clicked tile unless a
    // plugin drives setMouseCanvasHoverPosition() every frame.
    //
    // The centre-point reject keeps this cheap: localToCanvas is one
    // projection and returns null for anything off screen, so only tiles
    // within ~96px of the cursor ever build a polygon.
    //
    // Returns { worldX, worldY, plane, canvasX, canvasY } or null.
    // -----------------------------------------------------------------------
    private int[] findHoverTile(WorldView wv, int mouseX, int mouseY)
    {
        if (mouseX < 0 || mouseY < 0) return null;

        // Mouse must be inside the 3D viewport — over the inventory, chat or
        // any open interface there is no hovered tile, and reporting the tile
        // that happens to sit behind the UI would be worse than reporting none.
        try
        {
            int vx = client.getViewportXOffset();
            int vy = client.getViewportYOffset();
            int vw = client.getViewportWidth();
            int vh = client.getViewportHeight();
            if (vw > 0 && vh > 0
                    && (mouseX < vx || mouseY < vy || mouseX > vx + vw || mouseY > vy + vh))
                return null;
        }
        catch (Throwable ignored) { }

        int plane = wv.getPlane();
        Tile[][][] tiles;
        try { tiles = wv.getScene().getTiles(); }
        catch (Throwable t) { return null; }
        if (tiles == null || plane < 0 || plane >= tiles.length) return null;

        for (int x = 0; x < tiles[plane].length; x++)
        {
            for (int y = 0; y < tiles[plane][x].length; y++)
            {
                Tile t = tiles[plane][x][y];
                if (t == null) continue;

                LocalPoint lp = t.getLocalLocation();
                if (lp == null) continue;

                net.runelite.api.Point c = Perspective.localToCanvas(client, lp, plane);
                if (c == null) continue;
                if (Math.abs(c.getX() - mouseX) > 96 || Math.abs(c.getY() - mouseY) > 96) continue;

                Polygon poly = Perspective.getCanvasTilePoly(client, lp);
                if (poly == null || !poly.contains(mouseX, mouseY)) continue;

                WorldPoint wpt = t.getWorldLocation();
                if (wpt == null) continue;
                return new int[]{ wpt.getX(), wpt.getY(), wpt.getPlane(), c.getX(), c.getY() };
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // NPC aggression timer.
    //
    // The server remembers two tiles. Moving more than 10 steps from BOTH
    // shifts the older tile under you and restarts the 10 minute timer. The
    // client is never told where those tiles are, so — as the RuneLite wiki
    // spells out — the only way to learn them is to observe a move that must
    // have reset them: a jump far enough that both old anchors are guaranteed
    // out of range. A jump greater than 2x the 10 tile radius from the last
    // known position satisfies that regardless of where the anchors sat.
    //
    // Until such a jump is seen we report UNKNOWN. A wrong countdown is worse
    // than no countdown for anything that acts on it.
    // -----------------------------------------------------------------------
    private void updateAggroTimer()
    {
        try { if (!config.aggroTimerEnabled()) { aggroReset(); return; } }
        catch (Throwable ignored) { }

        if (client.getLocalPlayer() == null) return;
        WorldPoint p = client.getLocalPlayer().getWorldLocation();
        if (p == null) return;

        long now = System.currentTimeMillis();

        if (aggroLastLoc == null)
        {
            // First tick after login: position known, anchors are not.
            aggroLastLoc = p;
            return;
        }

        boolean jumped = p.getPlane() != aggroLastLoc.getPlane()
                || chebyshev(aggroLastLoc, p) > AGGRO_SAFE_RADIUS * 2;

        if (jumped)
        {
            aggroAnchorA = p;
            aggroAnchorB = p;
            aggroStartMs = now;
            aggroKnown   = true;
        }
        else if (aggroKnown)
        {
            boolean nearA = inAggroRange(aggroAnchorA, p);
            boolean nearB = inAggroRange(aggroAnchorB, p);
            if (!nearA && !nearB)
            {
                aggroAnchorA = aggroAnchorB;   // oldest tile moves under us
                aggroAnchorB = p;
                aggroStartMs = now;
            }
        }

        aggroLastLoc = p;
    }

    private boolean inAggroRange(WorldPoint anchor, WorldPoint p)
    {
        if (anchor == null) return false;
        if (anchor.getPlane() != p.getPlane()) return false;
        return chebyshev(anchor, p) <= AGGRO_SAFE_RADIUS;
    }

    private int chebyshev(WorldPoint a, WorldPoint b)
    {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    private void aggroReset()
    {
        aggroAnchorA = null;
        aggroAnchorB = null;
        aggroLastLoc = null;
        aggroStartMs = 0;
        aggroKnown   = false;
        aggroSawRlTimer = false;
    }

    private void writeIdle()
    {
        writeRaw(baseIdleHeader()
                + buildSlotState()
                + buildInventoryState()
                + idleFields());
    }

    private void writeLoggedOut()
    {
        writeRaw(baseIdleHeader()
                + buildSlotState()
                + buildInventoryState()
                + "action_required=false\naction=logged_out\npending_actions=\n"
                + "copilot_status=not_found\n"
                + "item_name=\nitem_id=\noffer_type=\ntarget_price=\ntarget_quantity=\nis_dump_alert=false\n"
                + "x1=0\ny1=0\nx2=0\ny2=0\n"
                + "action2=\nx1_2=0\ny1_2=0\nx2_2=0\ny2_2=0\n");
    }

    private void writeError(String reason)
    {
        writeRaw(baseIdleHeader()
                + buildSlotState()
                + buildInventoryState()
                + "action_required=false\naction=" + reason + "\npending_actions=\n"
                + "copilot_status=not_found\n"
                + "x1=0\ny1=0\nx2=0\ny2=0\n"
                + "action2=\nx1_2=0\ny1_2=0\nx2_2=0\ny2_2=0\n");
    }

    private void writeRaw(String content)
    {
        // Plugin v2.13 — Build the body once and publish it to the HTTP
        // server's in-memory string FIRST. This happens before the file
        // output gate so the /state endpoint stays current even when file
        // output is disabled, and the volatile write means the HTTP thread
        // always sees a complete buffer.
        String body = content + bondTracker.buildFileBlock();
        latestState = body;

        if (!config.fileOutputEnabled()) return;

        String folder = config.outputFolder();
        if (!folder.endsWith("\\") && !folder.endsWith("/"))
            folder += "\\";

        try
        {
            java.io.File dir = new java.io.File(folder);
            if (!dir.exists()) dir.mkdirs();
        }
        catch (Exception e)
        {
            log.warn("GEVisualAid could not create folder: {}", e.getMessage());
        }

        // Plugin v2.15 — Single-file output. Always write the one generic
        // ge_visual_aid.txt regardless of login state. The v2.2 account-
        // prefixed file (e.g. Gump12_ge_visual_aid.txt) and the logged-out
        // dual-write are removed: consumers now monitor exactly one path
        // that is always current, eliminating the stale-named-file class of
        // bugs (named file frozen at the pre-logout snapshot). The HTTP
        // endpoint already serves this same body from latestState above.
        writeOne(folder + "ge_visual_aid.txt", body);
    }

    private void writeOne(String path, String body)
    {
        try (FileWriter fw = new FileWriter(path, false))
        {
            fw.write(body);
        }
        catch (IOException e)
        {
            log.warn("GEVisualAid write error for {}: {}", path, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // HTTP server (Plugin v2.13)
    // Serves GET http://127.0.0.1:<port>/state as plain text — the exact
    // same payload written to the .txt. The response is built from the
    // volatile latestState string published by writeRaw, so the HTTP thread
    // never reads a partially written buffer and there is no file-read race.
    // -----------------------------------------------------------------------
    private void startHttpServer()
    {
        if (!config.httpServerEnabled())
        {
            log.info("GEVisualAid HTTP server disabled in config");
            return;
        }
        try
        {
            int port = config.httpServerPort();
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            httpServer.createContext("/state", this::handleStateRequest);
            httpServer.createContext("/path",  this::handlePathRequest);
            httpServer.createContext("/filter", this::handleFilterRequest);
            httpServer.createContext("/hop",    this::handleHopRequest);
            httpServer.createContext("/agility", this::handleAgilityRequest);
            httpServer.createContext("/plugin",  this::handlePluginRequest);
            // 2.73: one thread, so two plugin actions can never overlap,
            // and a daemon so it cannot hold the client open on exit.
            pluginExec = Executors.newSingleThreadExecutor(r ->
            {
                Thread t = new Thread(r, "GEVisualAid-PluginCtl");
                t.setDaemon(true);
                return t;
            });
            httpServer.setExecutor(Executors.newSingleThreadExecutor(r ->
            {
                Thread t = new Thread(r, "GEVisualAid-HTTP");
                t.setDaemon(true);
                return t;
            }));
            httpServer.start();
            log.info("GEVisualAid v2.13 HTTP server started on http://127.0.0.1:{}/state", port);
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid could not start HTTP server: {}", t.getMessage());
            httpServer = null;
        }
    }

    private void stopHttpServer()
    {
        try
        {
            if (pluginExec != null)
            {
                pluginExec.shutdownNow();
                pluginExec = null;
            }
        }
        catch (Throwable ignored) { }
        try
        {
            if (httpServer != null)
            {
                httpServer.stop(0);
                httpServer = null;
                log.info("GEVisualAid HTTP server stopped");
            }
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid HTTP server stop error: {}", t.getMessage());
        }
    }

    private void handleStateRequest(HttpExchange ex)
    {
        try
        {
            byte[] out = latestState.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody())
            {
                os.write(out);
            }
        }
        catch (Throwable t)
        {
            log.warn("GEVisualAid HTTP request error: {}", t.getMessage());
        }
        finally
        {
            ex.close();
        }
    }

    // -----------------------------------------------------------------------
    // Widget helpers
    // -----------------------------------------------------------------------
    private Widget getOfferChild(int child)
    {
        Widget c = client.getWidget(465, 26);
        return c == null ? null : c.getChild(child);
    }

    private Rectangle fullBounds(Widget w)
    {
        return new Rectangle(0, 0, w.getWidth(), w.getHeight());
    }

    private Widget findInventoryItem(Widget inv, int unnotedId)
    {
        Widget noted = null, unnoted = null;
        for (Widget w : inv.getDynamicChildren())
        {
            int id = w.getItemId();
            if (id < 0) continue;
            ItemComposition c = client.getItemDefinition(id);
            if (c.getNote() != -1 && c.getLinkedNoteId() == unnotedId) noted = w;
            else if (id == unnotedId) unnoted = w;
        }
        return noted != null ? noted : unnoted;
    }

    private int inventoryCount(Widget inv, int itemId)
    {
        int total = 0;
        for (Widget w : inv.getDynamicChildren())
            if (w.getItemId() == itemId) total += w.getItemQuantity();
        return total;
    }

    // -----------------------------------------------------------------------
    // Suggestion type checks
    // -----------------------------------------------------------------------
    private boolean isBuy(Object s)    throws Exception { return (boolean) invoke(s, "isBuySuggestion"); }
    private boolean isSell(Object s)   throws Exception { return (boolean) invoke(s, "isSellSuggestion"); }
    private boolean isAbort(Object s)  throws Exception { return (boolean) invoke(s, "isAbortSuggestion"); }
    private boolean isModify(Object s) throws Exception { return (boolean) invoke(s, "isModifySuggestion"); }
    private boolean isWait(Object s)   throws Exception { return (boolean) invoke(s, "isWaitSuggestion"); }
    private int     getOpenSlot()      { return client.getVarbitValue(4439) - 1; }

    private String getStringSafe(Object o, String m)
    {
        try { return (String) invoke(o, m); } catch (Exception e) { return ""; }
    }

    private int getIntSafe(Object o, String m)
    {
        // V2.20: was `(int) invoke(o, m)`. A hard cast on a boxed Long throws
        // ClassCastException, which the catch swallowed as -1 — that is why
        // target_price read -1 after Copilot widened price to 64-bit while
        // itemId/quantity stayed 32-bit. Unbox via Number instead so any
        // numeric width works.
        try
        {
            Object v = invoke(o, m);
            return (v instanceof Number) ? ((Number) v).intValue() : -1;
        }
        catch (Exception e) { return -1; }
    }

    private boolean getBoolSafe(Object o, String m)
    {
        try { return (boolean) invoke(o, m); } catch (Exception e) { return false; }
    }

    // -----------------------------------------------------------------------
    // Reflection — Copilot link (optional)
    // -----------------------------------------------------------------------
    private void linkToCopilot()
    {
        for (Plugin p : pluginManager.getPlugins())
        {
            if (!p.getClass().getName().equals(
                    "com.flippingcopilot.controller.FlippingCopilotPlugin")) continue;

            log.info("GEVisualAid: found FlippingCopilotPlugin, linking...");
            suggestionManager            = getField(p, "suggestionManager");
            accountStatusManager         = getField(p, "accountStatusManager");
            // V2.18: was two hardcoded field-name lookups; both returned null
            // after a Copilot refactor, silently blanking every copilot_* pref.
            suggestionPreferencesManager = findPreferencesManager(p);

            // Grab profitCalculator via tooltipController
            Object tooltipController = getField(p, "tooltipController");
            if (tooltipController != null)
            {
                profitCalculator = getField(tooltipController, "profitCalculator");
                if (profitCalculator != null)
                    log.info("GEVisualAid: linked to Copilot ProfitCalculator successfully");
                else
                    log.warn("GEVisualAid: tooltipController found but profitCalculator field missing");
            }
            else
            {
                log.warn("GEVisualAid: tooltipController field not found on FlippingCopilotPlugin");
            }

            if (suggestionManager != null)
                log.info("GEVisualAid: linked to Copilot suggestionManager successfully");
            else
                log.warn("GEVisualAid: could not read Copilot fields");
            return;
        }
        log.warn("GEVisualAid: FlippingCopilotPlugin not loaded \u2014 GE monitor mode only");
    }

    private void linkToApm()
    {
        for (Plugin p : pluginManager.getPlugins())
        {
            if (!p.getClass().getName().equals("com.apm.ApmPlugin")) continue;
            apmPlugin = p;
            log.info("GEVisualAid: linked to ApmPlugin successfully");
            return;
        }
        log.info("GEVisualAid: ApmPlugin not loaded — APM will read as 0");
    }

    /** Returns int[]{currentApm, sessionAvgApm} from ApmPlugin via reflection, or {0,0}. */
    private int[] getApmValues()
    {
        if (apmPlugin == null) return new int[]{0, 0};
        try
        {
            int currentApm = (int) getFieldValue(apmPlugin, "currentApm");
            int total      = (int) getFieldValue(apmPlugin, "totalInputCount");
            int seconds    = (int) getFieldValue(apmPlugin, "seconds");
            int sessionAvg = seconds > 0 ? (int)(total / (seconds / 60.0)) : 0;
            return new int[]{currentApm, sessionAvg};
        }
        catch (Exception e)
        {
            log.warn("GEVisualAid: APM read error: {}", e.getMessage());
            return new int[]{0, 0};
        }
    }

    private Object getFieldValue(Object obj, String name) throws Exception
    {
        Class<?> cls = obj.getClass();
        while (cls != null)
        {
            try
            {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            }
            catch (NoSuchFieldException ignored) { cls = cls.getSuperclass(); }
        }
        throw new NoSuchFieldException(name + " not found in hierarchy of "
                + obj.getClass().getSimpleName());
    }

    // V2.18: Locate Copilot's preferences manager without depending on a single
    // hardcoded field name. Pass 1 tries the known names (quietly — a miss here
    // is expected after a rename and should not log a warning). Pass 2 scans
    // every field in the class hierarchy for one whose TYPE name contains
    // "Preferences". Logs whichever route succeeded so a future rename is
    // visible in client.log rather than silently blanking the copilot_* fields.
    private Object findPreferencesManager(Object plugin)
    {
        Class<?> cls = plugin.getClass();
        while (cls != null)
        {
            for (String name : new String[]{ "preferencesManager", "suggestionPreferencesManager" })
            {
                try
                {
                    Field f = cls.getDeclaredField(name);
                    f.setAccessible(true);
                    Object v = f.get(plugin);
                    if (v != null)
                    {
                        log.info("GEVisualAid: preferences manager linked via field '{}' (type {})",
                                name, v.getClass().getSimpleName());
                        return v;
                    }
                }
                catch (Exception ignored) { }
            }
            cls = cls.getSuperclass();
        }

        cls = plugin.getClass();
        while (cls != null)
        {
            for (Field f : cls.getDeclaredFields())
            {
                if (!f.getType().getSimpleName().toLowerCase().contains("preferences")) continue;
                try
                {
                    f.setAccessible(true);
                    Object v = f.get(plugin);
                    if (v != null)
                    {
                        log.info("GEVisualAid: preferences manager found by TYPE scan: field='{}' type='{}'",
                                f.getName(), f.getType().getSimpleName());
                        return v;
                    }
                }
                catch (Exception ignored) { }
            }
            cls = cls.getSuperclass();
        }

        log.warn("GEVisualAid: NO preferences manager field found on FlippingCopilotPlugin "
                + "\u2014 all copilot_* preference fields will be blank");
        return null;
    }

    private Object getField(Object obj, String name)
    {
        Class<?> cls = obj.getClass();
        while (cls != null)
        {
            try
            {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            }
            catch (NoSuchFieldException ignored) { cls = cls.getSuperclass(); }
            catch (Exception e)
            {
                log.warn("getField({}) failed: {}", name, e.getMessage());
                return null;
            }
        }
        log.warn("getField({}) not found in hierarchy", name);
        return null;
    }

    // -----------------------------------------------------------------------
    // Plugin v2.6 — Pull the full PortfolioSummaryData from Flipping Copilot
    // in a single reflection traversal, plus expose the portfolio's tracked
    // item-ID set for the untracked-inventory alert. Both reach via the
    // already-linked profitCalculator. The chain is:
    //   profitCalculator
    //     .portfolioStateRS                         (PortfolioStateRS field)
    //     .get()                                    (returns PortfolioState)
    //     .getSummaryData()  / .getItemCardDataByItemId()
    //
    // getPortfolioSummary() returns long[5] in the order:
    //   [0] portfolio_market_value     ← getPortfolioMarketValue()
    //   [1] portfolio_unrealised_profit ← getUnrealizedProfit()
    //   [2] portfolio_cash_value       ← getCashValue()
    //   [3] portfolio_assets_value     ← getAssetsValue()
    //   [4] portfolio_locked_buy_cash  ← getLockedBuyCash()
    // Returns null (not zeros) on failure so the emit site can distinguish
    // "Copilot disconnected" from "all values genuinely zero".
    //
    // getInPortfolioItemIds() (v2.7) iterates the map entries and returns
    // the IDs where isInPortfolio=true or portfolioQuantity>0. Returns
    // null on failure so the alert path can skip emitting rather than
    // false-flag every inventory item as untracked.
    // -----------------------------------------------------------------------
    private Object getPortfolioState()
    {
        if (profitCalculator == null) return null;
        try
        {
            Object portfolioStateRS = getField(profitCalculator, "portfolioStateRS");
            if (portfolioStateRS == null) return null;
            java.lang.reflect.Method getMethod = portfolioStateRS.getClass().getMethod("get");
            return getMethod.invoke(portfolioStateRS);
        }
        catch (Throwable t)
        {
            log.debug("getPortfolioState failed: {}", t.getMessage());
            return null;
        }
    }

    private long[] getPortfolioSummary()
    {
        Object portfolioState = getPortfolioState();
        if (portfolioState == null) return null;
        try
        {
            java.lang.reflect.Method getSummaryMethod = portfolioState.getClass().getMethod("getSummaryData");
            Object summary = getSummaryMethod.invoke(portfolioState);
            if (summary == null) return null;

            Class<?> sCls = summary.getClass();
            long marketValue   = ((Number) sCls.getMethod("getPortfolioMarketValue").invoke(summary)).longValue();
            long unrealised    = ((Number) sCls.getMethod("getUnrealizedProfit").invoke(summary)).longValue();
            long cashValue     = ((Number) sCls.getMethod("getCashValue").invoke(summary)).longValue();
            long assetsValue   = ((Number) sCls.getMethod("getAssetsValue").invoke(summary)).longValue();
            long lockedBuyCash = ((Number) sCls.getMethod("getLockedBuyCash").invoke(summary)).longValue();
            return new long[]{ marketValue, unrealised, cashValue, assetsValue, lockedBuyCash };
        }
        catch (Throwable t)
        {
            log.debug("getPortfolioSummary failed: {}", t.getMessage());
            return null;
        }
    }

    /**
     * v2.7 — Item IDs currently marked as "in the portfolio" by Flipping
     * Copilot. The map itemCardDataByItemId contains entries for every
     * item FC knows about (inventory items, recent flips, etc.) — NOT
     * just the actively-tracked ones. v2.8 expanded the tracking check
     * to cover four FC-side indicators (any one positive = tracked):
     *   isInPortfolio                 (boolean)
     *   isPartiallyInPortfolio        (boolean — catches the Earth-orb
     *                                  partial-stack case)
     *   portfolioQuantity > 0         (long)
     *   notInPortfolioQuantity <
     *     runeliteInventoryQuantity   (some portion is in portfolio)
     *
     * Returns null if the reflection chain breaks at any point so the
     * caller can skip the alert rather than false-flag every item.
     *
     * Side-effects diagnostic counts via the passed-in int[2]:
     *   [0] = total map entries (how many items FC knows about)
     *   [1] = entries that pass the tracking check above
     *
     * Side-effects per-item card data via the passed-in Map for any
     * item the caller might still flag — used by the diagnostic emitter
     * so we don't traverse the FC map twice per write.
     */
    private java.util.Set<Integer> getInPortfolioItemIds(
            int[] counts,
            java.util.Map<Integer, Object> cardDataByItemId)
    {
        Object portfolioState = getPortfolioState();
        if (portfolioState == null) return null;
        try
        {
            java.lang.reflect.Method getMapMethod =
                    portfolioState.getClass().getMethod("getItemCardDataByItemId");
            Object mapObj = getMapMethod.invoke(portfolioState);
            if (!(mapObj instanceof java.util.Map)) return null;

            @SuppressWarnings("unchecked")
            java.util.Map<Integer, ?> rawMap = (java.util.Map<Integer, ?>) mapObj;

            java.util.Set<Integer> tracked = new java.util.HashSet<>();
            int total = 0;
            int inPortfolio = 0;
            for (java.util.Map.Entry<Integer, ?> entry : rawMap.entrySet())
            {
                total++;
                Object cardData = entry.getValue();
                if (cardData == null) continue;

                // Stash for diagnostic emitter — we re-read specific
                // fields from the same object rather than refetching.
                if (cardDataByItemId != null)
                {
                    cardDataByItemId.put(entry.getKey(), cardData);
                }

                if (cardDataSaysTracked(cardData))
                {
                    tracked.add(entry.getKey());
                    inPortfolio++;
                }
            }

            if (counts != null && counts.length >= 2)
            {
                counts[0] = total;
                counts[1] = inPortfolio;
            }
            return tracked;
        }
        catch (Throwable t)
        {
            log.debug("getInPortfolioItemIds failed: {}", t.getMessage());
            return null;
        }
    }

    /**
     * v2.8/v2.12 — Returns true if FC considers this PortfolioItemCardData
     * to be currently in the portfolio. v2.8 had a four-way OR check;
     * v2.12 simplified to the canonical isInPortfolio() flag after
     * confirmation from the FC source that:
     *
     *   isInPortfolio()           ⟺ portfolioQuantity > 0
     *   isPartiallyInPortfolio()  ⟹ isInPortfolio()  (strict subset)
     *
     * So the v2.8 four-way OR collapses to a single check. The
     * additional checks added defensive overhead without semantic
     * value — and the suggestion-list union from v2.9 (which v2.12
     * also removed) was the actual source of false negatives, not
     * any missing card-data flag.
     *
     * This mirrors what FC's own InventorySlotTooltipDataProvider
     * does for the per-slot tooltip: "data != null && data.isInPortfolio()".
     */
    private boolean cardDataSaysTracked(Object cardData)
    {
        if (cardData == null) return false;
        Boolean flag = safeInvokeBool(cardData, cardData.getClass(), "isInPortfolio");
        return Boolean.TRUE.equals(flag);
    }

    /** Reflective method invoke returning Boolean or null on failure. */
    private Boolean safeInvokeBool(Object target, Class<?> cls, String methodName)
    {
        try
        {
            Object v = cls.getMethod(methodName).invoke(target);
            return v instanceof Boolean ? (Boolean) v : null;
        }
        catch (Throwable t) { return null; }
    }

    /** Reflective method invoke returning long, defaulting to 0 on failure. */
    private long safeInvokeLong(Object target, Class<?> cls, String methodName)
    {
        try
        {
            Object v = cls.getMethod(methodName).invoke(target);
            return v instanceof Number ? ((Number) v).longValue() : 0L;
        }
        catch (Throwable t) { return 0L; }
    }

    /**
     * v2.11 — Convert a raw inventory item ID to its unnoted form via
     * ItemController.toUnnotedItemId(). FC's portfolio stores the
     * unnoted ID even when the player holds the noted form, so any
     * portfolio-membership check has to convert first. Earth orb's
     * inventory ID is 576 (noted, stackable) but FC's portfolio stores
     * 575 (unnoted) — without this conversion every Earth orb check
     * misses.
     *
     * Path:
     *   accountStatusManager  (already linked in linkToCopilot)
     *     .itemController     (ItemController field)
     *     .toUnnotedItemId(int) → int
     *
     * Returns the original ID if the reflection chain breaks so we
     * degrade to v2.10 behaviour rather than blocking the alert
     * entirely.
     */
    private int toUnnotedItemId(int rawId)
    {
        if (accountStatusManager == null) return rawId;
        try
        {
            Object itemController = getField(accountStatusManager, "itemController");
            if (itemController == null) return rawId;
            java.lang.reflect.Method m =
                    itemController.getClass().getMethod("toUnnotedItemId", int.class);
            Object result = m.invoke(itemController, rawId);
            return result instanceof Number ? ((Number) result).intValue() : rawId;
        }
        catch (Throwable t)
        {
            log.debug("toUnnotedItemId({}) failed: {}", rawId, t.getMessage());
            return rawId;
        }
    }

    /**
     * v2.9 — Reads the server-authoritative portfolio item-ID set from
     * the Suggestion object. Path:
     *   suggestionManager.getSuggestion()  → Suggestion
     *     .portfolioItems                  → List<Suggestion.PortfolioItem>
     *       .itemId                        → int (one per entry)
     *
     * This is the source we need for items that exist in the user's
     * portfolio but have no PortfolioItemCardData entry — typically
     * stackable consumables like Earth orb that get added to portfolio
     * before FC has any other state for them.
     *
     * Returns an empty set (not null) when the reflection chain breaks,
     * because the union semantics work fine with an empty contribution
     * — we don't want to suppress the entire alert just because this
     * one source is unavailable.
     */
    private java.util.Set<Integer> getPortfolioItemIdsFromSuggestion()
    {
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        if (suggestionManager == null) return ids;
        try
        {
            java.lang.reflect.Method getSugMethod =
                    suggestionManager.getClass().getMethod("getSuggestion");
            Object suggestion = getSugMethod.invoke(suggestionManager);
            if (suggestion == null) return ids;

            Object portfolioItems = getField(suggestion, "portfolioItems");
            if (!(portfolioItems instanceof java.util.List)) return ids;

            for (Object pi : (java.util.List<?>) portfolioItems)
            {
                if (pi == null) continue;
                try
                {
                    java.lang.reflect.Field idField = pi.getClass().getDeclaredField("itemId");
                    idField.setAccessible(true);
                    Object idObj = idField.get(pi);
                    if (idObj instanceof Number)
                    {
                        ids.add(((Number) idObj).intValue());
                    }
                }
                catch (Throwable ignored) { /* skip this entry, continue */ }
            }
        }
        catch (Throwable t)
        {
            log.debug("getPortfolioItemIdsFromSuggestion failed: {}", t.getMessage());
        }
        return ids;
    }

    /**
     * v2.6 — Build the portfolio_* emit block. Five lines pulled from one
     * PortfolioSummaryData traversal. All zero-defaulted on null so the
     * field set stays stable for AHK consumers.
     */
    private String buildPortfolioState()
    {
        long[] s = getPortfolioSummary();
        if (s == null)
        {
            return "portfolio_market_value=0\n"
                    + "portfolio_unrealised_profit=0\n"
                    + "portfolio_cash_value=0\n"
                    + "portfolio_assets_value=0\n"
                    + "portfolio_locked_buy_cash=0\n";
        }
        return "portfolio_market_value="     + s[0] + "\n"
                + "portfolio_unrealised_profit=" + s[1] + "\n"
                + "portfolio_cash_value="       + s[2] + "\n"
                + "portfolio_assets_value="     + s[3] + "\n"
                + "portfolio_locked_buy_cash="  + s[4] + "\n";
    }

    /**
     * v2.6 — Detect inventory items that are not tracked in the Flipping
     * Copilot portfolio. v2.7 — fixed to read each PortfolioItemCardData's
     * isInPortfolio flag rather than treating any map key as "tracked".
     * v2.8 — expanded tracking check (isInPortfolio | isPartiallyInPortfolio
     * | portfolioQuantity>0 | partial inventory coverage) and added per-item
     * diagnostic so we can see exactly what FC reports for each item still
     * in the alert list.
     *
     * Filters applied (in order, each one removes noise):
     *   • Empty slots
     *   • Coins (id 995) — never trackable as a portfolio item
     *   • Untradeable items — can't be flipped, would always show as
     *     untracked otherwise (quest items, untradeable gear, etc.)
     *   • Items where cardDataSaysTracked() returns true
     *
     * Output format:
     *   portfolio_known_item_count=N        — items FC has any record of
     *   portfolio_in_portfolio_count=N      — items FC considers tracked
     *   untracked_inv_count=2
     *   untracked_inv_list=5:1234:Rune sword|12:5678:Dragon dagger
     *   untracked_inv_card_data=5:1234:known=n:inPort=n:partial=n:portQty=0|12:5678:known=y:inPort=n:partial=n:portQty=0
     *
     * The list uses `|` as the row separator since OSRS item names can
     * legitimately contain commas (e.g. "Bow, dragon"). Within an alert
     * row, the three fields are slot:id:name in fixed order. Within a
     * card-data row, the fields are slot:id:known:inPort:partial:portQty.
     *
     * If the portfolio set can't be retrieved (Copilot disconnected, etc.)
     * we emit count=0 and an empty list so AHK doesn't spuriously flag
     * every item as untracked.
     */
    private String buildUntrackedInventoryState()
    {
        int[] counts = new int[2];
        java.util.Map<Integer, Object> cardDataByItemId = new java.util.HashMap<>();
        java.util.Set<Integer> trackedIds = getInPortfolioItemIds(counts, cardDataByItemId);

        // v2.12 — Suggestion-list IDs read for diagnostic only.
        // v2.9 unioned this into trackedIds; v2.12 reverted because the
        // list contains every item FC has ever tracked (including
        // ghost/disappeared portfolio entries), not just currently-in-
        // portfolio items. The canonical "is item X in the portfolio
        // RIGHT NOW" signal is cardData.isInPortfolio() — same as what
        // FC's InventorySlotTooltipDataProvider uses for tooltips.
        java.util.Set<Integer> suggestionIds = getPortfolioItemIdsFromSuggestion();

        if (trackedIds == null)
        {
            // Card-data path completely unavailable — emit a safe count=0
            // rather than false-flagging everything as untracked.
            trackedIds = new java.util.HashSet<>();
        }

        StringBuilder list = new StringBuilder();
        StringBuilder diag = new StringBuilder();
        int count = 0;
        for (int i = 0; i < 28; i++)
        {
            InventorySlot s = inventorySlots[i];
            int rawId = s.getItemId();
            if (rawId <= 0) continue;                      // empty slot
            if (rawId == 995) continue;                    // Coins (gp) — never a flip target

            // v2.11 — FC's portfolio stores the unnoted ID. Convert
            // the inventory ID before any portfolio comparison.
            int unnotedId = toUnnotedItemId(rawId);

            if (trackedIds.contains(unnotedId)) continue;  // FC has it marked as tracked (either source)

            // Untradeable items can't be flipped — skip so they don't
            // generate false alerts. Wrapped in try because ItemManager
            // can throw on freshly-loaded items in some edge cases.
            // Note: we check the RAW id here — itemManager handles the
            // noted/unnoted distinction itself for tradeable lookups.
            try
            {
                if (!itemManager.getItemComposition(rawId).isTradeable()) continue;
            }
            catch (Throwable ignored) { /* if we can't tell, include it */ }

            // Item is in the alert list — record what FC says about it.
            if (count > 0) { list.append('|'); diag.append('|'); }
            list.append(i + 1).append(':')
                    .append(rawId).append(':')
                    .append(s.getItemName());

            // Look up card-data using the UNNOTED id (FC's key).
            Object cardData = cardDataByItemId.get(unnotedId);
            boolean known    = cardData != null;
            boolean inPort   = false;
            boolean partial  = false;
            long    portQty  = 0L;
            if (known)
            {
                Class<?> cls = cardData.getClass();
                Boolean ip = safeInvokeBool(cardData, cls, "isInPortfolio");
                Boolean pp = safeInvokeBool(cardData, cls, "isPartiallyInPortfolio");
                inPort  = Boolean.TRUE.equals(ip);
                partial = Boolean.TRUE.equals(pp);
                portQty = safeInvokeLong(cardData, cls, "getPortfolioQuantity");
            }
            boolean suggPort = suggestionIds.contains(unnotedId);
            diag.append(i + 1).append(':')
                    .append(rawId).append(':')
                    .append("unnoted=").append(unnotedId).append(':')
                    .append("known=").append(known    ? 'y' : 'n').append(':')
                    .append("inPort=").append(inPort  ? 'y' : 'n').append(':')
                    .append("partial=").append(partial ? 'y' : 'n').append(':')
                    .append("portQty=").append(portQty).append(':')
                    .append("suggPort=").append(suggPort ? 'y' : 'n');
            count++;
        }
        return "portfolio_known_item_count=" + counts[0] + "\n"
                + "portfolio_in_portfolio_count=" + counts[1] + "\n"
                + "portfolio_suggestion_item_count=" + suggestionIds.size() + "\n"
                + "untracked_inv_count=" + count + "\n"
                + "untracked_inv_list="  + list  + "\n"
                + "untracked_inv_card_data=" + diag + "\n";
    }

    private Object invoke(Object obj, String methodName, Object... args) throws Exception
    {
        Class<?> cls = obj.getClass();
        while (cls != null)
        {
            for (Method m : cls.getDeclaredMethods())
            {
                if (m.getName().equals(methodName) && m.getParameterCount() == args.length)
                {
                    m.setAccessible(true);
                    return m.invoke(obj, args);
                }
            }
            cls = cls.getSuperclass();
        }
        throw new NoSuchMethodException(methodName + " not found on "
                + obj.getClass().getSimpleName());
    }
}
