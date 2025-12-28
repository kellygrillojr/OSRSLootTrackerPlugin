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

    @Override
    protected void startUp() throws Exception
    {
        log.info("OSRS Loot Tracker plugin started!");

        // Check for existing authentication BEFORE initializing the panel
        // so the panel knows whether to show login or main view
        authManager.checkStoredAuth();

        // Initialize the injected panel
        panel.init();
        
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
        final List<ProcessedItem> processedItems = new ArrayList<>();
        int totalDropValue = 0;
        
        for (ItemStack item : event.getItems())
        {
            final int itemId = item.getId();
            final int quantity = item.getQuantity();
            final int value = itemManager.getItemPrice(itemId) * quantity;
            final String itemName = itemManager.getItemComposition(itemId).getName();
            
            processedItems.add(new ProcessedItem(itemName, quantity, value));
            totalDropValue += value;
        }
        
        log.info("Processing loot for RSN: {}, from: {}, items: {}, total value: {}gp", 
            rsn, sourceName, processedItems.size(), totalDropValue);

        // Check if this drop would be sent to at least one channel based on per-channel thresholds
        if (!apiClient.wouldDropBeSent(totalDropValue))
        {
            log.debug("Drop value {}gp doesn't meet any channel threshold", totalDropValue);
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
                final String rsn = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Unknown";
                
                LootDropData drop = new LootDropData(
                    rsn,
                    itemName,
                    1,
                    0, // Value will be looked up on server
                    "Collection Log",
                    "COLLECTION_LOG"
                );

                executor.execute(() -> {
                    try
                    {
                        apiClient.submitCollectionLogEntry(drop);
                        log.info("Submitted collection log entry: {}", itemName);
                        SwingUtilities.invokeLater(() -> panel.addRecentDrop(drop));
                    }
                    catch (Exception e)
                    {
                        log.error("Failed to submit collection log entry", e);
                    }
                });
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
            
            LootDropData drop = new LootDropData(
                rsn,
                "Pet Drop",
                1,
                0,
                "Pet",
                "PET"
            );

            executor.execute(() -> {
                try
                {
                    apiClient.submitPetDrop(drop, message);
                    log.info("Submitted pet drop!");
                    SwingUtilities.invokeLater(() -> panel.addRecentDrop(drop));
                }
                catch (Exception e)
                {
                    log.error("Failed to submit pet drop", e);
                }
            });
        }
    }

    private String extractCollectionLogItem(String message)
    {
        // Format: "New item added to your collection log: <item name>"
        int colonIndex = message.lastIndexOf(':');
        if (colonIndex != -1 && colonIndex < message.length() - 1)
        {
            return message.substring(colonIndex + 1).trim();
        }
        return null;
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
}

