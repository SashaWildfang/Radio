/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.session;

import com.sasha.radio.config.BarMode;
import com.sasha.radio.config.RadioConfig;
import com.sasha.radio.gui.SortMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A player's radio preferences. Stored in the player's own persistent data, so they survive
 * rejoins and restarts without a separate data file.
 */
public final class PlayerSettings {

    private int volume;
    private boolean shuffle;
    private boolean repeat;
    private SortMode sort;
    /** null = use the server default from config.yml */
    private BarMode bar;
    private final Set<String> favorites;

    private PlayerSettings(int volume, boolean shuffle, boolean repeat, SortMode sort, BarMode bar, Set<String> favorites) {
        this.volume = volume;
        this.shuffle = shuffle;
        this.repeat = repeat;
        this.sort = sort;
        this.bar = bar;
        this.favorites = favorites;
    }

    static PlayerSettings load(Player player, Keys keys, RadioConfig config) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        String bar = data.get(keys.bar, PersistentDataType.STRING);
        List<String> favorites = data.getOrDefault(keys.favorites, PersistentDataType.LIST.strings(), List.of());
        return new PlayerSettings(
                clampVolume(data.getOrDefault(keys.volume, PersistentDataType.INTEGER, config.defaultVolume())),
                data.getOrDefault(keys.shuffle, PersistentDataType.BOOLEAN, config.defaultShuffle()),
                data.getOrDefault(keys.repeat, PersistentDataType.BOOLEAN, false),
                SortMode.parse(data.get(keys.sort, PersistentDataType.STRING)),
                bar == null ? null : BarMode.parse(bar, null),
                new LinkedHashSet<>(favorites)
        );
    }

    /** Settings for the lobby station, which has no player to store them on. */
    static PlayerSettings station(boolean shuffle) {
        return new PlayerSettings(100, shuffle, false, SortMode.NAME_AZ, BarMode.OFF, new LinkedHashSet<>());
    }

    void save(Player player, Keys keys) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        data.set(keys.volume, PersistentDataType.INTEGER, volume);
        data.set(keys.shuffle, PersistentDataType.BOOLEAN, shuffle);
        data.set(keys.repeat, PersistentDataType.BOOLEAN, repeat);
        data.set(keys.sort, PersistentDataType.STRING, sort.name());
        if (bar == null) data.remove(keys.bar);
        else data.set(keys.bar, PersistentDataType.STRING, bar.name());
        data.set(keys.favorites, PersistentDataType.LIST.strings(), List.copyOf(favorites));
    }

    public int volume() {
        return volume;
    }

    public boolean shuffle() {
        return shuffle;
    }

    public boolean repeat() {
        return repeat;
    }

    public SortMode sort() {
        return sort;
    }

    /** The player's chosen bar mode, falling back to the server default. */
    public BarMode bar(RadioConfig config) {
        return bar == null ? config.defaultBar() : bar;
    }

    /** Song ids, in the order they were favorited. */
    public Set<String> favorites() {
        return Collections.unmodifiableSet(favorites);
    }

    public boolean isFavorite(String songId) {
        return favorites.contains(songId);
    }

    void volume(int volume) {
        this.volume = clampVolume(volume);
    }

    void shuffle(boolean shuffle) {
        this.shuffle = shuffle;
    }

    void repeat(boolean repeat) {
        this.repeat = repeat;
    }

    void sort(SortMode sort) {
        this.sort = sort;
    }

    void bar(BarMode bar) {
        this.bar = bar;
    }

    /** Returns true if the song is now a favorite. */
    boolean toggleFavorite(String songId) {
        if (favorites.remove(songId)) return false;
        favorites.add(songId);
        return true;
    }

    private static int clampVolume(int volume) {
        return Math.max(0, Math.min(100, volume));
    }

    record Keys(NamespacedKey volume, NamespacedKey shuffle, NamespacedKey repeat, NamespacedKey sort,
                NamespacedKey bar, NamespacedKey favorites) {
        Keys(Plugin plugin) {
            this(new NamespacedKey(plugin, "volume"), new NamespacedKey(plugin, "shuffle"),
                    new NamespacedKey(plugin, "repeat"), new NamespacedKey(plugin, "sort"),
                    new NamespacedKey(plugin, "bar"), new NamespacedKey(plugin, "favorites"));
        }
    }
}
