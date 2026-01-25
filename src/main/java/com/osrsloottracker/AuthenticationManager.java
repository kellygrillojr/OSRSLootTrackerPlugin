package com.osrsloottracker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.LinkBrowser;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Manages Discord OAuth authentication flow for the plugin.
 * Uses web-based callback with polling instead of localhost server.
 */
@Slf4j
@Singleton
public class AuthenticationManager
{
    // Default Discord Client ID (PRODUCTION) - can be overridden via system property for dev
    private static final String DEFAULT_DISCORD_CLIENT_ID = "1339214146356383744";
    private static final String DISCORD_OAUTH_URL = "https://discord.com/api/oauth2/authorize";
    private static final String OAUTH_SCOPES = "identify guilds";
    
    private static String getDiscordClientId()
    {
        String envClientId = System.getProperty("osrsloottracker.discord.clientId");
        return envClientId != null ? envClientId : DEFAULT_DISCORD_CLIENT_ID;
    }
    
    // Polling settings
    private static final int POLL_INTERVAL_MS = 2000; // Poll every 2 seconds
    private static final int POLL_TIMEOUT_MS = 120000; // Give up after 2 minutes
    
    private final ScheduledExecutorService pollExecutor = Executors.newSingleThreadScheduledExecutor();
    
    @Inject
    private Gson gson;
    
    @Inject
    private OkHttpClient okHttpClient;
    
    @Inject
    private ConfigManager configManager;
    
    @Inject
    private OSRSLootTrackerConfig config;
    
    @Getter
    private boolean authenticated = false;
    
    @Getter
    private String discordId;
    
    @Getter
    private String discordUsername;
    
    @Getter
    private String authToken;
    
    private ScheduledFuture<?> pollTask;
    private Consumer<AuthResult> authCallback;
    private long pollStartTime;
    
    /**
     * Check if we have stored authentication and restore it.
     * We skip validation on startup - if the token is invalid/expired,
     * API calls will fail with 401 and the user can re-login.
     */
    public void checkStoredAuth()
    {
        String storedToken = config.authToken();
        String storedDiscordId = config.discordId();
        String storedUsername = config.discordUsername();
        
        if (storedToken != null && !storedToken.isEmpty())
        {
            // Trust the stored token without validation
            // If it's invalid, API calls will fail and user can re-login
            this.authToken = storedToken;
            this.discordId = storedDiscordId;
            this.discordUsername = storedUsername;
            this.authenticated = true;
            log.info("Restored authentication for user: {}", discordUsername);
        }
        else
        {
            log.debug("No stored authentication found");
        }
    }
    
    /**
     * Start the Discord OAuth flow using web-based callback
     * @param callback Called when authentication completes or fails
     */
    public void startOAuthFlow(Consumer<AuthResult> callback)
    {
        this.authCallback = callback;
        
        try
        {
            // Generate unique session ID for this auth attempt
            String sessionId = UUID.randomUUID().toString();
            
            // Build OAuth URL - redirects to our web server, not localhost
            String redirectUri = getCallbackUrl();
            
            String authUrl = DISCORD_OAUTH_URL + 
                "?client_id=" + getDiscordClientId() +
                "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8) +
                "&response_type=code" +
                "&scope=" + URLEncoder.encode(OAUTH_SCOPES, StandardCharsets.UTF_8) +
                "&state=" + sessionId;
            
            log.debug("Starting OAuth flow with session: {}", sessionId);
            log.debug("Redirect URI: {}", redirectUri);
            
            // Open browser using RuneLite's LinkBrowser
            LinkBrowser.browse(authUrl);
            log.debug("Opened browser for Discord authentication");
            
            // Start polling for auth result
            startPolling(sessionId);
        }
        catch (Exception e)
        {
            log.error("Error starting OAuth flow", e);
            callback.accept(AuthResult.error("Failed to start authentication: " + e.getMessage()));
        }
    }
    
    /**
     * Get the callback URL for OAuth
     */
    private String getCallbackUrl()
    {
        String apiEndpoint = getApiEndpoint();
        log.debug("Using API endpoint for callback: {}", apiEndpoint);
        return apiEndpoint + "/auth/plugin-callback";
    }
    
    /**
     * Get the current API endpoint (prioritizes system property, defaults to production)
     */
    public String getApiEndpoint()
    {
        String apiEndpoint = System.getProperty("osrsloottracker.api");
        // Always default to production - don't use config (which might have stale persisted data)
        return (apiEndpoint != null && !apiEndpoint.isEmpty()) 
            ? apiEndpoint 
            : "https://osrsloottracker.com/api";
    }
    
    /**
     * Start polling the backend for auth completion
     */
    private void startPolling(String sessionId)
    {
        // Cancel any existing poll task
        if (pollTask != null && !pollTask.isDone())
        {
            pollTask.cancel(false);
        }
        
        pollStartTime = System.currentTimeMillis();
        
        pollTask = pollExecutor.scheduleAtFixedRate(() -> {
            try
            {
                // Check for timeout
                if (System.currentTimeMillis() - pollStartTime > POLL_TIMEOUT_MS)
                {
                    log.warn("Authentication polling timed out");
                    pollTask.cancel(false);
                    authCallback.accept(AuthResult.error("Authentication timed out. Please try again."));
                    return;
                }
                
                // Poll the session endpoint
                String pollUrl = getApiEndpoint() + "/auth/plugin-session/" + sessionId;
                
                Request request = new Request.Builder()
                    .url(pollUrl)
                    .get()
                    .build();
                
                try (Response response = okHttpClient.newCall(request).execute())
                {
                    int responseCode = response.code();
                    
                    if (responseCode == 200 && response.body() != null)
                    {
                        String responseBody = response.body().string();
                        JsonObject json = gson.fromJson(responseBody, JsonObject.class);
                        String status = json.get("status").getAsString();
                        
                        if ("complete".equals(status))
                        {
                            // Success! Extract token and user info
                            pollTask.cancel(false);
                            
                            this.authToken = json.get("access_token").getAsString();
                            JsonObject user = json.getAsJsonObject("user");
                            this.discordId = user.get("discord_id").getAsString();
                            this.discordUsername = user.get("discord_username").getAsString();
                            this.authenticated = true;
                            
                            // Store in config using ConfigManager for proper persistence
                            configManager.setConfiguration("osrsloottracker", "authToken", authToken);
                            configManager.setConfiguration("osrsloottracker", "discordId", discordId);
                            configManager.setConfiguration("osrsloottracker", "discordUsername", discordUsername);
                            
                            log.info("Authentication successful for user: {}", discordUsername);
                            authCallback.accept(AuthResult.success(discordUsername));
                        }
                        else if ("error".equals(status))
                        {
                            // Auth failed
                            pollTask.cancel(false);
                            String error = json.has("error") ? json.get("error").getAsString() : "Unknown error";
                            log.error("Authentication failed: {}", error);
                            authCallback.accept(AuthResult.error(error));
                        }
                        // If status is "pending", continue polling
                    }
                    else
                    {
                        log.debug("Poll returned status {}, continuing...", responseCode);
                    }
                }
            }
            catch (Exception e)
            {
                log.debug("Poll error (may be transient): {}", e.getMessage());
            }
        }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Clear authentication and log out
     */
    public void logout()
    {
        // Cancel any pending poll
        if (pollTask != null && !pollTask.isDone())
        {
            pollTask.cancel(false);
        }
        
        clearAuth();
        log.info("User logged out");
    }
    
    private void clearAuth()
    {
        this.authToken = null;
        this.discordId = null;
        this.discordUsername = null;
        this.authenticated = false;
        
        // Clear from config using ConfigManager for proper persistence
        configManager.setConfiguration("osrsloottracker", "authToken", "");
        configManager.setConfiguration("osrsloottracker", "discordId", "");
        configManager.setConfiguration("osrsloottracker", "discordUsername", "");
    }
    
    /**
     * Result of authentication attempt
     */
    public static class AuthResult
    {
        public final boolean success;
        public final String message;
        public final String username;
        
        private AuthResult(boolean success, String message, String username)
        {
            this.success = success;
            this.message = message;
            this.username = username;
        }
        
        public static AuthResult success(String username)
        {
            return new AuthResult(true, "Authentication successful", username);
        }
        
        public static AuthResult error(String message)
        {
            return new AuthResult(false, message, null);
        }
    }
}
