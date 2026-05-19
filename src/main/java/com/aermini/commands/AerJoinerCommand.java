package com.aermini.commands;

import com.aermini.AerJoiner;
import com.aermini.managers.CategoryData;
import com.aermini.managers.JoinLogic;
import com.aermini.managers.ServerData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class AerJoinerCommand implements CommandExecutor {
    private final AerJoiner plugin;
    public AerJoinerCommand(AerJoiner plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e§lAerJoiner §r§7for §b§lYanYuTing §8| §r§fby. §dAerMini");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "quick":
                handleQuickJoin(sender, args);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "list":
                handleList(sender, args);
                break;
            case "help":
                sendUsage(sender);
                break;
            default:
                sender.sendMessage("§e§lAerJoiner §r§7for §b§lYanYuTing §8| §r§fby. §dAerMini");
                break;
        }
        return true;
    }

    private void handleQuickJoin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c此命令只能由玩家执行");
            return;
        }
        Player player = (Player) sender;
        String categoryName = args.length > 1 ? args[1] : "core";
        JoinLogic.quickJoin(plugin, player, categoryName);
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("aerjoiner.reload")) {
            return;
        }
        plugin.reloadConfig();
        plugin.getServerManager().loadServers();
        sender.sendMessage("§aAerJoiner 配置已重新加载");
    }

    private void handleList(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aerjoiner.list")) {
            return;
        }
        if (args.length < 2) {
            Collection<CategoryData> categories = plugin.getServerManager().getCategories();
            if (categories.isEmpty()) {
                sender.sendMessage("§e你还没有配置分组");
                return;
            }
            sender.sendMessage("§6--- ALL ---");
            for (CategoryData category : categories) {
                int serverCount = category.getServerNames().size();
                sender.sendMessage("§b" + category.getName() + " §f- " + category.getDisplayName() + " §7(" + serverCount + "个房间)");
            }
        } else {
            String categoryName = args[1];
            CategoryData category = plugin.getServerManager().getCategory(categoryName);
            if (category == null) {
                sender.sendMessage("§c未找到 " + categoryName);
                return;
            }
            Collection<ServerData> servers = plugin.getServerManager().getServersInCategory(categoryName);
            if (servers.isEmpty()) {
                sender.sendMessage("§e分组 " + categoryName + " 为空");
                return;
            }
            sender.sendMessage("§6--- " + category.getDisplayName() + " ---");
            for (ServerData server : servers) {
                String status;
                if (server.getPlayerCount() == -1) {
                    status = "§c离线";
                } else {
                    status = "§a在线§7(" + server.getPlayerCount() + ")";
                }
                boolean isJoinable = isServerJoinable(server.getName(), server.getMotd(), category.getPrefixJoinablePatterns());
                String joinableStatus = isJoinable ? "§a可加入" : "§c不可加入";
                sender.sendMessage("§f- §b" + server.getDisplayName() + " " + status + " §f| " + joinableStatus);
            }
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

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§6--- AerJoiner 命令帮助 ---");
        sender.sendMessage("§b/aerjoiner quick <分组> §f- 快速加入分组内人数最多的房间");
        sender.sendMessage("§b/aerjoiner reload §f- 重新加载配置文件");
        sender.sendMessage("§b/aerjoiner list [分组] §f- 列出所有分组或指定分组的房间");
        sender.sendMessage("§b/join <分组> §f- 快速加入的快捷方式");
    }
}