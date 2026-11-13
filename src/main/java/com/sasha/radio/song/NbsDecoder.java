/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.song;

import org.bukkit.Sound;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Note Block Studio (.nbs) files: the legacy format and Open Note Block Studio versions 1-5.
 * Format reference: https://opennbs.org/nbs
 */
public final class NbsDecoder {

    /** Vanilla instruments in NBS order. Anything past this list is a custom instrument and is skipped. */
    private static final Sound[] INSTRUMENTS = {
            Sound.BLOCK_NOTE_BLOCK_HARP,
            Sound.BLOCK_NOTE_BLOCK_BASS,
            Sound.BLOCK_NOTE_BLOCK_BASEDRUM,
            Sound.BLOCK_NOTE_BLOCK_SNARE,
            Sound.BLOCK_NOTE_BLOCK_HAT,
            Sound.BLOCK_NOTE_BLOCK_GUITAR,
            Sound.BLOCK_NOTE_BLOCK_FLUTE,
            Sound.BLOCK_NOTE_BLOCK_BELL,
            Sound.BLOCK_NOTE_BLOCK_CHIME,
            Sound.BLOCK_NOTE_BLOCK_XYLOPHONE,
            Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE,
            Sound.BLOCK_NOTE_BLOCK_COW_BELL,
            Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO,
            Sound.BLOCK_NOTE_BLOCK_BIT,
            Sound.BLOCK_NOTE_BLOCK_BANJO,
            Sound.BLOCK_NOTE_BLOCK_PLING,
    };

    /** NBS keys 33..57 (F#3..F#5) are the two octaves a vanilla note block can play. */
    private static final int MIN_KEY = 33;
    private static final int MAX_KEY = 57;
    private static final int CENTER_KEY = 45;

    /** The client plays a limited number of sounds at once; more than this in one instant starts cutting notes. */
    private static final int MAX_NOTES_PER_TICK = 24;

    private NbsDecoder() {
    }

    public static Song decode(Path file, String category) throws IOException {
        String fileName = file.getFileName().toString();
        String id = fileName.substring(0, fileName.length() - ".nbs".length());
        try {
            return decode(id, category, Files.readAllBytes(file));
        } catch (BufferUnderflowException | NegativeArraySizeException e) {
            throw new IOException("File is truncated or not a valid .nbs file", e);
        }
    }

    private static Song decode(String id, String category, byte[] data) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // Header. A leading zero length marks the Open NBS format, which carries a version number.
        int length = unsignedShort(in);
        int version = 0;
        int vanillaInstruments = 10;
        if (length == 0) {
            version = unsigned(in);
            vanillaInstruments = unsigned(in);
            if (version >= 3) {
                length = unsignedShort(in);
            }
        }
        int layerCount = unsignedShort(in);
        readString(in); // song name (the file name is used instead)
        readString(in); // author (who made the note block version)
        String originalAuthor = readString(in);
        readString(in); // description
        float tempo = unsignedShort(in) / 100f;
        in.get();       // auto-saving enabled
        in.get();       // auto-saving duration
        in.get();       // time signature
        in.position(in.position() + 4 * 5); // minutes spent, left/right clicks, blocks added/removed
        readString(in); // imported file name
        if (version >= 4) {
            in.get();      // loop on/off
            in.get();      // max loop count
            in.getShort(); // loop start tick
        }

        // Notes are stored as jumps: tick jumps, and within each tick, layer jumps.
        List<RawNote> raw = new ArrayList<>();
        int tick = -1;
        int maxLayer = -1;
        for (int jump = unsignedShort(in); jump != 0; jump = unsignedShort(in)) {
            tick += jump;
            int layer = -1;
            for (int layerJump = unsignedShort(in); layerJump != 0; layerJump = unsignedShort(in)) {
                layer += layerJump;
                int instrument = unsigned(in);
                int key = unsigned(in);
                int velocity = 100;
                int panning = 100;
                int pitch = 0;
                if (version >= 4) {
                    velocity = unsigned(in);
                    panning = unsigned(in);
                    pitch = in.getShort();
                }
                raw.add(new RawNote(tick, layer, instrument, key, velocity, panning, pitch));
                maxLayer = Math.max(maxLayer, layer);
            }
        }

        // Layer volume/stereo. Some older tools omit this section, so it is optional.
        int[] layerVolume = new int[Math.max(layerCount, maxLayer + 1)];
        int[] layerPanning = new int[layerVolume.length];
        Arrays.fill(layerVolume, 100);
        Arrays.fill(layerPanning, 100);
        try {
            for (int i = 0; i < layerCount; i++) {
                readString(in); // layer name
                if (version >= 4) in.get(); // lock
                layerVolume[i] = unsigned(in);
                if (version >= 2) layerPanning[i] = unsigned(in);
            }
        } catch (BufferUnderflowException ignored) {
            // Missing layer section: keep the defaults.
        }

        if (tempo <= 0) tempo = 10f;
        int songLength = Math.max(length, tick + 1);
        if (songLength <= 0) throw new IOException("Song has no notes");

        List<List<Note>> byTick = new ArrayList<>(songLength);
        for (int i = 0; i < songLength; i++) byTick.add(null);
        int playable = 0;
        for (RawNote n : raw) {
            Note note = resolve(n, vanillaInstruments, layerVolume[n.layer], layerPanning[n.layer]);
            if (note == null) continue;
            List<Note> list = byTick.get(n.tick);
            if (list == null) byTick.set(n.tick, list = new ArrayList<>(4));
            list.add(note);
            playable++;
        }
        // Songs built on custom instruments (which need a resource pack) come out mostly silent.
        if (playable == 0 || playable < raw.size() / 2) {
            throw new IOException("only " + playable + " of " + raw.size() + " notes use vanilla instruments");
        }
        Note[][] ticks = new Note[songLength][];
        for (int i = 0; i < songLength; i++) {
            List<Note> list = byTick.get(i);
            if (list != null) ticks[i] = clean(list);
        }

        // "Original author" is the song's artist; "author" is usually whoever made the note block version.
        return new Song(id, category, originalAuthor, tempo, ticks);
    }

    /**
     * Tidies the notes of one tick so they sound clean on the client:
     * <ul>
     *   <li>Identical notes (same instrument and pitch) stacked on several layers, which arrangers use to
     *       make a part louder, become one note at the combined volume. Stacked copies only add clutter
     *       and use up the client's limited sound channels, which makes other notes cut out.</li>
     *   <li>At most {@value #MAX_NOTES_PER_TICK} notes play at once, keeping the loudest.</li>
     * </ul>
     */
    private static Note[] clean(List<Note> notes) {
        Map<NoteKey, Note> merged = new LinkedHashMap<>();
        for (Note note : notes) {
            NoteKey key = new NoteKey(note.sound(), note.pitch());
            Note existing = merged.get(key);
            if (existing == null) {
                merged.put(key, note);
                continue;
            }
            float volume = existing.volume() + note.volume();
            float pan = (existing.pan() * existing.volume() + note.pan() * note.volume()) / volume;
            merged.put(key, new Note(note.sound(), Math.min(1f, volume), note.pitch(), pan));
        }
        List<Note> result = new ArrayList<>(merged.values());
        if (result.size() > MAX_NOTES_PER_TICK) {
            result.sort((a, b) -> Float.compare(b.volume(), a.volume()));
            result = result.subList(0, MAX_NOTES_PER_TICK);
        }
        return result.toArray(Note[]::new);
    }

    private static Note resolve(RawNote n, int vanillaInstruments, int layerVolume, int layerPanning) {
        if (n.instrument >= vanillaInstruments || n.instrument >= INSTRUMENTS.length) {
            return null; // custom instrument: needs a resource pack, not supported
        }
        float volume = (layerVolume / 100f) * (n.velocity / 100f);
        if (volume <= 0) return null;

        // Fine pitch is in cents. Notes outside the note block range are moved by octaves into range.
        float key = n.key + n.pitch / 100f;
        while (key < MIN_KEY) key += 12;
        while (key > MAX_KEY) key -= 12;
        float pitch = (float) Math.pow(2.0, (key - CENTER_KEY) / 12.0);

        int pan = (n.panning - 100) + (layerPanning - 100);
        pan = Math.max(-100, Math.min(100, pan));

        return new Note(INSTRUMENTS[n.instrument], Math.min(1f, volume), pitch, pan / 100f);
    }

    private static int unsigned(ByteBuffer in) {
        return in.get() & 0xFF;
    }

    private static int unsignedShort(ByteBuffer in) {
        return in.getShort() & 0xFFFF;
    }

    private static String readString(ByteBuffer in) throws IOException {
        int length = in.getInt();
        if (length < 0 || length > in.remaining()) throw new IOException("Invalid string length " + length);
        byte[] bytes = new byte[length];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private record NoteKey(Sound sound, float pitch) {
    }

    private record RawNote(int tick, int layer, int instrument, int key, int velocity, int panning, int pitch) {
    }
}
