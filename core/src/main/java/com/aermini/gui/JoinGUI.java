package com.aermini.gui;

import com.aermini.AerJoiner;
import com.aermini.managers.CategoryData;
import com.aermini.managers.ServerData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class JoinGUI {
    private static final int INVENTORY_SIZE = 54;
    private static final int ITEMS_PER_PAGE = 28;
    private static final String CATEGORY_INV_TITLE = "§8选择分类";
    private static final String ROOM_INV_TITLE_PREFIX = "§8选择房间 - ";

    private final AerJoiner plugin;
    private final Map<UUID, PageState> pageState = new ConcurrentHashMap<>();

    public JoinGUI(AerJoiner plugin) {
        this.plugin = plugin;
    }

    // ==================== 分类 GUI ====================

    public void openCategoryGUI(Player player) {
        openCategoryGUI(player, 0);
    }

    public void openCategoryGUI(Player player, int page) {
        List<CategoryData> sorted = new ArrayList<>(plugin.getServerManager().getCategories());
        sorted.sort(Comparator.comparing(c -> c.getName().toLowerCase()));
        if (sorted.isEmpty()) {
            player.sendMessage("§c没有可用的分类");
            return;
        }

        int maxPage = Math.max(0, (sorted.size() - 1) / ITEMS_PER_PAGE);
        page = clamp(page, 0, maxPage);

        Inventory inv = Bukkit.createInventory(null, INVENTORY_SIZE, CATEGORY_INV_TITLE);
        fillItems(inv, sorted, page, this::createCategoryItem);
        addNavigation(inv, page, maxPage);
        pageState.put(player.getUniqueId(), new PageState(page, maxPage, null));
        player.openInventory(inv);
    }

    // ==================== 房间 GUI ====================

    public void openRoomGUI(Player player, String categoryName) {
        openRoomGUI(player, categoryName, 0);
    }

    public void openRoomGUI(Player player, String categoryName, int page) {
        CategoryData category = plugin.getServerManager().getCategory(categoryName);
        if (category == null) return;

        List<ServerData> servers = new ArrayList<>(plugin.getServerManager().getServersInCategory(categoryName));
        Map<String, Integer> arenaCounts = countArenas(servers);

        int maxPage = servers.isEmpty() ? 0 : Math.max(0, (servers.size() - 1) / ITEMS_PER_PAGE);
        page = clamp(page, 0, maxPage);

        Inventory inv = Bukkit.createInventory(null, INVENTORY_SIZE, ROOM_INV_TITLE_PREFIX + category.getDisplayName());
        fillItems(inv, servers, page, s -> createRoomItem(s, category, arenaCounts.getOrDefault(s.getArenaName(), 1)));
        addNavigation(inv, page, maxPage);
        pageState.put(player.getUniqueId(), new PageState(page, maxPage, categoryName));
        player.openInventory(inv);
    }

    private Map<String, Integer> countArenas(List<ServerData> servers) {
        Map<String, Integer> counts = new HashMap<>();
        for (ServerData s : servers) {
            counts.merge(s.getArenaName(), 1, Integer::sum);
        }
        return counts;
    }

    // ==================== 分页填充 ====================

    private <T> void fillItems(Inventory inv, List<T> items, int page, Function<T, ItemStack> factory) {
        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, items.size());
        for (int i = start; i < end; i++) {
            inv.setItem(getItemSlot(i - start), factory.apply(items.get(i)));
        }
    }

    private int getItemSlot(int index) {
        return (index / 7 + 1) * 9 + (index % 7) + 1;
    }

    // ==================== 导航 ====================

    private void addNavigation(Inventory inv, int page, int maxPage) {
        inv.setItem(49, navButton(Material.BARRIER, "§c关闭"));
        if (page > 0) inv.setItem(47, navButton(Material.ARROW, "§7« 上一页"));
        if (page < maxPage) inv.setItem(50, navButton(Material.ARROW, "§7下一页 »"));
    }

    private ItemStack navButton(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    // ==================== 物品创建 ====================

    private ItemStack createCategoryItem(CategoryData category) {
        boolean isRedis = "redis".equals(category.getMethod());
        ItemStack item = new ItemStack(isRedis ? Material.DIAMOND : Material.IRON_INGOT);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§b" + category.getDisplayName());
        List<String> lore = new ArrayList<>();
        lore.add("§7类型: " + (isRedis ? "§dRedis" : "§fMOTD"));
        lore.add("§7房间数: §a" + category.getServerNames().size());
        lore.add("");
        lore.add("§e点击选择此分类");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createRoomItem(ServerData server, CategoryData category, int arenaCount) {
        boolean isRedis = "redis".equals(category.getMethod());
        boolean joinable = isRedis
                ? category.getJoinableStates().contains(server.getState())
                : server.getPlayerCount() >= 0;

        ItemStack item = new ItemStack(joinable ? Material.MAP : Material.PAPER);
        if (arenaCount > 1) item.setAmount(arenaCount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§b" + server.getDisplayName());

        List<String> lore = new ArrayList<>();
        lore.add("§7分组: §f" + category.getDisplayName());
        if (isRedis) {
            lore.add("§7状态: " + (joinable ? "§a" : "§c") + server.getState());
            lore.add("§7人数: §a" + server.getPlayerCount() + "§7/§c" + server.getMaxPlayers());
        } else {
            lore.add("§7状态: " + (joinable ? "§a在线 §7(" + server.getPlayerCount() + "人)" : "§c离线"));
        }
        if (arenaCount > 1) {
            lore.add("§7相同地图: §e" + arenaCount + " 个房间");
        }
        lore.add("");
        lore.add(joinable ? "§a点击加入" : "§c不可加入");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ==================== 状态 ====================

    public PageState getPageState(Player player) {
        return pageState.get(player.getUniqueId());
    }

    public void removePageState(Player player) {
        pageState.remove(player.getUniqueId());
    }

    public static boolean isCategoryGUI(String title) {
        return CATEGORY_INV_TITLE.equals(title);
    }

    public static boolean isRoomGUI(String title) {
        return title.startsWith(ROOM_INV_TITLE_PREFIX);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }


}
