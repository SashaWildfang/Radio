/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.song;

import java.util.List;

/**
 * Playback position within one song, driven by the wall clock rather than server ticks so notes land at
 * their exact time whatever the song's tempo. Read by the audio thread and controlled from the main
 * thread, so every method is synchronized (each holds the lock for microseconds).
 */
public final class Playback {

    /** Silence after the last note so it can ring out before the next song starts. */
    private static final double TAIL_SECONDS = 1.0;
    /** Notes later than this are skipped instead of played in a burst (after lag or a resume). */
    private static final double MAX_LATENESS_SECONDS = 0.25;

    private final Song song;
    /** nanoTime at which song tick 0 would play, shifted forward by every pause. */
    private long anchor;
    private long pausedAt;
    private boolean running;
    private boolean stopped;
    private int nextTick;

    public Playback(Song song) {
        this.song = song;
        this.anchor = System.nanoTime();
        this.pausedAt = anchor;
    }

    public Song song() {
        return song;
    }

    /** Starts or pauses the clock. Called every server tick by the session manager. */
    public synchronized void setRunning(boolean run, long now) {
        if (stopped || run == running) return;
        if (run) anchor += now - pausedAt;
        else pausedAt = now;
        running = run;
    }

    /** Permanently silences this playback (it was replaced or ended). */
    public synchronized void stop() {
        if (running) pausedAt = System.nanoTime();
        running = false;
        stopped = true;
    }

    public synchronized void restart() {
        long now = System.nanoTime();
        anchor = now;
        pausedAt = now;
        nextTick = 0;
    }

    /** Adds every note that is due by {@code now} to {@code out}. Called by the audio thread. */
    public synchronized void drain(long now, List<Note> out) {
        if (!running) return;
        int due = (int) Math.floor(position(now));
        int maxLate = (int) Math.ceil(song.tempo() * MAX_LATENESS_SECONDS);
        if (due - nextTick > maxLate) nextTick = due - maxLate;
        while (nextTick <= due && nextTick < song.length()) {
            for (Note note : song.notesAt(nextTick)) out.add(note);
            nextTick++;
        }
    }

    /** True once the song (plus its tail) has finished. */
    public synchronized boolean finished(long now) {
        return position(now) >= song.length() + song.tempo() * TAIL_SECONDS;
    }

    /** 0..1 through the song. */
    public synchronized double progress() {
        return Math.min(1.0, position(System.nanoTime()) / song.length());
    }

    public synchronized int elapsedSeconds() {
        return Math.min(song.durationSeconds(), (int) (position(System.nanoTime()) / song.tempo()));
    }

    /** Position in song ticks (fractional). */
    private double position(long now) {
        long at = running ? now : pausedAt;
        return Math.max(0, (at - anchor) / 1_000_000_000.0 * song.tempo());
    }
}
