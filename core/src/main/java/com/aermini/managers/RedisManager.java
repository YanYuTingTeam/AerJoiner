package com.aermini.managers;

import com.aermini.AerJoiner;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 扫描 Redis 游戏房间, 保留旧频道的四段加入消息.
 * BedwarsRel 直登用 bedwars:match:prepare 和独立的短期回执键, 无需订阅线程.
 */
public class RedisManager {

    private static final String PREPARE_CHANNEL = "bedwars:match:prepare";
    private static final long PREPARE_TIMEOUT_MS = 2000L;

    private final RedisClient jedis;
    private final AerJoiner plugin;

    public RedisManager(AerJoiner plugin) {
        this.plugin = plugin;
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("redis");
        if (section == null) throw new IllegalStateException("config.yml 中缺少 'redis' 配置段！");

        String host = section.getString("host", "127.0.0.1");
        int port = section.getInt("port", 6379);
        String password = section.getString("password", "");

        DefaultJedisClientConfig.Builder configBuilder = DefaultJedisClientConfig.builder();
        configBuilder.connectionTimeoutMillis(2000);
        configBuilder.socketTimeoutMillis(2000);
        if (password != null && !password.isEmpty()) {
            configBuilder.password(password);
        }

        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxWait(Duration.ofMillis(PREPARE_TIMEOUT_MS));
        this.jedis = RedisClient.builder()
                .hostAndPort(host, port)
                .clientConfig(configBuilder.build())
                .poolConfig(poolConfig)
                .build();

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

        try {
            jedis.publish(channel, message);
        } catch (Exception e) {
            plugin.getLogger().warning("RedisManager > [发送消息] 发送失败: " + e.getMessage());
        }
    }

    public void prepareJoin(String playerName, String mode, String arenaName,
                            String serverAddress, UUID requestId, Consumer<String> callback) {
        String key = PREPARE_CHANNEL + ":" + requestId;
        String message = playerName + "," + mode + "," + arenaName + "," + serverAddress + "," + requestId;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PREPARE_TIMEOUT_MS);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long ttl = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (ttl > 0) {
                try {
                    jedis.set(key, "waiting", SetParams.setParams().px(ttl));
                    if (System.nanoTime() - deadline < 0) {
                        jedis.publish(PREPARE_CHANNEL, message);
                        checkPrepareReply(key, deadline, callback);
                        return;
                    }
                } catch (Exception e) {
                    if (plugin.isEnabled()) plugin.getLogger().warning("RedisManager > 发送匹配请求失败: " + e.getMessage());
                }
            }
            finishPrepare(null, deadline, callback);
        });
    }

    private void checkPrepareReply(String key, long deadline, Consumer<String> callback) {
        String reply = null;
        if (System.nanoTime() - deadline < 0) {
            try {
                reply = jedis.get(key);
                if ("waiting".equals(reply) && System.nanoTime() - deadline < 0) {
                    Bukkit.getScheduler().runTaskLaterAsynchronously(plugin,
                            () -> checkPrepareReply(key, deadline, callback), 2L);
                    return;
                }
            } catch (Exception e) {
                if (plugin.isEnabled()) plugin.getLogger().warning("RedisManager > 读取匹配回执失败: " + e.getMessage());
            }
        }
        finishPrepare(reply, deadline, callback);
    }

    private void finishPrepare(String reply, long deadline, Consumer<String> callback) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () ->
                callback.accept(System.nanoTime() - deadline < 0 ? reply : null));
    }

    // ==================== SCAN: 扫描可用房间 ====================

    public List<ServerData> scanGames(String keyPrefix, List<String> modes,
                                       List<Pattern> serverFilter, List<Pattern> arenaExclude) {
        List<ServerData> games = new ArrayList<>();
        try {
            ScanParams scanParams = new ScanParams().match(keyPrefix + "*").count(100);
            String cursor = "0";

            do {
                ScanResult<String> result = jedis.scan(cursor, scanParams);
                for (String key : result.getResult()) {
                    GameKey gameKey = parseGameKey(keyPrefix, key);
                    if (gameKey == null) continue;
                    if (!isGameAllowed(gameKey, modes, serverFilter, arenaExclude)) continue;

                    Map<String, String> data = jedis.hgetAll(key);
                    if (data.isEmpty()) continue;

                    games.add(buildServerData(gameKey, data));
                }
                cursor = result.getCursor();
            } while (!cursor.equals("0"));
        } catch (Exception e) {
            plugin.getLogger().warning("RedisManager > [SCAN] 扫描失败: " + e.getMessage());
        }
        return games;
    }

    private GameKey parseGameKey(String keyPrefix, String key) {
        String[] parts = key.substring(keyPrefix.length()).split(":", -1);
        if (parts.length != 4) return null;
        return new GameKey(parts[0], parts[1] + ":" + parts[2], parts[3]);
    }

    private boolean isGameAllowed(GameKey gameKey, List<String> modes,
                                   List<Pattern> serverFilter, List<Pattern> arenaExclude) {
        if (!modes.contains(gameKey.mode)) return false;
        if (serverFilter != null && !matchesAny(serverFilter, gameKey.serverAddress)) return false;
        return arenaExclude == null || !matchesAny(arenaExclude, gameKey.arenaName);
    }

    private boolean matchesAny(List<Pattern> patterns, String value) {
        for (Pattern p : patterns) {
            if (p.matcher(value).matches()) return true;
        }
        return false;
    }

    private ServerData buildServerData(GameKey gameKey, Map<String, String> data) {
        ServerData sd = new ServerData(gameKey.arenaName, gameKey.arenaName, gameKey.serverAddress);
        sd.setPlayerCount(Integer.parseInt(data.getOrDefault("playerCount", "0")));
        sd.setMaxPlayers(Integer.parseInt(data.getOrDefault("maxPlayers", "0")));
        sd.setState(data.getOrDefault("state", "STOPPED"));
        sd.setArenaName(gameKey.arenaName);
        sd.setServerAddress(gameKey.serverAddress);
        sd.setRedisMode(gameKey.mode);
        return sd;
    }

    private static class GameKey {
        final String mode;
        final String serverAddress;
        final String arenaName;

        GameKey(String mode, String serverAddress, String arenaName) {
            this.mode = mode;
            this.serverAddress = serverAddress;
            this.arenaName = arenaName;
        }
    }

    // ==================== 关闭 ====================

    public void shutdown() {
        if (jedis != null) {
            jedis.close();
            plugin.getLogger().info("RedisManager > 已关闭");
        }
    }
}
