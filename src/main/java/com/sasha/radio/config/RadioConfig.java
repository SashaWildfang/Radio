/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.config;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** A validated snapshot of config.yml, rebuilt on /radio reload. */
public record RadioConfig(
        int defaultVolume,
        boolean defaultShuffle,
        OnJoin onJoin,
        boolean announceSongs,
        boolean stereo,
        SoundCategory soundCategory,
        int queueLimit,
        int minSongSeconds,
        int maxSongSeconds,
        Set<String> enabledWorlds,
        BarMode defaultBar,
        BossBar.Color barColor,
        BossBar.Overlay barOverlay,
        boolean stationEnabled,
        Set<String> stationCategories,
        boolean stationShuffle,
        boolean downloadsEnabled,
        boolean downloadOnStartup,
        String repository,
        boolean clickSound,
        Map<String, Material> categoryIcons,
        Material defaultCategoryIcon
) {

    public enum OnJoin { PERSONAL, STATION, NONE }

    public boolean worldEnabled(World world) {
        return enabledWorlds.isEmpty() || enabledWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    public Material categoryIcon(String category) {
        return categoryIcons.getOrDefault(category.toLowerCase(Locale.ROOT), defaultCategoryIcon);
    }

    public static RadioConfig load(FileConfiguration config, Logger logger) {
        Parser p = new Parser(logger);

        Set<String> worlds = new LinkedHashSet<>();
        config.getStringList("worlds.enabled").forEach(w -> worlds.add(w.toLowerCase(Locale.ROOT)));

        Set<String> stationCategories = new LinkedHashSet<>();
        config.getStringList("station.categories").forEach(c -> stationCategories.add(c.toLowerCase(Locale.ROOT)));

        String repository = config.getString("downloads.repository", "").trim();
        if (!repository.isEmpty() && !repository.endsWith("/")) repository += "/";

        Map<String, Material> icons = new HashMap<>();
        Material defaultIcon = Material.NOTE_BLOCK;
        ConfigurationSection iconSection = config.getConfigurationSection("gui.category-icons");
        if (iconSection != null) {
            for (String category : iconSection.getKeys(false)) {
                Material material = p.material("gui.category-icons." + category, iconSection.getString(category));
                if (material == null) continue;
                if (category.equalsIgnoreCase("default")) defaultIcon = material;
                else icons.put(category.toLowerCase(Locale.ROOT), material);
            }
        }

        return new RadioConfig(
                clamp(config.getInt("playback.default-volume", 20), 0, 100),
                config.getBoolean("playback.default-shuffle", true),
                p.enumValue("playback.on-join", config.getString("playback.on-join"), OnJoin.class, OnJoin.PERSONAL),
                config.getBoolean("playback.announce-songs", true),
                config.getBoolean("playback.stereo", true),
                p.enumValue("playback.sound-category", config.getString("playback.sound-category"), SoundCategory.class, SoundCategory.RECORDS),
                clamp(config.getInt("playback.queue-limit", 50), 1, 1000),
                Math.max(0, config.getInt("songs.min-length", 90)),
                Math.max(0, config.getInt("songs.max-length", 270)),
                Set.copyOf(worlds),
                p.enumValue("now-playing-bar.default", config.getString("now-playing-bar.default"), BarMode.class, BarMode.BOSSBAR),
                p.enumValue("now-playing-bar.boss-bar-color", config.getString("now-playing-bar.boss-bar-color"), BossBar.Color.class, BossBar.Color.PURPLE),
                p.enumValue("now-playing-bar.boss-bar-style", config.getString("now-playing-bar.boss-bar-style"), BossBar.Overlay.class, BossBar.Overlay.PROGRESS),
                config.getBoolean("station.enabled", true),
                Set.copyOf(stationCategories),
                config.getBoolean("station.shuffle", true),
                config.getBoolean("downloads.enabled", true) && !repository.isEmpty(),
                config.getBoolean("downloads.on-startup", true),
                repository,
                config.getBoolean("gui.click-sound", true),
                Map.copyOf(icons),
                defaultIcon
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Parses enum/material values, logging a clear warning and falling back on typos. */
    private record Parser(Logger logger) {
        <E extends Enum<E>> E enumValue(String path, String value, Class<E> type, E fallback) {
            if (value == null) return fallback;
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equalsIgnoreCase(value.trim())) return constant;
            }
            logger.warning("config.yml " + path + ": unknown value '" + value + "', using " + fallback.name());
            return fallback;
        }

        Material material(String path, String value) {
            Material material = value == null ? null : Material.matchMaterial(value.trim());
            if (material == null || !material.isItem()) {
                logger.warning("config.yml " + path + ": '" + value + "' is not an item");
                return null;
            }
            return material;
        }
    }
}
