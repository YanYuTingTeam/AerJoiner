package com.aermini.managers;

public class ServerData {
    private final String name;
    private final String displayName;
    private final String ip;
    private volatile int playerCount = 0;
    private volatile String motd = "";

    public ServerData(String name, String displayName, String ip) {
        this.name = name;
        this.displayName = displayName;
        this.ip = ip;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getIp() {
        return ip;
    }

    public int getPlayerCount() {
        return playerCount;
    }

    public void setPlayerCount(int playerCount) {
        this.playerCount = playerCount;
    }

    public String getMotd() {
        return motd;
    }

    public void setMotd(String motd) {
        this.motd = motd;
    }
}
