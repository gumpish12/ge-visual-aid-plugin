package com.ge.gevisualaid;

import lombok.Data;

@Data
public class InventorySlot
{
    private int    itemId    = -1;
    private String itemName  = "";
    private int    quantity  = 0;
    // RuneLite 1.13.0 widened ItemManager.getItemPrice from int to long, and a
    // price CAN now exceed Integer.MAX_VALUE. Narrowing it back would overflow to
    // a negative silently, which is the worst possible reading for a value field.
    private long   valueEach = 0;
}