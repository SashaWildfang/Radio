/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.display;

import com.sasha.radio.Radio;
import com.sasha.radio.config.BarMode;
import com.sasha.radio.config.Messages;
import com.sasha.radio.config.RadioConfig;
import com.sasha.radio.session.RadioSession;
import com.sasha.radio.session.SessionManager;
import com.sasha.radio.song.Playback;
import com.sasha.radio.song.Song;
import com.sasha.radio.util.Format;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Shows the current song as a boss bar or action bar line, per each player's choice. */
public final class NowPlayingBar {

    private final Radio plugin;
    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    public NowPlayingBar(Radio plugin) {
        this.plugin = plugin;
    }

    /** Runs every 10 ticks. */
    public void update() {
        for (Player player : Bukkit.getOnlinePlayers()) update(player);
    }

    private void update(Player player) {
        RadioConfig config = plugin.radioConfig();
        SessionManager sessions = plugin.sessions();
        RadioSession own = sessions.session(player);
        RadioSession audible = sessions.audibleSession(player);
        BarMode mode = own.settings().bar(config);
        Song song = audible.current();

        boolean show = mode != BarMode.OFF && song != null && config.worldEnabled(player.getWorld());
        if (!show || mode != BarMode.BOSSBAR) hideBossBar(player);
        if (!show) return;

        Messages m = plugin.messages();
        Playback playback = audible.playback();
        UUID host = sessions.hostOf(player.getUniqueId());
        Component source = host == null ? Component.empty()
                : m.component("bar.source", Messages.arg("name", sessions.hostName(host)));
        String key = mode == BarMode.BOSSBAR
                ? (audible.isPaused() ? "bar.boss-paused" : "bar.boss-playing")
                : (audible.isPaused() ? "bar.paused" : "bar.playing");
        Component text = m.component(key, TagResolver.resolver(
                Format.song(song),
                Messages.arg("elapsed", Format.duration(playback.elapsedSeconds())),
                Messages.arg("bar", m.progressBar(playback.progress())),
                Messages.arg("source", source)));

        if (mode == BarMode.ACTIONBAR) {
            player.sendActionBar(text);
            return;
        }
        float progress = (float) Math.max(0, Math.min(1, playback.progress()));
        BossBar bar = bossBars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(text, progress, config.barColor(), config.barOverlay());
            bossBars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(text);
            bar.progress(progress);
            bar.color(config.barColor());
            bar.overlay(config.barOverlay());
        }
    }

    private void hideBossBar(Player player) {
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
    }

    public void remove(Player player) {
        hideBossBar(player);
    }

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) hideBossBar(player);
        bossBars.clear();
    }
}
