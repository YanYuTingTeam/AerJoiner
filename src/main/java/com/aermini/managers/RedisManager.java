package com.aermini.managers;

import com.aermini.AerJoiner;
import org.bukkit.configuration.ConfigurationSection;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 精简版 RedisManager — 只做两件事:
 * 1. SCAN: 扫描 Redis 中的游戏房间 key（定时调用）
 * 2. PUBLISH: 发送 fire-and-forget 加入消息到游戏服
 *
 * 无 Pub/Sub 订阅，无请求-响应，无跨集群。
 * 对应 BedwarsRel (朋友版) 的 Redis 协议:
 *   Key 格式:   bedwars:{mode}:{ip}:{port}:{arenaName}
 *   消息格式:   playerName,mode,arenaName,ip:port  →  频道 bedwars:match
 */
public class RedisManager {

    private final JedisPool pool;
    private final AerJoiner plugin;

    public RedisManager(AerJoiner plugin) {
        this.plugin = plugin;
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("redis");
        if (section == null) throw new IllegalStateException("config.yml 中缺少 'redis' 配置段！");

        String host = section.getString("host", "127.0.0.1");
        int port = section.getInt("port", 6379);
        String password = section.getString("password", "");
        int maxTotal = section.getInt("max-connections", 10);

        ConfigurationSection poolSection = section.getConfigurationSection("pool-config");
        int maxIdle = (poolSection != null) ? poolSection.getInt("max-idle", 5) : 5;
        int minIdle = (poolSection != null) ? poolSection.getInt("min-idle", 1) : 1;

        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(maxTotal);
        config.setMaxIdle(maxIdle);
        config.setMinIdle(minIdle);
        config.setTestOnBorrow(true);
        config.setTestWhileIdle(true);
        config.setTimeBetweenEvictionRunsMillis(30_000);

        int timeoutMs = 2000;
        if (password == null || password.isEmpty()) {
            this.pool = new JedisPool(config, host, port, timeoutMs);
        } else {
            this.pool = new JedisPool(config, host, port, timeoutMs, password);
        }

        plugin.getLogger().info("RedisManager > 已初始化 - host: " + host + ", port: " + port);
    }

    // ==================== PUBLISH: fire-and-forget 加入消息 ====================

    /**
     * 向游戏服发送 fire-and-forget 加入消息
     * 消息格式: playerName,mode,arenaName,ip:port
     *
     * @param playerName    玩家名
     * @param mode          游戏模式 (solo/dul/44/32/64 等)
     * @param arenaName     房间名
     * @param serverAddress 目标游戏服地址 ip:port
     * @param channel       Redis 频道名 (默认 bedwars:match)
     */
    public void publishJoinMessage(String playerName, String mode, String arenaName,
                                    String serverAddress, String channel) {
        String message = playerName + "," + mode + "," + arenaName + "," + serverAddress;

        try (Jedis jedis = pool.getResource()) {
            jedis.publish(channel, message);
        } catch (Exception e) {
            plugin.getLogger().warning("RedisManager > [发送消息] ✗ 发送失败: " + e.getMessage());
        }
    }

    // ==================== SCAN: 扫描可用房间 ====================

    /**
     * 扫描 Redis 中所有可用的游戏房间
     * Key 格式: bedwars:{mode}:{ip}:{port}:{arenaName}
     *
     * @param keyPrefix    Redis key 前缀，如 "bedwars:"
     * @param modes        要匹配的游戏模式列表
     * @param serverFilter 服务器地址过滤（可选，glob 通配符）
     * @param arenaExclude 地图名排除（可选，glob 通配符）
     */
    public List<ServerData> scanGames(String keyPrefix, List<String> modes,
                                       List<Pattern> serverFilter, List<Pattern> arenaExclude) {
        List<ServerData> games = new ArrayList<>();
        try (Jedis jedis = pool.getResource()) {
            String pattern = keyPrefix + "*";
            ScanParams scanParams = new ScanParams().match(pattern).count(100);
            String cursor = "0";

            do {
                ScanResult<String> result = jedis.scan(cursor, scanParams);
                for (String key : result.getResult()) {
                    try {
                        Map<String, String> data = jedis.hgetAll(key);
                        if (data.isEmpty()) continue;

                        // 解析 key: bedwars:{mode}:{ip}:{port}:{arenaName}
                        String keyBody = key.substring(keyPrefix.length());
                        String[] keyParts = keyBody.split(":", -1);

                        if (keyParts.length != 4) continue;

                        String scannedMode = keyParts[0];
                        String serverAddress = keyParts[1] + ":" + keyParts[2];
                        String arenaName = keyParts[3];

                        // 过滤: 模式
                        if (!modes.contains(scannedMode)) continue;

                        // 过滤: server-filter
                        if (serverFilter != null) {
                            boolean matched = false;
                            for (Pattern p : serverFilter) {
                                if (p.matcher(serverAddress).matches()) { matched = true; break; }
                            }
                            if (!matched) continue;
                        }

                        // 过滤: arena-exclude
                        if (arenaExclude != null) {
                            boolean excluded = false;
                            for (Pattern p : arenaExclude) {
                                if (p.matcher(arenaName).matches()) { excluded = true; break; }
                            }
                            if (excluded) continue;
                        }

                        int playerCount = Integer.parseInt(data.getOrDefault("playerCount", "0"));
                        int maxPlayers = Integer.parseInt(data.getOrDefault("maxPlayers", "0"));
                        String state = data.getOrDefault("state", "STOPPED");

                        ServerData gameData = new ServerData(arenaName, arenaName, serverAddress);
                        gameData.setPlayerCount(playerCount);
                        gameData.setMaxPlayers(maxPlayers);
                        gameData.setState(state);
                        gameData.setArenaName(arenaName);
                        gameData.setServerAddress(serverAddress);
                        gameData.setRedisMode(scannedMode);

                        games.add(gameData);
                    } catch (Exception ignored) {}
                }
                cursor = result.getCursor();
            } while (!cursor.equals("0"));
        } catch (Exception e) {
            plugin.getLogger().warning("RedisManager > [SCAN] 扫描失败: " + e.getMessage());
        }
        return games;
    }

    // ==================== 关闭 ====================

    public void shutdown() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
            plugin.getLogger().info("RedisManager > 已关闭");
        }
    }
}
