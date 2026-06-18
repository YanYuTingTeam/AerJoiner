package com.aermini.managers;

import com.aermini.AerJoiner;
import com.aermini.util.SLPing;
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
    private Map<String, String> bungeeAddressToName = new HashMap<>();

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
                    bungeeAddressToName.put(address, serverName);
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
        bungeeAddressToName.clear();
        if (bungeeServers.isEmpty()) {
            plugin.getLogger().warning("未能从bungee读到任何服务器. 无法继续加载");
            return;
        }

        if (config.getConfigurationSection("group") == null) {
            plugin.getLogger().warning("servers.yml中未配置group");
            return;
        }

        for (String groupName : config.getConfigurationSection("group").getKeys(false)) {
            Object groupValue = config.get("group." + groupName);

            if (groupValue instanceof List) {
                loadMotdGroup(config, groupName, (List<String>) groupValue, bungeeServers);
            } else if (groupValue instanceof ConfigurationSection) {
                ConfigurationSection groupSection = config.getConfigurationSection("group." + groupName);
                String method = groupSection.getString("method", "motd");

                if ("redis".equals(method)) {
                    loadRedisGroup(groupName, groupSection);
                } else {
                    List<String> prefixes = groupSection.getStringList("prefixes");
                    loadMotdGroup(config, groupName, prefixes, bungeeServers);
                }
            }
        }
    }

    private void loadMotdGroup(FileConfiguration config, String groupName,
                               List<String> prefixes, Map<String, String> bungeeServers) {
        Set<String> serverNames = new HashSet<>();
        Map<String, Set<Pattern>> prefixJoinablePatterns = new HashMap<>();

        for (String prefix : prefixes) {
            List<String> matchedServers = matchServersByPrefix(prefix, bungeeServers);
            serverNames.addAll(matchedServers);
            Set<Pattern> patterns = new HashSet<>();
            if (config.getConfigurationSection("servers") != null && config.contains("servers." + prefix)) {
                List<String> patternStrings = config.getStringList("servers." + prefix);
                for (String patternStr : patternStrings) patterns.add(Pattern.compile(patternStr, Pattern.DOTALL));
            }
            prefixJoinablePatterns.put(prefix, patterns);
        }

        if (serverNames.isEmpty()) {
            plugin.getLogger().warning("分组 '" + groupName + "' 没有任何服务器");
            return;
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
                groupName, groupName, "motd",
                null, null, null, null, null, null,
                serverNames, Collections.emptySet(), Collections.emptySet(),
                prefixJoinablePatterns
        ));
        plugin.getLogger().info("分组 '" + groupName + "' [motd] 包含" + serverNames.size() + "个服务器");
    }

    private static Pattern compileGlobPattern(String glob) {
        String regex = glob.replace(".", "\\.").replace("*", ".*");
        return Pattern.compile(regex);
    }

    private void loadRedisGroup(String groupName, ConfigurationSection groupSection) {
        List<String> modes = groupSection.getStringList("modes");
        if (modes.isEmpty()) {
            String singleMode = groupSection.getString("mode", "");
            if (singleMode.isEmpty()) {
                plugin.getLogger().warning("Redis 分组 '" + groupName + "' 未配置 mode 或 modes");
                return;
            }
            modes = Collections.singletonList(singleMode);
        }

        Set<String> joinableStates = new HashSet<>(groupSection.getStringList("joinable-states"));
        if (joinableStates.isEmpty()) joinableStates.add("WAITING");

        String channel = groupSection.getString("channel", "bedwars:match");
        String keyPrefix = groupSection.getString("key-prefix", "bedwars:");

        List<Pattern> serverFilter = null;
        if (groupSection.contains("server-filter")) {
            serverFilter = groupSection.getStringList("server-filter").stream()
                    .map(ServerManager::compileGlobPattern)
                    .collect(Collectors.toList());
        }

        List<Pattern> arenaExclude = null;
        if (groupSection.contains("arena-exclude")) {
            arenaExclude = groupSection.getStringList("arena-exclude").stream()
                    .map(ServerManager::compileGlobPattern)
                    .collect(Collectors.toList());
        }

        categories.put(groupName, new CategoryData(
                groupName, groupName, "redis",
                modes, joinableStates, channel, keyPrefix,
                serverFilter, arenaExclude,
                ConcurrentHashMap.newKeySet(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap()
        ));
        plugin.getLogger().info("分组 '" + groupName + "' [redis] modes=" + modes
                + ", states=" + joinableStates + ", channel=" + channel + ", prefix=" + keyPrefix);
    }

    private boolean isUpdating = false;
    public void updateAllServers() {
        if (isUpdating) return;
        isUpdating = true;
        try {
            for (ServerData server : servers.values()) {
                if (server.getState().isEmpty()) {
                    updateServerStatusByPing(server);
                }
            }
            updateRedisGroups();
        } finally {
            isUpdating = false;
        }
    }

    private void updateServerStatusByPing(ServerData server) {
        String[] ipParts = server.getIp().split(":");
        String hostname = ipParts[0];
        int port = ipParts.length > 1 ? Integer.parseInt(ipParts[1]) : 25565;
        SLPing.Response response = SLPing.ping(hostname, port, plugin.getConfig().getInt("timeout", 500));
        if (response != null) {
            server.setPlayerCount(response.online);
            server.setMaxPlayers(response.max);
            server.setMotd(response.motdClean);
        } else {
            server.setPlayerCount(0);
            server.setMaxPlayers(0);
            server.setMotd("");
        }
    }

    private void updateRedisGroups() {
        RedisManager redisManager = plugin.getRedisManager();
        if (redisManager == null) return;

        for (CategoryData category : categories.values()) {
            if (!"redis".equals(category.getMethod())) continue;

            Set<String> currentNames = new HashSet<>();
            List<ServerData> games = redisManager.scanGames(
                    category.getRedisKeyPrefix(), category.getRedisModes(),
                    category.getServerFilter(), category.getArenaExclude()
            );

            for (ServerData game : games) {
                currentNames.add(game.getArenaName());
                String bungeeName = bungeeAddressToName.get(game.getServerAddress());
                if (bungeeName != null) game.setName(bungeeName);
                ServerData existing = servers.get(game.getArenaName());
                if (existing != null) {
                    existing.setPlayerCount(game.getPlayerCount());
                    existing.setMaxPlayers(game.getMaxPlayers());
                    existing.setState(game.getState());
                    existing.setRedisMode(game.getRedisMode());
                } else {
                    servers.put(game.getArenaName(), game);
                }
            }

            ((ConcurrentHashMap<String, ServerData>) servers).keySet()
                    .removeIf(key -> category.getServerNames().contains(key) && !currentNames.contains(key));
            category.getServerNames().clear();
            category.getServerNames().addAll(currentNames);
        }
    }

    public String getBungeeNameByAddress(String address) {
        return bungeeAddressToName.get(address);
    }

    public CategoryData findCategoryForServer(String serverName) {
        for (CategoryData category : categories.values()) {
            if (category.getServerNames().contains(serverName)) return category;
        }
        return null;
    }

    public CategoryData getCategory(String name) { return categories.get(name); }

    public ServerData getServer(String name) { return servers.get(name); }

    public Collection<ServerData> getServersInCategory(String categoryName) {
        CategoryData category = getCategory(categoryName);
        if (category == null) return Collections.emptyList();
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

    public Collection<CategoryData> getCategories() { return categories.values(); }
}