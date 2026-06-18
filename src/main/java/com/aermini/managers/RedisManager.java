package com.aermini.managers;

import com.aermini.AerJoiner;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class RedisManager {

    private final JedisPool pool;
    private final AerJoiner plugin;
    private final String stateChannel;

    private volatile JedisPubSub stateSubscriber;
    private volatile Thread stateSubscriberThread;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private static final long RECONNECT_DELAY_SECONDS = 5;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;

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

        this.stateChannel = section.getString("state-channel", "aerjoiner:state");

        plugin.getLogger().info("RedisManager > 已初始化 - host: " + host + ", port: " + port);
        plugin.getLogger().info("RedisManager > 状态订阅通道: " + stateChannel);

        startStateSubscriber();
    }

    // ==================== Pub/Sub 状态订阅（接收游戏服的实时更新） ====================

    private void startStateSubscriber() {
        if (stateSubscriberThread != null && stateSubscriberThread.isAlive()) {
            plugin.getLogger().info("RedisManager > 状态订阅器已在运行，跳过启动");
            return;
        }

        this.stateSubscriber = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                if (!stateChannel.equals(channel)) return;
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.getServerManager().handleStateUpdate(message);
                    });
                } catch (Exception e) {
                    plugin.getLogger().warning("RedisManager > 处理状态更新异常: " + e.getMessage());
                }
            }

            @Override
            public void onSubscribe(String channel, int subscribedChannels) {
                plugin.getLogger().info("RedisManager > 已订阅状态通道: " + channel
                        + " (共 " + subscribedChannels + " 个频道)");
            }

            @Override
            public void onUnsubscribe(String channel, int subscribedChannels) {
                plugin.getLogger().info("RedisManager > 已取消订阅状态通道: " + channel);
            }
        };

        this.stateSubscriberThread = new Thread(this::runStateSubscriptionLoop, "AerJoinerStateSubscriber");
        stateSubscriberThread.setDaemon(true);
        stateSubscriberThread.start();
    }

    private void runStateSubscriptionLoop() {
        int attempt = 0;
        while (running.get()) {
            try (Jedis jedis = pool.getResource()) {
                plugin.getLogger().info("RedisManager > 开始监听状态通道: " + stateChannel);
                jedis.subscribe(stateSubscriber, stateChannel);
                break;
            } catch (Exception e) {
                attempt++;
                if (!running.get()) break;

                plugin.getLogger().warning("RedisManager > 状态订阅断开 (第 " + attempt
                        + "/" + MAX_RECONNECT_ATTEMPTS + " 次): " + e.getMessage());

                if (attempt >= MAX_RECONNECT_ATTEMPTS) {
                    plugin.getLogger().severe("RedisManager > 状态订阅达到最大重连次数，放弃重连");
                    break;
                }

                try {
                    Thread.sleep(RECONNECT_DELAY_SECONDS * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        plugin.getLogger().info("RedisManager > 状态订阅线程已退出");
    }

    // ==================== SCAN（全量发现） ====================

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

    // ==================== PUBLISH（通知游戏服有玩家要来） ====================

    public void publishJoin(String playerName, String mode, String arenaName,
                            String serverAddress, String channel) {
        try (Jedis jedis = pool.getResource()) {
            String message = playerName + "," + mode + "," + arenaName + "," + serverAddress;
            jedis.publish(channel, message);
        } catch (Exception e) {
            plugin.getLogger().warning("RedisManager > publish 失败: " + e.getMessage());
        }
    }

    // ==================== 关闭 ====================

    public void shutdown() {
        running.set(false);

        if (stateSubscriber != null && stateSubscriber.isSubscribed()) {
            try {
                stateSubscriber.unsubscribe();
            } catch (Exception e) {
                plugin.getLogger().warning("RedisManager > 取消状态订阅失败: " + e.getMessage());
            }
        }
        if (stateSubscriberThread != null) {
            stateSubscriberThread.interrupt();
            try {
                stateSubscriberThread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (pool != null && !pool.isClosed()) {
            pool.close();
            plugin.getLogger().info("RedisManager > 已关闭");
        }
    }
}