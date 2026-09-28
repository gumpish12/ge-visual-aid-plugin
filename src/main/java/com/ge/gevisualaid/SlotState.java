package com.ge.gevisualaid;

import lombok.Data;

@Data
public class SlotState
{
    private int    itemId          = -1;
    private String itemName        = "";
    private String status          = "empty";
    private int    quantityDone    = 0;
    private int    quantityTotal   = 0;
    // RuneLite 1.13.0 widened GrandExchangeOffer.getPrice/getSpent to long.
    // Kept as long the whole way through rather than narrowed at the door: a
    // cast back to int overflows to a NEGATIVE price silently, and a negative
    // price feeds straight into the profit maths.
    private long   priceEach       = 0;
    private String offerType       = "";
    private long   lastChangedMs   = 0;

    /**
     * Copilot's estimated profit per item for this slot (raw GP).
     * Populated by reading the profit text Copilot injects into the
     * GE tooltip widget when you hover over a selling slot.
     * 0 = unknown (buying slots, or tooltip not yet seen this session).
     */
    private long   copilotProfitGp = 0;
}
