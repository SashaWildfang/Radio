/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.song;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * An immutable set of loaded songs, sorted by title. A reload builds a new library and swaps it in,
 * so sessions that are mid-song are never disturbed.
 *
 * <p>Songs directly in the song folder go in the {@value #DEFAULT_CATEGORY} category; songs in a subfolder
 * (at any depth) take the name of the top-level subfolder as their category.
 */
public final class SongLibrary {

    public static final String DEFAULT_CATEGORY = "Other";
    public static final SongLibrary EMPTY = new SongLibrary(List.of());

    private final List<Song> songs;
    private final Map<String, Song> byId = new HashMap<>();
    private final Map<String, Integer> indexById = new HashMap<>();
    /** Category name → its songs, categories in alphabetical order with "Other" last. */
    private final Map<String, List<Song>> categories;

    private SongLibrary(List<Song> songs) {
        this.songs = List.copyOf(songs);
        Map<String, List<Song>> byCategory = new TreeMap<>(Comparator
                .comparing((String c) -> c.equals(DEFAULT_CATEGORY))
                .thenComparing(String.CASE_INSENSITIVE_ORDER));
        for (int i = 0; i < this.songs.size(); i++) {
            Song song = this.songs.get(i);
            byId.put(song.id(), song);
            indexById.put(song.id(), i);
            byCategory.computeIfAbsent(song.category(), c -> new ArrayList<>()).add(song);
        }
        Map<String, List<Song>> frozen = new LinkedHashMap<>();
        byCategory.forEach((category, list) -> frozen.put(category, List.copyOf(list)));
        this.categories = frozen;
    }

    /**
     * Reads every .nbs file under {@code folder}, keeping songs between {@code minSeconds} and {@code maxSeconds}
     * long (0 = no limit). Blocking: call off the main thread.
     */
    public static SongLibrary load(Path folder, Logger logger, int minSeconds, int maxSeconds) {
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not create song folder " + folder, e);
            return EMPTY;
        }

        List<Path> files;
        try (Stream<Path> stream = Files.walk(folder)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nbs"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not read song folder " + folder, e);
            return EMPTY;
        }

        List<Song> songs = new ArrayList<>(files.size());
        Map<String, Path> seen = new HashMap<>();
        int outsideLimits = 0;
        for (Path file : files) {
            Path relative = folder.relativize(file);
            String category = relative.getNameCount() > 1 ? relative.getName(0).toString() : DEFAULT_CATEGORY;
            try {
                Song song = NbsDecoder.decode(file, category);
                int seconds = song.durationSeconds();
                if ((minSeconds > 0 && seconds < minSeconds) || (maxSeconds > 0 && seconds > maxSeconds)) {
                    outsideLimits++;
                    continue;
                }
                Path duplicate = seen.putIfAbsent(song.id(), relative);
                if (duplicate != null) {
                    logger.warning("Skipping " + relative + ": a song with the same file name is already loaded from " + duplicate);
                    continue;
                }
                songs.add(song);
            } catch (IOException | RuntimeException e) {
                logger.warning("Skipping " + relative + ": " + e.getMessage());
            }
        }
        if (outsideLimits > 0) {
            logger.info("Skipped " + outsideLimits + " song(s) outside the length limits in config.yml (songs.min-length / max-length).");
        }
        songs.sort(Comparator.comparing((Song s) -> s.title().toLowerCase(Locale.ROOT)).thenComparing(Song::id));
        return new SongLibrary(songs);
    }

    public List<Song> songs() {
        return songs;
    }

    public int size() {
        return songs.size();
    }

    public boolean isEmpty() {
        return songs.isEmpty();
    }

    public Song get(int index) {
        return songs.get(index);
    }

    public Song byId(String id) {
        return byId.get(id);
    }

    public boolean contains(Song song) {
        return song != null && byId.get(song.id()) == song;
    }

    /** Position of a song (matched by id, so it also works across reloads), or -1. */
    public int indexOf(Song song) {
        return song == null ? -1 : indexById.getOrDefault(song.id(), -1);
    }

    /** Category name → songs in it. */
    public Map<String, List<Song>> categories() {
        return categories;
    }

    /**
     * Finds songs matching a search over title, artist and file name. An exact title/file name match wins
     * outright; otherwise every song whose title or file name starts with the query, then every song
     * containing it anywhere (artist included).
     */
    public List<Song> search(String query) {
        String q = normalize(query);
        if (q.isEmpty()) return List.of();

        for (Song song : songs) {
            if (normalize(song.title()).equals(q) || normalize(song.id()).equals(q)) return List.of(song);
        }
        List<Song> prefix = songs.stream()
                .filter(s -> normalize(s.title()).startsWith(q) || normalize(s.id()).startsWith(q))
                .toList();
        if (!prefix.isEmpty()) return prefix;
        return songs.stream().filter(s -> matches(s, q)).toList();
    }

    /** Whether a song's title, artist or file name contains an already-{@link #normalize normalized} query. */
    public static boolean matches(Song song, String normalizedQuery) {
        return normalize(song.title()).contains(normalizedQuery)
                || normalize(song.author()).contains(normalizedQuery)
                || normalize(song.id()).contains(normalizedQuery);
    }

    /**
     * Lower-case, accents removed, any run of non-alphanumerics collapsed to one space.
     * "Für_Elise" → "fur elise".
     */
    public static String normalize(String input) {
        String plain = Normalizer.normalize(input, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
