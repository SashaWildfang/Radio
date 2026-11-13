/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.command;

import com.sasha.radio.Radio;
import com.sasha.radio.config.BarMode;
import com.sasha.radio.config.Messages;
import com.sasha.radio.gui.MenuState;
import com.sasha.radio.session.RadioSession;
import com.sasha.radio.session.SessionManager;
import com.sasha.radio.song.Song;
import com.sasha.radio.song.SongLibrary;
import com.sasha.radio.util.Format;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Every radio command. Each action is a subcommand of /radio (e.g. /radio skip) and most also have a short
 * top-level command (/skip) so existing habits keep working.
 */
public final class RadioCommands {

    private static final String USE = "radio.use";
    private static final String LISTEN_WITH = "radio.listenwith";
    private static final String STATION = "radio.station";
    private static final String ADMIN = "radio.admin";

    private final Radio plugin;
    private final Map<String, Sub> subcommands = new LinkedHashMap<>();

    public RadioCommands(Radio plugin) {
        this.plugin = plugin;

        add("play", "[song]", USE, player(this::play), this::suggestSongs, "play");
        add("pause", "", USE, player(this::pause), null, "pause", "pauseradio", "stopradio");
        add("resume", "", USE, player(this::resume), null, "resume", "resumeradio", "startradio", "listen");
        add("skip", "", USE, player(this::skip), null, "skip");
        add("previous", "", USE, player(this::previous), null, "lastsong");
        add("next", "", USE, player(this::next), null, "nextsong");
        add("nowplaying", "", USE, player(this::nowPlaying), null, "songinfo", "playing");
        add("queue", "[song|clear]", USE, player(this::queue), this::suggestQueue, "queue");
        add("favorite", "", USE, player(this::favorite), null);
        add("search", "<query>", USE, player(this::search), null);
        add("shuffle", "[on|off]", USE, player(this::shuffle), RadioCommands::suggestOnOff, "shuffle");
        add("repeat", "[on|off]", USE, player(this::repeat), RadioCommands::suggestOnOff, "repeat");
        add("volume", "[0-100]", USE, player(this::volume), null, "volume");
        add("bar", "[bossbar|actionbar|off]", USE, player(this::bar), RadioCommands::suggestBar);
        add("station", "[join|leave|skip]", STATION, player(this::station), this::suggestStation);
        add("listenwith", "<player>", LISTEN_WITH, player(this::listenWith), RadioCommands::suggestPlayers, "listenwith");
        add("stoplistening", "", LISTEN_WITH, player(this::stopListening), null, "stoplistening");
        add("reload", "", ADMIN, this::reload, null);
        add("download", "", ADMIN, this::download, null);
        add("help", "", USE, (sender, args) -> help(sender), null);
    }

    private void add(String name, String usage, String permission, BiConsumer<CommandSender, String[]> handler,
                     Suggester suggester, String... shortcuts) {
        subcommands.put(name, new Sub(name, usage, permission, handler, suggester, List.of(shortcuts)));
    }

    /**
     * Registers /radio with every subcommand, plus the short commands. Arguments are one "rest of the line"
     * argument, so completions can be whole song titles with spaces in them.
     */
    public void register(Commands commands) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("radio")
                .requires(source -> source.getSender().hasPermission(USE))
                .executes(context -> {
                    CommandSender sender = context.getSource().getSender();
                    if (sender instanceof Player player) openMenu(player);
                    else help(sender);
                    return Command.SINGLE_SUCCESS;
                });
        for (Sub sub : subcommands.values()) root.then(node(sub.name, sub));
        commands.register(root.build(), "Open the radio or control it", List.of());

        for (Sub sub : subcommands.values()) {
            if (sub.shortcuts.isEmpty()) continue;
            commands.register(node(sub.shortcuts.getFirst(), sub).build(), "Radio: " + sub.name,
                    sub.shortcuts.subList(1, sub.shortcuts.size()));
        }
    }

    // ---------------------------------------------------------------- plumbing

    private LiteralArgumentBuilder<CommandSourceStack> node(String literal, Sub sub) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(literal)
                .requires(source -> source.getSender().hasPermission(sub.permission))
                .executes(context -> {
                    sub.handler.accept(context.getSource().getSender(), new String[0]);
                    return Command.SINGLE_SUCCESS;
                });
        if (sub.usage.isEmpty()) return node;
        return node.then(Commands.argument("args", StringArgumentType.greedyString())
                .suggests((context, builder) -> {
                    if (sub.suggester != null) {
                        for (Suggestion suggestion : sub.suggester.suggest(context.getSource().getSender(), builder.getRemaining())) {
                            if (suggestion.tooltip == null) builder.suggest(suggestion.text);
                            else builder.suggest(suggestion.text, new LiteralMessage(suggestion.tooltip));
                        }
                    }
                    return builder.buildFuture();
                })
                .executes(context -> {
                    String args = StringArgumentType.getString(context, "args").strip();
                    sub.handler.accept(context.getSource().getSender(), args.isEmpty() ? new String[0] : args.split("\\s+"));
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private record Sub(String name, String usage, String permission,
                       BiConsumer<CommandSender, String[]> handler,
                       Suggester suggester,
                       List<String> shortcuts) {
    }

    /** A completion: the text to insert and an optional hover tooltip. */
    private record Suggestion(String text, String tooltip) {
    }

    /** Completions for the argument text typed so far (which may contain spaces). */
    @FunctionalInterface
    private interface Suggester {
        List<Suggestion> suggest(CommandSender sender, String typed);
    }

    @FunctionalInterface
    private interface PlayerHandler {
        void run(Player player, String[] args);
    }

    private BiConsumer<CommandSender, String[]> player(PlayerHandler handler) {
        return (sender, args) -> {
            if (sender instanceof Player player) handler.run(player, args);
            else messages().send(sender, "general.players-only");
        };
    }

    private Messages messages() {
        return plugin.messages();
    }

    private SessionManager sessions() {
        return plugin.sessions();
    }

    /** The player's own session, or null (with a message) while they're tuned in to someone else's radio. */
    private RadioSession ownRadio(Player player) {
        UUID host = sessions().hostOf(player.getUniqueId());
        if (host != null) {
            messages().send(player, "listen.blocked", Messages.arg("name", sessions().hostName(host)));
            return null;
        }
        return sessions().session(player);
    }

    private boolean requireSongs(CommandSender sender) {
        if (!plugin.library().isEmpty()) return true;
        messages().send(sender, "general.no-songs");
        return false;
    }

    /** Finds exactly one song for a search, telling the player when there are none or too many. */
    private Song findSong(Player player, String query) {
        List<Song> matches = plugin.library().search(query);
        if (matches.isEmpty()) {
            messages().send(player, "playback.song-not-found", Messages.arg("query", query));
            return null;
        }
        if (matches.size() > 1) {
            String names = String.join(", ", matches.stream().limit(5).map(Song::title).toList())
                    + (matches.size() > 5 ? ", …" : "");
            messages().send(player, "playback.song-ambiguous",
                    Messages.arg("count", matches.size()), Messages.arg("songs", names));
            return null;
        }
        return matches.getFirst();
    }

    // ---------------------------------------------------------------- handlers

    private void openMenu(Player player) {
        if (!player.hasPermission(USE)) return;
        if (!plugin.radioConfig().worldEnabled(player.getWorld())) {
            messages().send(player, "general.world-disabled");
            return;
        }
        plugin.menus().open(player);
    }

    private void play(Player player, String[] args) {
        RadioSession session = ownRadio(player);
        if (session == null || !requireSongs(player)) return;
        if (args.length == 0) {
            if (session.isPaused()) {
                session.resume();
                messages().send(player, "playback.resumed");
            } else if (session.current() == null) {
                session.skip();
            } else {
                messages().send(player, "playback.already-playing");
            }
            return;
        }
        Song song = findSong(player, String.join(" ", args));
        if (song != null) session.play(song);
    }

    private void pause(Player player, String[] args) {
        RadioSession session = ownRadio(player);
        if (session == null) return;
        if (session.current() == null) {
            messages().send(player, "playback.nothing-playing");
        } else if (session.isPaused()) {
            messages().send(player, "playback.already-paused");
        } else {
            session.pause();
            messages().send(player, "playback.paused");
        }
    }

    private void resume(Player player, String[] args) {
        RadioSession session = ownRadio(player);
        if (session == null) return;
        if (session.isPlaying()) {
            messages().send(player, "playback.already-playing");
        } else if (session.isPaused()) {
            session.resume();
            messages().send(player, "playback.resumed");
        } else if (requireSongs(player)) {
            session.skip();
        }
    }

    private void skip(Player player, String[] args) {
        RadioSession session = ownRadio(player);
        if (session != null && requireSongs(player)) session.skip();
    }

    private void previous(Player player, String[] args) {
        RadioSession session = ownRadio(player);
        if (session == null) return;
        if (session.current() == null) messages().send(player, "playback.nothing-playing");
        else session.previous();
    }

    private void next(Player player, String[] args) {
        Song next = sessions().audibleSession(player).peekNext();
        if (next == null) messages().send(player, "general.no-songs");
        else messages().send(player, "playback.up-next", Format.song(next));
    }

    private void nowPlaying(Player player, String[] args) {
        RadioSession session = sessions().audibleSession(player);
        Song song = session.current();
        if (song == null) {
            messages().send(player, "playback.nothing-playing");
            return;
        }
        UUID host = sessions().hostOf(player.getUniqueId());
        if (host != null) messages().send(player, "playback.info-listening", Messages.arg("name", sessions().hostName(host)));
        messages().send(player, "playback.info", TagResolver.resolver(
                Format.song(song),
                Messages.arg("elapsed", Format.duration(session.playback().elapsedSeconds())),
                Messages.arg("bar", messages().progressBar(session.playback().progress())),
                Messages.arg("state", session.isPaused() ? messages().component("playback.info-paused")
                        : net.kyori.adventure.text.Component.empty())));
    }

    private void queue(Player player, String[] args) {
        RadioSession session = sessions().session(player);
        if (args.length == 0) {
            List<Song> queue = session.queue();
            if (queue.isEmpty()) {
                messages().send(player, "queue.empty");
                return;
            }
            messages().send(player, "queue.header", Messages.arg("count", queue.size()));
            for (int i = 0; i < queue.size(); i++) {
                messages().send(player, "queue.line", Format.song(queue.get(i)), Messages.arg("position", i + 1));
            }
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("clear")) {
            session.clearQueue();
            messages().send(player, "queue.cleared");
            return;
        }
        if (!requireSongs(player)) return;
        Song song = findSong(player, String.join(" ", args));
        if (song == null) return;
        int position = session.enqueue(song);
        if (position < 0) messages().send(player, "queue.full", Messages.arg("limit", plugin.radioConfig().queueLimit()));
        else messages().send(player, "queue.added", Format.song(song), Messages.arg("position", position));
    }

    private void favorite(Player player, String[] args) {
        Song song = sessions().audibleSession(player).current();
        if (song == null) {
            messages().send(player, "playback.nothing-playing");
            return;
        }
        boolean favorite = sessions().session(player).toggleFavorite(song);
        messages().send(player, favorite ? "favorites.added" : "favorites.removed", Format.song(song));
    }

    private void search(Player player, String[] args) {
        if (args.length == 0) {
            openMenu(player);
            return;
        }
        if (!plugin.radioConfig().worldEnabled(player.getWorld())) {
            messages().send(player, "general.world-disabled");
            return;
        }
        plugin.menus().open(player, MenuState.search(String.join(" ", args)));
    }

    private void shuffle(Player player, String[] args) {
        RadioSession session = ownRadio(player);
        if (session == null) return;
        Boolean value = parseToggle(player, args, session.settings().shuffle(), "shuffle");
        if (value == null) return;
        session.setShuffle(value);
        messages().send(player, "playback.shuffle", Messages.arg("state", messages().onOff(value)));
    }

    private void repeat(Player player, String[] args) {
        RadioSession session = ownRadio(player);
        if (session == null) return;
        Boolean value = parseToggle(player, args, session.settings().repeat(), "repeat");
        if (value == null) return;
        session.setRepeat(value);
        messages().send(player, "playback.repeat", Messages.arg("state", messages().onOff(value)));
    }

    /** No argument flips the current value; "on"/"off" set it. Null means bad input (already reported). */
    private Boolean parseToggle(Player player, String[] args, boolean current, String command) {
        if (args.length == 0) return !current;
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes" -> true;
            case "off", "false", "no" -> false;
            default -> {
                messages().send(player, "playback.usage-toggle", Messages.arg("command", command));
                yield null;
            }
        };
    }

    /** Volume is personal, so it can be changed while listening along too. */
    private void volume(Player player, String[] args) {
        RadioSession session = sessions().session(player);
        if (args.length == 0) {
            messages().send(player, "playback.volume-current", Messages.arg("volume", session.settings().volume()));
            return;
        }
        int volume;
        try {
            volume = Integer.parseInt(args[0].replace("%", ""));
        } catch (NumberFormatException e) {
            volume = -1;
        }
        if (volume < 0 || volume > 100) {
            messages().send(player, "playback.volume-invalid");
            return;
        }
        session.setVolume(volume);
        messages().send(player, "playback.volume-set", Messages.arg("volume", volume));
    }

    private void bar(Player player, String[] args) {
        RadioSession session = sessions().session(player);
        BarMode mode = args.length == 0
                ? session.settings().bar(plugin.radioConfig()).next()
                : BarMode.parse(args[0], null);
        if (mode == null) {
            messages().send(player, "general.unknown-command");
            return;
        }
        session.setBar(mode);
        messages().send(player, "bar.changed", Messages.arg("mode", messages().component("bar.modes." + mode.key())));
    }

    private void station(Player player, String[] args) {
        String action = args.length == 0 ? "toggle" : args[0].toLowerCase(Locale.ROOT);
        boolean tunedIn = SessionManager.STATION_ID.equals(sessions().hostOf(player.getUniqueId()));
        switch (action) {
            case "join" -> sessions().tuneInStation(player);
            case "leave" -> {
                if (tunedIn) sessions().leave(player);
                else messages().send(player, "listen.not-listening");
            }
            case "skip" -> {
                if (!player.hasPermission(ADMIN)) {
                    messages().send(player, "general.unknown-command");
                } else if (sessions().station() == null) {
                    messages().send(player, "station.disabled");
                } else {
                    sessions().station().skip();
                    messages().send(player, "station.skipped");
                }
            }
            case "toggle" -> {
                if (tunedIn) sessions().leave(player);
                else sessions().tuneInStation(player);
            }
            default -> messages().send(player, "general.unknown-command");
        }
    }

    private void listenWith(Player player, String[] args) {
        if (args.length != 1) {
            messages().send(player, "general.unknown-command");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !player.canSee(target)) {
            messages().send(player, "general.player-not-found", Messages.arg("name", args[0]));
            return;
        }

        UUID previous = sessions().hostOf(player.getUniqueId());
        SessionManager.FollowResult result = sessions().follow(player, target);
        var hostName = sessions().hostName(result.host());
        switch (result.status()) {
            case SELF -> messages().send(player, "listen.self");
            case HAS_LISTENERS -> messages().send(player, "listen.has-listeners");
            case DISABLED -> messages().send(player, "station.disabled");
            case ALREADY -> messages().send(player, "listen.already", Messages.arg("name", hostName));
            case OK -> {
                if (previous != null && !previous.equals(result.host()) && !SessionManager.STATION_ID.equals(previous)) {
                    Player oldHost = Bukkit.getPlayer(previous);
                    if (oldHost != null) messages().send(oldHost, "listen.notify-host-stopped", Messages.arg("name", player.getName()));
                }
                if (SessionManager.STATION_ID.equals(result.host())) {
                    messages().send(player, "listen.joined-their-host",
                            Messages.arg("target", target.getName()), Messages.arg("name", hostName));
                    return;
                }
                if (!result.host().equals(target.getUniqueId())) {
                    messages().send(player, "listen.joined-their-host",
                            Messages.arg("target", target.getName()), Messages.arg("name", hostName));
                } else {
                    messages().send(player, "listen.started", Messages.arg("name", hostName));
                }
                Player host = Bukkit.getPlayer(result.host());
                if (host != null) messages().send(host, "listen.notify-host", Messages.arg("name", player.getName()));
            }
        }
    }

    private void stopListening(Player player, String[] args) {
        if (!sessions().leave(player)) messages().send(player, "listen.not-listening");
    }

    private void reload(CommandSender sender, String[] args) {
        plugin.reload(library -> messages().send(sender, "admin.reloaded", Messages.arg("count", library.size())));
    }

    private void download(CommandSender sender, String[] args) {
        if (!plugin.radioConfig().downloadsEnabled()) {
            messages().send(sender, "admin.download-disabled");
            return;
        }
        messages().send(sender, "admin.download-started");
        plugin.downloadSongs((result, error) -> {
            if (error != null) {
                messages().send(sender, "admin.download-failed", Messages.arg("error", String.valueOf(error.getMessage())));
            } else {
                messages().send(sender, "admin.download-finished",
                        Messages.arg("new", result.added()), Messages.arg("updated", result.updated()),
                        Messages.arg("removed", result.removed()),
                        Messages.arg("failed", result.failed()), Messages.arg("total", result.total()));
            }
        });
    }

    private void help(CommandSender sender) {
        messages().send(sender, "general.help-header", Messages.arg("version", plugin.getPluginMeta().getVersion()));
        messages().send(sender, "general.help-line", Messages.arg("command", "radio"), Messages.arg("usage", ""),
                Messages.arg("description", messages().component("help.menu")));
        for (Sub sub : subcommands.values()) {
            if (sub.name.equals("help") || !sender.hasPermission(sub.permission)) continue;
            String label = sub.shortcuts.isEmpty() ? "radio " + sub.name : sub.shortcuts.getFirst();
            messages().send(sender, "general.help-line",
                    Messages.arg("command", label),
                    Messages.arg("usage", sub.usage.isEmpty() ? "" : " " + sub.usage),
                    Messages.arg("description", messages().component("help." + sub.name)));
        }
    }

    // ---------------------------------------------------------------- suggestions

    /** Up to this many completions are sent; the client only shows a handful at a time anyway. */
    private static final int MAX_SUGGESTIONS = 60;

    /** Plain word options (on/off, player names...) that start with what was typed. */
    private static List<Suggestion> words(Collection<String> options, String typed) {
        String prefix = typed.strip().toLowerCase(Locale.ROOT);
        if (prefix.contains(" ")) return List.of();
        return options.stream()
                .filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix))
                .map(o -> new Suggestion(o, null))
                .toList();
    }

    /**
     * Song titles exactly as players see them (spaces, punctuation and all), with the artist as a tooltip.
     * Titles that start with what was typed come first, then titles or artists that contain it. A title shared
     * by two songs is completed as "Artist - Title" so it still picks exactly one song.
     */
    private List<Suggestion> songSuggestions(String typed) {
        String query = SongLibrary.normalize(typed);
        List<Song> songs = plugin.library().songs();
        Map<String, Integer> titleCounts = new HashMap<>();
        for (Song song : songs) titleCounts.merge(SongLibrary.normalize(song.title()), 1, Integer::sum);

        List<Suggestion> starts = new ArrayList<>();
        List<Suggestion> contains = new ArrayList<>();
        for (Song song : songs) {
            String title = SongLibrary.normalize(song.title());
            boolean unique = titleCounts.get(title) == 1;
            String text = unique ? song.title() : song.author() + " - " + song.title();
            Suggestion suggestion = new Suggestion(text, song.author() + " · " + Format.duration(song.durationSeconds()));
            if (query.isEmpty() || title.startsWith(query) || SongLibrary.normalize(text).startsWith(query)) {
                starts.add(suggestion);
            } else if (SongLibrary.matches(song, query)) {
                contains.add(suggestion);
            }
        }
        starts.addAll(contains);
        return starts.size() > MAX_SUGGESTIONS ? starts.subList(0, MAX_SUGGESTIONS) : starts;
    }

    private List<Suggestion> suggestSongs(CommandSender sender, String typed) {
        return songSuggestions(typed);
    }

    private List<Suggestion> suggestQueue(CommandSender sender, String typed) {
        List<Suggestion> result = new ArrayList<>(words(List.of("clear"), typed));
        result.addAll(songSuggestions(typed));
        return result;
    }

    private List<Suggestion> suggestStation(CommandSender sender, String typed) {
        return words(sender.hasPermission(ADMIN) ? List.of("join", "leave", "skip") : List.of("join", "leave"), typed);
    }

    private static List<Suggestion> suggestOnOff(CommandSender sender, String typed) {
        return words(List.of("on", "off"), typed);
    }

    private static List<Suggestion> suggestBar(CommandSender sender, String typed) {
        return words(List.of("bossbar", "actionbar", "off"), typed);
    }

    private static List<Suggestion> suggestPlayers(CommandSender sender, String typed) {
        return words(Bukkit.getOnlinePlayers().stream()
                .filter(p -> p != sender && (!(sender instanceof Player s) || s.canSee(p)))
                .map(Player::getName)
                .toList(), typed);
    }
}
