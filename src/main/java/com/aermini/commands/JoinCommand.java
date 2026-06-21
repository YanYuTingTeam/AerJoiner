package com.aermini.commands;

import com.aermini.AerJoiner;
import com.aermini.gui.JoinGUI;
import com.aermini.managers.JoinLogic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class JoinCommand implements CommandExecutor {
    private final AerJoiner plugin;
    private final JoinGUI joinGUI;

    public JoinCommand(AerJoiner plugin) {
        this.plugin = plugin;
        this.joinGUI = new JoinGUI(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c此命令只能由玩家执行");
            return true;
        }
        Player player = (Player) sender;

        if (args.length > 0 && "gui".equalsIgnoreCase(args[0])) {
            if (args.length > 1) {
                if (plugin.getServerManager().getCategory(args[1]) == null
                        || plugin.getServerManager().getServersInCategory(args[1]).isEmpty()) {
                    JoinLogic.sendTitle(player, "failed.no_room");
                    return true;
                }
                joinGUI.openRoomGUI(player, args[1]);
            } else {
                joinGUI.openCategoryGUI(player);
            }
            return true;
        }

        String categoryName = args.length > 0 ? args[0] : "core";
        if (plugin.getServerManager().getCategory(categoryName) == null
                || plugin.getServerManager().getServersInCategory(categoryName).isEmpty()) {
            JoinLogic.sendTitle(player, "failed.no_room");
            return true;
        }
        // 冷却
        if (plugin.getCooldownManager().isOnCooldown(player)) {
            JoinLogic.sendTitle(player, "failed.too_fast");
            return true;
        }
        // 传送锁
        if (plugin.getTeleportLockManager().isTeleporting(player)) {
            JoinLogic.sendTitle(player, "start");
            return true;
        }
        JoinLogic.sendTitle(player, "start");
        // 过AerParty查队伍人数
        plugin.requestPartyCheck(player, categoryName, null);

        return true;
    }
}
