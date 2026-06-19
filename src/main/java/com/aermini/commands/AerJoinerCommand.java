package com.aermini.commands;

import com.aermini.AerJoiner;
import com.aermini.managers.CategoryData;
import com.aermini.managers.JoinLogic;
import com.aermini.managers.ServerData;
import com.aermini.util.SLPing;
import org.bukkit.Bukkit;
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
    public AerJoinerCommand(AerJoiner plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e§lAerJoiner §r§7for §b§lYanYuTing §8| §r§fby. §dAerMini");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "quick": handleQuickJoin(sender, args); break;
            case "reload": handleReload(sender); break;
            case "list": handleList(sender, args); break;
            case "getmotd": handleGetMotd(sender, args); break;
            case "help": sendUsage(sender); break;
            default: sender.sendMessage("§e§lAerJoiner §r§7for §b§lYanYuTing §8| §r§fby. §dAerMini"); break;
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
        if (!sender.hasPermission("aerjoiner.reload")) return;
        plugin.reloadConfig();
        plugin.getServerManager().loadServers();
        sender.sendMessage("§aAerJoiner 配置已重新加载");
    }

    private void handleList(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aerjoiner.list")) return;
        if (args.length < 2) {
            Collection<CategoryData> categories = plugin.getServerManager().getCategories();
            if (categories.isEmpty()) {
                sender.sendMessage("§e你还没有配置分组");
                return;
            }
            sender.sendMessage("§6--- ALL ---");
            for (CategoryData category : categories) {
                String methodTag = "redis".equals(category.getMethod()) ? " §d[redis]" : " §7[motd]";
                sender.sendMessage("§b" + category.getName() + " §f- " + category.getDisplayName() + methodTag + " §7(" + category.getServerNames().size() + "个房间)");
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
            String methodTag = "redis".equals(category.getMethod()) ? " [redis]" : " [motd]";
            sender.sendMessage("§6--- " + category.getDisplayName() + methodTag + " ---");
            for (ServerData server : servers) {
                String status;
                if ("redis".equals(category.getMethod())) {
                    String state = server.getState();
                    boolean joinable = category.getJoinableStates().contains(state);
                    status = (joinable ? "§a" : "§c") + state + "§7(" + server.getPlayerCount() + "/" + server.getMaxPlayers() + ")";
                    sender.sendMessage("§f- §b" + server.getDisplayName() + " " + status + " §f| " + (joinable ? "§a可加入" : "§c不可加入"));
                } else {
                    if (server.getPlayerCount() == -1) {
                        status = "§c离线";
                    } else {
                        status = "§a在线§7(" + server.getPlayerCount() + ")";
                    }
                    boolean isJoinable = isMotdServerJoinable(server.getName(), server.getMotd(), category.getPrefixJoinablePatterns());
                    sender.sendMessage("§f- §b" + server.getDisplayName() + " " + status + " §f| " + (isJoinable ? "§a可加入" : "§c不可加入"));
                }
            }
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

    private void handleGetMotd(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aerjoiner.getmotd")) return;
        if (args.length < 2) {
            sender.sendMessage("§c用法: /aerjoiner getmotd <ip>[:port]");
            return;
        }
        String host = args[1];
        int port = 25565;
        if (host.contains(":")) {
            String[] parts = host.split(":", 2);
            host = parts[0];
            try { port = Integer.parseInt(parts[1]); } catch (NumberFormatException e) {
                sender.sendMessage("§c端口号无效: " + parts[1]);
                return;
            }
        }
        sender.sendMessage("§e正在 ping §f" + host + ":" + port + "§e...");
        final String fHost = host;
        final int fPort = port;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int timeout = plugin.getConfig().getInt("timeout", 500);
            SLPing.Response resp = SLPing.ping(fHost, fPort, timeout);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (resp == null) {
                    sender.sendMessage("§cPing 失败: 服务器无响应或连接超时");
                    return;
                }
                sender.sendMessage("§6--- SLP Ping 结果 ---");
                sender.sendMessage("§7地址: §f" + fHost + ":" + fPort);
                sender.sendMessage("§7版本: §f" + resp.version + " §7(协议 " + resp.protocol + ")");
                sender.sendMessage("§7人数: §a" + resp.online + "§7/§c" + resp.max);
                sender.sendMessage("§7MOTD: §f" + resp.motdClean);
                sender.sendMessage("§7Favicon: " + (resp.favicon.isEmpty() ? "§c无" : "§a有 (" + resp.favicon.length() + " 字符)"));
            });
        });
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§6--- AerJoiner 命令帮助 ---");
        sender.sendMessage("§b/aerjoiner quick <分组> §f- 快速加入分组内人数最多的房间");
        sender.sendMessage("§b/aerjoiner reload §f- 重新加载配置文件");
        sender.sendMessage("§b/aerjoiner list [分组] §f- 列出所有分组或指定分组的房间");
        sender.sendMessage("§b/aerjoiner getmotd <ip>[:port] §f- 测试 SLP Ping");
        sender.sendMessage("§b/join <分组> §f- 快速加入的快捷方式");
    }
}