package com.osrsloottracker;

import lombok.Data;

/**
 * Data class representing a loot drop to be submitted
 */
@Data
public class LootDropData
{
    private String username;
    private String itemName;
    private int quantity;
    private int value;
    private String sourceName; // Monster, raid, etc.
    private String dropType;   // NPC, PLAYER, EVENT, COLLECTION_LOG, PET
    private String screenshotUrl; // Optional screenshot URL
    private long timestamp; // When the drop occurred
    
    // Constructor without screenshot
    public LootDropData(String username, String itemName, int quantity, int value, 
                        String sourceName, String dropType)
    {
        this.username = username;
        this.itemName = itemName;
        this.quantity = quantity;
        this.value = value;
        this.sourceName = sourceName;
        this.dropType = dropType;
        this.screenshotUrl = null;
        this.timestamp = System.currentTimeMillis();
    }
    
    // Constructor with screenshot
    public LootDropData(String username, String itemName, int quantity, int value, 
                        String sourceName, String dropType, String screenshotUrl)
    {
        this.username = username;
        this.itemName = itemName;
        this.quantity = quantity;
        this.value = value;
        this.sourceName = sourceName;
        this.dropType = dropType;
        this.screenshotUrl = screenshotUrl;
        this.timestamp = System.currentTimeMillis();
    }
}

