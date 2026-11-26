package com.aermini.managers;

import com.aermini.AerJoiner;
import com.connorlinfoot.titleapi.TitleAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

public class JoinLogic {
    private static final Random random = new Random();
    private static final String PREPARE_METADATA = "aerjoiner.match.prepare";

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
        executeQuickJoin(plugin, player, categoryName, null);
    }

    public static void executeQuickJoin(AerJoiner plugin, Player player, String categoryName, String arenaName) {
        if (!player.isOnline() || Bukkit.getPlayer(player.getUniqueId()) != player
                || plugin.getTeleportLockManager().isTeleporting(player)) {
            return;
        }
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

        ServerData targetServer;
        if (arenaName != null) {
            targetServer = joinableServers.stream()
                    .filter(s -> arenaName.equals(s.getArenaName()))
                    .max(Comparator.comparingInt(ServerData::getPlayerCount))
                    .orElse(selectServer(joinableServers, null));
        } else {
            targetServer = selectServer(joinableServers, null);
        }

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
     * Redis 模式:
     * 1. 将 Redis 地址通过 ip-map 映射，再从 BungeeCord 配置查找对应服务器名
     * 2. BedwarsRel 确认登记直登目标后再 Connect, 其他频道保留原加入流程
     * 3. 失败时重试，达到上限后尝试下一个房间
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
        final String fPlayerName = player.getUniqueId().toString();
        final String fMode = targetGame.getRedisMode();
        final String fArenaName = targetGame.getArenaName();
        final String fChannel = category.getRedisChannel();
        boolean prepare = "bedwars:match".equals(fChannel);
        if (!prepare) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                    redisManager.publishJoinMessage(fPlayerName, fMode, fArenaName, redisAddress, fChannel));
        }

        new Runnable() {
            private int retryCount;

            @Override
            public void run() {
                if (!isCurrentMatch()) return;
                if (!prepare) {
                    sendToServer(player, bungeeServerName);
                    if (retryCount == 0) sendTitle(player, "success");
                    scheduleRetry();
                    return;
                }

                UUID requestId = UUID.randomUUID();
                player.setMetadata(PREPARE_METADATA, new FixedMetadataValue(plugin, requestId.toString()));
                redisManager.prepareJoin(fPlayerName, fMode, fArenaName, redisAddress, requestId, reply -> {
                    if (!isCurrentMatch()) return;
                    player.removeMetadata(PREPARE_METADATA, plugin);
                    if ("joined".equals(reply)) {
                        plugin.getTeleportLockManager().removeTeleportLock(player);
                        sendTitle(player, "success");
                        return;
                    }
                    if ("ready".equals(reply)) {
                        sendToServer(player, bungeeServerName);
                        sendTitle(player, "success");
                    } else if (reply == null) {
                        plugin.getLogger().fine("RedisManager > 匹配回执超时: " + fArenaName + " - " + redisAddress);
                    }
                    scheduleRetry();
                });
            }

            private boolean isCurrentMatch() {
                return player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player
                        && plugin.getTeleportLockManager().getTeleportState(player) == lockState;
            }

            private void scheduleRetry() {
                int taskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!isCurrentMatch()) return;
                    if (retryCount++ < maxRetries) {
                        run();
                        return;
                    }

                    if (lockState.getServerAttemptCount() < maxServers) {
                        CategoryData currentCategory = plugin.getServerManager().getCategory(categoryName);
                        List<ServerData> joinableServers = plugin.getServerManager().getServersInCategory(categoryName).stream()
                                .filter(server -> "redis".equals(currentCategory.getMethod())
                                        && isRedisServerJoinable(server, currentCategory))
                                .filter(server -> !Objects.equals(server.getArenaName(), fArenaName)
                                        || !Objects.equals(server.getServerAddress(), redisAddress)
                                        || !Objects.equals(server.getRedisMode(), fMode))
                                .collect(Collectors.toList());

                        ServerData nextGame = selectServer(joinableServers, null);
                        if (nextGame != null) {
                            lockState.incrementServerAttemptCount();
                            lockState.setCurrentServer(nextGame.getArenaName());
                            startRedisTeleportProcess(plugin, player, categoryName, nextGame, currentCategory);
                            return;
                        }
                    }
                    plugin.getTeleportLockManager().removeTeleportLock(player);
                    sendTitle(player, "failed.no_room");
                }, checkInterval * 20L).getTaskId();
                lockState.setTaskId(taskId);
            }
        }.run();
    }

    public static void clearPrepare(Player player, AerJoiner plugin) {
        player.removeMetadata(PREPARE_METADATA, plugin);
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
