package com.ge.gevisualaid;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import java.time.LocalDate;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static final String PLUGIN_OUTPUT_VERSION = "2.2";

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
    private String lastKnownPlayerName = "";

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
        for (int i = 0; i < 8; i++)  slots[i]          = new SlotState();
        for (int i = 0; i < 28; i++) inventorySlots[i]  = new InventorySlot();
        session.load();
        bondTracker.load();
        overlayManager.add(overlay);
        linkToCopilot();
        linkToApm();

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
                .tooltip("GE Visual Aid")
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
        writeIdle();
    }

    @Override
    protected void shutDown()
    {
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
    public void onGameTick(GameTick tick)
    {
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
            log.warn("GEVisualAid resolve error: {}", e.getMessage());
        }
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

        String  sugType   = (String) invoke(sug, "offerType");
        int     sugItemId = (int)    invoke(sug, "getItemId");
        int     sugPrice  = (int)    invoke(sug, "getPrice");
        int     sugQty    = (int)    invoke(sug, "getQuantity");

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
        boolean geOfferScreen = isVisible(465, 26);
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
            geSlotOpen  = getOpenSlot() + 1;
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
                + "game_state=" + lastGameState.name() + "\n"
                + "login_index=" + safeLoginIndex() + "\n"
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
                + buildClerkState()
                + buildCopilotPreferencesState()
                + buildApmAndMembershipState();
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
                + "game_state=" + lastGameState.name() + "\n"
                + "login_index=" + safeLoginIndex() + "\n"
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
                + buildClerkState()
                + buildCopilotPreferencesState()
                + buildApmAndMembershipState();
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
        if (!config.fileOutputEnabled()) return;

        // Plugin v2.2 — Determine the current logged-in player name (or
        // empty if not logged in) and remember it for use after logout.
        String currentName = "";
        if (client.getLocalPlayer() != null
                && client.getLocalPlayer().getName() != null)
        {
            currentName = client.getLocalPlayer().getName();
            if (!currentName.isEmpty()) lastKnownPlayerName = currentName;
        }

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

        String body = content + bondTracker.buildFileBlock();

        if (!currentName.isEmpty())
        {
            // Logged in — write only to the player-named file. This is
            // the canonical artefact that AHK consumers monitor.
            writeOne(folder + currentName + "_ge_visual_aid.txt", body);
        }
        else
        {
            // Logged out (or never logged in this session) — write the
            // generic file always. ALSO write the player-named file if
            // we ever observed a logged-in player this session, so the
            // named file doesn't go stale after logout. Without this
            // dual-write, AHK consumers monitoring Gump12_ge_visual_aid.txt
            // would keep seeing the pre-logout snapshot indefinitely.
            writeOne(folder + "ge_visual_aid.txt", body);
            if (!lastKnownPlayerName.isEmpty())
            {
                writeOne(folder + lastKnownPlayerName + "_ge_visual_aid.txt", body);
            }
        }
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
        try { return (int) invoke(o, m); } catch (Exception e) { return -1; }
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
            suggestionPreferencesManager = getField(p, "preferencesManager");
            if (suggestionPreferencesManager == null)
                suggestionPreferencesManager = getField(p, "suggestionPreferencesManager");

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
