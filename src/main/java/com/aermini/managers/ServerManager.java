package com.aermini.managers;

import com.aermini.AerJoiner;
import com.aermini.util.SLPing;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
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
    private Map<String, String> ipMap = new HashMap<>();

    public ServerManager(AerJoiner plugin) {
        this.plugin = plugin;
        this.serversFile = new File(plugin.getDataFolder(), "servers.yml");
        loadServers();
    }

    // ==================== IP 映射 ====================

    /**
     * 读取 config.yml 中 redis.ip-map，建立 Redis IP → BungeeCord IP 的映射
     * 例如: 192.168.1.92 → 127.0.0.1
     *
     * 注意: Bukkit 的 MemorySection 会把 key 中的 '.' 当作路径分隔符，
     * 导致 "192.168.2.91" 被拆成嵌套的 192->168->2->91，
     * 所以必须直接用 SnakeYAML 读取原始 YAML 文件来获取 ip-map。
     */
    @SuppressWarnings("unchecked")
    private void loadIpMap() {
        ipMap.clear();
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.getLogger().warning("config.yml 不存在，跳过 ip-map 加载");
            return;
        }
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> root;
            try (InputStream is = new FileInputStream(configFile)) {
                root = (Map<String, Object>)yaml.load(is);
            }
            if (root == null) return;
            Map<String, Object> redis = (Map<String, Object>) root.get("redis");
            if (redis == null) return;
            Map<String, Object> ipMapRaw = (Map<String, Object>) redis.get("ip-map");
            if (ipMapRaw == null) return;
            for (Map.Entry<String, Object> entry : ipMapRaw.entrySet()) {
                String redisIp = entry.getKey();
                String mappedIp = String.valueOf(entry.getValue());
                ipMap.put(redisIp, mappedIp);
                plugin.getLogger().info("IP 映射: " + redisIp + " → " + mappedIp);
            }
            if (!ipMap.isEmpty()) {
                plugin.getLogger().info("共加载 " + ipMap.size() + " 条 IP 映射");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("加载 IP 映射失败: " + e.getMessage());
        }
    }

    /**
     * 将 Redis 中的地址（如 192.168.1.92:20001）应用 IP 映射后返回（如 127.0.0.1:20001）
     * 无映射则原样返回
     */
    public String applyIpMap(String address) {
        int colonIdx = address.lastIndexOf(':');
        if (colonIdx < 0) return address;
        String ip = address.substring(0, colonIdx);
        String port = address.substring(colonIdx + 1);
        String mappedIp = ipMap.get(ip);
        if (mappedIp != null) {
            return mappedIp + ":" + port;
        }
        return address;
    }

    /**
     * 调试用: 返回当前 ip-map 的内容（用于日志诊断）
     */
    public String dumpIpMap() {
        if (ipMap.isEmpty()) return "(empty)";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : ipMap.entrySet()) {
            sb.append(e.getKey()).append("->").append(e.getValue()).append(", ");
        }
        return sb.toString();
    }

    /**
     * 规范化地址: 将 localhost / 0.0.0.0 统一为 127.0.0.1，
     * 确保 BungeeCord 配置中的 "localhost:port" 与 ip-map 映射后的 "127.0.0.1:port" 能正确匹配。
     */
    private static String normalizeAddress(String address) {
        if (address == null) return null;
        int colonIdx = address.lastIndexOf(':');
        if (colonIdx < 0) return address;
        String host = address.substring(0, colonIdx);
        String port = address.substring(colonIdx); // 含冒号
        if ("localhost".equalsIgnoreCase(host) || "0.0.0.0".equals(host)) {
            return "127.0.0.1" + port;
        }
        return address;
    }

    /**
     * 根据 Redis 中的地址，经过 IP 映射后查找 BungeeCord 中的服务器名
     * @param redisAddress Redis 中的原始地址，如 192.168.1.92:20001
     * @return BungeeCord 服务器名，如 "bw-1"；找不到返回 null
     */
    public String resolveBungeeServerName(String redisAddress) {
        String mappedAddress = normalizeAddress(applyIpMap(redisAddress));
        String serverName = bungeeAddressToName.get(mappedAddress);
        return serverName;
    }

    // ==================== BungeeCord 配置读取（仅 MOTD 模式需要） ====================

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
                    bungeeAddressToName.put(normalizeAddress(address), serverName);
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

    // ==================== 加载服务器分组 ====================

    public void loadServers() {
        // 重新加载 ip-map，确保 /aerjoiner reload 后 ip-map 也是最新的
        loadIpMap();

        FileConfiguration config = YamlConfiguration.loadConfiguration(serversFile);
        categories.clear();
        servers.clear();
        bungeeAddressToName.clear();

        Map<String, String> bungeeServers = readBungeeConfig();

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

    // ==================== 定时更新 ====================

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

    /**
     * 定时 SCAN Redis 刷新房间数据
     * Redis 模式的房间从 Redis key 中发现（不依赖 BungeeCord 配置）
     */
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
                ServerData existing = servers.get(game.getArenaName());
                if (existing != null) {
                    existing.setPlayerCount(game.getPlayerCount());
                    existing.setMaxPlayers(game.getMaxPlayers());
                    existing.setState(game.getState());
                    existing.setRedisMode(game.getRedisMode());
                    existing.setServerAddress(game.getServerAddress());
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

    // ==================== 查询方法 ====================

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
