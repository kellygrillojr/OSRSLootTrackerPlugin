# OSRS Loot Tracker Plugin

A RuneLite plugin that sends loot drops directly to the [OSRS Loot Tracker](https://osrsloottracker.com) platform. Supports Bingo events, Snakes & Ladders, and general loot tracking.

## Features

- 🎮 **Discord Login** - Authenticate directly from RuneLite using your Discord account
- 📊 **Real-time Tracking** - Drops are sent instantly to your dashboard
- 🎯 **Bingo Events** - Automatically track progress for Bingo tiles
- 🐍 **Snakes & Ladders** - Participate in S&L events with drop-based progression
- 📝 **Collection Log** - Track new collection log entries
- 🐾 **Pet Drops** - Track pet drops automatically
- ⚙️ **Server Selection** - Choose which Discord server to send drops to
- 💰 **Value Filtering** - Set minimum GP thresholds for tracking

## Installation

### From RuneLite Plugin Hub (Coming Soon)
1. Open RuneLite
2. Click the Wrench icon to open Configuration
3. Click the Plugin Hub icon
4. Search for "OSRS Loot Tracker"
5. Click Install

### Manual Installation (Development)
1. Clone this repository
2. Run `./gradlew build` to build the plugin
3. Copy the JAR from `build/libs/` to your RuneLite plugins folder

## Setup

### 1. Discord Authentication
1. Click the OSRS Loot Tracker panel icon in RuneLite's sidebar
2. Click "Login with Discord"
3. Authorize the app in your browser
4. Return to RuneLite - you should see your username

### 2. Configure Server & Events
1. Select your Discord server from the dropdown
2. If there's an active Bingo/S&L event, select it from the Event dropdown
3. Configure tracking options in the plugin settings

### 3. Start Playing!
Your drops will automatically be sent to the platform.

## Configuration Options

| Option | Description | Default |
|--------|-------------|---------|
| Track Loot Drops | Track valuable NPC/PvP loot | ✓ Enabled |
| Track Collection Log | Track new collection log entries | ✓ Enabled |
| Track Pet Drops | Track pet drops | ✓ Enabled |
| Track Raid Drops | Track CoX/ToB/ToA drops | ✓ Enabled |
| Minimum Value | Minimum GP value to track | 100,000 |
| Include Untradeable | Track untradeable items | ✓ Enabled |

## Requirements

- RuneLite client
- Discord account
- OSRS Loot Tracker bot installed in your Discord server
- Account on [osrsloottracker.com](https://osrsloottracker.com)

## Development

### Building
```bash
./gradlew build
```

### Testing
```bash
./gradlew test
```

### Project Structure
```
src/main/java/com/osrsloottracker/
├── OSRSLootTrackerPlugin.java    # Main plugin class
├── OSRSLootTrackerConfig.java    # Configuration interface
├── OSRSLootTrackerPanel.java     # Side panel UI
├── AuthenticationManager.java    # Discord OAuth handling
├── LootTrackerApiClient.java     # API communication
└── LootDropData.java             # Drop data model
```

## API Endpoints Used

The plugin communicates with the OSRS Loot Tracker backend:

- `POST /api/auth/plugin-token` - Exchange Discord OAuth code for JWT
- `GET /api/plugin/servers` - Get user's servers with bot installed
- `GET /api/plugin/servers/:id/events` - Get active events for a server
- `POST /api/plugin/drops` - Submit a loot drop
- `POST /api/plugin/collection-log` - Submit collection log entry
- `POST /api/plugin/pets` - Submit pet drop
- `GET /api/plugin/drops/recent` - Get recent drops

## License

BSD 2-Clause License - See [LICENSE](LICENSE) for details.

## Support

- Discord: [OSRS Loot Tracker Support](https://discord.gg/vXSWXRTZYB)
- Website: [osrsloottracker.com](https://osrsloottracker.com)
- Issues: [GitHub Issues](https://github.com/kellygrillojr/OSRSLootTrackerPlugin/issues)

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

## Credits

- [RuneLite](https://runelite.net) - The OSRS client
- [better-discord-loot-logger](https://github.com/RinZJ/better-discord-loot-logger) - Inspiration for this project
- The OSRS Loot Tracker community

