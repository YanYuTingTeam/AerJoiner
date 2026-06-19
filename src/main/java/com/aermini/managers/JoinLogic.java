package com.aermini.managers;

import com.aermini.AerJoiner;
import com.connorlinfoot.titleapi.TitleAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public class JoinLogic {
    private static final Random random = new Random();

    public static void sendToServer(Player player, String serverName) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        try {
            out.writeUTF("Connect");
            out.writeUTF(serverName);
        } catch (IOException e) {
            e.printStackTrace();
        }
        player.sendPluginMessage(AerJoiner.getPlugin(AerJoiner.class), "BungeeCord", b.toByteArray());
    }

    private static boolean isMotdServerJoinable(ServerData server, CategoryData category) {
        if (category == null) return false;
        String serverName = server.getName();
        for (String prefix : category.getPrefixJoinablePatterns().keySet()) {
            if (serverName.startsWith(prefix)) {
                for (java.util.regex.Pattern p : category.getPrefixJoinablePatterns().get(prefix)) {
                    if (p.matcher(server.getMotd()).find()) return true;
                }
            }
        }
        return false;
    }

    private static boolean isRedisServerJoinable(ServerData server, CategoryData category) {
        return category.getJoinableStates().contains(server.getState());
    }

    public static void quickJoin(AerJoiner plugin, Player player, String categoryName) {
        if (plugin.getCooldownManager().isOnCooldown(player)) {
            sendTitle(player, "failed.too_fast");
            return;
        }
        if (plugin.getTeleportLockManager().isTeleporting(player)) {
            sendTitle(player, "start");
            return;
        }
        sendTitle(player, "start");
        executeQuickJoin(plugin, player, categoryName);
    }

    public static void executeQuickJoin(AerJoiner plugin, Player player, String categoryName) {
        CategoryData category = plugin.getServerManager().getCategory(categoryName);
        if (category == null) {
            sendTitle(player, "failed.no_group");
            return;
        }

        List<ServerData> joinableServers = plugin.getServerManager().getServersInCategory(categoryName).stream()
                .filter(server -> {
                    if ("redis".equals(category.getMethod())) return isRedisServerJoinable(server, category);
                    return isMotdServerJoinable(server, category);
                })
                .collect(Collectors.toList());

        if (joinableServers.isEmpty()) {
            sendTitle(player, "failed.no_room");
            return;
        }

        plugin.getTeleportLockManager().addTeleportLock(player, categoryName);
        ServerData targetServer = selectServer(joinableServers, null);
        TeleportLockManager.TeleportLockState lockState = plugin.getTeleportLockManager().getTeleportState(player);
        lockState.setCurrentServer(targetServer.getName());

        if ("redis".equals(category.getMethod())) {
            startRedisTeleportProcess(plugin, player, categoryName, targetServer, category);
        } else {
            startTeleportProcess(plugin, player, categoryName, targetServer);
        }
    }

    private static ServerData selectServer(List<ServerData> availableServers, String excludeServer) {
        List<ServerData> filteredServers = availableServers;
        if (excludeServer != null) {
            filteredServers = availableServers.stream()
                    .filter(server -> !server.getName().equals(excludeServer))
                    .collect(Collectors.toList());
        }
        if (filteredServers.isEmpty()) return null;

        int maxPlayerCount = filteredServers.stream()
                .max(Comparator.comparingInt(ServerData::getPlayerCount))
                .map(ServerData::getPlayerCount)
                .orElse(0);

        List<ServerData> mostPopulatedServers = filteredServers.stream()
                .filter(server -> server.getPlayerCount() == maxPlayerCount)
                .collect(Collectors.toList());

        return mostPopulatedServers.get(random.nextInt(mostPopulatedServers.size()));
    }

    // ==================== MOTD 模式转服（不变） ====================

    private static void startTeleportProcess(AerJoiner plugin, Player player, String categoryName, ServerData targetServer) {
        TeleportLockManager.TeleportLockState lockState = plugin.getTeleportLockManager().getTeleportState(player);

        // 取消旧定时器，避免切换房间后多个定时器同时运行
        if (lockState != null && lockState.getTaskId() != null) {
            Bukkit.getScheduler().cancelTask(lockState.getTaskId());
        }

        int maxRetries = plugin.getConfig().getInt("teleport_max_retries", 3);
        int maxServers = plugin.getConfig().getInt("teleport_max_servers", 3);
        int checkInterval = plugin.getConfig().getInt("teleport_check_interval", 1);
        sendToServer(player, targetServer.getName());
        sendTitle(player, "success");
        final int[] retryCount = {0};
        int taskId = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                if (!plugin.getTeleportLockManager().isTeleporting(player)) return;
                if (!player.isOnline()) {
                    plugin.getTeleportLockManager().removeTeleportLock(player);
                    return;
                }
                TeleportLockManager.TeleportLockState currentState = plugin.getTeleportLockManager().getTeleportState(player);
                if (currentState == null || !currentState.getCurrentServer().equals(targetServer.getName())) return;

                retryCount[0]++;
                if (retryCount[0] <= maxRetries) {
                    sendToServer(player, targetServer.getName());
                } else {
                    if (lockState.getServerAttemptCount() < maxServers) {
                        lockState.incrementServerAttemptCount();
                        lockState.resetRetryCount();

                        CategoryData currentCategory = plugin.getServerManager().getCategory(categoryName);
                        List<ServerData> joinableServers = plugin.getServerManager().getServersInCategory(categoryName).stream()
                                .filter(server -> isMotdServerJoinable(server, currentCategory))
                                .collect(Collectors.toList());

                        ServerData nextServer = selectServer(joinableServers, targetServer.getName());
                        if (nextServer != null) {
                            lockState.setCurrentServer(nextServer.getName());
                            startTeleportProcess(plugin, player, categoryName, nextServer);
                        } else {
                            plugin.getTeleportLockManager().removeTeleportLock(player);
                            sendTitle(player, "failed.no_room");
                        }
                    } else {
                        plugin.getTeleportLockManager().removeTeleportLock(player);
                        sendTitle(player, "failed.no_room");
                    }
                }
            }
        }, checkInterval * 20L, checkInterval * 20L).getTaskId();
        lockState.setTaskId(taskId);
    }

    // ==================== Redis 模式转服 ====================

    /**
     * Redis fire-and-forget 模式:
     * 1. 将 Redis 地址通过 ip-map 映射，再从 BungeeCord 配置查找对应服务器名
     * 2. 向游戏服 Redis 频道发送加入消息 (fire-and-forget)
     * 3. 用查到的 BungeeCord 服务器名转服
     * 4. 失败时重试，达到上限后尝试下一个房间
     */
    private static void startRedisTeleportProcess(final AerJoiner plugin, final Player player,
                                                  final String categoryName,
                                                  final ServerData targetGame, final CategoryData category) {
        final TeleportLockManager.TeleportLockState lockState =
                plugin.getTeleportLockManager().getTeleportState(player);

        // 取消旧定时器，避免切换房间后多个定时器同时运行
        if (lockState != null && lockState.getTaskId() != null) {
            Bukkit.getScheduler().cancelTask(lockState.getTaskId());
        }

        final int maxServers = plugin.getConfig().getInt("teleport_max_servers", 3);
        final int maxRetries = plugin.getConfig().getInt("teleport_max_retries", 3);
        final int checkInterval = plugin.getConfig().getInt("teleport_check_interval", 1);

        RedisManager redisManager = plugin.getRedisManager();
        if (redisManager == null) {
            plugin.getTeleportLockManager().removeTeleportLock(player);
            sendTitle(player, "failed.no_room");
            return;
        }

        String redisAddress = targetGame.getServerAddress();

        // 用 ip-map 映射 + BungeeCord 地址表解析服务器名
        String bungeeServerName = plugin.getServerManager().resolveBungeeServerName(redisAddress);
        if (bungeeServerName == null) {
            plugin.getTeleportLockManager().removeTeleportLock(player);
            sendTitle(player, "failed.no_room");
            return;
        }
        // 1. 异步发送 fire-and-forget 消息（不阻塞主线程）
        final String fPlayerName = player.getName();
        final String fMode = targetGame.getRedisMode();
        final String fArenaName = targetGame.getArenaName();
        final String fChannel = category.getRedisChannel();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            redisManager.publishJoinMessage(fPlayerName, fMode, fArenaName, redisAddress, fChannel);
        });

        // 2. 用 BungeeCord 服务器名转服
        sendToServer(player, bungeeServerName);
        sendTitle(player, "success");

        // 3. 重试计时器
        final int[] retryCount = {0};
        int taskId = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                if (!plugin.getTeleportLockManager().isTeleporting(player)) return;
                if (!player.isOnline()) {
                    // 玩家已离开大厅（成功转服或断线），停止重试
                    plugin.getTeleportLockManager().removeTeleportLock(player);
                    return;
                }

                // 当前目标房间已变更（已切换到下一个房间），旧定时器退出
                TeleportLockManager.TeleportLockState currentState = plugin.getTeleportLockManager().getTeleportState(player);
                if (currentState == null || !currentState.getCurrentServer().equals(targetGame.getName())) return;

                retryCount[0]++;
                if (retryCount[0] <= maxRetries) {
                    sendToServer(player, bungeeServerName);
                } else {
                    // 当前房间失败，尝试下一个
                    if (lockState.getServerAttemptCount() < maxServers) {
                        lockState.incrementServerAttemptCount();
                        lockState.resetRetryCount();

                        CategoryData currentCategory = plugin.getServerManager().getCategory(categoryName);
                        List<ServerData> joinableServers =
                                plugin.getServerManager().getServersInCategory(categoryName).stream()
                                        .filter(server -> isRedisServerJoinable(server, currentCategory))
                                        .collect(Collectors.toList());

                        ServerData nextGame = selectServer(joinableServers, targetGame.getArenaName());
                        if (nextGame != null) {
                            lockState.setCurrentServer(nextGame.getArenaName());
                            startRedisTeleportProcess(plugin, player, categoryName, nextGame, currentCategory);
                        } else {
                            plugin.getTeleportLockManager().removeTeleportLock(player);
                            sendTitle(player, "failed.no_room");
                        }
                    } else {
                        plugin.getTeleportLockManager().removeTeleportLock(player);
                        sendTitle(player, "failed.no_room");
                    }
                }
            }
        }, checkInterval * 20L, checkInterval * 20L).getTaskId();
        lockState.setTaskId(taskId);
    }

    public static void sendTitle(Player player, String configPath) {
        AerJoiner plugin = AerJoiner.getInstance();
        String titleString = plugin.getConfig().getString(configPath + ".title", "");
        String[] parts = titleString.split("\\|");
        if (parts.length < 5) return;
        String title = ChatColor.translateAlternateColorCodes('&', parts[0]);
        String subtitle = ChatColor.translateAlternateColorCodes('&', parts[1]);
        int fadeIn = Integer.parseInt(parts[2]);
        int stay = Integer.parseInt(parts[3]);
        int fadeOut = Integer.parseInt(parts[4]);
        TitleAPI.sendTitle(player, fadeIn, stay, fadeOut, title, subtitle);
    }
}
