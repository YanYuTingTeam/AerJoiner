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

    public AerJoinerExpansion(AerJoiner plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "aerjoiner";
    }

    @Override
    public String getAuthor() {
        return "AerMini";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        String[] parts = params.split("_");
        if (parts.length != 2) {
            return null;
        }

        String type = parts[0];
        String name = parts[1];

        boolean isServerPrefixMode = false;
        String actualType = type.toLowerCase();

        // 可以直接使用 %aerjoiner_<type>s_<prefix>% 在不配置servers的情况下获取到指定前缀子服的信息.
        // 比如 %aerjoiner_os_bw44% -> bungee servers中所有子服名前缀为 bw44 的总在线人数
        if (type.length() > 0 && (type.endsWith("s") || type.endsWith("S"))) {
            String typeWithoutS = type.substring(0, type.length() - 1).toLowerCase();
            if (typeWithoutS.equals("o") || typeWithoutS.equals("r") ||
                typeWithoutS.equals("ar") || typeWithoutS.equals("sr")) {
                isServerPrefixMode = true;
                actualType = typeWithoutS;
            }
        }

        Collection<ServerData> servers;
        final Map<String, Set<Pattern>> joinablePatterns;

        if (isServerPrefixMode) {
            String prefix = name;
            servers = plugin.getServerManager().getServersByPrefix(prefix);
            if (servers == null || servers.isEmpty()) {
                return "0";
            }
            joinablePatterns = getPrefixPatternsFromAnyCategory(prefix);
        } else {
            CategoryData category = plugin.getServerManager().getCategory(name);
            if (category == null) {
                return "0";
            }
            servers = plugin.getServerManager().getServersInCategory(name);
            if (servers == null || servers.isEmpty()) {
                return "0";
            }
            joinablePatterns = category.getPrefixJoinablePatterns();
        }

        // 然后这里就是普通的根据servers.yml去返回
        switch (actualType) {
            case "o":
                return String.valueOf(servers.stream().mapToInt(ServerData::getPlayerCount).sum());
            case "r":
                long joinableCount = servers.stream()
                        .filter(server -> isServerJoinable(server.getName(), server.getMotd(), joinablePatterns))
                        .count();
                return String.valueOf(joinableCount);
            case "ar":
                return String.valueOf(servers.size());
            case "sr":
                long unavailableCount = servers.stream()
                        .filter(server -> !isServerJoinable(server.getName(), server.getMotd(), joinablePatterns))
                        .count();
                return String.valueOf(unavailableCount);
            default:
                return null;
        }
    }

    private boolean isServerJoinable(String serverName, String motd, Map<String, Set<Pattern>> prefixPatterns) {
        for (String prefix : prefixPatterns.keySet()) {
            if (serverName.startsWith(prefix)) {
                Set<Pattern> patterns = prefixPatterns.get(prefix);
                for (Pattern pattern : patterns) {
                    if (pattern.matcher(motd).find()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Map<String, Set<Pattern>> getPrefixPatternsFromAnyCategory(String prefix) {
        Map<String, Set<Pattern>> result = new java.util.HashMap<>();
        for (CategoryData category : plugin.getServerManager().getCategories()) {
            Map<String, Set<Pattern>> patterns = category.getPrefixJoinablePatterns();
            if (patterns.containsKey(prefix)) {
                result.put(prefix, patterns.get(prefix));
                break;
            }
        }
        return result;
    }
}