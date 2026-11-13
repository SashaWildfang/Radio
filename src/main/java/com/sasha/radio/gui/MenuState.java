/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.gui;

/** What a player is looking at in the menu. Kept between openings so /radio returns to the same place. */
public final class MenuState {

    public enum View { SONGS, CATEGORIES, QUEUE }

    View view = View.SONGS;
    /** Category filter, or null for every song. */
    String category;
    boolean favoritesOnly;
    /** Search filter, or null. */
    String search;
    int page;

    void showSongs() {
        view = View.SONGS;
        page = 0;
    }

    void clearFilters() {
        category = null;
        favoritesOnly = false;
        search = null;
    }

    public static MenuState search(String query) {
        MenuState state = new MenuState();
        state.search = query;
        return state;
    }
}
