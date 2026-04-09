package com.ge.gevisualaid;

import lombok.Data;

@Data
public class SlotState
{
    private int itemId = -1;
    private String itemName = "";
    private String status = "empty";
    private int quantityDone = 0;
    private int quantityTotal = 0;
    private int priceEach = 0;
    private String offerType = "";
    private long lastChangedMs = 0;
}