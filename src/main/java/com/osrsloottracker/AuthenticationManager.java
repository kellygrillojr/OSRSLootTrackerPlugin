package com.osrsloottracker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.io.*;
import java.net.*;
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
     * Check if we have stored authentication and validate it
     */
    public void checkStoredAuth()
    {
        String storedToken = config.authToken();
        String storedDiscordId = config.discordId();
        String storedUsername = config.discordUsername();
        
        if (storedToken != null && !storedToken.isEmpty())
        {
            log.info("Found stored token, validating...");
            
            // Validate the token with the API
            int validationResult = validateToken(storedToken);
            
            if (validationResult == 200)
            {
                // Token is valid
                this.authToken = storedToken;
                this.discordId = storedDiscordId;
                this.discordUsername = storedUsername;
                this.authenticated = true;
                log.info("Restored authentication for user: {}", discordUsername);
            }
            else if (validationResult == 401 || validationResult == 403)
            {
                // Token is explicitly invalid/expired
                log.info("Stored token is invalid ({}), clearing authentication", validationResult);
                clearAuth();
            }
            else if (validationResult == -1)
            {
                // Network error - trust stored token temporarily
                log.info("Network error during validation, trusting stored token for user: {}", storedUsername);
                this.authToken = storedToken;
                this.discordId = storedDiscordId;
                this.discordUsername = storedUsername;
                this.authenticated = true;
            }
            else
            {
                // Other error (500, etc) - trust stored token temporarily
                log.warn("Unexpected validation response ({}), trusting stored token", validationResult);
                this.authToken = storedToken;
                this.discordId = storedDiscordId;
                this.discordUsername = storedUsername;
                this.authenticated = true;
            }
        }
        else
        {
            log.info("No stored authentication found");
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
            
            log.info("Starting OAuth flow with session: {}", sessionId);
            log.info("Redirect URI: {}", redirectUri);
            
            // Open browser
            if (Desktop.isDesktopSupported())
            {
                Desktop.getDesktop().browse(new URI(authUrl));
                log.info("Opened browser for Discord authentication");
                
                // Start polling for auth result
                startPolling(sessionId);
            }
            else
            {
                log.error("Desktop not supported, cannot open browser");
                callback.accept(AuthResult.error("Cannot open browser for authentication"));
            }
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
        log.info("Using API endpoint for callback: {}", apiEndpoint);
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
                
                URL url = new URL(pollUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                int responseCode = conn.getResponseCode();
                
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
                        
                        JsonObject json = gson.fromJson(response.toString(), JsonObject.class);
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
                            
                            // Store in config
                            config.setAuthToken(authToken);
                            config.setDiscordId(discordId);
                            config.setDiscordUsername(discordUsername);
                            
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
                }
                else
                {
                    log.debug("Poll returned status {}, continuing...", responseCode);
                }
            }
            catch (Exception e)
            {
                log.debug("Poll error (may be transient): {}", e.getMessage());
            }
        }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Validate an existing token with the API
     * @return HTTP status code, or -1 for network/connection errors
     */
    private int validateToken(String token)
    {
        try
        {
            URL url = new URL(getApiEndpoint() + "/auth/me");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            int responseCode = conn.getResponseCode();
            log.debug("Token validation response: {}", responseCode);
            return responseCode;
        }
        catch (Exception e)
        {
            log.error("Network error validating token: {}", e.getMessage());
            return -1; // Network error
        }
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
        
        config.setAuthToken("");
        config.setDiscordId("");
        config.setDiscordUsername("");
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
