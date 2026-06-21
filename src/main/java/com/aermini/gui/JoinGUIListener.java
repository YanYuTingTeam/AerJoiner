package com.aermini.gui;

import com.aermini.AerJoiner;
import com.aermini.managers.CategoryData;
import com.aermini.managers.JoinLogic;
import com.aermini.managers.ServerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

public class JoinGUIListener implements Listener {
    private final AerJoiner plugin;
    private final JoinGUI joinGUI;

    public JoinGUIListener(AerJoiner plugin, JoinGUI joinGUI) {
        this.plugin = plugin;
        this.joinGUI = joinGUI;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();

        if (JoinGUI.isCategoryGUI(title)) {
            event.setCancelled(true);
            handleClick(player, event, true);
        } else if (JoinGUI.isRoomGUI(title)) {
            event.setCancelled(true);
            handleClick(player, event, false);
        }
    }

    private void handleClick(Player player, InventoryClickEvent event, boolean isCategory) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        if (slot == 49) {
            joinGUI.removePageState(player);
            if (isCategory) {
                player.closeInventory();
            } else {
                joinGUI.openCategoryGUI(player);
            }
            return;
        }

        PageState state = joinGUI.getPageState(player);

        if (slot == 47 && state != null && state.page > 0) {
            if (isCategory) {
                joinGUI.openCategoryGUI(player, state.page - 1);
            } else if (state.categoryName != null) {
                joinGUI.openRoomGUI(player, state.categoryName, state.page - 1);
            }
            return;
        }

        if (slot == 50 && state != null && state.page < state.maxPage) {
            if (isCategory) {
                joinGUI.openCategoryGUI(player, state.page + 1);
            } else if (state.categoryName != null) {
                joinGUI.openRoomGUI(player, state.categoryName, state.page + 1);
            }
            return;
        }

        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;
        if (item.getItemMeta() == null || item.getItemMeta().getDisplayName() == null) return;

        String displayName = item.getItemMeta().getDisplayName();

        if (isCategory) {
            String categoryName = findCategoryByDisplayName(displayName);
            if (categoryName == null) return;
            if (plugin.getServerManager().getServersInCategory(categoryName).isEmpty()) {
                JoinLogic.sendTitle(player, "failed.no_room");
                return;
            }
            player.closeInventory();
            joinGUI.openRoomGUI(player, categoryName);
        } else {
            String arenaName = stripColor(displayName);
            String categoryName = findCategoryByRoomDisplayName(displayName);
            if (categoryName == null) return;

            CategoryData category = plugin.getServerManager().getCategory(categoryName);
            if (category != null) {
                ServerData server = findServerByDisplayName(category, displayName);
                if (server != null && !isJoinable(server, category) && !player.hasPermission("aerjoiner.bypass")) {
                    JoinLogic.sendTitle(player, "failed.no_room");
                    return;
                }
            }

            player.closeInventory();
            if (plugin.getCooldownManager().isOnCooldown(player)) {
                JoinLogic.sendTitle(player, "failed.too_fast");
                return;
            }
            if (plugin.getTeleportLockManager().isTeleporting(player)) {
                JoinLogic.sendTitle(player, "start");
                return;
            }
            JoinLogic.sendTitle(player, "start");
            plugin.requestPartyCheck(player, categoryName, arenaName);
        }
    }

    private String findCategoryByDisplayName(String displayName) {
        String clean = stripColor(displayName);
        for (CategoryData category : plugin.getServerManager().getCategories()) {
            if (category.getDisplayName().equals(clean)) return category.getName();
        }
        return null;
    }

    private String findCategoryByRoomDisplayName(String roomDisplayName) {
        String clean = stripColor(roomDisplayName);
        for (CategoryData category : plugin.getServerManager().getCategories()) {
            if ("redis".equals(category.getMethod())) {
                for (ServerData server : category.getCategoryServers().values()) {
                    if (server.getDisplayName().equals(clean)) return category.getName();
                }
            } else {
                for (String serverName : category.getServerNames()) {
                    ServerData server = plugin.getServerManager().getServer(serverName);
                    if (server != null && server.getDisplayName().equals(clean)) return category.getName();
                }
            }
        }
        return null;
    }

    private ServerData findServerByDisplayName(CategoryData category, String roomDisplayName) {
        String clean = stripColor(roomDisplayName);
        if ("redis".equals(category.getMethod())) {
            for (ServerData server : category.getCategoryServers().values()) {
                if (server.getDisplayName().equals(clean)) return server;
            }
        } else {
            for (String serverName : category.getServerNames()) {
                ServerData server = plugin.getServerManager().getServer(serverName);
                if (server != null && server.getDisplayName().equals(clean)) return server;
            }
        }
        return null;
    }

    private boolean isJoinable(ServerData server, CategoryData category) {
        if ("redis".equals(category.getMethod())) {
            return category.getJoinableStates().contains(server.getState());
        }
        return server.getPlayerCount() >= 0;
    }

    private String stripColor(String text) {
        return text.replaceAll("§[0-9a-fk-or]", "");
    }
}
