package com.osrsloottracker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * API client for communicating with the OSRS Loot Tracker backend
 */
@Slf4j
@Singleton
public class LootTrackerApiClient
{
    private final Gson gson = new Gson();
    
    @Inject
    private OSRSLootTrackerConfig config;
    
    @Inject
    private AuthenticationManager authManager;
    
    /**
     * Submit a loot drop to the server(s)
     * Uses the configured destinations (servers + channels)
     */
    public void submitDrop(LootDropData drop) throws IOException
    {
        if (!authManager.isAuthenticated())
        {
            throw new IllegalStateException("Not authenticated");
        }
        
        // Get configured destinations from config
        String destinationsJson = config.dropDestinations();
        
        JsonObject body = new JsonObject();
        body.addProperty("username", drop.getUsername());
        body.addProperty("item_name", drop.getItemName());
        body.addProperty("quantity", drop.getQuantity());
        body.addProperty("item_value", drop.getValue());
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
                
                if (rawDest.has("channelIds") && !rawDest.get("channelIds").isJsonNull())
                {
                    formattedDest.add("channel_ids", rawDest.getAsJsonArray("channelIds"));
                }
                
                if (rawDest.has("eventId") && !rawDest.get("eventId").isJsonNull())
                {
                    formattedDest.addProperty("event_id", rawDest.get("eventId").getAsString());
                }
                
                formattedDestinations.add(formattedDest);
            }
            
            body.add("destinations", formattedDestinations);
            log.info("Submitting drop to {} destinations", formattedDestinations.size());
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
     */
    public void submitDropBatch(List<OSRSLootTrackerPlugin.ProcessedItem> items, String rsn, 
                                 String sourceName, String dropType, String screenshotUrl) throws IOException
    {
        if (!authManager.isAuthenticated())
        {
            throw new IllegalStateException("Not authenticated");
        }
        
        // Get configured destinations from config
        String destinationsJson = config.dropDestinations();
        
        JsonObject body = new JsonObject();
        body.addProperty("username", rsn);
        body.addProperty("monster_name", sourceName);
        body.addProperty("drop_type", dropType);
        
        // Build items array
        JsonArray itemsArray = new JsonArray();
        int totalValue = 0;
        for (OSRSLootTrackerPlugin.ProcessedItem item : items)
        {
            JsonObject itemObj = new JsonObject();
            itemObj.addProperty("item_name", item.name);
            itemObj.addProperty("quantity", item.quantity);
            itemObj.addProperty("item_value", item.value);
            itemsArray.add(itemObj);
            totalValue += item.value;
        }
        body.add("items", itemsArray);
        body.addProperty("total_value", totalValue);
        
        // Include screenshot URL if available
        if (screenshotUrl != null && !screenshotUrl.isEmpty())
        {
            body.addProperty("screenshot_url", screenshotUrl);
        }
        
        // Add destinations
        if (destinationsJson != null && !destinationsJson.isEmpty() && !destinationsJson.equals("[]"))
        {
            JsonArray rawDestinations = gson.fromJson(destinationsJson, JsonArray.class);
            JsonArray formattedDestinations = new JsonArray();
            
            for (JsonElement elem : rawDestinations)
            {
                JsonObject rawDest = elem.getAsJsonObject();
                JsonObject formattedDest = new JsonObject();
                
                formattedDest.addProperty("guild_id", rawDest.get("guildId").getAsString());
                
                if (rawDest.has("channelIds") && !rawDest.get("channelIds").isJsonNull())
                {
                    formattedDest.add("channel_ids", rawDest.getAsJsonArray("channelIds"));
                }
                
                if (rawDest.has("eventId") && !rawDest.get("eventId").isJsonNull())
                {
                    formattedDest.addProperty("event_id", rawDest.get("eventId").getAsString());
                }
                
                formattedDestinations.add(formattedDest);
            }
            
            body.add("destinations", formattedDestinations);
            log.info("Submitting batch drop ({} items) to {} destinations", items.size(), formattedDestinations.size());
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
     * Upload a screenshot to the server
     * @param imageBytes PNG image bytes
     * @param guildId The guild ID to save the screenshot under
     * @return URL of the uploaded image
     */
    public String uploadScreenshot(byte[] imageBytes, String guildId) throws IOException
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
        
        if (responseJson.has("url"))
        {
            return responseJson.get("url").getAsString();
        }
        
        throw new IOException("No URL returned from screenshot upload");
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
     * Submit a collection log entry
     */
    public void submitCollectionLogEntry(LootDropData drop) throws IOException
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
        
        // Use first destination's guild_id for collection log (or legacy mode)
        if (destinationsJson != null && !destinationsJson.isEmpty() && !destinationsJson.equals("[]"))
        {
            JsonArray rawDestinations = gson.fromJson(destinationsJson, JsonArray.class);
            if (rawDestinations.size() > 0)
            {
                JsonObject firstDest = rawDestinations.get(0).getAsJsonObject();
                body.addProperty("guild_id", firstDest.get("guildId").getAsString());
                
                if (firstDest.has("eventId") && !firstDest.get("eventId").isJsonNull())
                {
                    body.addProperty("event_id", firstDest.get("eventId").getAsString());
                }
            }
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
     * Submit a pet drop
     */
    public void submitPetDrop(LootDropData drop, String message) throws IOException
    {
        if (!authManager.isAuthenticated())
        {
            throw new IllegalStateException("Not authenticated");
        }
        
        JsonObject body = new JsonObject();
        body.addProperty("username", drop.getUsername());
        body.addProperty("message", message);
        body.addProperty("guild_id", config.selectedServerId());
        
        if (!config.selectedEventId().isEmpty())
        {
            body.addProperty("event_id", config.selectedEventId());
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
                log.info("Fetching servers from API...");
                String response = get("/plugin/servers");
                log.info("Server response: {}", response);
                List<ServerInfo> servers = gson.fromJson(response, new TypeToken<List<ServerInfo>>(){}.getType());
                log.info("Parsed {} servers", servers != null ? servers.size() : 0);
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
                log.info("Fetching channels for server {}...", serverId);
                String response = get("/plugin/servers/" + serverId + "/channels");
                List<ChannelInfo> channels = gson.fromJson(response, new TypeToken<List<ChannelInfo>>(){}.getType());
                log.info("Found {} channels", channels != null ? channels.size() : 0);
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
                log.info("Fetching user stats for RSN: {}", rsn);
                String response = get(endpoint);
                log.info("Stats response: {}", response.length() > 200 ? response.substring(0, 200) + "..." : response);
                UserStats stats = gson.fromJson(response, UserStats.class);
                log.info("Parsed stats: {} total drops, {} total value", 
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
     * Get the current API endpoint (prioritizes system property)
     */
    private String getApiEndpoint()
    {
        String apiEndpoint = System.getProperty("osrsloottracker.api");
        if (apiEndpoint == null || apiEndpoint.isEmpty())
        {
            apiEndpoint = config.apiEndpoint();
        }
        return apiEndpoint;
    }
    
    private String get(String endpoint) throws IOException
    {
        String fullUrl = getApiEndpoint() + endpoint;
        log.debug("GET request to: {}", fullUrl);
        
        URL url = new URL(fullUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + authManager.getAuthToken());
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        
        int responseCode = conn.getResponseCode();
        log.debug("Response code: {}", responseCode);
        
        if (responseCode == 200)
        {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream())))
            {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null)
                {
                    response.append(line);
                }
                return response.toString();
            }
        }
        else
        {
            // Try to read error body
            String errorBody = "";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getErrorStream())))
            {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null)
                {
                    response.append(line);
                }
                errorBody = response.toString();
            }
            catch (Exception ignored) {}
            
            log.error("GET {} failed with status {}: {}", endpoint, responseCode, errorBody);
            throw new IOException("Request failed with status: " + responseCode + " - " + errorBody);
        }
    }
    
    /**
     * Perform a POST request
     */
    private String post(String endpoint, String body) throws IOException
    {
        URL url = new URL(getApiEndpoint() + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authManager.getAuthToken());
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        
        try (OutputStream os = conn.getOutputStream())
        {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        
        int responseCode = conn.getResponseCode();
        if (responseCode >= 200 && responseCode < 300)
        {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream())))
            {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null)
                {
                    response.append(line);
                }
                return response.toString();
            }
        }
        else
        {
            String errorBody = "";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getErrorStream())))
            {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null)
                {
                    response.append(line);
                }
                errorBody = response.toString();
            }
            catch (Exception ignored) {}
            
            throw new IOException("Request failed with status: " + responseCode + " - " + errorBody);
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

