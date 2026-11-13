/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.song;

/**
 * An immutable, fully decoded note block song.
 * Ticks are song ticks (rows in Note Block Studio), played at {@link #tempo()} ticks per second.
 *
 * <p>The display name comes from the file name, written as {@code Artist - Title.nbs}. Names embedded in
 * .nbs files are unreliable (often blank, or the transcriber's name), so they are only a fallback for the artist.
 */
public final class Song {

    private static final Note[] NO_NOTES = new Note[0];

    private final String id;
    private final String category;
    private final String title;
    private final String artist;
    private final float tempo;
    private final Note[][] ticks;

    Song(String id, String category, String embeddedArtist, float tempo, Note[][] ticks) {
        this.id = id;
        this.category = category;
        this.tempo = tempo;
        this.ticks = ticks;

        String name = clean(id);
        int split = name.indexOf(" - ");
        if (split > 0) {
            this.artist = name.substring(0, split).strip();
            this.title = name.substring(split + 3).strip();
        } else {
            String fallback = clean(embeddedArtist == null ? "" : embeddedArtist);
            this.artist = fallback.isEmpty() ? "Unknown" : fallback;
            this.title = name;
        }
    }

    /** Underscores to spaces, any run of whitespace collapsed to one space. */
    private static String clean(String text) {
        return text.replace('_', ' ').replaceAll("\\s+", " ").strip();
    }

    /** The file name without the .nbs extension. Unique within a library; used for favorites. */
    public String id() {
        return id;
    }

    /** The folder the song is in, e.g. "Christmas". */
    public String category() {
        return category;
    }

    public String title() {
        return title;
    }

    public String author() {
        return artist;
    }

    /** Song ticks per second. */
    public float tempo() {
        return tempo;
    }

    /** Length in song ticks. */
    public int length() {
        return ticks.length;
    }

    public int durationSeconds() {
        return (int) Math.ceil(ticks.length / tempo);
    }

    Note[] notesAt(int tick) {
        Note[] notes = ticks[tick];
        return notes == null ? NO_NOTES : notes;
    }

    @Override
    public String toString() {
        return "Song[" + category + "/" + id + "]";
    }
}
