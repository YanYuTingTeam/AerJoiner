package com.aermini.managers;

import com.aermini.AerJoiner;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TeleportLockManager {
    private final AerJoiner plugin;
    private final Map<UUID, TeleportLockState> activeTeleports = new ConcurrentHashMap<>();
    public TeleportLockManager(AerJoiner plugin) {
        this.plugin = plugin;
    }

    public boolean isTeleporting(Player player) {
        return activeTeleports.containsKey(player.getUniqueId());
    }

    public void addTeleportLock(Player player, String categoryName) {
        activeTeleports.put(player.getUniqueId(), new TeleportLockState(categoryName));
    }

    public void removeTeleportLock(Player player) {
        TeleportLockState state = activeTeleports.remove(player.getUniqueId());
        if (state != null && state.getTaskId() != null) {
            Bukkit.getScheduler().cancelTask(state.getTaskId());
        }
    }

    public TeleportLockState getTeleportState(Player player) {
        return activeTeleports.get(player.getUniqueId());
    }

    public static class TeleportLockState {
        private final String categoryName;
        private final long startTime;
        private String currentServer;
        private int retryCount;
        private int serverAttemptCount;
        private boolean active;
        private Integer taskId;

        public TeleportLockState(String categoryName) {
            this.categoryName = categoryName;
            this.startTime = System.currentTimeMillis();
            this.retryCount = 0;
            this.serverAttemptCount = 1;
            this.active = true;
        }

        public String getCategoryName() {
            return categoryName;
        }

        public long getStartTime() {
            return startTime;
        }

        public String getCurrentServer() {
            return currentServer;
        }

        public void setCurrentServer(String currentServer) {
            this.currentServer = currentServer;
        }

        public int getRetryCount() {
            return retryCount;
        }

        public void incrementRetryCount() {
            this.retryCount++;
        }

        public void resetRetryCount() {
            this.retryCount = 0;
        }

        public int getServerAttemptCount() {
            return serverAttemptCount;
        }

        public void incrementServerAttemptCount() {
            this.serverAttemptCount++;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public Integer getTaskId() {
            return taskId;
        }

        public void setTaskId(Integer taskId) {
            this.taskId = taskId;
        }
    }
}
