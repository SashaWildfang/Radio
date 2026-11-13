/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.gui;

import com.sasha.radio.Radio;
import com.sasha.radio.config.BarMode;
import com.sasha.radio.config.Messages;
import com.sasha.radio.config.RadioConfig;
import com.sasha.radio.session.RadioSession;
import com.sasha.radio.session.SessionManager;
import com.sasha.radio.song.Playback;
import com.sasha.radio.song.Song;
import com.sasha.radio.song.SongLibrary;
import com.sasha.radio.util.Format;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The /radio menu. It is the inventory's holder, which is how clicks are recognised.
 *
 * <pre>
 *  [categories][favorites][search] ▒ [NOW PLAYING] ▒ [queue][station][sort]
 *  ▓ [ 7 songs per row, 4 rows = 28 per page ] ▓
 *  [« page][volume][shuffle][prev][play/pause][skip][repeat][bar][page »]
 * </pre>
 */
public final class RadioMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int[] GRID = new int[28];

    static {
        for (int i = 0; i < GRID.length; i++) GRID[i] = (1 + i / 7) * 9 + 1 + i % 7;
    }

    private static final int CATEGORIES = 0, FAVORITES = 1, SEARCH = 2, NOW_PLAYING = 4, QUEUE = 6, STATION = 7, SORT = 8;
    private static final int PAGE_BACK = 45, VOLUME = 46, SHUFFLE = 47, PREVIOUS = 48, PLAY = 49, SKIP = 50,
            REPEAT = 51, BAR = 52, PAGE_NEXT = 53;
    private static final int EMPTY_SLOT = 22;

    /** Within this many seconds of the start, "previous" goes to the last song instead of restarting. */
    private static final int RESTART_THRESHOLD_SECONDS = 3;

    private static final Material[] DISCS = {
            Material.MUSIC_DISC_13, Material.MUSIC_DISC_CAT, Material.MUSIC_DISC_BLOCKS,
            Material.MUSIC_DISC_CHIRP, Material.MUSIC_DISC_FAR, Material.MUSIC_DISC_MALL,
            Material.MUSIC_DISC_MELLOHI, Material.MUSIC_DISC_STAL, Material.MUSIC_DISC_STRAD,
            Material.MUSIC_DISC_WARD, Material.MUSIC_DISC_11, Material.MUSIC_DISC_WAIT,
            Material.MUSIC_DISC_PIGSTEP, Material.MUSIC_DISC_OTHERSIDE, Material.MUSIC_DISC_RELIC,
    };

    private static final String ALL_SONGS = "\0all";
    private static final String FAVORITE_SONGS = "\0favorites";

    private final Radio plugin;
    private final MenuService service;
    private final UUID viewer;
    private final MenuState state;
    private final Component title;
    private final Inventory inventory;

    /** What each grid slot holds: a Song, a category name, or a queue index. */
    private final Map<Integer, Object> entries = new HashMap<>();
    /** Song the grid was last drawn for, so a song change redraws the "now playing" badge. */
    private String drawnForSong;

    RadioMenu(Radio plugin, MenuService service, Player viewer, MenuState state) {
        this.plugin = plugin;
        this.service = service;
        this.viewer = viewer.getUniqueId();
        this.state = state;
        this.title = title(plugin.messages(), state);
        this.inventory = Bukkit.createInventory(this, SIZE, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    UUID viewer() {
        return viewer;
    }

    static Component title(Messages messages, MenuState state) {
        Component view = switch (state.view) {
            case CATEGORIES -> messages.component("gui.views.categories");
            case QUEUE -> messages.component("gui.views.queue");
            case SONGS -> filterName(messages, state);
        };
        return messages.component("gui.title", Messages.arg("view", view));
    }

    private static Component filterName(Messages messages, MenuState state) {
        if (state.search != null) return messages.component("gui.views.search", Messages.arg("query", state.search));
        if (state.favoritesOnly) return messages.component("gui.views.favorites");
        if (state.category != null) return messages.component("gui.views.category", Messages.arg("category", state.category));
        return messages.component("gui.views.all");
    }

    // ---------------------------------------------------------------- drawing

    /** Draws every slot. */
    void render(Player player) {
        SessionManager sessions = plugin.sessions();
        RadioSession own = sessions.session(player);
        RadioSession audible = sessions.audibleSession(player);
        Song current = audible.current();
        drawnForSong = current == null ? null : current.id();

        ItemStack dark = pane(Material.BLACK_STAINED_GLASS_PANE);
        ItemStack accent = pane(Material.PURPLE_STAINED_GLASS_PANE);
        for (int slot = 0; slot < SIZE; slot++) {
            int column = slot % 9;
            boolean side = slot >= 9 && slot < 45 && (column == 0 || column == 8);
            inventory.setItem(slot, side ? accent : dark);
        }
        inventory.setItem(3, pane(Material.MAGENTA_STAINED_GLASS_PANE));
        inventory.setItem(5, pane(Material.MAGENTA_STAINED_GLASS_PANE));

        entries.clear();
        List<Object> items = switch (state.view) {
            case SONGS -> new ArrayList<>(visibleSongs(own));
            case CATEGORIES -> categoryEntries();
            case QUEUE -> {
                List<Object> indexes = new ArrayList<>();
                for (int i = 0; i < own.queue().size(); i++) indexes.add(i);
                yield indexes;
            }
        };

        int pages = Math.max(1, (items.size() + GRID.length - 1) / GRID.length);
        state.page = Math.max(0, Math.min(state.page, pages - 1));
        int first = state.page * GRID.length;
        for (int i = 0; i < GRID.length; i++) {
            int slot = GRID[i];
            if (first + i >= items.size()) {
                inventory.setItem(slot, null);
                continue;
            }
            Object entry = items.get(first + i);
            entries.put(slot, entry);
            inventory.setItem(slot, gridItem(entry, own, current));
        }
        if (items.isEmpty()) {
            Messages m = plugin.messages();
            inventory.setItem(EMPTY_SLOT, item(Material.STRUCTURE_VOID, m.item("gui.empty.name"), m.lore("gui.empty.lore"), false));
        }

        if (state.page > 0) {
            inventory.setItem(PAGE_BACK, item(Material.SPECTRAL_ARROW, plugin.messages().item("gui.page-back.name", pageArgs(state.page, pages)), List.of(), false));
        }
        if (state.page < pages - 1) {
            inventory.setItem(PAGE_NEXT, item(Material.SPECTRAL_ARROW, plugin.messages().item("gui.page-next.name", pageArgs(state.page + 2, pages)), List.of(), false));
        }

        renderHeader(player, own, audible);
        renderFooter(own);
    }

    /** Redraws the live parts (progress, play state). Redraws everything if the song changed. Runs every second. */
    void refresh(Player player) {
        SessionManager sessions = plugin.sessions();
        RadioSession audible = sessions.audibleSession(player);
        Song current = audible.current();
        String id = current == null ? null : current.id();
        if (id == null ? drawnForSong != null : !id.equals(drawnForSong)) {
            render(player);
            return;
        }
        RadioSession own = sessions.session(player);
        renderHeader(player, own, audible);
        renderFooter(own);
    }

    private void renderHeader(Player player, RadioSession own, RadioSession audible) {
        Messages m = plugin.messages();
        RadioConfig config = plugin.radioConfig();
        SessionManager sessions = plugin.sessions();

        inventory.setItem(CATEGORIES, item(Material.BOOKSHELF, m.item("gui.categories.name"),
                m.lore("gui.categories.lore", Messages.arg("view", filterName(m, state))), state.view == MenuState.View.CATEGORIES));

        inventory.setItem(FAVORITES, item(Material.NETHER_STAR, m.item("gui.favorites.name"),
                m.lore("gui.favorites.lore", Messages.arg("count", own.settings().favorites().size())), state.favoritesOnly));

        Component query = state.search == null ? Component.empty()
                : m.component("gui.search.current", Messages.arg("query", state.search));
        inventory.setItem(SEARCH, item(Material.SPYGLASS, m.item("gui.search.name"),
                m.lore("gui.search.lore", Messages.arg("query", query)), state.search != null));

        inventory.setItem(NOW_PLAYING, nowPlayingItem(player, own, audible));

        if (state.view == MenuState.View.QUEUE) {
            inventory.setItem(QUEUE, item(Material.SPECTRAL_ARROW, m.item("gui.queue-back.name"), m.lore("gui.queue-back.lore"), false));
        } else {
            inventory.setItem(QUEUE, item(Material.WRITABLE_BOOK, m.item("gui.queue.name", Messages.arg("count", own.queue().size())),
                    m.lore("gui.queue.lore"), false));
        }

        RadioSession station = sessions.station();
        if (station != null && player.hasPermission("radio.station")) {
            boolean tunedIn = SessionManager.STATION_ID.equals(sessions.hostOf(player.getUniqueId()));
            Song playing = station.current();
            TagResolver args = TagResolver.resolver(
                    Messages.arg("name", sessions.hostName(SessionManager.STATION_ID)),
                    Messages.arg("title", playing == null ? "-" : playing.title()),
                    Messages.arg("listeners", sessions.listenersOf(SessionManager.STATION_ID).size()));
            inventory.setItem(STATION, item(Material.JUKEBOX, m.item("gui.station.name", args),
                    m.lore(tunedIn ? "gui.station.lore-listening" : "gui.station.lore", args), tunedIn));
        }

        SortMode sort = own.settings().sort();
        List<Component> options = new ArrayList<>();
        for (SortMode mode : SortMode.values()) {
            options.add(m.item(mode == sort ? "gui.sort.option-selected" : "gui.sort.option",
                    Messages.arg("mode", m.component("gui.sort-modes." + mode.name()))));
        }
        inventory.setItem(SORT, item(Material.HOPPER,
                m.item("gui.sort.name", Messages.arg("mode", m.component("gui.sort-modes." + sort.name()))),
                m.lore("gui.sort.lore", Map.of("options", options)), false));
    }

    private void renderFooter(RadioSession own) {
        Messages m = plugin.messages();
        var settings = own.settings();

        inventory.setItem(VOLUME, item(Material.NOTE_BLOCK,
                m.item("gui.volume.name", Messages.arg("volume", settings.volume())),
                m.lore("gui.volume.lore", Messages.arg("bar", m.progressBar(settings.volume() / 100.0))), false));

        inventory.setItem(SHUFFLE, item(settings.shuffle() ? Material.ENDER_EYE : Material.ENDER_PEARL,
                m.item("gui.shuffle.name", Messages.arg("state", m.onOff(settings.shuffle()))),
                m.lore("gui.shuffle.lore"), settings.shuffle()));

        inventory.setItem(PREVIOUS, item(Material.ECHO_SHARD, m.item("gui.previous.name"), m.lore("gui.previous.lore"), false));

        boolean playing = own.isPlaying();
        inventory.setItem(PLAY, playing
                ? item(Material.PURPLE_DYE, m.item("gui.pause.name"), m.lore("gui.pause.lore"), true)
                : item(Material.LIME_DYE, m.item("gui.play.name"), m.lore("gui.play.lore"), false));

        inventory.setItem(SKIP, item(Material.AMETHYST_SHARD, m.item("gui.skip.name"), m.lore("gui.skip.lore"), false));

        inventory.setItem(REPEAT, item(Material.REPEATER,
                m.item("gui.repeat.name", Messages.arg("state", m.onOff(settings.repeat()))),
                m.lore("gui.repeat.lore"), settings.repeat()));

        BarMode bar = settings.bar(plugin.radioConfig());
        Material barIcon = switch (bar) {
            case BOSSBAR -> Material.DRAGON_HEAD;
            case ACTIONBAR -> Material.NAME_TAG;
            case OFF -> Material.GRAY_DYE;
        };
        inventory.setItem(BAR, item(barIcon,
                m.item("gui.bar.name", Messages.arg("mode", m.component("bar.modes." + bar.key()))),
                m.lore("gui.bar.lore"), false));
    }

    private ItemStack nowPlayingItem(Player player, RadioSession own, RadioSession audible) {
        Messages m = plugin.messages();
        Song song = audible.current();
        if (song == null) {
            return item(Material.BARRIER, m.item("gui.nothing-playing.name"), m.lore("gui.nothing-playing.lore"), false);
        }
        Playback playback = audible.playback();
        Song next = audible.peekNext();
        UUID host = plugin.sessions().hostOf(player.getUniqueId());
        Component source = host == null ? Component.empty()
                : m.component("gui.now-playing.source", Messages.arg("name", plugin.sessions().hostName(host)));
        TagResolver args = TagResolver.resolver(
                Format.song(song),
                Messages.arg("elapsed", Format.duration(playback.elapsedSeconds())),
                Messages.arg("bar", m.progressBar(playback.progress())),
                Messages.arg("next", next == null ? "-" : next.title()),
                Messages.arg("source", source));
        return disc(song, m.item("gui.now-playing.name", args), m.lore("gui.now-playing.lore", args), true);
    }

    private ItemStack gridItem(Object entry, RadioSession own, Song current) {
        Messages m = plugin.messages();
        if (entry instanceof Song song) {
            List<Component> badges = new ArrayList<>();
            if (current != null && current.id().equals(song.id())) badges.add(m.component("gui.song.badge-playing"));
            if (own.settings().isFavorite(song.id())) badges.add(m.component("gui.song.badge-favorite"));
            int position = own.queuePosition(song);
            if (position > 0) badges.add(m.component("gui.song.badge-queued", Messages.arg("position", position)));
            TagResolver args = TagResolver.resolver(Format.song(song),
                    Messages.arg("badges", Component.join(net.kyori.adventure.text.JoinConfiguration.separator(Component.text(" · ")), badges)));
            boolean playing = current != null && current.id().equals(song.id());
            return disc(song, m.item("gui.song.name", args), m.lore("gui.song.lore", args), playing);
        }
        if (entry instanceof Integer index) {
            Song song = own.queue().get(index);
            TagResolver args = TagResolver.resolver(Format.song(song), Messages.arg("position", index + 1));
            return disc(song, m.item("gui.queued-song.name", args), m.lore("gui.queued-song.lore", args), false);
        }
        String category = (String) entry;
        SongLibrary library = plugin.library();
        if (category.equals(ALL_SONGS)) {
            boolean selected = state.category == null && !state.favoritesOnly && state.search == null;
            return item(Material.MUSIC_DISC_STRAD, m.item("gui.all-songs.name"),
                    m.lore("gui.all-songs.lore", Messages.arg("count", library.size())), selected);
        }
        if (category.equals(FAVORITE_SONGS)) {
            return item(Material.NETHER_STAR, m.item("gui.favorites.name"),
                    m.lore("gui.favorites.lore", Messages.arg("count", own.settings().favorites().size())), state.favoritesOnly);
        }
        TagResolver args = TagResolver.resolver(Messages.arg("category", category),
                Messages.arg("count", library.categories().getOrDefault(category, List.of()).size()));
        return item(plugin.radioConfig().categoryIcon(category), m.item("gui.category.name", args),
                m.lore("gui.category.lore", args), category.equals(state.category));
    }

    private List<Song> visibleSongs(RadioSession own) {
        Stream<Song> songs = plugin.library().songs().stream();
        if (state.category != null) songs = songs.filter(s -> s.category().equalsIgnoreCase(state.category));
        if (state.favoritesOnly) songs = songs.filter(s -> own.settings().isFavorite(s.id()));
        if (state.search != null) {
            String query = SongLibrary.normalize(state.search);
            songs = songs.filter(s -> SongLibrary.matches(s, query));
        }
        return songs.sorted(own.settings().sort().comparator()).toList();
    }

    private List<Object> categoryEntries() {
        List<Object> list = new ArrayList<>();
        list.add(ALL_SONGS);
        list.add(FAVORITE_SONGS);
        list.addAll(plugin.library().categories().keySet());
        return list;
    }

    // ---------------------------------------------------------------- clicks

    void handleClick(Player player, int slot, ClickType click) {
        SessionManager sessions = plugin.sessions();
        Messages m = plugin.messages();
        RadioSession own = sessions.session(player);
        UUID host = sessions.hostOf(player.getUniqueId());

        switch (slot) {
            case CATEGORIES -> {
                state.view = state.view == MenuState.View.CATEGORIES ? MenuState.View.SONGS : MenuState.View.CATEGORIES;
                state.page = 0;
            }
            case FAVORITES -> {
                boolean show = !state.favoritesOnly;
                state.clearFilters();
                state.favoritesOnly = show;
                state.showSongs();
            }
            case SEARCH -> {
                if (click.isRightClick()) {
                    state.search = null;
                    state.showSongs();
                } else {
                    sound(player, true);
                    service.promptSearch(player, state);
                    return;
                }
            }
            case NOW_PLAYING -> {
                Song song = sessions.audibleSession(player).current();
                if (!click.isShiftClick() || song == null) return;
                toggleFavorite(player, own, song);
            }
            case QUEUE -> {
                if (state.view != MenuState.View.QUEUE) {
                    state.view = MenuState.View.QUEUE;
                    state.page = 0;
                } else if (click.isRightClick()) {
                    own.clearQueue();
                    m.send(player, "queue.cleared");
                } else {
                    state.showSongs();
                }
            }
            case STATION -> {
                if (sessions.station() == null || !player.hasPermission("radio.station")) return;
                if (SessionManager.STATION_ID.equals(host)) sessions.leave(player);
                else sessions.tuneInStation(player);
            }
            case SORT -> {
                own.setSort(own.settings().sort().next());
                state.page = 0;
            }
            case PAGE_BACK -> state.page--;
            case PAGE_NEXT -> state.page++;
            case VOLUME -> {
                int step = click.isShiftClick() ? 1 : 10;
                own.setVolume(own.settings().volume() + (click.isRightClick() ? -step : step));
            }
            case BAR -> own.setBar(own.settings().bar(plugin.radioConfig()).next());
            case SHUFFLE, PREVIOUS, PLAY, SKIP, REPEAT -> {
                if (host != null) {
                    m.send(player, "gui.listening-blocked", Messages.arg("name", sessions.hostName(host)));
                    sound(player, false);
                    return;
                }
                control(own, slot);
            }
            default -> {
                Object entry = entries.get(slot);
                if (entry == null) return; // glass or empty slot
                if (!clickEntry(player, own, entry, click)) return;
            }
        }
        sound(player, true);
        service.redraw(player, this, state);
    }

    private void control(RadioSession own, int slot) {
        switch (slot) {
            case SHUFFLE -> own.setShuffle(!own.settings().shuffle());
            case PREVIOUS -> {
                Playback playback = own.playback();
                if (playback != null && playback.elapsedSeconds() >= RESTART_THRESHOLD_SECONDS) own.restart();
                else own.previous();
            }
            case PLAY -> {
                if (own.isPlaying()) own.pause();
                else own.resume();
            }
            case SKIP -> own.skip();
            case REPEAT -> own.setRepeat(!own.settings().repeat());
            default -> {
            }
        }
    }

    /** Returns false if nothing happened. */
    private boolean clickEntry(Player player, RadioSession own, Object entry, ClickType click) {
        Messages m = plugin.messages();
        if (entry instanceof Song song) {
            if (click.isShiftClick()) {
                toggleFavorite(player, own, song);
            } else if (click.isRightClick()) {
                int position = own.enqueue(song);
                if (position < 0) m.send(player, "queue.full", Messages.arg("limit", plugin.radioConfig().queueLimit()));
                else m.send(player, "queue.added", Format.song(song), Messages.arg("position", position));
            } else {
                plugin.sessions().leave(player); // picking a song means you want your own radio again
                own.play(song);
            }
            return true;
        }
        if (entry instanceof Integer index) {
            Song removed = own.removeFromQueue(index);
            if (removed != null) m.send(player, "queue.removed", Format.song(removed));
            return removed != null;
        }
        String category = (String) entry;
        state.clearFilters();
        if (category.equals(FAVORITE_SONGS)) state.favoritesOnly = true;
        else if (!category.equals(ALL_SONGS)) state.category = category;
        state.showSongs();
        return true;
    }

    private void toggleFavorite(Player player, RadioSession own, Song song) {
        boolean favorite = own.toggleFavorite(song);
        plugin.messages().send(player, favorite ? "favorites.added" : "favorites.removed", Format.song(song));
    }

    private void sound(Player player, boolean success) {
        if (!plugin.radioConfig().clickSound()) return;
        if (success) player.playSound(player, Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
        else player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
    }

    boolean titleMatches(MenuState other) {
        return title.equals(title(plugin.messages(), other));
    }

    // ---------------------------------------------------------------- items

    private static TagResolver pageArgs(int page, int pages) {
        return TagResolver.resolver(Messages.arg("page", page), Messages.arg("pages", pages));
    }

    private static ItemStack disc(Song song, Component name, List<Component> lore, boolean glow) {
        ItemStack item = item(DISCS[Math.floorMod(song.id().hashCode(), DISCS.length)], name, lore, glow);
        item.unsetData(DataComponentTypes.JUKEBOX_PLAYABLE); // hides the vanilla "C418 - cat" line
        return item;
    }

    private static ItemStack item(Material material, Component name, List<Component> lore, boolean glow) {
        ItemStack item = ItemStack.of(material);
        item.editMeta(meta -> {
            meta.displayName(name);
            if (!lore.isEmpty()) meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            if (glow) meta.setEnchantmentGlintOverride(true);
        });
        return item;
    }

    private static ItemStack pane(Material material) {
        ItemStack item = ItemStack.of(material);
        item.editMeta(meta -> meta.setHideTooltip(true));
        return item;
    }
}
