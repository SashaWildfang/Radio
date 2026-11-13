/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.session;

import com.sasha.radio.Radio;
import com.sasha.radio.config.Messages;
import com.sasha.radio.config.RadioConfig;
import com.sasha.radio.song.Playback;
import com.sasha.radio.song.Song;
import com.sasha.radio.song.SongLibrary;
import com.sasha.radio.util.Format;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Owns every online player's {@link RadioSession}, the lobby station, the "listen with" links between
 * players, and the per-tick loop that plays notes. Main thread only.
 */
public final class SessionManager {

    /** Fixed id of the lobby station's session. Never a real player's UUID (version 3, name-based). */
    public static final UUID STATION_ID = UUID.nameUUIDFromBytes("radio:lobby-station".getBytes(StandardCharsets.UTF_8));

    private final Radio plugin;
    private final PlayerSettings.Keys keys;
    private final AudioEngine audio;
    private final Map<UUID, RadioSession> sessions = new HashMap<>();
    /** listener → the session they hear (another player's, or the station) */
    private final Map<UUID, UUID> following = new HashMap<>();
    private RadioSession station;

    public SessionManager(Radio plugin) {
        this.plugin = plugin;
        this.keys = new PlayerSettings.Keys(plugin);
        this.audio = new AudioEngine(plugin.getLogger());
        this.audio.start();
    }

    SongLibrary library() {
        return plugin.library();
    }

    RadioConfig config() {
        return plugin.radioConfig();
    }

    PlayerSettings.Keys keys() {
        return keys;
    }

    private Messages messages() {
        return plugin.messages();
    }

    // ---------------------------------------------------------------- sessions

    /** The player's own session, created on first use. */
    public RadioSession session(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(),
                id -> new RadioSession(this, id, PlayerSettings.load(player, keys, config())));
    }

    /** The session the player hears: the station's or their host's when tuned in elsewhere, otherwise their own. */
    public RadioSession audibleSession(Player player) {
        UUID host = following.get(player.getUniqueId());
        if (host != null) {
            RadioSession hostSession = sessions.get(host);
            if (hostSession != null) return hostSession;
        }
        return session(player);
    }

    /** Cleans up when a player leaves, sending anyone listening with them back to their own radio. */
    public void remove(Player player) {
        UUID id = player.getUniqueId();
        following.remove(id);
        for (UUID listenerId : listenersOf(id)) {
            following.remove(listenerId);
            Player listener = Bukkit.getPlayer(listenerId);
            if (listener != null) messages().send(listener, "listen.host-left", Messages.arg("name", player.getName()));
        }
        RadioSession session = sessions.remove(id);
        if (session != null) session.stop();
    }

    public void shutdown() {
        audio.stop();
        sessions.values().forEach(RadioSession::stop);
        sessions.clear();
        following.clear();
        station = null;
    }

    // ---------------------------------------------------------------- station

    /** The lobby station, or null when it is turned off. */
    public RadioSession station() {
        return station;
    }

    public boolean isStation(RadioSession session) {
        return session != null && session == station;
    }

    /** Creates, updates or removes the station to match config.yml. Call after (re)loading the config. */
    public void applyStationConfig() {
        RadioConfig config = config();
        if (!config.stationEnabled()) {
            if (station != null) {
                for (UUID listener : listenersOf(STATION_ID)) following.remove(listener);
                sessions.remove(STATION_ID);
                station = null;
            }
            return;
        }
        if (station == null) {
            station = new RadioSession(this, STATION_ID, PlayerSettings.station(config.stationShuffle()));
            sessions.put(STATION_ID, station);
        }
        station.setShuffle(config.stationShuffle());
        station.setPool(config.stationCategories().isEmpty() ? null
                : song -> config.stationCategories().contains(song.category().toLowerCase(Locale.ROOT)));
    }

    /** Tunes a player in to the station. */
    public FollowResult followStation(Player listener) {
        if (station == null) return new FollowResult(FollowResult.Status.DISABLED, STATION_ID);
        UUID id = listener.getUniqueId();
        if (hasListeners(id)) return new FollowResult(FollowResult.Status.HAS_LISTENERS, STATION_ID);
        if (STATION_ID.equals(following.get(id))) return new FollowResult(FollowResult.Status.ALREADY, STATION_ID);
        following.put(id, STATION_ID);
        station.startIfIdle();
        return new FollowResult(FollowResult.Status.OK, STATION_ID);
    }

    /** Display name of whoever a player is listening to: the station's name or the host's player name. */
    public Component hostName(UUID host) {
        if (STATION_ID.equals(host)) return messages().component("station.name");
        Player player = Bukkit.getPlayer(host);
        return Component.text(player == null ? "?" : player.getName());
    }

    // ---------------------------------------------------------------- listen with

    public UUID hostOf(UUID listener) {
        return following.get(listener);
    }

    public List<UUID> listenersOf(UUID host) {
        List<UUID> result = new ArrayList<>();
        following.forEach((listener, h) -> {
            if (h.equals(host)) result.add(listener);
        });
        return result;
    }

    public boolean hasListeners(UUID host) {
        return following.containsValue(host);
    }

    /**
     * Makes {@code listener} hear {@code target}'s radio. If the target is themselves listening to someone
     * (or the station), the listener joins that instead, so there are never chains.
     */
    public FollowResult follow(Player listener, Player target) {
        UUID listenerId = listener.getUniqueId();
        UUID hostId = following.getOrDefault(target.getUniqueId(), target.getUniqueId());

        if (hostId.equals(listenerId)) return new FollowResult(FollowResult.Status.SELF, hostId);
        if (hasListeners(listenerId)) return new FollowResult(FollowResult.Status.HAS_LISTENERS, hostId);
        if (Objects.equals(following.get(listenerId), hostId)) return new FollowResult(FollowResult.Status.ALREADY, hostId);

        if (STATION_ID.equals(hostId)) return followStation(listener);
        Player host = Bukkit.getPlayer(hostId);
        if (host == null) return new FollowResult(FollowResult.Status.SELF, hostId);
        session(host); // make sure the host has a session to listen to
        following.put(listenerId, hostId);
        return new FollowResult(FollowResult.Status.OK, hostId);
    }

    /** Stops listening along. The player's own radio carries on from where it was. Returns the old host. */
    public UUID unfollow(UUID listener) {
        return following.remove(listener);
    }

    /** Stops listening along and tells everyone involved. Returns false if the player wasn't tuned in anywhere. */
    public boolean leave(Player player) {
        UUID host = following.remove(player.getUniqueId());
        if (host == null) return false;
        if (STATION_ID.equals(host)) {
            messages().send(player, "station.left", Messages.arg("name", hostName(host)));
        } else {
            Player hostPlayer = Bukkit.getPlayer(host);
            if (hostPlayer != null) {
                messages().send(hostPlayer, "listen.notify-host-stopped", Messages.arg("name", player.getName()));
            }
            messages().send(player, "listen.stopped");
        }
        return true;
    }

    /** Tunes a player in to the station (leaving whoever they were listening with) and tells them. */
    public void tuneInStation(Player player) {
        UUID previous = following.get(player.getUniqueId());
        FollowResult result = followStation(player);
        switch (result.status()) {
            case DISABLED -> messages().send(player, "station.disabled");
            case HAS_LISTENERS -> messages().send(player, "listen.has-listeners");
            case ALREADY -> messages().send(player, "listen.already", Messages.arg("name", hostName(STATION_ID)));
            case OK -> {
                if (previous != null && !STATION_ID.equals(previous)) {
                    Player oldHost = Bukkit.getPlayer(previous);
                    if (oldHost != null) {
                        messages().send(oldHost, "listen.notify-host-stopped", Messages.arg("name", player.getName()));
                    }
                }
                messages().send(player, "station.joined", Messages.arg("name", hostName(STATION_ID)));
            }
            default -> {
            }
        }
    }

    public record FollowResult(Status status, UUID host) {
        public enum Status { OK, SELF, HAS_LISTENERS, ALREADY, DISABLED }
    }

    // ---------------------------------------------------------------- playback loop

    /**
     * Runs every server tick: decides which radios are running and who hears them, moves finished radios
     * to their next song, and hands the result to the audio thread, which plays the notes on time.
     */
    public void tick() {
        RadioConfig config = config();
        long now = System.nanoTime();
        List<AudioEngine.Stream> streams = new ArrayList<>();
        for (RadioSession session : List.copyOf(sessions.values())) {
            Playback playback = session.playback();
            if (playback == null) continue;
            boolean run = shouldRun(session, config);
            playback.setRunning(run, now);
            if (!run) continue;

            if (playback.finished(now)) {
                session.onSongFinished();
                playback = session.playback();
                if (playback == null) continue;
                playback.setRunning(true, now);
            }

            List<AudioEngine.Listener> listeners = new ArrayList<>(2);
            for (Player player : audience(session)) {
                RadioSession own = sessions.get(player.getUniqueId());
                int volume = own == null ? config.defaultVolume() : own.settings().volume();
                if (volume > 0) listeners.add(AudioEngine.Listener.of(player, volume / 100f));
            }
            // Radios nobody hears still keep time, so listeners who tune in join mid-song.
            streams.add(new AudioEngine.Stream(playback, listeners));
        }
        audio.publish(streams, config.soundCategory(), config.stereo());
    }

    private boolean shouldRun(RadioSession session, RadioConfig config) {
        if (session.isPaused()) return false;
        if (session == station) return true;
        // A player tuned in elsewhere hears that; their own radio waits where it is.
        if (following.containsKey(session.owner())) return false;
        // A personal radio also waits while its player is in a world without music.
        Player owner = Bukkit.getPlayer(session.owner());
        return owner != null && config.worldEnabled(owner.getWorld());
    }

    /** Everyone who hears this session right now: the owner plus anyone tuned in, in worlds with music. */
    public List<Player> audience(RadioSession session) {
        RadioConfig config = config();
        List<Player> players = new ArrayList<>(2);
        Player owner = Bukkit.getPlayer(session.owner());
        if (owner != null && !following.containsKey(owner.getUniqueId())) players.add(owner);
        for (UUID listener : listenersOf(session.owner())) {
            Player player = Bukkit.getPlayer(listener);
            if (player != null) players.add(player);
        }
        players.removeIf(p -> !config.worldEnabled(p.getWorld()));
        return players;
    }

    void announce(RadioSession session, Song song, boolean manual) {
        if (!manual && !config().announceSongs()) return;
        for (Player player : audience(session)) {
            messages().send(player, "playback.now-playing", Format.song(song));
        }
    }
}
