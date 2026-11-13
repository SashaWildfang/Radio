/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.gui;

import com.sasha.radio.Radio;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Opens menus, routes their clicks, keeps them live, and runs the chat search prompt. */
public final class MenuService implements Listener {

    private static final long SEARCH_TIMEOUT_TICKS = 20L * 60;
    private static final int MAX_SEARCH_LENGTH = 48;

    private final Radio plugin;
    /** Where each player was in the menu, so reopening returns there. Main thread only. */
    private final Map<UUID, MenuState> states = new HashMap<>();
    /** Players we're waiting on to type a search. Read from the async chat thread. */
    private final Map<UUID, MenuState> pendingSearch = new ConcurrentHashMap<>();

    public MenuService(Radio plugin) {
        this.plugin = plugin;
    }

    /** Opens the menu where the player left it. */
    public void open(Player player) {
        open(player, states.computeIfAbsent(player.getUniqueId(), id -> new MenuState()));
    }

    public void open(Player player, MenuState state) {
        if (plugin.library().isEmpty()) {
            plugin.messages().send(player, "general.no-songs");
            return;
        }
        states.put(player.getUniqueId(), state);
        RadioMenu menu = new RadioMenu(plugin, this, player, state);
        menu.render(player);
        player.openInventory(menu.getInventory());
    }

    /** After a click: redraw in place, or reopen when the title has to change (different view or filter). */
    void redraw(Player player, RadioMenu menu, MenuState state) {
        if (menu.titleMatches(state)) menu.render(player);
        else open(player, state);
    }

    void promptSearch(Player player, MenuState state) {
        UUID id = player.getUniqueId();
        pendingSearch.put(id, state);
        player.closeInventory();
        plugin.messages().send(player, "search.prompt");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pendingSearch.remove(id, state)) {
                Player online = Bukkit.getPlayer(id);
                if (online != null) plugin.messages().send(online, "search.timed-out");
            }
        }, SEARCH_TIMEOUT_TICKS);
    }

    /** Forgets a player's menu position (on quit). */
    public void forget(Player player) {
        states.remove(player.getUniqueId());
        pendingSearch.remove(player.getUniqueId());
    }

    /** Closes every open radio menu (before a reload, so nobody clicks a stale menu). */
    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof RadioMenu) player.closeInventory();
        }
    }

    /** Updates progress and play state for everyone with the menu open. Runs once a second. */
    public void refreshOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof RadioMenu menu
                    && menu.viewer().equals(player.getUniqueId())) {
                menu.refresh(player);
            }
        }
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof RadioMenu menu)) return;

        // Nothing in the menu (or moved into it from the player's inventory) can ever be taken or placed.
        event.setCancelled(true);
        if (event.getClickedInventory() != top) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!event.getClick().isMouseClick()) return; // number keys, drops, double clicks...
        menu.handleClick(player, event.getRawSlot(), event.getClick());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof RadioMenu) event.setCancelled(true);
    }

    /** Captures the next chat message of a player who clicked Search. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        MenuState state = pendingSearch.remove(player.getUniqueId());
        if (state == null) return;
        event.setCancelled(true);

        String text = PlainTextComponentSerializer.plainText().serialize(event.originalMessage()).strip();
        if (text.length() > MAX_SEARCH_LENGTH) text = text.substring(0, MAX_SEARCH_LENGTH);
        String query = text;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (query.isEmpty() || query.equalsIgnoreCase("cancel")) {
                plugin.messages().send(player, "search.cancelled");
            } else {
                state.clearFilters();
                state.search = query;
                state.showSongs();
            }
            open(player, state);
        });
    }
}
