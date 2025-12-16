package com.osrsloottracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("osrsloottracker")
public interface OSRSLootTrackerConfig extends Config
{
    // Internal section keys (no visible UI - items are hidden)
    String authSection = "authentication";
    String eventsSection = "events";

    @ConfigSection(
        name = "Tracking",
        description = "What to track",
        position = 1
    )
    String trackingSection = "tracking";

    @ConfigSection(
        name = "Filters",
        description = "Filter settings",
        position = 2
    )
    String filtersSection = "filters";

    // === Authentication Section (Hidden - internal use only) ===
    
    @ConfigItem(
        keyName = "apiEndpoint",
        name = "API Endpoint",
        description = "The API endpoint for the loot tracker service",
        section = authSection,
        hidden = true,
        position = 0
    )
    default String apiEndpoint()
    {
        // Read from system property if set (for dev/prod switching via Gradle)
        // Otherwise defaults to production
        String envApi = System.getProperty("osrsloottracker.api");
        return envApi != null ? envApi : "https://osrsloottracker.com/api";
    }

    @ConfigItem(
        keyName = "authToken",
        name = "Auth Token",
        description = "Your authentication token (set automatically after Discord login)",
        section = authSection,
        hidden = true,
        secret = true,
        position = 1
    )
    default String authToken()
    {
        return "";
    }

    @ConfigItem(
        keyName = "authToken",
        name = "",
        description = "",
        hidden = true
    )
    void setAuthToken(String token);

    @ConfigItem(
        keyName = "discordId",
        name = "Discord ID",
        description = "Your Discord ID (set automatically after login)",
        section = authSection,
        hidden = true,
        position = 2
    )
    default String discordId()
    {
        return "";
    }

    @ConfigItem(
        keyName = "discordId",
        name = "",
        description = "",
        hidden = true
    )
    void setDiscordId(String id);

    @ConfigItem(
        keyName = "discordUsername",
        name = "Discord Username",
        description = "Your Discord username (set automatically after login)",
        section = authSection,
        hidden = true,
        position = 3
    )
    default String discordUsername()
    {
        return "";
    }

    @ConfigItem(
        keyName = "discordUsername",
        name = "",
        description = "",
        hidden = true
    )
    void setDiscordUsername(String username);

    // === Tracking Section ===

    @ConfigItem(
        keyName = "trackLoot",
        name = "Track Valuable Drops",
        description = "Sends messages to Discord when valuable drops over the minimum value are received",
        section = trackingSection,
        position = 0
    )
    default boolean trackLoot()
    {
        return true;
    }

    @ConfigItem(
        keyName = "trackCollectionLog",
        name = "Track Collection Log",
        description = "Track new collection log entries",
        section = trackingSection,
        position = 1
    )
    default boolean trackCollectionLog()
    {
        return true;
    }

    @ConfigItem(
        keyName = "trackPets",
        name = "Track Pets",
        description = "Track pet drops",
        section = trackingSection,
        position = 2
    )
    default boolean trackPets()
    {
        return true;
    }

    @ConfigItem(
        keyName = "trackRaidDrops",
        name = "Track Raid Drops",
        description = "Track drops from CoX, ToB, and ToA",
        section = trackingSection,
        position = 3
    )
    default boolean trackRaidDrops()
    {
        return true;
    }
    
    @ConfigItem(
        keyName = "captureScreenshots",
        name = "Capture Screenshots",
        description = "Take a screenshot when valuable loot is received and include it with the drop",
        section = trackingSection,
        position = 4
    )
    default boolean captureScreenshots()
    {
        return true;
    }

    // === Filters Section ===

    @ConfigItem(
        keyName = "minLootValue",
        name = "Minimum Value",
        description = "Minimum GP value to track a drop (0 = track all)",
        section = filtersSection,
        position = 0
    )
    @Range(min = 0, max = 100000000)
    default int minLootValue()
    {
        return 100000; // 100k default
    }

    @ConfigItem(
        keyName = "includeUntradeable",
        name = "Include Untradeable",
        description = "Include untradeable items (pets, uniques, etc.)",
        section = filtersSection,
        position = 1
    )
    default boolean includeUntradeable()
    {
        return true;
    }

    // === Events Section ===

    // Server/Event selection is done through the panel UI, not config
    @ConfigItem(
        keyName = "selectedServerId",
        name = "Selected Server",
        description = "The Discord server ID to send drops to",
        section = eventsSection,
        hidden = true,
        position = 0
    )
    default String selectedServerId()
    {
        return "";
    }

    @ConfigItem(
        keyName = "selectedServerId",
        name = "",
        description = "",
        hidden = true
    )
    void setSelectedServerId(String serverId);

    @ConfigItem(
        keyName = "selectedEventId",
        name = "Selected Event",
        description = "The Bingo/Event ID to participate in",
        section = eventsSection,
        hidden = true,
        position = 1
    )
    default String selectedEventId()
    {
        return "";
    }

    @ConfigItem(
        keyName = "selectedEventId",
        name = "",
        description = "",
        hidden = true
    )
    void setSelectedEventId(String eventId);

    @ConfigItem(
        keyName = "autoJoinEvents",
        name = "Auto-Join Events",
        description = "Automatically join active events on your selected server",
        section = eventsSection,
        hidden = true,
        position = 2
    )
    default boolean autoJoinEvents()
    {
        return false;
    }
    
    // Drop destinations - JSON array of { guild_id, channel_ids: [], event_id? }
    @ConfigItem(
        keyName = "dropDestinations",
        name = "Drop Destinations",
        description = "Configured servers and channels to send drops to (JSON)",
        section = eventsSection,
        hidden = true,
        position = 3
    )
    default String dropDestinations()
    {
        return "[]";
    }
    
    @ConfigItem(
        keyName = "dropDestinations",
        name = "",
        description = "",
        hidden = true
    )
    void setDropDestinations(String destinations);
}

