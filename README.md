# CozyRadio Mod

A [Fabric](https://fabricmc.net/) mod for Minecraft 1.21.11 that adds the **Cozy Radio
disc** — a music disc that plays an endless, server-synchronized internet radio
stream for every player near a jukebox.

## Features

- **Cozy Radio disc** (`cozyradio_disc`): craft it with a `jukebox` + a `diamond`,
  pop it into a jukebox, and everyone within ~65 blocks tunes in.
- **Server-synchronized rotation**: the whole server rotates stations together on the
  configurable `rotationMinutes` interval (default 5 min) — no client state.
- **Per-player controls**: `/cozyradio next|prev|station <id>` switches *your* station
  until the next rotation boundary — other players keep their own picks or the rotation.
- **Personal YouTube radios**: every player can register their own YouTube live
  streams with `/cozyradio add <url> [label]` (up to 5), listed with ★ and played like
  any other station — each player's taste, no server config edits.
- **No bundled audio**: stations are MP3 internet streams **or live YouTube streams**
  (both played with Lavaplayer + youtube-source, Apache-2.0 core with LGPL FFmpeg
  natives — MP3 decodes natively via libmpg123, cheap on weak CPUs). The default
  playlist is curated MP3 relays (SomaFM, Radio Paradise); YouTube live streams are
  unstable by nature (their 24/7 broadcasts restart and video IDs go stale), so
  they're opt-in via `/cozyradio add`.
- **HUD status card**: bottom-left card shows an animated "Connecting…" sweep bar
  while a station buffers, then a fading "Now playing" card with the station name; it
  reappears on station change and disappears on stop.
- **Volume follows your Record × Master sliders** (both must be non-zero); stream
  dropouts retry up to 3 times (5s backoff).
- **Works with vanilla jukeboxes**: insert/eject, redstone comparator output and music
  particles all behave normally.

> **Station URLs can go stale**: radio relays change occasionally. If a station goes
> silent or fails to load, update its `url` in `playlist.json` (see below) and restart
> the server.

## Usage

1. Install Fabric Loader + Fabric API on a 1.21.11 client *and* server (or singleplayer).
2. Drop `cozyradio-mod-*.jar` into the `mods/` folder of both.
3. Craft the disc (`jukebox` + `diamond`), place a jukebox, insert the disc.
4. On first server start, the mod writes its config to
   `config/cozyradio-mod/playlist.json` — edit the station list or rotation and restart.

## Commands

All commands run anywhere; `next`/`prev`/`station` take effect only while at least one
jukebox is playing the Cozy Radio disc, and apply per player until the next rotation.

| Command                | Description                                        |
| ---------------------- | -------------------------------------------------- |
| `/cozyradio status`    | Jukeboxes playing, rotation interval, current station, your personal-station count |
| `/cozyradio list`      | All stations (yours are marked ★); marks the one you're hearing |
| `/cozyradio next`      | Skip to the next station (per player)               |
| `/cozyradio prev`      | Go back to the previous station (per player)        |
| `/cozyradio station <name>` | Jump to a station by name (shared or yours; tab-complete supported) |
| `/cozyradio add <url> [label]` | Register a YouTube live stream as *your* station (max 5) |
| `/cozyradio remove <name>` | Remove one of your personal stations by name (tab-complete supported) |
| `/cozyradio rotation on\|off` | Cycle the rotation through *your* personal stations (no-arg shows state) |
| `/cozyradio debug`     | Op-only: jukebox positions, listener streams        |

### Personal stations (YouTube)

Every player can add up to 5 of their own YouTube live streams to their personal
station list:

```
/cozyradio add "https://www.youtube.com/watch?v=X4VbdwhkE10" "MyLofi"
→ Added "MyLofi" to your stations — /cozyradio station MyLofi
/cozyradio list        # shared stations first, yours after with ★
/cozyradio station MyLofi   # select by name, case-insensitive
```

- Personal stations are identified by their label — `/cozyradio station` and
  `/cozyradio remove` take the station's name (case-insensitive). The id stored
  on disk is internal only; files from older versions are migrated automatically
  when loaded.

- Accepted links: `www.youtube.com/watch?v=…`, `youtu.be/…`, `music.youtube.com/…`
  (plain `http` works too). Anything else — including MP3 relay URLs — is rejected,
  because the client streams whatever the server broadcasts and YouTube-only keeps
  servers from pointing players' clients at arbitrary hosts.
- Stations are saved to `config/cozyradio-mod/personal-stations.json` and survive
  restarts; re-adding the same video replaces it instead of duplicating.
- Personal stations play only when *you* pick them (they never appear in the shared
  rotation); other players near the same jukebox keep their own stations until the
  rotation boundary.
- **Rotate your own streams**: `/cozyradio rotation on` replaces the shared rotation
  for you — the rotation cadence now cycles *only your* personal stations (with none
  registered it falls back to the shared rotation). `/cozyradio rotation off` (or
  `/cozyradio rotation` to check) returns you to the shared rotation. The toggle is
  saved with your stations, and an active manual override (`station`/`next`/`prev`)
  still wins until the next rotation boundary.
- The same YouTube caveats apply as to `"youtube"` stations below: the stream's
  download URL expires after a few hours and the client re-resolves the `watch?v=`
  URL automatically; the station's video ID dies when the 24/7 broadcast restarts —
  just remove it and add the current one.

## Configuration

`config/cozyradio-mod/playlist.json`:

```json
{
  "rotationMinutes": 5,
  "stations": [
    { "id": "groove-salad", "name": "Groove Salad — SomaFM", "url": "https://ice1.somafm.com/groovesalad-128-mp3", "type": "mp3" },
    { "id": "lush", "name": "Lush — SomaFM", "url": "https://ice1.somafm.com/lush-128-mp3", "type": "mp3" },
    { "id": "radio-paradise", "name": "Radio Paradise", "url": "https://stream.radioparadise.com/mp3-192", "type": "mp3" }
  ]
}
```

The defaults above are only written when `playlist.json` does not exist yet. Servers
that already ran the mod keep their old list — delete
`config/cozyradio-mod/playlist.json` to regenerate the new defaults.

Each station has a `type`:

- `"mp3"` — a SHOUTcast/Icecast MP3 stream played by Lavaplayer's HTTP source with
  native libmpg123 decoding; a `Winamp/5.09` User-Agent is sent.
- `"youtube"` — a YouTube video or live stream URL, played with Lavaplayer
  (`dev.arbjerg:lavaplayer`, `dev.lavalink.youtube:v2`). Live streams resolve on the
  client with an internal 2s buffer tuned to smooth DASH segment-boundary stalls.
- Missing `type` defaults to `"mp3"`, so older `playlist.json` files keep working.

> **YouTube live stations**: live IDs die whenever the 24/7 broadcast restarts — the
> famous Lofi Girl `jfKfPfyJRdk` ID died in 2026. That's why no YouTube station ships
> in the default playlist; players add current ones with `/cozyradio add`. Each live
> stream's download URL also expires after a few hours — the client detects this,
> re-resolves the `watch?v=` URL and continues automatically. On a network that blocks
> anonymous YouTube access you may need OAuth for the embedded Lavaplayer (see the
> caveat below).

Station URLs are resolved on the client, so any mod can add stations; the server only
forwards the playlist.

> **No sound?** The stream volume follows the **Record** and **Master** sliders
> (Sound Settings). If either is muted (e.g. `soundCategory_music: 0.0` — the mod does
> *not* use the Music slider), you'll hear silence. Check the chat log for
> "Starting Cozy Radio stream…" and `latest.log` for `(cozyradio-mod)` entries.

## Development

- `./gradlew build` — compile and package (`build/libs/cozyradio-mod-1.0.0.jar`).
- `./gradlew runServer` / `runClient` — run in a dev environment.
- `./gradlew runGametest` — run the server-side GameTests (jukebox play/stop tracking).

## License

Mod code is CC0-1.0. Bundled via jar-in-jar:

- Lavaplayer (`dev.arbjerg:lavaplayer`, Apache-2.0), lava-common (Apache-2.0) and
  youtube-source (`dev.lavalink.youtube:v2`) for all playback (MP3 + YouTube)
- Lavaplayer natives (LGPL — FFmpeg-based `libconnector`) for Opus/MP3 decode

The mod jar is ~34 MB; the extra size is the Lavaplayer natives for all platforms.
