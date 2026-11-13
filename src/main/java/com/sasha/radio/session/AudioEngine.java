/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.session;

import com.sasha.radio.song.Note;
import com.sasha.radio.song.Playback;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plays notes on its own thread at their exact time (about 2 ms resolution), instead of rounding every
 * note to the server's 50 ms ticks. Rounding made any song whose tempo doesn't divide 20 evenly (most
 * of them: 6.25, 8.5, 7.5 ticks/s...) play with an uneven, stuttering rhythm.
 *
 * <p>The main thread decides what plays and who hears it, and publishes an immutable snapshot every
 * server tick; this thread only reads snapshots and sends sound packets.
 */
final class AudioEngine implements Runnable {

    private static final long RESOLUTION_NANOS = 2_000_000L;
    /** How far (in blocks) a fully panned note is placed to the side of the listener. */
    private static final double STEREO_DISTANCE = 2.0;

    /** One listener as of the last server tick. */
    record Listener(Player player, float volume, World world, double x, double y, double z, double rightX, double rightZ) {
        static Listener of(Player player, float volume) {
            Location eye = player.getEyeLocation();
            double yaw = Math.toRadians(eye.getYaw());
            return new Listener(player, volume, eye.getWorld(), eye.getX(), eye.getY(), eye.getZ(), -Math.cos(yaw), -Math.sin(yaw));
        }
    }

    record Stream(Playback playback, List<Listener> listeners) {
    }

    private record Snapshot(List<Stream> streams, SoundCategory category, boolean stereo) {
    }

    private final Logger logger;
    private volatile Snapshot snapshot = new Snapshot(List.of(), SoundCategory.RECORDS, true);
    private volatile boolean running;
    private Thread thread;
    private boolean warned;

    AudioEngine(Logger logger) {
        this.logger = logger;
    }

    void start() {
        running = true;
        thread = new Thread(this, "Radio Audio");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    void stop() {
        running = false;
        snapshot = new Snapshot(List.of(), SoundCategory.RECORDS, true);
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Called by the main thread every server tick. */
    void publish(List<Stream> streams, SoundCategory category, boolean stereo) {
        snapshot = new Snapshot(List.copyOf(streams), category, stereo);
    }

    @Override
    public void run() {
        List<Note> due = new ArrayList<>();
        while (running) {
            Snapshot current = snapshot;
            long now = System.nanoTime();
            for (Stream stream : current.streams) {
                due.clear();
                stream.playback.drain(now, due);
                if (due.isEmpty()) continue;
                for (Listener listener : stream.listeners) play(listener, due, current);
            }
            LockSupport.parkNanos(RESOLUTION_NANOS);
        }
    }

    private void play(Listener listener, List<Note> notes, Snapshot current) {
        try {
            Player player = listener.player;
            if (!player.isOnline()) return;
            for (Note note : notes) {
                float volume = note.volume() * listener.volume;
                if (volume < 0.005f) continue;
                if (current.stereo && note.pan() != 0) {
                    double offset = note.pan() * STEREO_DISTANCE;
                    Location at = new Location(listener.world, listener.x + listener.rightX * offset, listener.y,
                            listener.z + listener.rightZ * offset);
                    player.playSound(at, note.sound(), current.category, volume, note.pitch());
                } else {
                    player.playSound(player, note.sound(), current.category, volume, note.pitch());
                }
            }
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                logger.log(Level.WARNING, "Could not play a note (further errors are hidden)", e);
            }
        }
    }
}
