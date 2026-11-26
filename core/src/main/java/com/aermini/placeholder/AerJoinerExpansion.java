package com.aermini.placeholder;

import com.aermini.AerJoiner;
import com.aermini.managers.CategoryData;
import com.aermini.managers.ServerData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class AerJoinerExpansion extends PlaceholderExpansion {

    private final AerJoiner plugin;

    public AerJoinerExpansion(AerJoiner plugin) { this.plugin = plugin; }

    @Override
    public String getIdentifier() { return "aerjoiner"; }

    @Override
    public String getAuthor() { return "AerMini"; }

    @Override
    public String getVersion() { return "2.0.0"; }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        String[] parts = params.split("_");
        if (parts.length != 2) return null;

        String type = parts[0];
        String name = parts[1];

        boolean isServerPrefixMode = false;
        String actualType = type.toLowerCase();

        if (type.length() > 0 && (type.endsWith("s") || type.endsWith("S"))) {
            String typeWithoutS = type.substring(0, type.length() - 1).toLowerCase();
            if (typeWithoutS.equals("o") || typeWithoutS.equals("r") ||
                typeWithoutS.equals("ar") || typeWithoutS.equals("sr") || typeWithoutS.equals("m")) {
                isServerPrefixMode = true;
                actualType = typeWithoutS;
            }
        }

        Collection<ServerData> servers;

        if (isServerPrefixMode) {
            String prefix = name;
            servers = plugin.getServerManager().getServersByPrefix(prefix);
            if (servers == null || servers.isEmpty()) return "0";

            switch (actualType) {
                case "o": return String.valueOf(servers.stream().mapToInt(ServerData::getPlayerCount).sum());
                case "ar": return String.valueOf(servers.size());
                default: return null;
            }
        }

        CategoryData category = plugin.getServerManager().getCategory(name);
        if (category == null) return "0";
        servers = plugin.getServerManager().getServersInCategory(name);
        if (servers == null || servers.isEmpty()) return "0";

        if ("redis".equals(category.getMethod())) {
            return handleRedisPlaceholder(actualType, servers, category.getJoinableStates());
        } else {
            return handleMotdPlaceholder(actualType, servers, category.getPrefixJoinablePatterns());
        }
    }

    private String handleRedisPlaceholder(String actualType, Collection<ServerData> servers, Set<String> joinableStates) {
        switch (actualType) {
            case "o": return String.valueOf(servers.stream().mapToInt(ServerData::getPlayerCount).sum());
            case "r": return String.valueOf(servers.stream().filter(s -> joinableStates.contains(s.getState())).count());
            case "ar": return String.valueOf(servers.size());
            case "sr": return String.valueOf(servers.stream().filter(s -> !joinableStates.contains(s.getState())).count());
            case "m": return String.valueOf(servers.stream().mapToInt(ServerData::getMaxPlayers).sum());
            default: return null;
        }
    }

    private String handleMotdPlaceholder(String actualType, Collection<ServerData> servers,
                                           Map<String, Set<Pattern>> joinablePatterns) {
        switch (actualType) {
            case "o": return String.valueOf(servers.stream().mapToInt(ServerData::getPlayerCount).sum());
            case "r":
                return String.valueOf(servers.stream()
                        .filter(server -> isMotdServerJoinable(server.getName(), server.getMotd(), joinablePatterns))
                        .count());
            case "ar": return String.valueOf(servers.size());
            case "sr":
                return String.valueOf(servers.stream()
                        .filter(server -> !isMotdServerJoinable(server.getName(), server.getMotd(), joinablePatterns))
                        .count());
            default: return null;
        }
    }

    private boolean isMotdServerJoinable(String serverName, String motd, Map<String, Set<Pattern>> prefixPatterns) {
        for (String prefix : prefixPatterns.keySet()) {
            if (serverName.startsWith(prefix)) {
                for (Pattern pattern : prefixPatterns.get(prefix)) {
                    if (pattern.matcher(motd).find()) return true;
                }
            }
        }
        return false;
    }
}