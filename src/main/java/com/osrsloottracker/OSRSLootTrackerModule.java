package com.osrsloottracker;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import javax.inject.Singleton;

/**
 * Guice module for dependency injection bindings
 */
public class OSRSLootTrackerModule extends AbstractModule
{
    @Override
    protected void configure()
    {
        bind(AuthenticationManager.class).in(Singleton.class);
        bind(LootTrackerApiClient.class).in(Singleton.class);
    }
}

