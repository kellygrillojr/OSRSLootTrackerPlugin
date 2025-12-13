package com.osrsloottracker;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.QuantityFormatter;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Side panel for the OSRS Loot Tracker plugin
 */
@Slf4j
public class OSRSLootTrackerPanel extends PluginPanel
{
    private static final Color BRAND_COLOR = new Color(88, 101, 242); // Discord blurple
    private static final Color SUCCESS_COLOR = new Color(87, 242, 135);
    private static final Color WARNING_COLOR = new Color(254, 231, 92);
    private static final Color ERROR_COLOR = new Color(237, 66, 69);
    
    private final AuthenticationManager authManager;
    private final LootTrackerApiClient apiClient;
    private final OSRSLootTrackerConfig config;
    private final Gson gson;
    
    // UI Components
    private JPanel authPanel;
    private JPanel mainPanel;
    private JPanel recentDropsPanel;
    private JPanel destinationsPanel;
    private JPanel statsPanel;
    private JLabel statusLabel;
    private JLabel usernameLabel;
    
    // Stats labels
    private JLabel totalDropsLabel;
    private JLabel totalValueLabel;
    private JLabel todayDropsLabel;
    private JLabel weekDropsLabel;
    
    // Tracking status labels (for live updates)
    private JLabel lootDropsStatusLabel;
    private JLabel collectionLogStatusLabel;
    private JLabel petDropsStatusLabel;
    
    // Cached data
    private List<LootTrackerApiClient.ServerInfo> availableServers = new ArrayList<>();
    private Map<String, List<LootTrackerApiClient.ChannelInfo>> serverChannelsCache = new HashMap<>();
    
    private final List<LootDropData> recentDrops = new ArrayList<>();
    private static final int MAX_RECENT_DROPS = 10;
    
    // Callback to get current RSN from the plugin (on client thread)
    private java.util.function.Supplier<String> rsnSupplier = () -> null;
    
    @Inject
    public OSRSLootTrackerPanel(AuthenticationManager authManager, LootTrackerApiClient apiClient, OSRSLootTrackerConfig config, Gson gson)
    {
        this.authManager = authManager;
        this.apiClient = apiClient;
        this.config = config;
        this.gson = gson;
    }
    
    public void init()
    {
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(10, 10, 10, 10));
        
        buildPanel();
        updateAuthState();
    }
    
    private void buildPanel()
    {
        removeAll();
        
        // Header
        JPanel headerPanel = buildHeader();
        add(headerPanel, BorderLayout.NORTH);
        
        // Main content
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        // Auth panel (shown when not logged in)
        authPanel = buildAuthPanel();
        contentPanel.add(authPanel);
        
        // Main panel (shown when logged in)
        mainPanel = buildMainPanel();
        contentPanel.add(mainPanel);
        
        add(contentPanel, BorderLayout.CENTER);
        
        revalidate();
        repaint();
    }
    
    private JPanel buildHeader()
    {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(ColorScheme.DARK_GRAY_COLOR);
        header.setBorder(new EmptyBorder(0, 0, 10, 0));
        
        // Title
        JLabel title = new JLabel("OSRS Loot Tracker");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(BRAND_COLOR);
        header.add(title, BorderLayout.WEST);
        
        // Settings button
        JButton settingsBtn = new JButton("⚙");
        settingsBtn.setToolTipText("Open website");
        settingsBtn.addActionListener(e -> LinkBrowser.browse("https://osrsloottracker.com"));
        header.add(settingsBtn, BorderLayout.EAST);
        
        return header;
    }
    
    private JPanel buildAuthPanel()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new GridBagLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(30, 20, 30, 20));
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.CENTER;
        
        // Content wrapper for vertical centering
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        // Discord-style icon
        JPanel iconWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        iconWrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        JLabel iconLabel = new JLabel("🎮");
        iconLabel.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 56));
        iconLabel.setForeground(new Color(88, 101, 242));
        iconWrapper.add(iconLabel);
        contentPanel.add(iconWrapper);
        
        contentPanel.add(Box.createVerticalStrut(20));
        
        // Welcome text - line 1
        JPanel textWrapper1 = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        textWrapper1.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        JLabel welcomeLabel1 = new JLabel("Connect with Discord to");
        welcomeLabel1.setForeground(Color.WHITE);
        welcomeLabel1.setFont(FontManager.getRunescapeSmallFont());
        textWrapper1.add(welcomeLabel1);
        contentPanel.add(textWrapper1);
        
        // Welcome text - line 2
        JPanel textWrapper2 = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        textWrapper2.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        JLabel welcomeLabel2 = new JLabel("track your loot!");
        welcomeLabel2.setForeground(Color.WHITE);
        welcomeLabel2.setFont(FontManager.getRunescapeSmallFont());
        textWrapper2.add(welcomeLabel2);
        contentPanel.add(textWrapper2);
        
        contentPanel.add(Box.createVerticalStrut(20));
        
        // Login button wrapper for centering
        JPanel buttonWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        buttonWrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JButton loginBtn = new JButton("Login with Discord");
        loginBtn.setBackground(BRAND_COLOR);
        loginBtn.setForeground(Color.WHITE);
        loginBtn.setFocusPainted(false);
        loginBtn.setBorderPainted(false);
        loginBtn.setFont(FontManager.getRunescapeSmallFont());
        loginBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        loginBtn.setPreferredSize(new Dimension(160, 32));
        loginBtn.addActionListener(e -> startLogin());
        
        // Hover effect
        loginBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                loginBtn.setBackground(new Color(71, 82, 196)); // Darker on hover
            }
            @Override
            public void mouseExited(MouseEvent e) {
                loginBtn.setBackground(BRAND_COLOR);
            }
        });
        
        buttonWrapper.add(loginBtn);
        contentPanel.add(buttonWrapper);
        
        contentPanel.add(Box.createVerticalStrut(15));
        
        // Status label wrapper
        JPanel statusWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        statusWrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        statusLabel = new JLabel(" ");
        statusLabel.setForeground(new Color(130, 130, 130));
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        statusWrapper.add(statusLabel);
        contentPanel.add(statusWrapper);
        
        gbc.gridy = 0;
        panel.add(contentPanel, gbc);
        
        return panel;
    }
    
    private JPanel buildMainPanel()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        // User info section
        JPanel userPanel = new JPanel(new BorderLayout());
        userPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        userPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        usernameLabel = new JLabel("Not logged in");
        usernameLabel.setForeground(Color.WHITE);
        usernameLabel.setFont(FontManager.getRunescapeSmallFont());
        userPanel.add(usernameLabel, BorderLayout.WEST);
        
        JButton logoutBtn = new JButton("Logout");
        logoutBtn.setFont(FontManager.getRunescapeSmallFont());
        logoutBtn.addActionListener(e -> logout());
        userPanel.add(logoutBtn, BorderLayout.EAST);
        
        panel.add(userPanel);
        panel.add(Box.createVerticalStrut(10));
        
        // Stats section
        statsPanel = buildStatsPanel();
        panel.add(statsPanel);
        panel.add(Box.createVerticalStrut(10));
        
        // Destinations section header
        JPanel destHeader = new JPanel(new BorderLayout());
        destHeader.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JLabel destLabel = new JLabel("Drop Destinations");
        destLabel.setForeground(BRAND_COLOR);
        destLabel.setFont(FontManager.getRunescapeBoldFont());
        destHeader.add(destLabel, BorderLayout.WEST);
        
        JButton configureBtn = new JButton("Configure");
        configureBtn.setFont(FontManager.getRunescapeSmallFont());
        configureBtn.addActionListener(e -> openDestinationsDialog());
        destHeader.add(configureBtn, BorderLayout.EAST);
        
        panel.add(destHeader);
        panel.add(Box.createVerticalStrut(5));
        
        // Current destinations display
        destinationsPanel = new JPanel();
        destinationsPanel.setLayout(new BoxLayout(destinationsPanel, BoxLayout.Y_AXIS));
        destinationsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        destinationsPanel.setBorder(new EmptyBorder(8, 8, 8, 8));
        
        panel.add(destinationsPanel);
        panel.add(Box.createVerticalStrut(15));
        
        // Recent drops section (moved above tracking status)
        JPanel recentSection = new JPanel(new BorderLayout());
        recentSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JLabel recentLabel = new JLabel("Recent Drops");
        recentLabel.setForeground(BRAND_COLOR);
        recentLabel.setFont(FontManager.getRunescapeBoldFont());
        recentSection.add(recentLabel, BorderLayout.NORTH);
        
        recentDropsPanel = new JPanel();
        recentDropsPanel.setLayout(new BoxLayout(recentDropsPanel, BoxLayout.Y_AXIS));
        recentDropsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        recentDropsPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
        
        JLabel noDropsLabel = new JLabel("No drops yet...");
        noDropsLabel.setForeground(Color.GRAY);
        recentDropsPanel.add(noDropsLabel);
        
        JScrollPane scrollPane = new JScrollPane(recentDropsPanel);
        scrollPane.setPreferredSize(new Dimension(0, 150));
        scrollPane.setBorder(null);
        recentSection.add(scrollPane, BorderLayout.CENTER);
        
        panel.add(recentSection);
        panel.add(Box.createVerticalStrut(15));
        
        // Tracking status (moved below recent drops)
        JPanel trackingPanel = buildTrackingStatusPanel();
        panel.add(trackingPanel);
        
        // Open dashboard button
        panel.add(Box.createVerticalStrut(10));
        JButton dashboardBtn = new JButton("Open Dashboard");
        dashboardBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        dashboardBtn.addActionListener(e -> LinkBrowser.browse("https://osrsloottracker.com/dashboard"));
        panel.add(dashboardBtn);
        
        return panel;
    }
    
    private JPanel buildStatsPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        // Header
        JLabel headerLabel = new JLabel("📊 Your Loot Stats");
        headerLabel.setForeground(BRAND_COLOR);
        headerLabel.setFont(FontManager.getRunescapeBoldFont());
        panel.add(headerLabel, BorderLayout.NORTH);
        
        // Stats grid - use GridLayout for equal sizing
        JPanel statsGrid = new JPanel(new GridLayout(2, 2, 8, 8));
        statsGrid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        statsGrid.setBorder(new EmptyBorder(8, 0, 0, 0));
        
        // Total drops
        JPanel totalDropsBox = createStatBox("Total Drops", "0");
        totalDropsLabel = (JLabel) ((JPanel) totalDropsBox.getComponent(1)).getComponent(0);
        statsGrid.add(totalDropsBox);
        
        // Total value
        JPanel totalValueBox = createStatBox("Total Value", "0 GP");
        totalValueLabel = (JLabel) ((JPanel) totalValueBox.getComponent(1)).getComponent(0);
        statsGrid.add(totalValueBox);
        
        // Today's drops
        JPanel todayBox = createStatBox("Today", "0 drops");
        todayDropsLabel = (JLabel) ((JPanel) todayBox.getComponent(1)).getComponent(0);
        statsGrid.add(todayBox);
        
        // This week
        JPanel weekBox = createStatBox("This Week", "0 drops");
        weekDropsLabel = (JLabel) ((JPanel) weekBox.getComponent(1)).getComponent(0);
        statsGrid.add(weekBox);
        
        panel.add(statsGrid, BorderLayout.CENTER);
        
        return panel;
    }
    
    private JPanel createStatBox(String title, String value)
    {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBackground(new Color(30, 30, 30));
        box.setBorder(new EmptyBorder(5, 8, 5, 8));
        
        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(new Color(150, 150, 150));
        titleLabel.setFont(FontManager.getRunescapeSmallFont());
        box.add(titleLabel);
        
        JPanel valueWrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        valueWrapper.setBackground(new Color(30, 30, 30));
        JLabel valueLabel = new JLabel(value);
        valueLabel.setForeground(Color.WHITE);
        valueLabel.setFont(FontManager.getRunescapeSmallFont());
        valueWrapper.add(valueLabel);
        box.add(valueWrapper);
        
        return box;
    }
    
    /**
     * Set the RSN supplier callback (called from main plugin)
     */
    public void setRsnSupplier(java.util.function.Supplier<String> supplier)
    {
        this.rsnSupplier = supplier;
    }
    
    /**
     * Get the current RSN using the supplier
     */
    public String getCurrentRsn()
    {
        return rsnSupplier.get();
    }
    
    /**
     * Refresh the stats display
     */
    public void refreshStats()
    {
        if (authManager.isAuthenticated())
        {
            loadUserStats();
        }
    }
    
    private void loadUserStats()
    {
        String rsn = getCurrentRsn();
        log.info("Loading user stats for RSN: {}", rsn);
        apiClient.getUserStats(rsn).thenAccept(stats -> {
            SwingUtilities.invokeLater(() -> {
                if (stats != null)
                {
                    log.info("Received stats: {} drops, {} GP value", stats.total_drops, stats.total_value);
                    
                    // Format total drops
                    totalDropsLabel.setText(formatNumber(stats.total_drops));
                    
                    // Format total value
                    totalValueLabel.setText(formatValue(stats.total_value) + " GP");
                    totalValueLabel.setForeground(getValueColor(stats.total_value));
                    
                    // Today's stats
                    if (stats.periods != null && stats.periods.today != null)
                    {
                        todayDropsLabel.setText(stats.periods.today.drops + " drops");
                    }
                    
                    // Week stats
                    if (stats.periods != null && stats.periods.week != null)
                    {
                        weekDropsLabel.setText(stats.periods.week.drops + " drops");
                    }
                }
                else
                {
                    log.warn("Stats returned null - check if RSN is linked to your account");
                }
            });
        });
    }
    
    private String formatNumber(long number)
    {
        if (number >= 1_000_000_000)
        {
            return String.format("%.1fB", number / 1_000_000_000.0);
        }
        else if (number >= 1_000_000)
        {
            return String.format("%.1fM", number / 1_000_000.0);
        }
        else if (number >= 1_000)
        {
            return String.format("%.1fK", number / 1_000.0);
        }
        return String.valueOf(number);
    }
    
    private String formatValue(long value)
    {
        if (value >= 1_000_000_000)
        {
            return String.format("%.2fB", value / 1_000_000_000.0);
        }
        else if (value >= 1_000_000)
        {
            return String.format("%.2fM", value / 1_000_000.0);
        }
        else if (value >= 1_000)
        {
            return String.format("%.1fK", value / 1_000.0);
        }
        return String.valueOf(value);
    }
    
    private Color getValueColor(long value)
    {
        if (value >= 1_000_000_000) // 1B+
        {
            return new Color(255, 128, 0); // Orange
        }
        else if (value >= 100_000_000) // 100M+
        {
            return new Color(255, 215, 0); // Gold
        }
        else if (value >= 10_000_000) // 10M+
        {
            return Color.YELLOW;
        }
        return Color.WHITE;
    }
    
    private JPanel buildTrackingStatusPanel()
    {
        JPanel panel = new JPanel(new GridLayout(3, 2, 5, 5));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        // Loot Drops row
        JLabel lootLabel = new JLabel("Loot Drops");
        lootLabel.setForeground(Color.WHITE);
        panel.add(lootLabel);
        lootDropsStatusLabel = new JLabel();
        panel.add(lootDropsStatusLabel);
        
        // Collection Log row
        JLabel collectionLabel = new JLabel("Collection Log");
        collectionLabel.setForeground(Color.WHITE);
        panel.add(collectionLabel);
        collectionLogStatusLabel = new JLabel();
        panel.add(collectionLogStatusLabel);
        
        // Pet Drops row
        JLabel petLabel = new JLabel("Pet Drops");
        petLabel.setForeground(Color.WHITE);
        panel.add(petLabel);
        petDropsStatusLabel = new JLabel();
        panel.add(petDropsStatusLabel);
        
        // Set initial values
        updateTrackingStatus();
        
        return panel;
    }
    
    /**
     * Update the tracking status labels to reflect current config.
     * Can be called externally when config changes.
     */
    public void updateTrackingStatus()
    {
        updateStatusLabel(lootDropsStatusLabel, config.trackLoot());
        updateStatusLabel(collectionLogStatusLabel, config.trackCollectionLog());
        updateStatusLabel(petDropsStatusLabel, config.trackPets());
    }
    
    private void updateStatusLabel(JLabel label, boolean enabled)
    {
        if (label != null)
        {
            label.setText(enabled ? "✓ Enabled" : "✗ Disabled");
            label.setForeground(enabled ? SUCCESS_COLOR : Color.GRAY);
        }
    }
    
    private void updateAuthState()
    {
        boolean authenticated = authManager.isAuthenticated();
        
        authPanel.setVisible(!authenticated);
        mainPanel.setVisible(authenticated);
        
        if (authenticated)
        {
            usernameLabel.setText("👤 " + authManager.getDiscordUsername());
            loadServers();
            updateDestinationsDisplay();
            updateTrackingStatus(); // Refresh tracking status from config
            loadUserStats(); // Load user loot statistics
        }
        
        revalidate();
        repaint();
    }
    
    private void startLogin()
    {
        statusLabel.setText("Opening browser...");
        statusLabel.setForeground(Color.YELLOW);
        
        authManager.startOAuthFlow(result -> {
            SwingUtilities.invokeLater(() -> {
                if (result.success)
                {
                    statusLabel.setText("Login successful!");
                    statusLabel.setForeground(SUCCESS_COLOR);
                    updateAuthState();
                }
                else
                {
                    statusLabel.setText(result.message);
                    statusLabel.setForeground(ERROR_COLOR);
                }
            });
        });
    }
    
    private void logout()
    {
        authManager.logout();
        updateAuthState();
    }
    
    private void loadServers()
    {
        apiClient.getServers().thenAccept(servers -> {
            SwingUtilities.invokeLater(() -> {
                availableServers = servers;
                log.info("Loaded {} servers", servers.size());
                // Refresh destinations display now that we have server names
                updateDestinationsDisplay();
            });
        });
    }
    
    /**
     * Update the destinations display panel
     */
    private void updateDestinationsDisplay()
    {
        destinationsPanel.removeAll();
        
        List<DestinationConfig> destinations = getDestinations();
        
        if (destinations.isEmpty())
        {
            JLabel noDestLabel = new JLabel("No destinations configured");
            noDestLabel.setForeground(Color.GRAY);
            noDestLabel.setFont(FontManager.getRunescapeSmallFont());
            destinationsPanel.add(noDestLabel);
            
            JLabel hintLabel = new JLabel("Click 'Configure' to add servers");
            hintLabel.setForeground(Color.GRAY);
            hintLabel.setFont(FontManager.getRunescapeSmallFont());
            destinationsPanel.add(hintLabel);
        }
        else
        {
            for (DestinationConfig dest : destinations)
            {
                JPanel destRow = new JPanel(new BorderLayout());
                destRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                destRow.setBorder(new EmptyBorder(2, 0, 2, 0));
                
                // Find server name
                String serverName = dest.guildId;
                for (LootTrackerApiClient.ServerInfo server : availableServers)
                {
                    if (server.id.equals(dest.guildId))
                    {
                        serverName = server.name;
                        break;
                    }
                }
                
                JLabel serverLabel = new JLabel("📡 " + serverName);
                serverLabel.setForeground(Color.WHITE);
                serverLabel.setFont(FontManager.getRunescapeSmallFont());
                destRow.add(serverLabel, BorderLayout.WEST);
                
                int channelCount = dest.channelIds != null ? dest.channelIds.size() : 0;
                JLabel channelLabel = new JLabel(channelCount + " ch");
                channelLabel.setForeground(channelCount > 0 ? SUCCESS_COLOR : WARNING_COLOR);
                channelLabel.setFont(FontManager.getRunescapeSmallFont());
                destRow.add(channelLabel, BorderLayout.EAST);
                
                destinationsPanel.add(destRow);
            }
        }
        
        destinationsPanel.revalidate();
        destinationsPanel.repaint();
    }
    
    /**
     * Get the current destinations from config
     */
    private List<DestinationConfig> getDestinations()
    {
        String json = config.dropDestinations();
        if (json == null || json.isEmpty() || json.equals("[]"))
        {
            return new ArrayList<>();
        }
        try
        {
            return gson.fromJson(json, new TypeToken<List<DestinationConfig>>(){}.getType());
        }
        catch (Exception e)
        {
            log.error("Error parsing destinations", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * Save destinations to config
     */
    private void saveDestinations(List<DestinationConfig> destinations)
    {
        config.setDropDestinations(gson.toJson(destinations));
        updateDestinationsDisplay();
    }
    
    /**
     * Open the destinations configuration dialog
     */
    private void openDestinationsDialog()
    {
        // Create dialog
        JDialog dialog = new JDialog();
        dialog.setTitle("Configure Drop Destinations");
        dialog.setModal(true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        // Header panel with instructions
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
        headerPanel.setBorder(new EmptyBorder(10, 10, 5, 10));
        headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JLabel instructions = new JLabel("<html>Select servers and channels where your drops will be sent.</html>");
        instructions.setForeground(Color.WHITE);
        instructions.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerPanel.add(instructions);
        
        dialog.add(headerPanel, BorderLayout.NORTH);
        
        // Server list panel
        JPanel serversContainer = new JPanel();
        serversContainer.setLayout(new BoxLayout(serversContainer, BoxLayout.Y_AXIS));
        serversContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);
        serversContainer.setBorder(new EmptyBorder(5, 10, 5, 10));
        
        // Get current destinations
        List<DestinationConfig> currentDestinations = getDestinations();
        Map<String, DestinationConfig> destMap = new HashMap<>();
        for (DestinationConfig d : currentDestinations)
        {
            destMap.put(d.guildId, d);
        }
        
        // Track checkboxes and channel selections
        Map<String, JCheckBox> serverCheckboxes = new HashMap<>();
        Map<String, JScrollPane> channelScrollPanes = new HashMap<>();
        Map<String, List<JCheckBox>> channelCheckboxes = new HashMap<>();
        
        for (LootTrackerApiClient.ServerInfo server : availableServers)
        {
            JPanel serverRow = new JPanel();
            serverRow.setLayout(new BoxLayout(serverRow, BoxLayout.Y_AXIS));
            serverRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            serverRow.setBorder(new EmptyBorder(8, 8, 8, 8));
            serverRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            
            // Server checkbox header
            JPanel headerRow = new JPanel(new BorderLayout());
            headerRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            headerRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            
            JCheckBox serverCheck = new JCheckBox(server.name);
            serverCheck.setForeground(Color.WHITE);
            serverCheck.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            serverCheck.setSelected(destMap.containsKey(server.id));
            serverCheck.setFont(FontManager.getRunescapeBoldFont());
            serverCheckboxes.put(server.id, serverCheck);
            headerRow.add(serverCheck, BorderLayout.WEST);
            
            serverRow.add(headerRow);
            
            // Channel selection panel with its own scroll
            JPanel channelPanel = new JPanel();
            channelPanel.setLayout(new BoxLayout(channelPanel, BoxLayout.Y_AXIS));
            channelPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
            
            // Wrap channel panel in scroll pane with max height
            JScrollPane channelScroll = new JScrollPane(channelPanel);
            channelScroll.setBorder(new EmptyBorder(5, 15, 5, 0));
            channelScroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
            channelScroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
            channelScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            channelScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            channelScroll.setPreferredSize(new Dimension(350, 150)); // Fixed height for channel list
            channelScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
            channelScroll.setVisible(serverCheck.isSelected());
            channelScrollPanes.put(server.id, channelScroll);
            
            // Add loading label initially
            JLabel loadingLabel = new JLabel("Loading channels...");
            loadingLabel.setForeground(Color.GRAY);
            channelPanel.add(loadingLabel);
            
            serverRow.add(channelScroll);
            serversContainer.add(serverRow);
            serversContainer.add(Box.createVerticalStrut(8));
            
            // Load channels when server is checked
            final JPanel finalChannelPanel = channelPanel;
            serverCheck.addActionListener(e -> {
                channelScroll.setVisible(serverCheck.isSelected());
                if (serverCheck.isSelected() && !channelCheckboxes.containsKey(server.id))
                {
                    loadChannelsForDialog(server.id, finalChannelPanel, channelCheckboxes, destMap.get(server.id));
                }
                serversContainer.revalidate();
                serversContainer.repaint();
            });
            
            // If already selected, load channels
            if (serverCheck.isSelected())
            {
                loadChannelsForDialog(server.id, channelPanel, channelCheckboxes, destMap.get(server.id));
            }
        }
        
        // Main scroll pane for all servers
        JScrollPane mainScrollPane = new JScrollPane(serversContainer);
        mainScrollPane.setBorder(null);
        mainScrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
        mainScrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        mainScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        mainScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        
        dialog.add(mainScrollPane, BorderLayout.CENTER);
        
        // Buttons panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        buttonPanel.setBorder(new EmptyBorder(5, 10, 10, 10));
        
        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dialog.dispose());
        buttonPanel.add(cancelBtn);
        
        JButton saveBtn = new JButton("Save");
        saveBtn.setBackground(BRAND_COLOR);
        saveBtn.setForeground(Color.WHITE);
        saveBtn.addActionListener(e -> {
            // Build destinations from selections
            List<DestinationConfig> newDestinations = new ArrayList<>();
            
            for (LootTrackerApiClient.ServerInfo server : availableServers)
            {
                JCheckBox serverCheck = serverCheckboxes.get(server.id);
                if (serverCheck != null && serverCheck.isSelected())
                {
                    DestinationConfig dest = new DestinationConfig();
                    dest.guildId = server.id;
                    dest.channelIds = new ArrayList<>();
                    
                    List<JCheckBox> chBoxes = channelCheckboxes.get(server.id);
                    if (chBoxes != null)
                    {
                        for (JCheckBox chBox : chBoxes)
                        {
                            if (chBox.isSelected())
                            {
                                // Channel ID is stored in the checkbox name
                                dest.channelIds.add(chBox.getName());
                            }
                        }
                    }
                    
                    newDestinations.add(dest);
                }
            }
            
            saveDestinations(newDestinations);
            dialog.dispose();
        });
        buttonPanel.add(saveBtn);
        
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        
        // Set dialog size and show
        dialog.setPreferredSize(new Dimension(420, 500));
        dialog.setMinimumSize(new Dimension(350, 300));
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    
    /**
     * Load channels for a server in the config dialog
     */
    private void loadChannelsForDialog(String serverId, JPanel channelPanel, 
                                        Map<String, List<JCheckBox>> channelCheckboxes,
                                        DestinationConfig existingConfig)
    {
        apiClient.getServerChannels(serverId).thenAccept(channels -> {
            SwingUtilities.invokeLater(() -> {
                channelPanel.removeAll();
                
                if (channels == null || channels.isEmpty())
                {
                    JLabel noChannels = new JLabel("No text channels found");
                    noChannels.setForeground(Color.GRAY);
                    noChannels.setAlignmentX(Component.LEFT_ALIGNMENT);
                    channelPanel.add(noChannels);
                }
                else
                {
                    List<JCheckBox> checkboxes = new ArrayList<>();
                    List<String> existingChannelIds = existingConfig != null && existingConfig.channelIds != null 
                        ? existingConfig.channelIds 
                        : new ArrayList<>();
                    
                    // Group by category, putting uncategorized channels first
                    Map<String, List<LootTrackerApiClient.ChannelInfo>> byCategory = new java.util.LinkedHashMap<>();
                    
                    // First add uncategorized channels
                    List<LootTrackerApiClient.ChannelInfo> uncategorized = channels.stream()
                        .filter(ch -> ch.category == null || ch.category.isEmpty())
                        .collect(Collectors.toList());
                    if (!uncategorized.isEmpty())
                    {
                        byCategory.put("", uncategorized);
                    }
                    
                    // Then add categorized channels
                    channels.stream()
                        .filter(ch -> ch.category != null && !ch.category.isEmpty())
                        .collect(Collectors.groupingBy(ch -> ch.category))
                        .forEach(byCategory::put);
                    
                    for (Map.Entry<String, List<LootTrackerApiClient.ChannelInfo>> entry : byCategory.entrySet())
                    {
                        // Add category label if not empty
                        if (!entry.getKey().isEmpty())
                        {
                            channelPanel.add(Box.createVerticalStrut(5));
                            JLabel catLabel = new JLabel(entry.getKey());
                            catLabel.setForeground(new Color(150, 150, 150));
                            catLabel.setFont(FontManager.getRunescapeSmallFont());
                            catLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                            channelPanel.add(catLabel);
                        }
                        
                        for (LootTrackerApiClient.ChannelInfo channel : entry.getValue())
                        {
                            JCheckBox chBox = new JCheckBox("#" + channel.name);
                            chBox.setName(channel.id); // Store ID in name for retrieval
                            chBox.setForeground(Color.WHITE);
                            chBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
                            chBox.setOpaque(false);
                            chBox.setSelected(existingChannelIds.contains(channel.id));
                            chBox.setFont(FontManager.getRunescapeSmallFont());
                            chBox.setAlignmentX(Component.LEFT_ALIGNMENT);
                            chBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
                            channelPanel.add(chBox);
                            checkboxes.add(chBox);
                        }
                    }
                    
                    channelCheckboxes.put(serverId, checkboxes);
                    log.info("Loaded {} channels for server {}", checkboxes.size(), serverId);
                }
                
                channelPanel.revalidate();
                channelPanel.repaint();
                
                // Also update parent scroll pane
                if (channelPanel.getParent() != null)
                {
                    channelPanel.getParent().revalidate();
                    channelPanel.getParent().repaint();
                }
            });
        });
    }
    
    /**
     * Add a recent drop to the panel
     */
    public void addRecentDrop(LootDropData drop)
    {
        recentDrops.add(0, drop);
        while (recentDrops.size() > MAX_RECENT_DROPS)
        {
            recentDrops.remove(recentDrops.size() - 1);
        }
        
        updateRecentDropsPanel();
    }
    
    private void updateRecentDropsPanel()
    {
        recentDropsPanel.removeAll();
        
        if (recentDrops.isEmpty())
        {
            JLabel noDropsLabel = new JLabel("No drops yet...");
            noDropsLabel.setForeground(Color.GRAY);
            noDropsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            recentDropsPanel.add(noDropsLabel);
        }
        else
        {
            int index = 0;
            for (LootDropData drop : recentDrops)
            {
                // Alternating row colors
                Color rowColor = (index % 2 == 0) 
                    ? ColorScheme.DARKER_GRAY_COLOR 
                    : new Color(40, 40, 40);
                
                JPanel dropPanel = new JPanel();
                dropPanel.setLayout(new BoxLayout(dropPanel, BoxLayout.Y_AXIS));
                dropPanel.setBackground(rowColor);
                dropPanel.setBorder(new EmptyBorder(6, 8, 6, 8));
                dropPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                dropPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
                
                // Top row: Item name + value
                JPanel topRow = new JPanel(new BorderLayout());
                topRow.setBackground(rowColor);
                topRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                
                // Item name with quantity
                String itemText = drop.getItemName();
                if (drop.getQuantity() > 1)
                {
                    itemText += " x" + drop.getQuantity();
                }
                
                JLabel itemLabel = new JLabel(itemText);
                itemLabel.setForeground(Color.WHITE);
                itemLabel.setFont(FontManager.getRunescapeSmallFont());
                topRow.add(itemLabel, BorderLayout.WEST);
                
                // Value with color coding
                if (drop.getValue() > 0)
                {
                    JLabel valueLabel = new JLabel(QuantityFormatter.formatNumber(drop.getValue()) + " GP");
                    valueLabel.setFont(FontManager.getRunescapeSmallFont());
                    
                    // Color code based on value
                    if (drop.getValue() >= 1000000) // 1M+
                    {
                        valueLabel.setForeground(new Color(255, 128, 0)); // Orange for mega drops
                    }
                    else if (drop.getValue() >= 100000) // 100K+
                    {
                        valueLabel.setForeground(new Color(255, 215, 0)); // Gold
                    }
                    else if (drop.getValue() >= 10000) // 10K+
                    {
                        valueLabel.setForeground(Color.YELLOW);
                    }
                    else
                    {
                        valueLabel.setForeground(new Color(180, 180, 180)); // Light gray for low value
                    }
                    
                    topRow.add(valueLabel, BorderLayout.EAST);
                }
                
                dropPanel.add(topRow);
                
                // Bottom row: Source + time ago
                JPanel bottomRow = new JPanel(new BorderLayout());
                bottomRow.setBackground(rowColor);
                bottomRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                
                // Source/monster name
                String sourceText = drop.getSourceName() != null ? drop.getSourceName() : "";
                JLabel sourceLabel = new JLabel(sourceText);
                sourceLabel.setForeground(new Color(130, 130, 130));
                sourceLabel.setFont(FontManager.getRunescapeSmallFont());
                bottomRow.add(sourceLabel, BorderLayout.WEST);
                
                // Time ago
                String timeAgo = formatTimeAgo(drop.getTimestamp());
                JLabel timeLabel = new JLabel(timeAgo);
                timeLabel.setForeground(new Color(100, 100, 100));
                timeLabel.setFont(FontManager.getRunescapeSmallFont());
                bottomRow.add(timeLabel, BorderLayout.EAST);
                
                dropPanel.add(bottomRow);
                
                // Add separator line (except for last item)
                if (index < recentDrops.size() - 1)
                {
                    JSeparator separator = new JSeparator();
                    separator.setForeground(new Color(60, 60, 60));
                    separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
                    dropPanel.add(Box.createVerticalStrut(2));
                }
                
                recentDropsPanel.add(dropPanel);
                index++;
            }
        }
        
        recentDropsPanel.revalidate();
        recentDropsPanel.repaint();
    }
    
    /**
     * Format a timestamp as "time ago" string
     */
    private String formatTimeAgo(long timestamp)
    {
        if (timestamp == 0)
        {
            return "";
        }
        
        long diff = System.currentTimeMillis() - timestamp;
        long seconds = diff / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        
        if (seconds < 60)
        {
            return "just now";
        }
        else if (minutes < 60)
        {
            return minutes + "m ago";
        }
        else if (hours < 24)
        {
            return hours + "h ago";
        }
        else
        {
            long days = hours / 24;
            return days + "d ago";
        }
    }
    
    /**
     * Destination configuration - a server with selected channels
     */
    public static class DestinationConfig
    {
        public String guildId;
        public List<String> channelIds;
        public String eventId; // Optional event ID
        
        public DestinationConfig()
        {
            this.channelIds = new ArrayList<>();
        }
    }
}

