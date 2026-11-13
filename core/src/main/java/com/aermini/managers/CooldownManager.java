package com.aermini.managers;

import com.aermini.AerJoiner;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CooldownManager {
    private final AerJoiner plugin;
    private final Map<UUID, Long> lastRequestTime = new ConcurrentHashMap<>();
    public CooldownManager(AerJoiner plugin) {
        this.plugin = plugin;
    }

    public boolean isOnCooldown(Player player) {
        int maxRequestsPerSecond = plugin.getConfig().getInt("max_request", 1);
        if (maxRequestsPerSecond <= 0) {return false;}
        long cooldownMillis = 1000 / maxRequestsPerSecond;
        long currentTime = System.currentTimeMillis();
        Long lastTime = lastRequestTime.get(player.getUniqueId());
        if (lastTime == null || (currentTime - lastTime) >= cooldownMillis) {
            lastRequestTime.put(player.getUniqueId(), currentTime);
            return false;
        }
        return true;
    }
}