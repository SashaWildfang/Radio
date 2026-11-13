/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.util;

import com.sasha.radio.config.Messages;
import com.sasha.radio.song.Song;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

public final class Format {

    private Format() {
    }

    /** 234 → "3m 54s", 45 → "45s" */
    public static String duration(int seconds) {
        int minutes = seconds / 60;
        return minutes == 0 ? (seconds % 60) + "s" : minutes + "m " + (seconds % 60) + "s";
    }

    /** &lt;title&gt; &lt;artist&gt; &lt;length&gt; &lt;category&gt; for a song. */
    public static TagResolver song(Song song) {
        return TagResolver.resolver(
                Messages.arg("title", song.title()),
                Messages.arg("artist", song.author()),
                Messages.arg("length", duration(song.durationSeconds())),
                Messages.arg("category", song.category()));
    }
}
