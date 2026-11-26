package com.aermini.managers;

import com.aermini.AerJoiner;
import org.bukkit.Bukkit;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AerPartyManager {
    private final AerJoiner plugin;
    private final Map<UUID, PartyMatch> pendingMatches = new ConcurrentHashMap<>();
    private static final long REQUEST_TIMEOUT = 1000;
    public AerPartyManager(AerJoiner plugin) {
        this.plugin = plugin;
    }

    public boolean requestMatchCheck(org.bukkit.entity.Player player, String group, String arenaName) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        try {
            // AerParty
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(out);
            String message = String.format("action=joinServer,player=%s,group=%s", player.getName(), group);
            dos.writeUTF(message);
            dos.flush();
            player.sendPluginMessage(plugin, "aerparty:main", out.toByteArray());
            int taskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                PartyMatch match = pendingMatches.remove(player.getUniqueId());
                // 超时直接匹配
                if (match != null) {
                    JoinLogic.executeQuickJoin(plugin, player, group, match.getArenaName());
                }
            }, REQUEST_TIMEOUT / 50L).getTaskId();
            pendingMatches.put(player.getUniqueId(), new PartyMatch(group, taskId, arenaName));
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public void handleResponse(byte[] message) {
        try {
            String msg = new String(message, StandardCharsets.UTF_8);
            Map<String, String> data = parseMessage(msg);
            String playerName = data.get("player");
            String result = data.get("result");
            if (playerName == null || result == null) {
                plugin.getLogger().warning("消息缺少 player 或 result 字段");
                return;
            }
            plugin.getLogger().info("player=" + playerName + ", result=" + result);
            org.bukkit.entity.Player player = Bukkit.getPlayer(playerName);
            if (player == null) {
                plugin.getLogger().warning("玩家 " + playerName + " 不在线");
                return;
            }
            if ("denied".equals(result)) {
                PartyMatch match = pendingMatches.remove(player.getUniqueId());
                if (match != null) {
                    Bukkit.getScheduler().cancelTask(match.getTaskId());
                    plugin.getLogger().info("玩家 " + playerName + " 匹配被拒绝");
                }
            } else if ("allowed".equals(result)) {
                PartyMatch match = pendingMatches.remove(player.getUniqueId());
                if (match != null) {
                    Bukkit.getScheduler().cancelTask(match.getTaskId());
                    JoinLogic.executeQuickJoin(plugin, player, match.getGroup(), match.getArenaName());
                }
            } else {
                plugin.getLogger().warning("未知的 result -> " + result);
            }

        } catch (Exception e) {
            plugin.getLogger().severe("处理响应失败 -> " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Map<String, String> parseMessage(String message) {
        Map<String, String> params = new HashMap<>();
        if (message == null || message.isEmpty()) {
            return params;
        }

        String[] parts = message.split(",");
        for (String part : parts) {
            String[] keyValue = part.split("=", 2);
            if (keyValue.length == 2) {
                params.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
        return params;
    }

    public void cleanup() {
        for (PartyMatch match : pendingMatches.values()) {
            Bukkit.getScheduler().cancelTask(match.getTaskId());
        }
        pendingMatches.clear();
    }

    private static class PartyMatch {
        final String group;
        final int taskId;
        final String arenaName;
        PartyMatch(String group, int taskId, String arenaName) {
            this.group = group;
            this.taskId = taskId;
            this.arenaName = arenaName;
        }
        public String getGroup() {return group;}
        public int getTaskId() {return taskId;}
        public String getArenaName() {return arenaName;}
    }
}
