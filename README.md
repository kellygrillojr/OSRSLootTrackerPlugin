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


## Setup

### 1. Discord Authentication
1. Click the OSRS Loot Tracker panel icon in RuneLite's sidebar
2. Click "Login with Discord"
3. Authorize the app in your browser
4. Return to RuneLite - you should see your username

### 2. Configure Server & Events
1. Select your Discord server from the dropdown
2. Configure tracking options in the plugin settings

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


The plugin communicates with the OSRS Loot Tracker backend to:

- Exchange Discord OAuth code for JWT
- Get user's servers with bot installed
- Get active events for a server
- Submit a loot drop
- Submit collection log entry
- Submit pet drop
- Get recent drops

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

