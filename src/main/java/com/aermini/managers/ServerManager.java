package com.aermini.managers;

import br.com.azalim.mcserverping.MCPing;
import br.com.azalim.mcserverping.MCPingOptions;
import br.com.azalim.mcserverping.MCPingResponse;
import com.aermini.AerJoiner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ServerManager {

    private final AerJoiner plugin;
    private final Map<String, CategoryData> categories = new ConcurrentHashMap<>();
    private final Map<String, ServerData> servers = new ConcurrentHashMap<>();
    private final File serversFile;

    public ServerManager(AerJoiner plugin) {
        this.plugin = plugin;
        this.serversFile = new File(plugin.getDataFolder(), "servers.yml");
        loadServers();
    }

    private Map<String, String> readBungeeConfig() {
        Map<String, String> bungeeServers = new HashMap<>();
        try {
            String configPath = plugin.getConfig().getString("bungee_config_path", "../config.yml");
            File bungeeConfigFile = new File(configPath);
            if (!bungeeConfigFile.isAbsolute()) {
                bungeeConfigFile = new File(plugin.getDataFolder().getParent(), configPath);
            }
            if (!bungeeConfigFile.exists()) {
                plugin.getLogger().warning("bungee配置文件不存在 -> " + bungeeConfigFile.getAbsolutePath());
                return bungeeServers;
            }
            FileConfiguration bungeeConfig = YamlConfiguration.loadConfiguration(bungeeConfigFile);
            ConfigurationSection serversSection = bungeeConfig.getConfigurationSection("servers");
            if (serversSection == null) {
                plugin.getLogger().warning("bungee上不存在子服");
                return bungeeServers;
            }

            for (String serverName : serversSection.getKeys(false)) {
                ConfigurationSection serverSection = serversSection.getConfigurationSection(serverName);
                String address = serverSection.getString("address");

                if (address != null) {
                    bungeeServers.put(serverName, address);
                }
            }
            plugin.getLogger().info("成功从配置读取 " + bungeeServers.size() + " 个服务器");
        } catch (Exception e) {
            plugin.getLogger().severe("读取bungee配置时出错 -> " + e.getMessage());
            e.printStackTrace();
        }
        return bungeeServers;
    }

    private List<String> matchServersByPrefix(String prefix, Map<String, String> bungeeServers) {
        return bungeeServers.keySet().stream()
                .filter(serverName -> serverName.startsWith(prefix))
                .collect(Collectors.toList());
    }

    public void loadServers() {
        FileConfiguration config = YamlConfiguration.loadConfiguration(serversFile);
        categories.clear();
        servers.clear();

        Map<String, String> bungeeServers = readBungeeConfig();

        if (bungeeServers.isEmpty()) {
            plugin.getLogger().warning("未能从bungee读到任何服务器. 无法继续加载");
            return;
        }

        if (config.getConfigurationSection("group") == null) {
            plugin.getLogger().warning("servers.yml中未配置group");
            return;
        }

        for (String groupName : config.getConfigurationSection("group").getKeys(false)) {
            List<String> prefixes = config.getStringList("group." + groupName);
            Set<String> serverNames = new HashSet<>();
            Map<String, Set<Pattern>> prefixJoinablePatterns = new HashMap<>();

            for (String prefix : prefixes) {
                List<String> matchedServers = matchServersByPrefix(prefix, bungeeServers);
                serverNames.addAll(matchedServers);
                Set<Pattern> patterns = new HashSet<>();
                if (config.getConfigurationSection("servers") != null && config.contains("servers." + prefix)) {
                    List<String> patternStrings = config.getStringList("servers." + prefix);
                    for (String patternStr : patternStrings) {
                        patterns.add(Pattern.compile(patternStr, Pattern.DOTALL));
                    }
                }
                prefixJoinablePatterns.put(prefix, patterns);
            }

            if (serverNames.isEmpty()) {
                plugin.getLogger().warning("分组 '" + groupName + "' 没有任何服务器");
                continue;
            }

            for (String serverName : serverNames) {
                String ip = bungeeServers.get(serverName);
                if (ip != null) {
                    try {
                        servers.put(serverName, new ServerData(serverName, serverName, ip));
                    } catch (Exception e) {
                        plugin.getLogger().warning("为 " + serverName + " 创建数据时发生错误 -> " + e.getMessage());
                    }
                } else {
                    plugin.getLogger().warning("无法找到服务器的 IP 地址 -> " + serverName);
                }
            }

            categories.put(groupName, new CategoryData(
                    groupName,
                    groupName,
                    serverNames,
                    Collections.emptySet(),
                    Collections.emptySet(),
                    prefixJoinablePatterns
            ));
            plugin.getLogger().info("分组 '" + groupName + "' 包含" + serverNames.size() + "个服务器");
        }
    }

    private boolean isUpdating = false;
    public void updateAllServers() {
        if (isUpdating) {
            return;
        }
        isUpdating = true;
        try {
            for (ServerData server : servers.values()) {
                updateServerStatus(server);
            }
        } finally {
            isUpdating = false;
        }
    }

    private void updateServerStatus(ServerData server) {
        try {
            String[] ipParts = server.getIp().split(":");
            String hostname = ipParts[0];
            int port = ipParts.length > 1 ? Integer.parseInt(ipParts[1]) : 25565;
            MCPingOptions options = MCPingOptions.builder()
                    .hostname(hostname)
                    .port(port)
                    .timeout(plugin.getConfig().getInt("timeout", 500))
                    .protocolVersion(47)
                    .build();

            MCPingResponse response = MCPing.getPing(options);
            server.setPlayerCount(response.getPlayers().getOnline());
            server.setMotd(response.getDescription().getStrippedText());

        } catch (Exception e) {
            server.setPlayerCount(0);
            server.setMotd("");
        }
    }

    public CategoryData findCategoryForServer(String serverName) {
        for (CategoryData category : categories.values()) {
            if (category.getServerNames().contains(serverName)) {
                return category;
            }
        }
        return null;
    }

    public CategoryData getCategory(String name) {
        return categories.get(name);
    }

    public ServerData getServer(String name) {
        return servers.get(name);
    }

    public Collection<ServerData> getServersInCategory(String categoryName) {
        CategoryData category = getCategory(categoryName);
        if (category == null) {
            return Collections.emptyList();
        }
        return category.getServerNames().stream()
                .map(this::getServer)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public Collection<ServerData> getServersByPrefix(String prefix) {
        return servers.values().stream()
                .filter(server -> server.getName().startsWith(prefix))
                .collect(Collectors.toList());
    }

    public Collection<CategoryData> getCategories() {
        return categories.values();
    }
}
