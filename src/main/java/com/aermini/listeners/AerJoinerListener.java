package com.aermini.listeners;

import com.aermini.AerJoiner;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class AerJoinerListener implements Listener {
    private final AerJoiner plugin;
    public AerJoinerListener(AerJoiner plugin) {
        this.plugin = plugin;
    }

    // 上下线清匹配锁
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        cleanupPlayerState(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        cleanupPlayerState(player);
    }

    private void cleanupPlayerState(Player player) {
        if (plugin.getTeleportLockManager().isTeleporting(player)) {
            plugin.getTeleportLockManager().removeTeleportLock(player);
        }
    }
}
