/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.hook;

import com.sasha.radio.Radio;
import com.sasha.radio.session.RadioSession;
import com.sasha.radio.session.SessionManager;
import com.sasha.radio.song.Song;
import com.sasha.radio.util.Format;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * PlaceholderAPI placeholders. Only loaded when PlaceholderAPI is installed: this class must not be touched
 * otherwise, since its superclass comes from PlaceholderAPI.
 *
 * <pre>
 * %radio_title%  %radio_artist%  %radio_category%  %radio_elapsed%  %radio_length%  %radio_progress%
 * %radio_status% (playing / paused / off)  %radio_volume%  %radio_shuffle%  %radio_repeat%
 * %radio_listening_to%  %radio_station_title%  %radio_station_artist%  %radio_station_listeners%  %radio_songs%
 * </pre>
 */
public final class RadioExpansion extends PlaceholderExpansion {

    private final Radio plugin;

    public RadioExpansion(Radio plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "radio";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // survives /papi reload; we unregister ourselves on disable
    }

    @Override
    public String onRequest(OfflinePlayer offline, String params) {
        SessionManager sessions = plugin.sessions();
        switch (params) {
            case "songs" -> {
                return String.valueOf(plugin.library().size());
            }
            case "station_title", "station_artist", "station_listeners" -> {
                RadioSession station = sessions.station();
                if (station == null) return "";
                if (params.equals("station_listeners")) {
                    return String.valueOf(sessions.listenersOf(SessionManager.STATION_ID).size());
                }
                Song song = station.current();
                return song == null ? "" : params.equals("station_title") ? song.title() : song.author();
            }
            default -> {
            }
        }

        Player player = offline == null ? null : offline.getPlayer();
        if (player == null) return "";
        RadioSession own = sessions.session(player);
        RadioSession audible = sessions.audibleSession(player);
        Song song = audible.current();

        return switch (params) {
            case "title" -> song == null ? "" : song.title();
            case "artist" -> song == null ? "" : song.author();
            case "category" -> song == null ? "" : song.category();
            case "elapsed" -> song == null ? "" : Format.duration(audible.playback().elapsedSeconds());
            case "length" -> song == null ? "" : Format.duration(song.durationSeconds());
            case "progress" -> song == null ? "0" : String.valueOf(Math.round(audible.playback().progress() * 100));
            case "status" -> song == null ? "off" : audible.isPaused() ? "paused" : "playing";
            case "volume" -> String.valueOf(own.settings().volume());
            case "shuffle" -> String.valueOf(own.settings().shuffle());
            case "repeat" -> String.valueOf(own.settings().repeat());
            case "listening_to" -> {
                UUID host = sessions.hostOf(player.getUniqueId());
                yield host == null ? "" : PlainTextComponentSerializer.plainText().serialize(sessions.hostName(host));
            }
            default -> null; // unknown placeholder
        };
    }
}
