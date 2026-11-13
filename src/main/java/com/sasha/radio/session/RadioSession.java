/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.session;

import com.sasha.radio.config.BarMode;
import com.sasha.radio.gui.SortMode;
import com.sasha.radio.song.Playback;
import com.sasha.radio.song.Song;
import com.sasha.radio.song.SongLibrary;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * One radio: what is playing, the queue, history and settings. Every online player has one, and so does
 * the lobby station. All methods run on the main thread.
 */
public final class RadioSession {

    private static final int HISTORY_LIMIT = 50;

    private final SessionManager manager;
    private final UUID owner;
    private final PlayerSettings settings;

    private Playback playback;
    private boolean paused;

    /** Songs the owner lined up to play next, in order. */
    private final List<Song> queue = new ArrayList<>();
    /** Songs played before the current one, most recent first. */
    private final Deque<Song> history = new ArrayDeque<>();
    /** The song that plays after the queue runs out, fixed once someone looks at it so "up next" never lies. */
    private Song upNext;
    /** Remaining song ids in the current shuffle round, so shuffle plays everything before repeating. */
    private final List<String> shuffleBag = new ArrayList<>();

    /** Which songs this radio picks from on its own (the station's categories). null = every song. */
    private Predicate<Song> pool;
    private SongLibrary poolLibrary;
    private List<Song> poolSongs = List.of();

    RadioSession(SessionManager manager, UUID owner, PlayerSettings settings) {
        this.manager = manager;
        this.owner = owner;
        this.settings = settings;
    }

    public UUID owner() {
        return owner;
    }

    public PlayerSettings settings() {
        return settings;
    }

    public Song current() {
        return playback == null ? null : playback.song();
    }

    public Playback playback() {
        return playback;
    }

    public boolean isPlaying() {
        return playback != null && !paused;
    }

    public boolean isPaused() {
        return playback != null && paused;
    }

    // ---------------------------------------------------------------- controls

    /** Plays a chosen song right now. */
    public void play(Song song) {
        queue.remove(song);
        remember(current());
        start(song, true);
    }

    /** Moves to the next song (queue first). Returns false when there are no songs. */
    public boolean skip() {
        Song next = takeNext();
        if (next == null) return false;
        remember(current());
        start(next, true);
        return true;
    }

    /** Goes back to the previous song, or restarts the current one when there is no history. */
    public void previous() {
        Song previous = history.poll();
        if (previous == null) {
            restart();
            return;
        }
        if (current() != null) queue.addFirst(current()); // skipping forward again returns here
        start(previous, true);
    }

    public void restart() {
        if (playback == null) return;
        playback.restart();
        paused = false;
    }

    public void pause() {
        if (playback == null) return;
        paused = true;
        playback.setRunning(false, System.nanoTime()); // silence now, not on the next tick
    }

    /** Resumes, or starts the next song when nothing is loaded. */
    public void resume() {
        if (playback == null) skip();
        else paused = false;
    }

    /** Starts a song if nothing is loaded yet (autoplay). A paused song stays paused. */
    public void startIfIdle() {
        if (playback != null) return;
        Song next = takeNext();
        if (next != null) start(next, true);
    }

    /** The song that will play after this one, or null if there are no songs. */
    public Song peekNext() {
        if (!queue.isEmpty()) return queue.getFirst();
        if (upNext == null || !manager.library().contains(upNext)) upNext = pickNext();
        return upNext;
    }

    // ---------------------------------------------------------------- queue

    public List<Song> queue() {
        return Collections.unmodifiableList(queue);
    }

    /** Adds a song to the end of the queue. Returns its 1-based position, or -1 when the queue is full. */
    public int enqueue(Song song) {
        if (queue.size() >= manager.config().queueLimit()) return -1;
        queue.add(song);
        return queue.size();
    }

    /** 1-based position of a song in the queue, or 0. */
    public int queuePosition(Song song) {
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).id().equals(song.id())) return i + 1;
        }
        return 0;
    }

    public Song removeFromQueue(int index) {
        return index >= 0 && index < queue.size() ? queue.remove(index) : null;
    }

    public void clearQueue() {
        queue.clear();
    }

    // ---------------------------------------------------------------- settings

    public void setVolume(int volume) {
        settings.volume(volume);
        save();
    }

    public void setShuffle(boolean shuffle) {
        if (settings.shuffle() == shuffle) return;
        settings.shuffle(shuffle);
        upNext = null;
        shuffleBag.clear();
        save();
    }

    public void setRepeat(boolean repeat) {
        settings.repeat(repeat);
        save();
    }

    public void setSort(SortMode sort) {
        settings.sort(sort);
        save();
    }

    public void setBar(BarMode bar) {
        settings.bar(bar);
        save();
    }

    /** Returns true if the song is now a favorite. */
    public boolean toggleFavorite(Song song) {
        boolean favorite = settings.toggleFavorite(song.id());
        save();
        return favorite;
    }

    // ---------------------------------------------------------------- internals

    void setPool(Predicate<Song> pool) {
        this.pool = pool;
        this.poolLibrary = null;
        this.upNext = null;
        this.shuffleBag.clear();
    }

    /** Called by the ticker when the current song reaches its end. */
    void onSongFinished() {
        if (settings.repeat()) {
            playback.restart();
            return;
        }
        Song next = takeNext();
        if (next == null) {
            stop();
            return;
        }
        remember(current());
        start(next, false);
    }

    void stop() {
        if (playback != null) playback.stop();
        playback = null;
        paused = false;
    }

    private void start(Song song, boolean manual) {
        if (playback != null) playback.stop(); // the audio thread may still hold it until the next tick
        playback = new Playback(song);
        paused = false;
        manager.announce(this, song, manual);
    }

    private Song takeNext() {
        if (!queue.isEmpty()) return queue.removeFirst();
        Song next = peekNext();
        upNext = null;
        return next;
    }

    private Song pickNext() {
        SongLibrary library = manager.library();
        List<Song> candidates = candidates(library);
        if (candidates.isEmpty()) return null;
        Song current = current();

        if (settings.shuffle() && candidates.size() > 1) {
            while (true) {
                if (shuffleBag.isEmpty()) refillShuffleBag(candidates, current);
                Song candidate = library.byId(shuffleBag.removeLast());
                if (candidate != null && (current == null || !candidate.id().equals(current.id()))) {
                    return candidate;
                }
            }
        }

        int index = -1; // nothing playing (or current song not in the pool) → start at the first song
        if (current != null) {
            for (int i = 0; i < candidates.size(); i++) {
                if (candidates.get(i).id().equals(current.id())) {
                    index = i;
                    break;
                }
            }
        }
        return candidates.get((index + 1) % candidates.size());
    }

    private List<Song> candidates(SongLibrary library) {
        if (pool == null) return library.songs();
        if (poolLibrary != library) {
            poolSongs = library.songs().stream().filter(pool).toList();
            poolLibrary = library;
        }
        return poolSongs;
    }

    private void refillShuffleBag(List<Song> candidates, Song current) {
        for (Song song : candidates) {
            if (current == null || !song.id().equals(current.id())) shuffleBag.add(song.id());
        }
        Collections.shuffle(shuffleBag);
    }

    private void remember(Song song) {
        if (song == null) return;
        history.push(song);
        while (history.size() > HISTORY_LIMIT) history.removeLast();
    }

    private void save() {
        Player player = Bukkit.getPlayer(owner);
        if (player != null) settings.save(player, manager.keys());
    }
}
