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

    private static void startTeleportProcess(AerJoiner plugin, Player player, String categoryName, ServerData targetServer) {
        TeleportLockManager.TeleportLockState lockState = plugin.getTeleportLockManager().getTeleportState(player);
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

    private static void startRedisTeleportProcess(AerJoiner plugin, Player player, String categoryName,
                                                   ServerData targetGame, CategoryData category) {
        TeleportLockManager.TeleportLockState lockState = plugin.getTeleportLockManager().getTeleportState(player);
        int maxRetries = plugin.getConfig().getInt("teleport_max_retries", 3);
        int maxServers = plugin.getConfig().getInt("teleport_max_servers", 3);
        int checkInterval = plugin.getConfig().getInt("teleport_check_interval", 1);

        RedisManager redisManager = plugin.getRedisManager();
        if (redisManager == null) {
            plugin.getTeleportLockManager().removeTeleportLock(player);
            sendTitle(player, "failed.no_room");
            return;
        }

        String bungeeName = plugin.getServerManager().getBungeeNameByAddress(targetGame.getServerAddress());
        if (bungeeName == null) {
            bungeeName = targetGame.getServerAddress().split(":")[0] + ":" + targetGame.getServerAddress().split(":")[1];
        }

        redisManager.publishJoin(player.getName(), targetGame.getRedisMode(),
                targetGame.getArenaName(), targetGame.getServerAddress(), category.getRedisChannel());
        sendToServer(player, bungeeName);
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
                if (currentState == null || !currentState.getCurrentServer().equals(targetGame.getArenaName())) return;

                retryCount[0]++;
                if (retryCount[0] <= maxRetries) {
                    redisManager.publishJoin(player.getName(), targetGame.getRedisMode(),
                            targetGame.getArenaName(), targetGame.getServerAddress(), category.getRedisChannel());
                    sendToServer(player, bungeeName);
                } else {
                    if (lockState.getServerAttemptCount() < maxServers) {
                        lockState.incrementServerAttemptCount();
                        lockState.resetRetryCount();

                        List<ServerData> joinableServers = plugin.getServerManager().getServersInCategory(categoryName).stream()
                                .filter(server -> isRedisServerJoinable(server, category))
                                .collect(Collectors.toList());

                        ServerData nextGame = selectServer(joinableServers, targetGame.getArenaName());
                        if (nextGame != null) {
                            lockState.setCurrentServer(nextGame.getArenaName());
                            startRedisTeleportProcess(plugin, player, categoryName, nextGame, category);
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