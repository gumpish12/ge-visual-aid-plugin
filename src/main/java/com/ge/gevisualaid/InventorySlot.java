package com.ge.gevisualaid;

import lombok.Data;

@Data
public class InventorySlot
{
    private int    itemId    = -1;
    private String itemName  = "";
    private int    quantity  = 0;
    private int    valueEach = 0;
}