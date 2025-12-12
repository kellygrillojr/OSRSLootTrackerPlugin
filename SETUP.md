# OSRS Loot Tracker Plugin - Development Setup

## Prerequisites

- Java 11 JDK installed
- Gradle (or use the wrapper)
- Access to Discord Developer Portal

---

## Step 1: Configure Discord OAuth

### Add Plugin Callback URL to Discord App

1. Go to [Discord Developer Portal](https://discord.com/developers/applications)
2. Select your application (ID: `1357499239525122270` for dev)
3. Go to **OAuth2** → **General**
4. Under **Redirects**, add:
   ```
   http://localhost:43739/callback
   ```
5. Click **Save Changes**

This allows the RuneLite plugin to receive the OAuth callback on the local machine.

---

## Step 2: Build the Plugin

### Windows
```batch
cd E:\OSRSLootTrackerPlugin
gradlew.bat build
```

### Linux/Mac
```bash
cd OSRSLootTrackerPlugin
./gradlew build
```

The built JAR will be in `build/libs/osrs-loot-tracker-plugin-1.0.0.jar`

---

## Step 3: Install in RuneLite (Development)

### Option A: External Plugin Loading
1. Run RuneLite with `--developer-mode`
2. Open the Plugin Configuration
3. Add external plugin: point to the built JAR

### Option B: Local Testing with RuneLite SDK
1. Clone RuneLite repository
2. Add this plugin as a dependency
3. Run via IDE

---

## Step 4: Backend Configuration

The backend already has the plugin routes added at `/api/plugin/*`:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/auth/plugin-token` | POST | Exchange OAuth code for JWT |
| `/api/plugin/servers` | GET | Get user's servers |
| `/api/plugin/servers/:id/events` | GET | Get active events |
| `/api/plugin/drops` | POST | Submit a drop |
| `/api/plugin/collection-log` | POST | Submit collection log entry |
| `/api/plugin/pets` | POST | Submit pet drop |
| `/api/plugin/events/:id` | GET | Get event details |
| `/api/plugin/events/:id/progress` | GET | Get team progress |
| `/api/plugin/drops/recent` | GET | Get recent drops |

---

## Configuration Values

### Development Environment
- **Discord Client ID**: `1357499239525122270`
- **API Endpoint**: `https://dev.osrsloottracker.com/api`
- **OAuth Callback**: `http://localhost:43739/callback`

### Production Environment
- **Discord Client ID**: `1339214146356383744`
- **API Endpoint**: `https://osrsloottracker.com/api`
- **OAuth Callback**: `http://localhost:43739/callback`

---

## Testing the Plugin

1. Start RuneLite with the plugin installed
2. Open the OSRS Loot Tracker panel (sidebar icon)
3. Click "Login with Discord"
4. Authorize in browser
5. Select your server from the dropdown
6. Get a valuable drop in-game
7. Check the panel and your dashboard for the drop

---

## Troubleshooting

### "Authentication failed" error
- Verify the Discord Client ID is correct
- Check that `http://localhost:43739/callback` is added to Discord app redirects
- Ensure the backend is running and accessible

### Drops not appearing
- Check the minimum value filter in plugin settings
- Verify you've selected a server in the plugin
- Check browser console/logs for API errors

### Panel not showing
- Restart RuneLite
- Check the plugin is enabled in Plugin Configuration
- Look for errors in RuneLite developer tools

---

## File Structure

```
OSRSLootTrackerPlugin/
├── build.gradle                 # Gradle build config
├── settings.gradle              # Project settings
├── runelite-plugin.properties   # Plugin metadata
├── gradlew.bat                  # Windows build script
├── README.md                    # User documentation
├── SETUP.md                     # This file
├── LICENSE                      # BSD-2-Clause
└── src/main/java/com/osrsloottracker/
    ├── OSRSLootTrackerPlugin.java    # Main plugin
    ├── OSRSLootTrackerConfig.java    # Settings
    ├── OSRSLootTrackerPanel.java     # UI panel
    ├── AuthenticationManager.java    # OAuth handling
    ├── LootTrackerApiClient.java     # API client
    └── LootDropData.java             # Data model
```

