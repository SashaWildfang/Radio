/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.listener;

import com.sasha.radio.Radio;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ConnectionListener implements Listener {

    private final Radio plugin;

    public ConnectionListener(Radio plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        var session = plugin.sessions().session(player);
        switch (plugin.radioConfig().onJoin()) {
            case PERSONAL -> session.startIfIdle();
            case STATION -> {
                if (player.hasPermission("radio.station")) plugin.sessions().tuneInStation(player);
                else session.startIfIdle();
            }
            case NONE -> {
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.menus().forget(player);
        plugin.nowPlayingBar().remove(player);
        plugin.sessions().remove(player);
    }
}
