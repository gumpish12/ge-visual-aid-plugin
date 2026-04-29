package com.ge.gevisualaid;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Tracks Old School Bond (item ID 13190) purchases made through the Grand Exchange.
 *
 * Persists across sessions via ConfigManager. The bond stats are appended to
 * the file output each tick alongside the rest of the GE state.
 *
 * Stats tracked:
 *   - Total bonds purchased (all time, across sessions)
 *   - Total GP spent on bonds
 *   - Price of the most recently completed bond purchase
 */
@Slf4j
@Singleton
public class BondTracker
{
    /** Item ID for a tradeable Old School Bond. */
    public static final int BOND_ITEM_ID = 13190;

    private static final String CONFIG_GROUP      = "ge-visual-aid";
    private static final String KEY_BOND_COUNT    = "bondCount";
    private static final String KEY_BOND_TOTAL_GP = "bondTotalGp";
    private static final String KEY_BOND_LAST_GP  = "bondLastGp";

    @Inject
    private ConfigManager configManager;

    private int  bondCount   = 0;
    private long bondTotalGp = 0;
    private long bondLastGp  = 0;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    public void load()
    {
        try
        {
            String count = configManager.getConfiguration(CONFIG_GROUP, KEY_BOND_COUNT);
            String total = configManager.getConfiguration(CONFIG_GROUP, KEY_BOND_TOTAL_GP);
            String last  = configManager.getConfiguration(CONFIG_GROUP, KEY_BOND_LAST_GP);

            bondCount   = count != null ? Integer.parseInt(count) : 0;
            bondTotalGp = total != null ? Long.parseLong(total)   : 0;
            bondLastGp  = last  != null ? Long.parseLong(last)    : 0;
        }
        catch (Exception e)
        {
            log.warn("BondTracker load error: {}", e.getMessage());
        }
    }

    public void save()
    {
        configManager.setConfiguration(CONFIG_GROUP, KEY_BOND_COUNT,    bondCount);
        configManager.setConfiguration(CONFIG_GROUP, KEY_BOND_TOTAL_GP, bondTotalGp);
        configManager.setConfiguration(CONFIG_GROUP, KEY_BOND_LAST_GP,  bondLastGp);
    }

    public void reset()
    {
        bondCount   = 0;
        bondTotalGp = 0;
        bondLastGp  = 0;
        save();
    }

    // -----------------------------------------------------------------------
    // Recording
    // -----------------------------------------------------------------------

    /**
     * Call this when a BOUGHT offer completes. Pass the SlotState so we can
     * check whether it was a bond purchase and record it.
     *
     * @param s the completed SlotState
     */
    public void onOfferComplete(SlotState s)
    {
        if (s.getItemId() != BOND_ITEM_ID) return;
        if (!"buy".equals(s.getOfferType()))       return;

        int  qty      = s.getQuantityDone();
        long costEach = s.getPriceEach();
        long total    = costEach * qty;

        bondCount   += qty;
        bondTotalGp += total;
        bondLastGp   = costEach;   // price per bond for this purchase

        log.info("BondTracker: recorded {} bond(s) @ {:,}gp each (total {:,}gp). "
                        + "All-time: {} bonds, {:,}gp spent.",
                qty, costEach, total, bondCount, bondTotalGp);

        save();
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public int  getBondCount()   { return bondCount;   }
    public long getBondTotalGp() { return bondTotalGp; }
    public long getBondLastGp()  { return bondLastGp;  }

    // -----------------------------------------------------------------------
    // Formatting helpers
    // -----------------------------------------------------------------------

    public String formatGp(long amount)
    {
        if (Math.abs(amount) >= 1_000_000)
            return String.format("%.2fM", amount / 1_000_000.0);
        if (Math.abs(amount) >= 1_000)
            return String.format("%.1fK", amount / 1_000.0);
        return amount + "gp";
    }

    /**
     * Returns the bond stats block that gets appended to the .txt file output.
     * Format matches the rest of the file — one key=value per line.
     */
    public String buildFileBlock()
    {
        return "bond_count="     + bondCount                   + "\n"
             + "bond_total_gp="  + bondTotalGp                 + "\n"
             + "bond_last_gp="   + bondLastGp                  + "\n"
             + "bond_total_fmt=" + formatGp(bondTotalGp)        + "\n"
             + "bond_last_fmt="  + (bondLastGp > 0
                                     ? formatGp(bondLastGp)
                                     : "n/a")                  + "\n";
    }
}
