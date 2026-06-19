package com.aermini.managers;

public class ServerData {
    private String name;
    private final String displayName;
    private final String ip;
    private volatile int playerCount = 0;
    private volatile String motd = "";
    private volatile int maxPlayers = 0;
    private volatile String state = "";
    private volatile String arenaName = "";
    private volatile String serverAddress = "";
    private volatile String redisMode = "";

    public ServerData(String name, String displayName, String ip) {
        this.name = name;
        this.displayName = displayName;
        this.ip = ip;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDisplayName() { return displayName; }
    public String getIp() { return ip; }
    public int getPlayerCount() { return playerCount; }
    public void setPlayerCount(int playerCount) { this.playerCount = playerCount; }
    public String getMotd() { return motd; }
    public void setMotd(String motd) { this.motd = motd; }
    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getArenaName() { return arenaName; }
    public void setArenaName(String arenaName) { this.arenaName = arenaName; }
    public String getServerAddress() { return serverAddress; }
    public void setServerAddress(String serverAddress) { this.serverAddress = serverAddress; }
    public String getRedisMode() { return redisMode; }
    public void setRedisMode(String redisMode) { this.redisMode = redisMode; }
}
