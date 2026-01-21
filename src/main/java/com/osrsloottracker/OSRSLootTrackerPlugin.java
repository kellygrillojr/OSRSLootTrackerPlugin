package com.osrsloottracker;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.loottracker.LootRecordType;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
@PluginDescriptor(
    name = "OSRS Loot Tracker",
    description = "Track loot and send directly to your OSRS Loot Tracker dashboard. Supports Bingo events and Snakes & Ladders.",
    tags = {"loot", "tracker", "bingo", "discord", "drops", "collection"}
)
public class OSRSLootTrackerPlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private OSRSLootTrackerConfig config;

    @Inject
    private ItemManager itemManager;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ScheduledExecutorService executor;

    @Inject
    private LootTrackerApiClient apiClient;

    @Inject
    private AuthenticationManager authManager;

    @Inject
    private DrawManager drawManager;

    @Inject
    private OSRSLootTrackerPanel panel;
    
    private NavigationButton navButton;
    
    // Deduplication cache for collection log entries (item name -> timestamp)
    private final Map<String, Long> recentCollectionLogItems = new ConcurrentHashMap<>();
    private static final long COLLECTION_LOG_DEDUP_WINDOW_MS = 5000; // 5 seconds
    
    // Cache for recent collection log pet names (for cross-referencing with pet messages)
    // When we get a pet message, check if there was a recent collection log entry that might be the pet name
    private volatile String recentCollectionLogPetCandidate = null;
    private volatile long recentCollectionLogPetCandidateTime = 0;
    private static final long PET_COLLECTION_LOG_WINDOW_MS = 3000; // 3 second window
    
    /**
     * Debug mode flag - set to false for Plugin Hub releases!
     * When true, shows test buttons in the panel for testing pet/collection log detection.
     */
    public static final boolean DEBUG_MODE = false;

    @Override
    protected void startUp() throws Exception
    {
        log.info("OSRS Loot Tracker plugin started!");

        // Check for existing authentication BEFORE initializing the panel
        // so the panel knows whether to show login or main view
        authManager.checkStoredAuth();

        // Initialize the injected panel
        panel.init();
        panel.setPlugin(this); // For debug/test methods
        
        // Set up RSN supplier - gets the current player name on demand
        panel.setRsnSupplier(() -> {
            if (client.getLocalPlayer() != null)
            {
                return client.getLocalPlayer().getName();
            }
            return null;
        });
        
        // Use RuneLite's built-in loot icon
        BufferedImage icon;
        try
        {
            icon = ImageUtil.loadImageResource(getClass(), "/panel_icon.png");
        }
        catch (Exception e)
        {
            // Fallback to a simple colored square if icon not found
            icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = icon.createGraphics();
            g.setColor(new java.awt.Color(88, 101, 242)); // Discord blurple
            g.fillRect(0, 0, 16, 16);
            g.setColor(java.awt.Color.WHITE);
            g.drawString("L", 4, 12);
            g.dispose();
        }
        
        navButton = NavigationButton.builder()
            .tooltip("OSRS Loot Tracker")
            .icon(icon)
            .priority(5)
            .panel(panel)
            .build();

        clientToolbar.addNavigation(navButton);
    }

    @Override
    protected void shutDown() throws Exception
    {
        log.info("OSRS Loot Tracker plugin stopped!");
        clientToolbar.removeNavigation(navButton);
    }

    @Subscribe
    public void onLootReceived(LootReceived event)
    {
        log.info("LootReceived event: {} items from {}", event.getItems().size(), event.getName());
        
        if (!authManager.isAuthenticated())
        {
            log.info("Not authenticated, skipping loot submission");
            return;
        }

        if (!config.trackLoot())
        {
            log.info("Loot tracking disabled, skipping");
            return;
        }
        
        // Check if destinations are configured
        String destinations = config.dropDestinations();
        boolean hasDestinations = destinations != null && !destinations.isEmpty() && !destinations.equals("[]");
        boolean hasLegacyServer = config.selectedServerId() != null && !config.selectedServerId().isEmpty();
        
        if (!hasDestinations && !hasLegacyServer)
        {
            log.info("No destinations configured, skipping loot submission");
            return;
        }

        final String sourceName = event.getName();
        final LootRecordType type = event.getType();

        // Get the player's RSN
        final String rsn = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Unknown";
        
        // IMPORTANT: Pre-process all item data on the client thread because ItemManager 
        // methods must be called on the client thread. We store the processed data for 
        // use in the async callback.
        final List<ProcessedItem> allItems = new ArrayList<>();
        
        for (ItemStack item : event.getItems())
        {
            final int itemId = item.getId();
            final int quantity = item.getQuantity();
            final int value = itemManager.getItemPrice(itemId) * quantity;
            final String itemName = itemManager.getItemComposition(itemId).getName();
            
            allItems.add(new ProcessedItem(itemName, quantity, value));
        }
        
        // Get the lowest channel threshold - only items meeting this threshold will be logged
        final int lowestThreshold = apiClient.getLowestChannelMinValue();
        
        // Filter items to only include those that individually meet the threshold
        final List<ProcessedItem> processedItems = new ArrayList<>();
        for (ProcessedItem item : allItems)
        {
            if (item.value >= lowestThreshold)
            {
                processedItems.add(item);
                log.debug("Item {} ({}gp) meets threshold ({}gp)", item.name, item.value, lowestThreshold);
            }
            else
            {
                log.debug("Item {} ({}gp) below threshold ({}gp), skipping", item.name, item.value, lowestThreshold);
            }
        }
        
        // Calculate total value of items that passed the filter
        int totalDropValue = 0;
        for (ProcessedItem item : processedItems)
        {
            totalDropValue += item.value;
        }
        
        log.info("Processing loot for RSN: {}, from: {}, items: {}/{} passed filter, total value: {}gp (threshold: {}gp)", 
            rsn, sourceName, processedItems.size(), allItems.size(), totalDropValue, lowestThreshold);

        // Skip if no items passed the filter
        if (processedItems.isEmpty())
        {
            log.debug("No items meet the minimum value threshold of {}gp", lowestThreshold);
            return;
        }

        // Capture screenshot if enabled
        if (config.captureScreenshots())
        {
            captureScreenshot(screenshotData -> {
                String url = screenshotData != null ? screenshotData.url : null;
                String base64 = screenshotData != null ? screenshotData.base64 : null;
                submitProcessedItems(processedItems, rsn, sourceName, type, url, base64);
            });
        }
        else
        {
            submitProcessedItems(processedItems, rsn, sourceName, type, null, null);
        }
    }
    
    /**
     * Simple data class to hold pre-processed item info
     */
    public static class ProcessedItem
    {
        public final String name;
        public final int quantity;
        public final int value;
        
        public ProcessedItem(String name, int quantity, int value)
        {
            this.name = name;
            this.quantity = quantity;
            this.value = value;
        }
    }
    
    /**
     * Screenshot result containing URL (if saved) and/or base64 (for non-premium)
     */
    private static class ScreenshotData
    {
        final String url;
        final String base64;
        
        ScreenshotData(String url, String base64)
        {
            this.url = url;
            this.base64 = base64;
        }
    }
    
    /**
     * Capture a screenshot and pass it to the callback
     * Returns ScreenshotData with URL (if premium/saved) and/or base64 (for non-premium)
     */
    private void captureScreenshot(Consumer<ScreenshotData> callback)
    {
        // Get the first configured guild ID for screenshot storage
        String guildId = apiClient.getFirstConfiguredGuildId();
        if (guildId == null || guildId.isEmpty())
        {
            log.warn("No guild ID configured for screenshot upload");
            callback.accept(null);
            return;
        }
        
        log.info("Capturing screenshot for guild {}", guildId);
        
        drawManager.requestNextFrameListener(image -> {
            executor.execute(() -> {
                try
                {
                    BufferedImage screenshot = (BufferedImage) image;
                    log.info("Screenshot captured, uploading...");
                    
                    // Convert to PNG bytes
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(screenshot, "png", baos);
                    byte[] imageBytes = baos.toByteArray();
                    
                    // Validate screenshot with backend - saving happens per-destination
                    LootTrackerApiClient.ScreenshotResult result = apiClient.uploadScreenshot(imageBytes, guildId);
                    log.info("Screenshot validated - will be processed per-destination");
                    
                    callback.accept(new ScreenshotData(result.url, result.base64));
                }
                catch (Exception e)
                {
                    log.error("Failed to capture/upload screenshot", e);
                    callback.accept(null);
                }
            });
        });
    }
    
    /**
     * Submit pre-processed items to the backend as a batch.
     * Per-channel filtering is done in the API client based on total drop value.
     * 
     * @param items List of items to submit
     * @param rsn Player's RuneScape name
     * @param sourceName Source of the drop
     * @param type Type of loot record
     * @param screenshotUrl URL of saved screenshot (for premium guilds)
     * @param screenshotBase64 Base64 screenshot data (for non-premium guilds)
     */
    private void submitProcessedItems(List<ProcessedItem> items, String rsn, String sourceName, 
                                       LootRecordType type, String screenshotUrl, String screenshotBase64)
    {
        log.info("submitProcessedItems called with {} items, rsn={}, source={}, screenshot={}", 
            items.size(), rsn, sourceName, (screenshotUrl != null || screenshotBase64 != null) ? "yes" : "no");
        
        // Calculate total value (per-channel filtering happens in API client)
        int totalValue = 0;
        for (ProcessedItem item : items)
        {
            log.debug("Item: {} x{} = {}gp", item.name, item.quantity, item.value);
            totalValue += item.value;
        }
        
        if (items.isEmpty())
        {
            log.info("No items to submit");
            return;
        }
        
        // Submit all items as a batch - API client will filter by per-channel thresholds
        final int finalTotalValue = totalValue;
        final List<ProcessedItem> itemsCopy = new ArrayList<>(items);
        final String finalScreenshotUrl = screenshotUrl;
        final String finalScreenshotBase64 = screenshotBase64;
        executor.execute(() -> {
            try
            {
                log.info("Submitting {} items from {} (total: {}gp) with screenshot: {}", 
                    itemsCopy.size(), sourceName, finalTotalValue, 
                    (finalScreenshotUrl != null || finalScreenshotBase64 != null));
                
                apiClient.submitDropBatch(itemsCopy, rsn, sourceName, type.name(), 
                    finalScreenshotUrl, finalScreenshotBase64);
                log.info("Successfully submitted {} items", itemsCopy.size());
                
                // Update panel with each drop
                for (ProcessedItem item : itemsCopy)
                {
                    LootDropData drop = new LootDropData(
                        rsn, item.name, item.quantity, item.value, 
                        sourceName, type.name(), finalScreenshotUrl
                    );
                    SwingUtilities.invokeLater(() -> panel.addRecentDrop(drop));
                }
                
                // Refresh stats after successful drop submission
                SwingUtilities.invokeLater(() -> panel.refreshStats());
            }
            catch (Exception e)
            {
                log.error("Failed to submit drops: {}", e.getMessage());
            }
        });
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (event.getType() != ChatMessageType.GAMEMESSAGE)
        {
            return;
        }

        // Check for collection log messages
        final String message = event.getMessage();
        if (message.contains("New item added to your collection log:"))
        {
            if (!authManager.isAuthenticated() || !config.trackCollectionLog())
            {
                return;
            }

            // Extract item name from message
            String itemName = extractCollectionLogItem(message);
            if (itemName != null)
            {
                // Check for duplicate - prevent processing the same item within 5 seconds
                final String dedupKey = itemName.toLowerCase();
                final long now = System.currentTimeMillis();
                final Long lastProcessed = recentCollectionLogItems.get(dedupKey);
                
                if (lastProcessed != null && (now - lastProcessed) < COLLECTION_LOG_DEDUP_WINDOW_MS)
                {
                    log.debug("Skipping duplicate collection log entry for: {} (processed {}ms ago)", 
                        itemName, now - lastProcessed);
                    return;
                }
                
                // Mark as processed
                recentCollectionLogItems.put(dedupKey, now);
                
                // Clean up old entries (older than 30 seconds)
                recentCollectionLogItems.entrySet().removeIf(e -> (now - e.getValue()) > 30000);
                
                // Cache this as a potential pet name (for cross-referencing with pet messages)
                // This helps when we get a pet but it doesn't become our follower (dupe/inventory full)
                recentCollectionLogPetCandidate = itemName;
                recentCollectionLogPetCandidateTime = now;
                
                final String rsn = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Unknown";
                final String finalItemName = itemName;
                
                log.info("Processing collection log entry: {}", itemName);
                
                // Capture screenshot if enabled, then submit
                if (config.captureScreenshots())
                {
                    captureScreenshot(screenshotData -> {
                        String url = screenshotData != null ? screenshotData.url : null;
                        String base64 = screenshotData != null ? screenshotData.base64 : null;
                        submitCollectionLogEntry(rsn, finalItemName, url, base64);
                    });
                }
                else
                {
                    executor.execute(() -> submitCollectionLogEntry(rsn, finalItemName, null, null));
                }
            }
        }

        // Check for pet messages
        if (message.contains("You have a funny feeling") || message.contains("You feel something weird"))
        {
            if (!authManager.isAuthenticated() || !config.trackPets())
            {
                return;
            }

            final String rsn = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Unknown";
            final String petMessage = message;
            
            // Check if this is a "would have been followed" message (pet didn't become follower)
            final boolean isDuplicatePet = message.contains("would have been followed");
            
            // Try to get the pet name from the player's follower
            // Schedule a slight delay to allow the pet to spawn
            executor.schedule(() -> {
                clientThread.invoke(() -> {
                    String petName = null;
                    
                    // Method 1: Try to get the pet name from the player's follower
                    if (!isDuplicatePet && client.getFollower() != null)
                    {
                        petName = client.getFollower().getName();
                        log.info("Pet drop detected! Pet name from follower: {}", petName);
                    }
                    
                    // Method 2: If no follower (dupe/inventory full), check recent collection log entries
                    // Pets often trigger a collection log entry at the same time
                    if (petName == null)
                    {
                        long now = System.currentTimeMillis();
                        if (recentCollectionLogPetCandidate != null && 
                            (now - recentCollectionLogPetCandidateTime) < PET_COLLECTION_LOG_WINDOW_MS)
                        {
                            petName = recentCollectionLogPetCandidate;
                            log.info("Pet drop detected! Pet name from collection log: {}", petName);
                            // Clear the candidate so we don't reuse it
                            recentCollectionLogPetCandidate = null;
                        }
                        else
                        {
                            log.info("Pet drop detected! Could not determine pet name (no follower, no recent collection log)");
                        }
                    }
                    
                    final String finalPetName = petName;
                    
                    // Capture screenshot if enabled, then submit
                    if (config.captureScreenshots())
                    {
                        captureScreenshot(screenshotData -> {
                            String url = screenshotData != null ? screenshotData.url : null;
                            String base64 = screenshotData != null ? screenshotData.base64 : null;
                            submitPetDrop(rsn, petMessage, finalPetName, url, base64);
                        });
                    }
                    else
                    {
                        executor.execute(() -> submitPetDrop(rsn, petMessage, finalPetName, null, null));
                    }
                });
            }, 600, TimeUnit.MILLISECONDS); // Small delay for pet to spawn
        }
    }
    
    /**
     * Submit a pet drop with optional screenshot and pet name
     */
    private void submitPetDrop(String rsn, String message, String petName, String screenshotUrl, String screenshotBase64)
    {
        String displayName = petName != null ? petName : "Pet Drop";
        
        LootDropData drop = new LootDropData(
            rsn,
            displayName,
            1,
            0,
            "Pet",
            "PET",
            screenshotUrl
        );

        executor.execute(() -> {
            try
            {
                apiClient.submitPetDrop(drop, message, petName, screenshotUrl, screenshotBase64);
                log.info("Submitted pet drop: {} with screenshot: {}", displayName, (screenshotUrl != null || screenshotBase64 != null));
                SwingUtilities.invokeLater(() -> panel.addRecentDrop(drop));
            }
            catch (Exception e)
            {
                log.error("Failed to submit pet drop", e);
            }
        });
    }

    private String extractCollectionLogItem(String message)
    {
        // Format: "New item added to your collection log: <col=ff0000>item name</col>"
        int colonIndex = message.lastIndexOf(':');
        if (colonIndex != -1 && colonIndex < message.length() - 1)
        {
            String itemName = message.substring(colonIndex + 1).trim();
            // Strip OSRS color tags like <col=ff0000>...</col>
            itemName = stripColorTags(itemName);
            return itemName;
        }
        return null;
    }
    
    /**
     * Strip OSRS color formatting tags from text
     * Format: <col=XXXXXX>text</col>
     */
    private String stripColorTags(String text)
    {
        if (text == null)
        {
            return null;
        }
        // Remove opening color tags like <col=ff0000>
        text = text.replaceAll("<col=[0-9a-fA-F]+>", "");
        // Remove closing </col> tags
        text = text.replaceAll("</col>", "");
        return text.trim();
    }
    
    /**
     * Submit a collection log entry with optional screenshot
     */
    private void submitCollectionLogEntry(String rsn, String itemName, String screenshotUrl, String screenshotBase64)
    {
        LootDropData drop = new LootDropData(
            rsn,
            itemName,
            1,
            0, // Value will be looked up on server
            "Collection Log",
            "COLLECTION_LOG",
            screenshotUrl
        );

        executor.execute(() -> {
            try
            {
                apiClient.submitCollectionLogEntry(drop, screenshotUrl, screenshotBase64);
                log.info("Submitted collection log entry: {} with screenshot: {}", 
                    itemName, (screenshotUrl != null || screenshotBase64 != null));
                SwingUtilities.invokeLater(() -> panel.addRecentDrop(drop));
            }
            catch (Exception e)
            {
                log.error("Failed to submit collection log entry", e);
            }
        });
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (event.getGroup().equals("osrsloottracker"))
        {
            // Update the panel's tracking status display when config changes
            SwingUtilities.invokeLater(() -> {
                if (panel != null)
                {
                    panel.updateTrackingStatus();
                }
            });
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            log.info("LOGGED_IN detected, scheduling RSN check...");
            
            // Schedule a delayed check - RSN takes ~1-2 seconds to be available after login
            executor.schedule(() -> {
                // Now get the RSN on the client thread
                clientThread.invoke(() -> {
                    String rsn = null;
                    if (client.getLocalPlayer() != null)
                    {
                        rsn = client.getLocalPlayer().getName();
                    }
                    log.info("Player RSN after delay: {}", rsn);
                    
                    if (rsn != null && !rsn.isEmpty())
                    {
                        SwingUtilities.invokeLater(() -> {
                            if (panel != null)
                            {
                                panel.refreshStats();
                            }
                        });
                    }
                });
            }, 2, TimeUnit.SECONDS);
        }
    }

    @Provides
    OSRSLootTrackerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(OSRSLootTrackerConfig.class);
    }
    
    // ============== DEBUG/TEST METHODS (remove before release) ==============
    
    /**
     * Simulate a pet drop for testing purposes.
     * This bypasses the chat message detection and directly triggers the pet submission flow.
     */
    public void simulatePetDrop()
    {
        if (!authManager.isAuthenticated())
        {
            log.warn("[TEST] Cannot simulate pet drop - not authenticated");
            return;
        }
        
        log.info("[TEST] Simulating pet drop...");
        
        // Must access client on the client thread
        clientThread.invoke(() -> {
            final String rsn = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "TestPlayer";
            final String testMessage = "You have a funny feeling like you're being followed. (TEST)";
            
            // Try to get pet name from follower (if player has one)
            String petName = null;
            if (client.getFollower() != null)
            {
                petName = client.getFollower().getName();
            }
            if (petName == null)
            {
                petName = "Test Pet"; // Fallback for testing
            }
            
            final String finalPetName = petName;
            log.info("[TEST] Simulating pet drop for: {} with pet: {}", rsn, finalPetName);
            
            // Capture screenshot if enabled, then submit (just like real pet drop)
            if (config.captureScreenshots())
            {
                captureScreenshot(screenshotData -> {
                    String url = screenshotData != null ? screenshotData.url : null;
                    String base64 = screenshotData != null ? screenshotData.base64 : null;
                    submitPetDrop(rsn, testMessage, finalPetName, url, base64);
                });
            }
            else
            {
                executor.execute(() -> submitPetDrop(rsn, testMessage, finalPetName, null, null));
            }
        });
    }
    
    /**
     * Simulate a collection log entry for testing purposes.
     */
    public void simulateCollectionLog()
    {
        if (!authManager.isAuthenticated())
        {
            log.warn("[TEST] Cannot simulate collection log - not authenticated");
            return;
        }
        
        log.info("[TEST] Simulating collection log entry...");
        
        // Must access client on the client thread
        clientThread.invoke(() -> {
            final String rsn = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "TestPlayer";
            final String testItem = "Dragon chainbody (TEST)";
            
            log.info("[TEST] Simulating collection log entry for: {} - {}", rsn, testItem);
            
            // Capture screenshot if enabled, then submit
            if (config.captureScreenshots())
            {
                captureScreenshot(screenshotData -> {
                    String url = screenshotData != null ? screenshotData.url : null;
                    String base64 = screenshotData != null ? screenshotData.base64 : null;
                    submitCollectionLogEntry(rsn, testItem, url, base64);
                });
            }
            else
            {
                executor.execute(() -> submitCollectionLogEntry(rsn, testItem, null, null));
            }
        });
    }
}


