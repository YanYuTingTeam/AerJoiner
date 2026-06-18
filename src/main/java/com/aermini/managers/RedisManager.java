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
        config.setTimeBetweenEvictionRunsMillis(30000);

        int timeoutMs = 2000;
        if (password == null || password.isEmpty()) {
            this.pool = new JedisPool(config, host, port, timeoutMs);
        } else {
            this.pool = new JedisPool(config, host, port, timeoutMs, password);
        }
        plugin.getLogger().info("RedisManager > 已初始化 - host: " + host + ", port: " + port);
    }

    public List<ServerData> scanGames(String keyPrefix, List<String> modes,
                                       List<Pattern> serverFilter, List<Pattern> arenaExclude) {
        List<ServerData> games = new ArrayList<>();
        try (Jedis jedis = pool.getResource()) {
            for (String mode : modes) {
                String pattern = keyPrefix + mode + ":*";
                ScanParams scanParams = new ScanParams().match(pattern).count(100);
                String cursor = "0";
                do {
                    ScanResult<String> result = jedis.scan(cursor, scanParams);
                    for (String key : result.getResult()) {
                        try {
                            Map<String, String> data = jedis.hgetAll(key);
                            if (data.isEmpty()) continue;

                            String keyBody = key.substring(keyPrefix.length());
                            String[] parts = keyBody.split(":", 4);
                            if (parts.length < 4) continue;

                            String scannedMode = parts[0];
                            String serverAddress = parts[1] + ":" + parts[2];
                            String arenaName = parts[3];

                            if (serverFilter != null) {
                                boolean matched = false;
                                for (Pattern p : serverFilter) if (p.matcher(serverAddress).matches()) { matched = true; break; }
                                if (!matched) continue;
                            }

                            if (arenaExclude != null) {
                                boolean excluded = false;
                                for (Pattern p : arenaExclude) if (p.matcher(arenaName).matches()) { excluded = true; break; }
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
            }
        } catch (Exception e) {
            plugin.getLogger().warning("RedisManager > scan 失败: " + e.getMessage());
        }
        return games;
    }

    public void publishJoin(String playerName, String mode, String arenaName,
                            String serverAddress, String channel) {
        try (Jedis jedis = pool.getResource()) {
            String message = playerName + "," + mode + "," + arenaName + "," + serverAddress;
            jedis.publish(channel, message);
        } catch (Exception e) {
            plugin.getLogger().warning("RedisManager > publish 失败: " + e.getMessage());
        }
    }

    public void shutdown() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
            plugin.getLogger().info("RedisManager > 已关闭");
        }
    }
}