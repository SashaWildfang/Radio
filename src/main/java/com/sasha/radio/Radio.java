/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio;

import com.sasha.radio.command.RadioCommands;
import com.sasha.radio.config.Messages;
import com.sasha.radio.config.RadioConfig;
import com.sasha.radio.display.NowPlayingBar;
import com.sasha.radio.download.SongDownloader;
import com.sasha.radio.gui.MenuService;
import com.sasha.radio.hook.RadioExpansion;
import com.sasha.radio.listener.ConnectionListener;
import com.sasha.radio.session.RadioSession;
import com.sasha.radio.session.SessionManager;
import com.sasha.radio.song.SongLibrary;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Radio: a lobby radio that plays note block songs, with a GUI, categories, a shared lobby station and a
 * now-playing bar.
 *
 * <p>Built by Sasha Wildfang for Fractal Network.
 */
public final class Radio extends JavaPlugin {

    private RadioConfig config;
    private Messages messages;
    private volatile SongLibrary library = SongLibrary.EMPTY;
    private SessionManager sessions;
    private MenuService menus;
    private NowPlayingBar nowPlayingBar;
    private SongDownloader downloader;
    private Object placeholderExpansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        sessions = new SessionManager(this);
        sessions.applyStationConfig();
        menus = new MenuService(this);
        nowPlayingBar = new NowPlayingBar(this);
        downloader = new SongDownloader(getLogger(), getPluginMeta().getVersion());

        getServer().getPluginManager().registerEvents(new ConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(menus, this);

        RadioCommands commands = new RadioCommands(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> commands.register(event.registrar()));

        getServer().getScheduler().runTaskTimer(this, sessions::tick, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, nowPlayingBar::update, 10L, 10L);
        getServer().getScheduler().runTaskTimer(this, menus::refreshOpenMenus, 20L, 20L);

        // Players already online (plugin loaded at runtime) get sessions straight away.
        getServer().getOnlinePlayers().forEach(sessions::session);
        hookPlaceholderApi();

        loadSongs(loaded -> {
            if (config.downloadsEnabled() && config.downloadOnStartup()) downloadSongs(null);
        });
    }

    @Override
    public void onDisable() {
        if (placeholderExpansion != null) ((RadioExpansion) placeholderExpansion).unregister();
        if (menus != null) menus.closeAll();
        if (nowPlayingBar != null) nowPlayingBar.shutdown();
        if (sessions != null) sessions.shutdown();
    }

    public RadioConfig radioConfig() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public SongLibrary library() {
        return library;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public MenuService menus() {
        return menus;
    }

    public NowPlayingBar nowPlayingBar() {
        return nowPlayingBar;
    }

    /** Reloads config.yml, messages.yml and the song folder. {@code done} runs on the main thread once songs are loaded. */
    public void reload(Consumer<SongLibrary> done) {
        menus.closeAll();
        reloadConfig();
        loadSettings();
        sessions.applyStationConfig();
        loadSongs(done);
    }

    /**
     * Fetches new or changed songs from the repository, then reloads the library if anything changed.
     * {@code done} runs on the main thread with either a result or an error.
     */
    public void downloadSongs(BiConsumer<SongDownloader.Result, Exception> done) {
        String repository = config.repository();
        Path folder = songFolder();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            SongDownloader.Result result = null;
            Exception error = null;
            try {
                result = downloader.sync(repository, folder);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = e;
            } catch (IOException | RuntimeException e) {
                error = e;
            }
            if (!isEnabled()) return;
            SongDownloader.Result finalResult = result;
            Exception finalError = error;
            getServer().getScheduler().runTask(this, () -> {
                if (finalError != null) {
                    getLogger().warning("Song download failed: " + finalError.getMessage());
                } else {
                    getLogger().info("Song download: " + finalResult.added() + " new, " + finalResult.updated()
                            + " updated, " + finalResult.removed() + " removed, " + finalResult.failed() + " failed, " + finalResult.total() + " in the repository.");
                    if (finalResult.changed()) loadSongs(null);
                }
                if (done != null) done.accept(finalResult, finalError);
            });
        });
    }

    private void loadSettings() {
        config = RadioConfig.load(getConfig(), getLogger());
        messages = Messages.load(this);
    }

    private Path songFolder() {
        return getDataFolder().toPath().resolve("songs");
    }

    /** Decodes songs off the main thread, then swaps the new library in. Songs already playing keep going. */
    private void loadSongs(Consumer<SongLibrary> done) {
        Path folder = songFolder();
        int minSeconds = config.minSongSeconds();
        int maxSeconds = config.maxSongSeconds();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            SongLibrary loaded = SongLibrary.load(folder, getLogger(), minSeconds, maxSeconds);
            if (!isEnabled()) return;
            getServer().getScheduler().runTask(this, () -> {
                library = loaded;
                getLogger().info("Loaded " + loaded.size() + " song(s) in " + loaded.categories().size() + " categories.");
                if (loaded.isEmpty()) {
                    getLogger().warning("No songs found. Put .nbs files in " + folder + " and run /radio reload"
                            + (config.downloadsEnabled() ? ", or /radio download." : "."));
                }
                startIdleRadios();
                if (done != null) done.accept(loaded);
            });
        });
    }

    /** After songs load: start the station, and personal radios for online players if they autoplay. */
    private void startIdleRadios() {
        RadioSession station = sessions.station();
        if (station != null) station.startIfIdle();
        if (config.onJoin() != RadioConfig.OnJoin.PERSONAL) return;
        for (Player player : getServer().getOnlinePlayers()) {
            if (sessions.hostOf(player.getUniqueId()) == null) sessions.session(player).startIfIdle();
        }
    }

    private void hookPlaceholderApi() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) return;
        RadioExpansion expansion = new RadioExpansion(this);
        if (expansion.register()) {
            placeholderExpansion = expansion;
            getLogger().info("Registered PlaceholderAPI placeholders (%radio_...%).");
        }
    }
}
