# Radio

A lobby radio for Paper servers. It plays note block songs (`.nbs`) for each player, with a purple GUI for browsing hundreds of songs by category, a shared lobby station, and a now-playing bar.

**Built by Sasha Wildfang for Fractal Network.**

![Paper](https://img.shields.io/badge/Paper-1.21.11%2B-8E2DE2) ![Java](https://img.shields.io/badge/Java-21-B266FF) ![Songs](https://img.shields.io/badge/songs-366-E58BFF) [![Release](https://img.shields.io/github/v/release/SashaWildfang/Radio?color=8E2DE2)](https://github.com/SashaWildfang/Radio/releases/latest)

## Download

Get the latest `Radio-<version>.jar` from the **[Releases page](https://github.com/SashaWildfang/Radio/releases/latest)**, drop it into your server's `plugins/` folder and restart. Songs download automatically on first start.

## Features

- **Built-in note block player**: plays every `.nbs` format version, with stereo and fine pitch. No other plugins needed.
- **Precise timing**: notes play on their own timer at their exact time, not rounded to server ticks, so songs at any tempo stay smooth. Stacked duplicate notes are merged so busy songs stay clean.
- **Personal radios**: play, pause, skip, previous, shuffle, repeat and volume for every player
- **GUI**: categories, favorites, search, sorting, paging, live progress and "up next"
- **Categories**: each folder in `songs/` becomes a category
- **Queue**: right-click a song to play it next
- **Favorites**: shift-click a song to star it, then browse your favorites
- **Lobby station**: a shared, always-on radio everyone can tune in to
- **Listen together**: `/listenwith <player>` lets you hear another player's radio
- **Now-playing bar**: song and progress as a boss bar or action bar, chosen per player
- **World rules**: only play music in the worlds you choose
- **Song sync**: on startup (or `/radio download`) the server matches this repository: new and changed songs are downloaded, and songs removed here are removed from the server. Songs an admin added by hand are never touched.
- **PlaceholderAPI** placeholders (optional)
- Every message, colour and GUI label is editable in `messages.yml`
- Player settings (volume, shuffle, repeat, sort, bar, favorites) are saved on the player

## Commands

Everything is also available as `/radio <subcommand>`, e.g. `/radio skip`.

| Command | Description |
|---|---|
| `/radio` | Open the radio |
| `/play [song]` | Play a song by name (Tab completes titles), or resume |
| `/pause` · `/resume` | Pause or resume (aliases: `pauseradio`, `stopradio`, `resumeradio`, `startradio`, `listen`) |
| `/skip` · `/lastsong` | Next or previous song |
| `/nextsong` | Show what plays next |
| `/songinfo` | Current song and progress (alias: `playing`) |
| `/queue [song\|clear]` | Show your queue, add a song, or clear it |
| `/shuffle [on\|off]` · `/repeat [on\|off]` | Toggle shuffle or repeat |
| `/volume [0-100]` | Show or set your volume |
| `/radio favorite` | Favorite the current song |
| `/radio search <query>` | Open the GUI filtered to a search |
| `/radio bar [bossbar\|actionbar\|off]` | Change your now-playing bar |
| `/radio station [join\|leave\|skip]` | Tune in to or leave the lobby station (`skip` needs admin) |
| `/listenwith <player>` · `/stoplistening` | Listen along with another player |
| `/radio reload` | Reload config, messages and songs |
| `/radio download` | Sync songs with the repository now |

## Permissions

| Permission | Default | Allows |
|---|---|---|
| `radio.use` | everyone | GUI, playback, queue, favorites, search, volume, bar |
| `radio.listenwith` | everyone | `/listenwith`, `/stoplistening` |
| `radio.station` | everyone | Tuning in to the lobby station |
| `radio.admin` | op | `reload`, `download`, `station skip` |

## Placeholders

These need PlaceholderAPI: `%radio_title%`, `%radio_artist%`, `%radio_category%`, `%radio_elapsed%`, `%radio_length%`, `%radio_progress%` (0-100), `%radio_status%` (playing/paused/off), `%radio_volume%`, `%radio_shuffle%`, `%radio_repeat%`, `%radio_listening_to%`, `%radio_station_title%`, `%radio_station_artist%`, `%radio_station_listeners%`, `%radio_songs%`.

## Setup

1. Download `Radio-<version>.jar` from [Releases](https://github.com/SashaWildfang/Radio/releases/latest), drop it into `plugins/` and start the server.
2. Songs download into `plugins/Radio/songs/` automatically. To add your own, put `.nbs` files in a category folder, e.g. `songs/Christmas/`, and run `/radio reload`.
3. Change behaviour in `config.yml` and text or colours in `messages.yml`, then run `/radio reload`.

## Adding songs to this repository

Songs live in [`songs/`](songs), one folder per category. Name each file `Artist - Title.nbs`; that's exactly what players see. Songs must be between 1m 30s and 4m 30s long by default (`songs.min-length` / `songs.max-length` in `config.yml`), and use vanilla note block instruments. Songs mostly built on custom instruments are skipped.

Songs from Note Block World are credited in [`songs/CREDITS.md`](songs/CREDITS.md) as their CC BY-SA 4.0 license requires.

After adding, removing, moving or changing songs, regenerate the index the plugin syncs from, then commit and push. Servers pick up the changes on their next restart or `/radio download`:

```bash
./gradlew songIndex
```

## Building

```bash
./gradlew build
```

The jar goes to `build/libs/`. Run `./gradlew runServer` to start a local 1.21.11 test server with the plugin.

## Project layout

```
src/main/java/com/sasha/radio/
├── Radio.java          # Plugin entry point, loading, scheduling
├── command/            # All commands (Paper Brigadier API)
├── config/             # config.yml, messages.yml + theme
├── display/            # Boss bar / action bar
├── download/           # Song repository sync
├── gui/                # The radio menu and search prompt
├── hook/               # PlaceholderAPI expansion
├── listener/           # Join / quit
├── session/            # Personal radios, the station, listen-with, audio engine
├── song/               # .nbs decoder, library, playback position
└── util/
songs/                  # The song repository (one folder per category) + index.json
```

---

Radio is built by **Sasha Wildfang** for **Fractal Network**.
