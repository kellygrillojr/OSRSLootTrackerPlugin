package com.osrsloottracker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * API client for communicating with the OSRS Loot Tracker backend
 */
@Slf4j
@Singleton
public class LootTrackerApiClient
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    
    @Inject
    private Gson gson;
    
    @Inject
    private OkHttpClient okHttpClient;
    
    @Inject
    private OSRSLootTrackerConfig config;
    
    @Inject
    private AuthenticationManager authManager;
    
    /**
     * Submit a loot drop to the server(s)
     * Uses the configured destinations (servers + channels)
     * Filters channels based on per-channel minimum value thresholds
     */
    public void submitDrop(LootDropData drop) throws IOException
    {
        if (!authManager.isAuthenticated())
        {
            throw new IllegalStateException("Not authenticated");
        }
        
        int dropValue = drop.getValue();
        
        // Get configured destinations from config
        String destinationsJson = config.dropDestinations();
        
        JsonObject body = new JsonObject();
        body.addProperty("username", drop.getUsername());
        body.addProperty("item_name", drop.getItemName());
        body.addProperty("quantity", drop.getQuantity());
        body.addProperty("item_value", dropValue);
        body.addProperty("monster_name", drop.getSourceName());
        body.addProperty("drop_type", drop.getDropType());
        
        // Include screenshot URL if available
        if (drop.getScreenshotUrl() != null && !drop.getScreenshotUrl().isEmpty())
        {
            body.addProperty("screenshot_url", drop.getScreenshotUrl());
        }
        
        // If we have destinations configured, use them
        if (destinationsJson != null && !destinationsJson.isEmpty() && !destinationsJson.equals("[]"))
        {
            // Parse and convert field names to match backend (guildId -> guild_id, channelIds -> channel_ids)
            JsonArray rawDestinations = gson.fromJson(destinationsJson, JsonArray.class);
            JsonArray formattedDestinations = new JsonArray();
            
            for (JsonElement elem : rawDestinations)
            {
                JsonObject rawDest = elem.getAsJsonObject();
                JsonObject formattedDest = new JsonObject();
                
                formattedDest.addProperty("guild_id", rawDest.get("guildId").getAsString());
                
                // Check for new 'channels' format with per-channel min values
                if (rawDest.has("channels") && !rawDest.get("channels").isJsonNull())
                {
                    JsonArray channels = rawDest.getAsJsonArray("channels");
                    JsonArray filteredChannelIds = new JsonArray();
                    
                    for (JsonElement chElem : channels)
                    {
                        JsonObject channel = chElem.getAsJsonObject();
                        String channelId = channel.get("channelId").getAsString();
                        int minValue = channel.has("minValue") ? channel.get("minValue").getAsInt() : 0;
                        
                        // Only include this channel if drop value meets the threshold
                        if (dropValue >= minValue)
                        {
                            filteredChannelIds.add(channelId);
                        }
                    }
                    
                    // Only add this destination if at least one channel qualifies
                    if (filteredChannelIds.size() > 0)
                    {
                        formattedDest.add("channel_ids", filteredChannelIds);
                    }
                    else
                    {
                        continue; // Skip this destination entirely
                    }
                }
                // Fall back to legacy channelIds format (no per-channel filtering)
                else if (rawDest.has("channelIds") && !rawDest.get("channelIds").isJsonNull())
                {
                    formattedDest.add("channel_ids", rawDest.getAsJsonArray("channelIds"));
                }
                
                if (rawDest.has("eventId") && !rawDest.get("eventId").isJsonNull())
                {
                    formattedDest.addProperty("event_id", rawDest.get("eventId").getAsString());
                }
                
                formattedDestinations.add(formattedDest);
            }
            
            if (formattedDestinations.size() == 0)
            {
                log.debug("No destinations meet value threshold for this drop ({}gp)", dropValue);
                return; // Don't submit if no destinations qualify
            }
            
            body.add("destinations", formattedDestinations);
            log.debug("Submitting drop to {} destinations", formattedDestinations.size());
        }
        else
        {
            // Fall back to legacy single-server mode
            String serverId = config.selectedServerId();
            if (serverId == null || serverId.isEmpty())
            {
                throw new IllegalStateException("No destinations configured");
            }
            body.addProperty("guild_id", serverId);
            
            if (!config.selectedEventId().isEmpty())
            {
                body.addProperty("event_id", config.selectedEventId());
            }
        }
        
        String response = post("/plugin/drops", body.toString());
        log.debug("Drop submission response: {}", response);
    }
    
    /**
     * Submit multiple items from the same drop as a batch
     * This sends one Discord message with all items instead of separate messages
     * Filters channels based on per-channel minimum value thresholds
     * 
     * @param items List of items in the drop
     * @param rsn Player's RuneScape name
     * @param sourceName Source of the drop (monster name, etc.)
     * @param dropType Type of drop (NPC, PLAYER, etc.)
     * @param screenshotUrl URL of screenshot (for premium guilds that saved it)
     * @param screenshotBase64 Base64 screenshot data (for non-premium guilds, sent as attachment)
     */
    public void submitDropBatch(List<OSRSLootTrackerPlugin.ProcessedItem> items, String rsn, 
                                 String sourceName, String dropType, String screenshotUrl, 
                                 String screenshotBase64) throws IOException
    {
        if (!authManager.isAuthenticated())
        {
            throw new IllegalStateException("Not authenticated");
        }
        
        // Calculate total value for filtering
        int totalValue = 0;
        for (OSRSLootTrackerPlugin.ProcessedItem item : items)
        {
            totalValue += item.value;
        }
        
        // Get configured destinations from config
        String destinationsJson = config.dropDestinations();
        
        JsonObject body = new JsonObject();
        body.addProperty("username", rsn);
        body.addProperty("monster_name", sourceName);
        body.addProperty("drop_type", dropType);
        
        // Build items array
        JsonArray itemsArray = new JsonArray();
        for (OSRSLootTrackerPlugin.ProcessedItem item : items)
        {
            JsonObject itemObj = new JsonObject();
            itemObj.addProperty("item_name", item.name);
            itemObj.addProperty("quantity", item.quantity);
            itemObj.addProperty("item_value", item.value);
            itemsArray.add(itemObj);
        }
        body.add("items", itemsArray);
        body.addProperty("total_value", totalValue);
        
        // Include screenshot URL if available (premium guilds)
        if (screenshotUrl != null && !screenshotUrl.isEmpty())
        {
            body.addProperty("screenshot_url", screenshotUrl);
        }
        
        // Include base64 screenshot for non-premium guilds (sent as Discord attachment)
        if (screenshotBase64 != null && !screenshotBase64.isEmpty())
        {
            body.addProperty("screenshot_base64", screenshotBase64);
        }
        
        // Add destinations - filter channels based on per-channel min values
        if (destinationsJson != null && !destinationsJson.isEmpty() && !destinationsJson.equals("[]"))
        {
            JsonArray rawDestinations = gson.fromJson(destinationsJson, JsonArray.class);
            JsonArray formattedDestinations = new JsonArray();
            
            for (JsonElement elem : rawDestinations)
            {
                JsonObject rawDest = elem.getAsJsonObject();
                JsonObject formattedDest = new JsonObject();
                
                formattedDest.addProperty("guild_id", rawDest.get("guildId").getAsString());
                
                // Check for new 'channels' format with per-channel min values and drop type filters
                if (rawDest.has("channels") && !rawDest.get("channels").isJsonNull())
                {
                    JsonArray channels = rawDest.getAsJsonArray("channels");
                    JsonArray filteredChannelIds = new JsonArray();
                    
                    for (JsonElement chElem : channels)
                    {
                        JsonObject channel = chElem.getAsJsonObject();
                        String channelId = channel.get("channelId").getAsString();
                        int minValue = channel.has("minValue") ? channel.get("minValue").getAsInt() : 0;
                        
                        // Check if this channel accepts valuable drops (default to true for backward compatibility)
                        boolean sendValuableDrops = !channel.has("sendValuableDrops") || channel.get("sendValuableDrops").getAsBoolean();
                        
                        // Only include this channel if it accepts valuable drops and drop value meets threshold
                        if (!sendValuableDrops)
                        {
                            log.debug("Channel {} excluded (sendValuableDrops=false)", channelId);
                            continue;
                        }
                        
                        if (totalValue >= minValue)
                        {
                            filteredChannelIds.add(channelId);
                            log.debug("Channel {} included (drop {}gp >= min {}gp)", channelId, totalValue, minValue);
                        }
                        else
                        {
                            log.debug("Channel {} excluded (drop {}gp < min {}gp)", channelId, totalValue, minValue);
                        }
                    }
                    
                    // Only add this destination if at least one channel qualifies
                    if (filteredChannelIds.size() > 0)
                    {
                        formattedDest.add("channel_ids", filteredChannelIds);
                    }
                    else
                    {
                        log.debug("Skipping guild {} - no channels meet value/type filter", rawDest.get("guildId").getAsString());
                        continue; // Skip this destination entirely
                    }
                }
                // Fall back to legacy channelIds format (no per-channel filtering)
                else if (rawDest.has("channelIds") && !rawDest.get("channelIds").isJsonNull())
                {
                    formattedDest.add("channel_ids", rawDest.getAsJsonArray("channelIds"));
                }
                
                if (rawDest.has("eventId") && !rawDest.get("eventId").isJsonNull())
                {
                    formattedDest.addProperty("event_id", rawDest.get("eventId").getAsString());
                }
                
                formattedDestinations.add(formattedDest);
            }
            
            if (formattedDestinations.size() == 0)
            {
                log.debug("No destinations meet value threshold for this drop ({}gp)", totalValue);
                return; // Don't submit if no destinations qualify
            }
            
            body.add("destinations", formattedDestinations);
            log.debug("Submitting batch drop ({} items, {}gp) to {} destinations", 
                items.size(), totalValue, formattedDestinations.size());
        }
        else
        {
            String serverId = config.selectedServerId();
            if (serverId == null || serverId.isEmpty())
            {
                throw new IllegalStateException("No destinations configured");
            }
            body.addProperty("guild_id", serverId);
        }
        
        String response = post("/plugin/drops/batch", body.toString());
        log.debug("Batch drop submission response: {}", response);
    }
    
    /**
     * Result of screenshot upload attempt
     */
    public static class ScreenshotResult
    {
        public final String url;        // URL if saved (premium), null otherwise
        public final String base64;     // Base64 data to send as attachment (non-premium)
        public final boolean saved;     // Whether the image was saved to disk
        
        public ScreenshotResult(String url, String base64, boolean saved)
        {
            this.url = url;
            this.base64 = base64;
            this.saved = saved;
        }
    }
    
    /**
     * Upload a screenshot to the server
     * @param imageBytes PNG image bytes
     * @param guildId The guild ID to save the screenshot under
     * @return ScreenshotResult with URL (if premium/saved) or base64 (if non-premium)
     */
    public ScreenshotResult uploadScreenshot(byte[] imageBytes, String guildId) throws IOException
    {
        if (!authManager.isAuthenticated())
        {
            throw new IllegalStateException("Not authenticated");
        }
        
        if (guildId == null || guildId.isEmpty())
        {
            throw new IllegalArgumentException("Guild ID is required for screenshot upload");
        }
        
        // Convert to base64 for JSON upload
        String base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes);
        
        JsonObject body = new JsonObject();
        body.addProperty("image", base64Image);
        body.addProperty("guild_id", guildId);
        
        String response = post("/plugin/upload-screenshot", body.toString());
        JsonObject responseJson = gson.fromJson(response, JsonObject.class);
        
        // Screenshot is now validated but NOT saved at upload time
        // Saving happens per-destination in drop submission (only for premium guilds)
        // We always return base64 so drop submission can handle it per-destination
        log.debug("Screenshot validated - will be saved per-destination for premium guilds");
        return new ScreenshotResult(null, base64Image, false);
    }
    
    /**
     * Get the first configured guild ID from destinations
     * @return The first guild ID, or null if none configured
     */
    public String getFirstConfiguredGuildId()
    {
        String destinationsJson = config.dropDestinations();
        if (destinationsJson != null && !destinationsJson.isEmpty() && !destinationsJson.equals("[]"))
        {
            try
            {
                JsonArray rawDestinations = gson.fromJson(destinationsJson, JsonArray.class);
                if (rawDestinations.size() > 0)
                {
                    JsonObject firstDest = rawDestinations.get(0).getAsJsonObject();
                    return firstDest.get("guildId").getAsString();
                }
            }
            catch (Exception e)
            {
                log.error("Error parsing destinations JSON", e);
            }
        }
        // Fall back to legacy single-server mode
        return config.selectedServerId();
    }
    
    /**
     * Get the minimum value threshold across all configured channels.
     * This is the lowest min value of any channel, so we know the minimum
     * value a drop needs to be to go anywhere.
     * @return The lowest min value (0 if no channels configured or using legacy format)
     */
    public int getLowestChannelMinValue()
    {
        String destinationsJson = config.dropDestinations();
        if (destinationsJson == null || destinationsJson.isEmpty() || destinationsJson.equals("[]"))
        {
            return 0; // No destinations, use global default
        }
        
        try
        {
            JsonArray rawDestinations = gson.fromJson(destinationsJson, JsonArray.class);
            int lowestMin = Integer.MAX_VALUE;
            boolean hasAnyChannel = false;
            
            for (JsonElement elem : rawDestinations)
            {
                JsonObject rawDest = elem.getAsJsonObject();
                
                // Check for new 'channels' format
                if (rawDest.has("channels") && !rawDest.get("channels").isJsonNull())
                {
                    JsonArray channels = rawDest.getAsJsonArray("channels");
                    for (JsonElement chElem : channels)
                    {
                        JsonObject channel = chElem.getAsJsonObject();
                        int minValue = channel.has("minValue") ? channel.get("minValue").getAsInt() : 0;
                        lowestMin = Math.min(lowestMin, minValue);
                        hasAnyChannel = true;
                    }
                }
                // Legacy format - no per-channel filtering
                else if (rawDest.has("channelIds") && !rawDest.get("channelIds").isJsonNull())
                {
                    JsonArray channelIds = rawDest.getAsJsonArray("channelIds");
                    if (channelIds.size() > 0)
                    {
                        lowestMin = 0; // Legacy channels have no min value filter
                        hasAnyChannel = true;
                    }
                }
            }
            
            return hasAnyChannel ? (lowestMin == Integer.MAX_VALUE ? 0 : lowestMin) : 0;
        }
        catch (Exception e)
        {
            log.error("Error parsing destinations for min value check", e);
            return 0;
        }
    }
    
    /**
     * Check if a drop with the given value would be sent to at least one channel
     * @param dropValue The GP value of the drop
     * @return true if at least one channel would receive this drop
     */
    public boolean wouldDropBeSent(int dropValue)
    {
        String destinationsJson = config.dropDestinations();
        if (destinationsJson == null || destinationsJson.isEmpty() || destinationsJson.equals("[]"))
        {
            // Check legacy single-server mode
            return config.selectedServerId() != null && !config.selectedServerId().isEmpty();
        }
        
        try
        {
            JsonArray rawDestinations = gson.fromJson(destinationsJson, JsonArray.class);
            
            for (JsonElement elem : rawDestinations)
            {
                JsonObject rawDest = elem.getAsJsonObject();
                
                // Check for new 'channels' format
                if (rawDest.has("channels") && !rawDest.get("channels").isJsonNull())
                {
                    JsonArray channels = rawDest.getAsJsonArray("channels");
                    for (JsonElement chElem : channels)
                    {
                        JsonObject channel = chElem.getAsJsonObject();
                        int minValue = channel.has("minValue") ? channel.get("minValue").getAsInt() : 0;
                        if (dropValue >= minValue)
                        {
                            return true; // At least one channel would receive this drop
                        }
                    }
                }
                // Legacy format - no per-channel filtering, always accept
                else if (rawDest.has("channelIds") && !rawDest.get("channelIds").isJsonNull())
                {
                    JsonArray channelIds = rawDest.getAsJsonArray("channelIds");
                    if (channelIds.size() > 0)
                    {
                        return true;
                    }
                }
            }
            
            return false; // No channel would receive this drop
        }
        catch (Exception e)
        {
            log.error("Error checking if drop would be sent", e);
            return true; // Assume yes on error
        }
    }
    
    /**
     * Submit a collection log entry (legacy - no screenshot)
     */
    public void submitCollectionLogEntry(LootDropData drop) throws IOException
    {
        submitCollectionLogEntry(drop, null, null);
    }
    
    /**
     * Submit a collection log entry with optional screenshot
     */
    public void submitCollectionLogEntry(LootDropData drop, String screenshotUrl, String screenshotBase64) throws IOException
    {
        if (!authManager.isAuthenticated())
        {
            throw new IllegalStateException("Not authenticated");
        }
        
        // Get configured destinations
        String destinationsJson = config.dropDestinations();
        
        JsonObject body = new JsonObject();
        body.addProperty("username", drop.getUsername());
        body.addProperty("item_name", drop.getItemName());
        
        // Add screenshot if available
        if (screenshotUrl != null && !screenshotUrl.isEmpty())
        {
            body.addProperty("image_url", screenshotUrl);
        }
        if (screenshotBase64 != null && !screenshotBase64.isEmpty())
        {
            body.addProperty("image_base64", screenshotBase64);
        }
        
        // Build destinations, filtering channels that have sendCollectionLog enabled
        if (destinationsJson != null && !destinationsJson.isEmpty() && !destinationsJson.equals("[]"))
        {
            JsonArray rawDestinations = gson.fromJson(destinationsJson, JsonArray.class);
            JsonArray formattedDestinations = new JsonArray();
            
            for (JsonElement destElem : rawDestinations)
            {
                JsonObject rawDest = destElem.getAsJsonObject();
                JsonObject formattedDest = new JsonObject();
                
                formattedDest.addProperty("guild_id", rawDest.get("guildId").getAsString());
                
                // Filter channels that accept collection log entries
                if (rawDest.has("channels") && rawDest.get("channels").isJsonArray())
                {
                    JsonArray channels = rawDest.get("channels").getAsJsonArray();
                    JsonArray filteredChannelIds = new JsonArray();
                    
                    for (int i = 0; i < channels.size(); i++)
                    {
                        JsonObject ch = channels.get(i).getAsJsonObject();
                        
                        // Check if this channel accepts collection log (default to true for backward compat)
                        boolean sendCollectionLog = !ch.has("sendCollectionLog") || ch.get("sendCollectionLog").getAsBoolean();
                        
                        if (!sendCollectionLog)
                        {
                            log.debug("Channel excluded from collection log (sendCollectionLog=false)");
                            continue;
                        }
                        
                        // Get channel ID (check both formats)
                        String channelId = null;
                        if (ch.has("channelId") && !ch.get("channelId").isJsonNull())
                        {
                            channelId = ch.get("channelId").getAsString();
                        }
                        else if (ch.has("id") && !ch.get("id").isJsonNull())
                        {
                            channelId = ch.get("id").getAsString();
                        }
                        
                        if (channelId != null)
                        {
                            filteredChannelIds.add(channelId);
                            log.debug("Channel {} included for collection log", channelId);
                        }
                    }
                    
                    // Only add destination if at least one channel qualifies
                    if (filteredChannelIds.size() > 0)
                    {
                        formattedDest.add("channel_ids", filteredChannelIds);
                    }
                    else
                    {
                        log.debug("Skipping guild {} for collection log - no channels accept collection log", 
                            rawDest.get("guildId").getAsString());
                        continue;
                    }
                }
                // Fallback: legacy channelIds format (send to all)
                else if (rawDest.has("channelIds") && rawDest.get("channelIds").isJsonArray())
                {
                    formattedDest.add("channel_ids", rawDest.get("channelIds").getAsJsonArray());
                }
                else
                {
                    log.debug("Skipping guild {} for collection log - no channels configured", 
                        rawDest.get("guildId").getAsString());
                    continue;
                }
                
                if (rawDest.has("eventId") && !rawDest.get("eventId").isJsonNull())
                {
                    formattedDest.addProperty("event_id", rawDest.get("eventId").getAsString());
                }
                
                formattedDestinations.add(formattedDest);
            }
            
            if (formattedDestinations.size() == 0)
            {
                log.debug("No destinations accept collection log entries, skipping submission");
                return;
            }
            
            body.add("destinations", formattedDestinations);
            
            // For backward compatibility, also set guild_id from first destination
            JsonObject firstDest = formattedDestinations.get(0).getAsJsonObject();
            body.addProperty("guild_id", firstDest.get("guild_id").getAsString());
            body.add("channel_ids", firstDest.get("channel_ids").getAsJsonArray());
        }
        else
        {
            body.addProperty("guild_id", config.selectedServerId());
            if (!config.selectedEventId().isEmpty())
            {
                body.addProperty("event_id", config.selectedEventId());
            }
        }
        
        post("/plugin/collection-log", body.toString());
    }
    
    /**
     * Submit a pet drop (legacy - no screenshot)
     */
    public void submitPetDrop(LootDropData drop, String message) throws IOException
    {
        submitPetDrop(drop, message, null, null, null);
    }
    
    /**
     * Submit a pet drop with optional screenshot (legacy without pet name)
     */
    public void submitPetDrop(LootDropData drop, String message, String screenshotUrl, String screenshotBase64) throws IOException
    {
        submitPetDrop(drop, message, null, screenshotUrl, screenshotBase64);
    }
    
    /**
     * Submit a pet drop with optional screenshot and pet name
     */
    public void submitPetDrop(LootDropData drop, String message, String petName, String screenshotUrl, String screenshotBase64) throws IOException
    {
        if (!authManager.isAuthenticated())
        {
            throw new IllegalStateException("Not authenticated");
        }
        
        // Get configured destinations
        String destinationsJson = config.dropDestinations();
        
        JsonObject body = new JsonObject();
        body.addProperty("username", drop.getUsername());
        body.addProperty("message", message);
        
        // Add pet name if available
        if (petName != null && !petName.isEmpty())
        {
            body.addProperty("pet_name", petName);
        }
        
        // Add screenshot if available
        if (screenshotUrl != null && !screenshotUrl.isEmpty())
        {
            body.addProperty("image_url", screenshotUrl);
        }
        if (screenshotBase64 != null && !screenshotBase64.isEmpty())
        {
            body.addProperty("image_base64", screenshotBase64);
        }
        
        // Build destinations, filtering channels that have sendPets enabled
        if (destinationsJson != null && !destinationsJson.isEmpty() && !destinationsJson.equals("[]"))
        {
            JsonArray rawDestinations = gson.fromJson(destinationsJson, JsonArray.class);
            JsonArray formattedDestinations = new JsonArray();
            
            for (JsonElement destElem : rawDestinations)
            {
                JsonObject rawDest = destElem.getAsJsonObject();
                JsonObject formattedDest = new JsonObject();
                
                formattedDest.addProperty("guild_id", rawDest.get("guildId").getAsString());
                
                // Filter channels that accept pet drops
                if (rawDest.has("channels") && rawDest.get("channels").isJsonArray())
                {
                    JsonArray channels = rawDest.get("channels").getAsJsonArray();
                    JsonArray filteredChannelIds = new JsonArray();
                    
                    for (int i = 0; i < channels.size(); i++)
                    {
                        JsonObject ch = channels.get(i).getAsJsonObject();
                        
                        // Check if this channel accepts pets (default to true for backward compat)
                        boolean sendPets = !ch.has("sendPets") || ch.get("sendPets").getAsBoolean();
                        
                        if (!sendPets)
                        {
                            log.debug("Channel excluded from pet drops (sendPets=false)");
                            continue;
                        }
                        
                        // Get channel ID (check both formats)
                        String channelId = null;
                        if (ch.has("channelId") && !ch.get("channelId").isJsonNull())
                        {
                            channelId = ch.get("channelId").getAsString();
                        }
                        else if (ch.has("id") && !ch.get("id").isJsonNull())
                        {
                            channelId = ch.get("id").getAsString();
                        }
                        
                        if (channelId != null)
                        {
                            filteredChannelIds.add(channelId);
                            log.debug("Channel {} included for pet drop", channelId);
                        }
                    }
                    
                    // Only add destination if at least one channel qualifies
                    if (filteredChannelIds.size() > 0)
                    {
                        formattedDest.add("channel_ids", filteredChannelIds);
                    }
                    else
                    {
                        log.debug("Skipping guild {} for pet drop - no channels accept pets", 
                            rawDest.get("guildId").getAsString());
                        continue;
                    }
                }
                // Fallback: legacy channelIds format (send to all)
                else if (rawDest.has("channelIds") && rawDest.get("channelIds").isJsonArray())
                {
                    formattedDest.add("channel_ids", rawDest.get("channelIds").getAsJsonArray());
                }
                else
                {
                    log.debug("Skipping guild {} for pet drop - no channels configured", 
                        rawDest.get("guildId").getAsString());
                    continue;
                }
                
                if (rawDest.has("eventId") && !rawDest.get("eventId").isJsonNull())
                {
                    formattedDest.addProperty("event_id", rawDest.get("eventId").getAsString());
                }
                
                formattedDestinations.add(formattedDest);
            }
            
            if (formattedDestinations.size() == 0)
            {
                log.debug("No destinations accept pet drops, skipping submission");
                return;
            }
            
            body.add("destinations", formattedDestinations);
            
            // For backward compatibility, also set guild_id from first destination
            JsonObject firstDest = formattedDestinations.get(0).getAsJsonObject();
            body.addProperty("guild_id", firstDest.get("guild_id").getAsString());
            body.add("channel_ids", firstDest.get("channel_ids").getAsJsonArray());
        }
        else
        {
            body.addProperty("guild_id", config.selectedServerId());
            if (!config.selectedEventId().isEmpty())
            {
                body.addProperty("event_id", config.selectedEventId());
            }
        }
        
        post("/plugin/pets", body.toString());
    }
    
    /**
     * Get list of servers the user has access to
     */
    public CompletableFuture<List<ServerInfo>> getServers()
    {
        return CompletableFuture.supplyAsync(() -> {
            try
            {
                log.debug("Fetching servers from API...");
                String response = get("/plugin/servers");
                log.debug("Server response: {}", response);
                List<ServerInfo> servers = gson.fromJson(response, new TypeToken<List<ServerInfo>>(){}.getType());
                log.debug("Parsed {} servers", servers != null ? servers.size() : 0);
                return servers;
            }
            catch (Exception e)
            {
                log.error("Failed to get servers: {} - {}", e.getClass().getSimpleName(), e.getMessage());
                return List.of();
            }
        });
    }
    
    /**
     * Get list of text channels for a server
     */
    public CompletableFuture<List<ChannelInfo>> getServerChannels(String serverId)
    {
        return CompletableFuture.supplyAsync(() -> {
            try
            {
                log.debug("Fetching channels for server {}...", serverId);
                String response = get("/plugin/servers/" + serverId + "/channels");
                List<ChannelInfo> channels = gson.fromJson(response, new TypeToken<List<ChannelInfo>>(){}.getType());
                log.debug("Found {} channels", channels != null ? channels.size() : 0);
                return channels;
            }
            catch (Exception e)
            {
                log.error("Failed to get channels for server {}: {}", serverId, e.getMessage());
                return List.of();
            }
        });
    }
    
    /**
     * Get list of active events for a server
     */
    public CompletableFuture<List<EventInfo>> getServerEvents(String serverId)
    {
        return CompletableFuture.supplyAsync(() -> {
            try
            {
                String response = get("/plugin/servers/" + serverId + "/events");
                return gson.fromJson(response, new TypeToken<List<EventInfo>>(){}.getType());
            }
            catch (Exception e)
            {
                log.error("Failed to get events for server {}", serverId, e);
                return List.of();
            }
        });
    }
    
    /**
     * Get event details including tile requirements
     */
    public CompletableFuture<EventDetails> getEventDetails(String eventId)
    {
        return CompletableFuture.supplyAsync(() -> {
            try
            {
                String response = get("/plugin/events/" + eventId);
                return gson.fromJson(response, EventDetails.class);
            }
            catch (Exception e)
            {
                log.error("Failed to get event details for {}", eventId, e);
                return null;
            }
        });
    }
    
    /**
     * Get user's team progress for an event
     */
    public CompletableFuture<TeamProgress> getTeamProgress(String eventId)
    {
        return CompletableFuture.supplyAsync(() -> {
            try
            {
                String response = get("/plugin/events/" + eventId + "/progress");
                return gson.fromJson(response, TeamProgress.class);
            }
            catch (Exception e)
            {
                log.error("Failed to get team progress for event {}", eventId, e);
                return null;
            }
        });
    }
    
    /**
     * Get recent drops for the user
     */
    public CompletableFuture<List<RecentDrop>> getRecentDrops(int limit)
    {
        return CompletableFuture.supplyAsync(() -> {
            try
            {
                String response = get("/plugin/drops/recent?limit=" + limit);
                return gson.fromJson(response, new TypeToken<List<RecentDrop>>(){}.getType());
            }
            catch (Exception e)
            {
                log.error("Failed to get recent drops", e);
                return List.of();
            }
        });
    }
    
    /**
     * Get user loot statistics for a specific RSN
     */
    public CompletableFuture<UserStats> getUserStats(String rsn)
    {
        return CompletableFuture.supplyAsync(() -> {
            try
            {
                String endpoint = "/plugin/stats";
                if (rsn != null && !rsn.isEmpty())
                {
                    endpoint += "?rsn=" + java.net.URLEncoder.encode(rsn, java.nio.charset.StandardCharsets.UTF_8);
                }
                log.debug("Fetching user stats for RSN: {}", rsn);
                String response = get(endpoint);
                log.debug("Stats response: {}", response.length() > 200 ? response.substring(0, 200) + "..." : response);
                UserStats stats = gson.fromJson(response, UserStats.class);
                log.debug("Parsed stats: {} total drops, {} total value", 
                    stats != null ? stats.total_drops : "null", 
                    stats != null ? stats.total_value : "null");
                return stats;
            }
            catch (Exception e)
            {
                log.error("Failed to get user stats: {}", e.getMessage());
                return null;
            }
        });
    }
    
    /**
     * Get the current API endpoint (prioritizes system property, defaults to production)
     */
    private String getApiEndpoint()
    {
        String apiEndpoint = System.getProperty("osrsloottracker.api");
        // Always default to production - don't use config (which might have stale persisted data)
        return (apiEndpoint != null && !apiEndpoint.isEmpty()) 
            ? apiEndpoint 
            : "https://osrsloottracker.com/api";
    }
    
    private String get(String endpoint) throws IOException
    {
        String fullUrl = getApiEndpoint() + endpoint;
        log.debug("GET request to: {}", fullUrl);
        
        Request request = new Request.Builder()
            .url(fullUrl)
            .header("Authorization", "Bearer " + authManager.getAuthToken())
            .header("Content-Type", "application/json")
            .get()
            .build();
        
        try (Response response = okHttpClient.newCall(request).execute())
        {
            int responseCode = response.code();
            log.debug("Response code: {}", responseCode);
            
            String responseBody = response.body() != null ? response.body().string() : "";
            
            if (response.isSuccessful())
            {
                return responseBody;
            }
            else
            {
                log.error("GET {} failed with status {}: {}", endpoint, responseCode, responseBody);
                throw new IOException("Request failed with status: " + responseCode + " - " + responseBody);
            }
        }
    }
    
    /**
     * Perform a POST request
     */
    private String post(String endpoint, String body) throws IOException
    {
        String fullUrl = getApiEndpoint() + endpoint;
        
        RequestBody requestBody = RequestBody.create(JSON, body);
        
        Request request = new Request.Builder()
            .url(fullUrl)
            .header("Authorization", "Bearer " + authManager.getAuthToken())
            .header("Content-Type", "application/json")
            .post(requestBody)
            .build();
        
        try (Response response = okHttpClient.newCall(request).execute())
        {
            int responseCode = response.code();
            String responseBody = response.body() != null ? response.body().string() : "";
            
            if (response.isSuccessful())
            {
                return responseBody;
            }
            else
            {
                throw new IOException("Request failed with status: " + responseCode + " - " + responseBody);
            }
        }
    }
    
    // === Data classes ===
    
    public static class ServerInfo
    {
        public String id;
        public String name;
        public String icon;
        public boolean hasBot;
    }
    
    public static class ChannelInfo
    {
        public String id;
        public String name;
        public String type; // "text" or "announcement"
        public String category; // Category name, if any
        
        @Override
        public String toString()
        {
            return (category != null ? "[" + category + "] " : "") + "#" + name;
        }
    }
    
    public static class EventInfo
    {
        public String id;
        public String name;
        public String type; // "bingo" or "snakes_ladders"
        public String status;
        public String startDate;
        public String endDate;
    }
    
    public static class EventDetails
    {
        public String id;
        public String name;
        public String type;
        public List<TileInfo> tiles;
        public TeamInfo team;
    }
    
    public static class TileInfo
    {
        public int id;
        public int position;
        public String name;
        public List<RequirementInfo> requirements;
        public String status; // "locked", "pending", "completed"
    }
    
    public static class RequirementInfo
    {
        public int id;
        public String itemName;
        public int requiredQuantity;
        public int currentQuantity;
    }
    
    public static class TeamInfo
    {
        public String id;
        public String name;
        public int points;
        public List<TeamMember> members;
    }
    
    public static class TeamMember
    {
        public String discordId;
        public String runescapeName;
    }
    
    public static class TeamProgress
    {
        public int totalTiles;
        public int completedTiles;
        public int pendingTiles;
        public int points;
        public List<TileProgress> tiles;
    }
    
    public static class TileProgress
    {
        public int tileId;
        public String status;
        public List<RequirementProgress> requirements;
    }
    
    public static class RequirementProgress
    {
        public int requirementId;
        public String itemName;
        public int required;
        public int current;
    }
    
    public static class RecentDrop
    {
        public String id;
        public String itemName;
        public int quantity;
        public long value;
        public String monsterName;
        public String createdAt;
    }
    
    public static class UserStats
    {
        public int total_drops;
        public long total_value;
        public List<TopItem> top_items;
        public List<TopItem> recent_activity;
        public List<String> rsns;
        public PeriodStats periods;
    }
    
    public static class TopItem
    {
        public String item_name;
        public int quantity;
        public long value;
        public String source;
        public String date;
    }
    
    public static class PeriodStats
    {
        public PeriodData today;
        public PeriodData week;
        public PeriodData month;
    }
    
    public static class PeriodData
    {
        public int drops;
        public long value;
    }
}

