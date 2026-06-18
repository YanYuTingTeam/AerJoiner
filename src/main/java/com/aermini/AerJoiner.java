package com.aermini;

import com.aermini.commands.AerJoinerCommand;
import com.aermini.commands.JoinCommand;
import com.aermini.listeners.AerJoinerListener;
import com.aermini.managers.AerPartyManager;
import com.aermini.managers.CooldownManager;
import com.aermini.managers.RedisManager;
import com.aermini.managers.ServerManager;
import com.aermini.managers.TeleportLockManager;
import com.aermini.placeholder.AerJoinerExpansion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Objects;

public class AerJoiner extends JavaPlugin implements PluginMessageListener {
    private static AerJoiner instance;
    private ServerManager serverManager;
    private CooldownManager cooldownManager;
    private TeleportLockManager teleportLockManager;
    private AerPartyManager aerPartyManager;
    private RedisManager redisManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        saveResource("servers.yml", false);
        this.getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        this.getServer().getMessenger().registerOutgoingPluginChannel(this, "aerparty:main");
        this.getServer().getMessenger().registerIncomingPluginChannel(this, "aerparty:main", this);
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new AerJoinerExpansion(this).register();
            getLogger().info("papi 已注册");
        } else {
            getLogger().warning("未找到 papi");
        }

        if (getConfig().getBoolean("redis.enabled", false)) {
            try {
                this.redisManager = new RedisManager(this);
            } catch (Exception e) {
                getLogger().severe("RedisManager 初始化失败: " + e.getMessage());
                this.redisManager = null;
            }
        }

        this.serverManager = new ServerManager(this);
        this.cooldownManager = new CooldownManager(this);
        this.teleportLockManager = new TeleportLockManager(this);
        this.aerPartyManager = new AerPartyManager(this);
        Bukkit.getPluginManager().registerEvents(new AerJoinerListener(this), this);
        Objects.requireNonNull(getCommand("aerjoiner")).setExecutor(new AerJoinerCommand(this));
        Objects.requireNonNull(getCommand("join")).setExecutor(new JoinCommand(this));

        Bukkit.getScheduler().runTaskTimerAsynchronously(
                this, serverManager::updateAllServers, 0,
                getConfig().getLong("update_delay", 200)
        );

        getLogger().info("AerJoiner 已启用");
    }

    @Override
    public void onDisable() {
        if (aerPartyManager != null) aerPartyManager.cleanup();
        if (redisManager != null) redisManager.shutdown();
        this.getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        this.getServer().getMessenger().unregisterIncomingPluginChannel(this, "aerparty:main", this);
        getLogger().info("AerJoiner 已禁用");
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (channel.equals("aerparty:main")) {
            aerPartyManager.handleResponse(message);
        }
    }

    public void requestPartyCheck(Player player, String groupName) {
        aerPartyManager.requestMatchCheck(player, groupName);
    }

    public ServerManager getServerManager() { return serverManager; }
    public CooldownManager getCooldownManager() { return cooldownManager; }
    public TeleportLockManager getTeleportLockManager() { return teleportLockManager; }
    public AerPartyManager getAerPartyManager() { return aerPartyManager; }
    public RedisManager getRedisManager() { return redisManager; }

    public static AerJoiner getInstance() { return instance; }
}