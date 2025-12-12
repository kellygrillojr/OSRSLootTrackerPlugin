package com.osrsloottracker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class OSRSLootTrackerPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(OSRSLootTrackerPlugin.class);
        RuneLite.main(args);
    }
}

