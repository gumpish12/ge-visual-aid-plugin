package com.ge.gevisualaid;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Singleton
public class SessionTracker
{
    private static final String CONFIG_GROUP = "ge-visual-aid";
    private static final String KEY_TOTAL_PROFIT = "totalProfit";
    private static final String KEY_TOTAL_FLIPS  = "totalFlips";
    private static final String KEY_BEST_FLIP    = "bestFlip";

    @Inject
    private ConfigManager configManager;

    // In-memory buy price tracking: itemId -> price paid per item
    private final Map<Integer, Integer> buyPrices = new HashMap<>();

    // Session totals (loaded from persistent config on startup)
    private long totalProfit = 0;
    private int  totalFlips  = 0;
    private long bestFlip    = 0;

    public void load()
    {
        try
        {
            String tp = configManager.getConfiguration(CONFIG_GROUP, KEY_TOTAL_PROFIT);
            String tf = configManager.getConfiguration(CONFIG_GROUP, KEY_TOTAL_FLIPS);
            String bf = configManager.getConfiguration(CONFIG_GROUP, KEY_BEST_FLIP);
            totalProfit = tp != null ? Long.parseLong(tp) : 0;
            totalFlips  = tf != null ? Integer.parseInt(tf) : 0;
            bestFlip    = bf != null ? Long.parseLong(bf) : 0;
        }
        catch (Exception e)
        {
            log.warn("SessionTracker load error: {}", e.getMessage());
        }
    }

    public void save()
    {
        configManager.setConfiguration(CONFIG_GROUP, KEY_TOTAL_PROFIT, totalProfit);
        configManager.setConfiguration(CONFIG_GROUP, KEY_TOTAL_FLIPS,  totalFlips);
        configManager.setConfiguration(CONFIG_GROUP, KEY_BEST_FLIP,    bestFlip);
    }

    public void reset()
    {
        totalProfit = 0;
        totalFlips  = 0;
        bestFlip    = 0;
        buyPrices.clear();
        save();
    }

    public void recordBuy(int itemId, int priceEach)
    {
        buyPrices.put(itemId, priceEach);
    }

    public long recordSell(int itemId, int sellPriceEach, int quantity)
    {
        if (!buyPrices.containsKey(itemId)) return 0;
        int buyPrice = buyPrices.get(itemId);
        long margin  = (long)(sellPriceEach - buyPrice) * quantity;
        totalProfit += margin;
        totalFlips++;
        if (margin > bestFlip) bestFlip = margin;
        save();
        return margin;
    }

    public long getTotalProfit() { return totalProfit; }
    public int  getTotalFlips()  { return totalFlips; }
    public long getBestFlip()    { return bestFlip; }

    public String formatGp(long amount)
    {
        if (Math.abs(amount) >= 1_000_000)
            return String.format("%.1fM", amount / 1_000_000.0);
        if (Math.abs(amount) >= 1_000)
            return String.format("%.1fK", amount / 1_000.0);
        return amount + "gp";
    }
}