/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.config;

import java.util.Locale;

/** Where the now-playing line is shown. */
public enum BarMode {
    BOSSBAR, ACTIONBAR, OFF;

    public BarMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static BarMode parse(String value, BarMode fallback) {
        if (value != null) {
            for (BarMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) return mode;
            }
        }
        return fallback;
    }
}
