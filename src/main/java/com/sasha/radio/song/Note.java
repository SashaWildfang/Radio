/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.song;

import org.bukkit.Sound;

/**
 * A single note, already resolved to what the client needs to hear it.
 *
 * @param sound  the note block sound for the instrument
 * @param volume 0..1, layer volume multiplied by note velocity
 * @param pitch  client pitch multiplier (0.5..2.0)
 * @param pan    -1 (left) .. 0 (centre) .. 1 (right)
 */
public record Note(Sound sound, float volume, float pitch, float pan) {
}
