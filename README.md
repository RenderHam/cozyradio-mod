# CozyRadio Mod (Fabric)

> **Note:** This project is roughly 90% AI-generated code, reviewed and tested by a
> human before release — bugs are still possible, reports welcome.

> **Fabric-only** — no Forge/NeoForge ports are planned. Release jars are named `cozyradio-mod-fabric-<mc-version>-<mod-version>.jar`.

A [Fabric](https://fabricmc.net/) mod for Minecraft 1.21.1 that adds the **Cozy Radio
disc** — a music disc that plays an endless, server-synchronized internet radio
stream for every player near a jukebox.

## Requirements

- Minecraft **1.21.1** with [Fabric Loader](https://fabricmc.net/use/installer/) **0.19.3+**
- [Fabric API](https://modrinth.com/mod/fabric-api) (1.21.1 build)
- Java **21+**
- Install the mod on the **server and every client** (or in singleplayer)

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

## Usage

1. Install Fabric Loader + Fabric API on a 1.21.1 client *and* server (or singleplayer).
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
| `/cozyradio station <name>` | Jump to a station by name (tab-complete: *your* stations) |
| `/cozyradio add <url> [label]` | Register a YouTube live stream as *your* station (max 5; label must be a single word) |
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

- Personal stations are identified by their name — `/cozyradio station` and
  `/cozyradio remove` take the station's label (case-insensitive), and the id
  stored on disk is that same name. `/cozyradio station` tab-completes only
  *your* stations. Files from older versions (which used `yours-<videoId>` ids)
  are migrated automatically when loaded.

- Labels must be a single word (no spaces): `/cozyradio add <url> MyLofi`, not
  `My Lofi`.

- Accepted links: `www.youtube.com/watch?v=…`, `youtu.be/…`, `music.youtube.com/…`
  (plain `http` works too). Anything else — including MP3 relay URLs — is rejected,
  because the client streams whatever the server broadcasts and YouTube-only keeps
  servers from pointing players' clients at arbitrary hosts.
- Stations are saved to `config/cozyradio-mod/personal-stations.json` and survive
  restarts; re-adding the same name replaces that station's URL instead of
  duplicating (a differently-cased name is rejected as a duplicate).
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

### YouTube authorization (one-time)

YouTube bot-checks anonymous Lavaplayer requests, so each player's client
authorizes YouTube access once through YouTube's official device flow. When a
YouTube station is played without a saved token, the client posts a message in
the **in-game chat** (the link is clickable):

```
Cozy Radio: YouTube authorization required — open <url> and enter code <code>
```

Click the link (or open it in any browser on any device), sign in with any Google
account, enter the code and allow access. The client polls automatically, saves
the refresh token to `config/cozyradio-mod/youtube-oauth.json`, and starts
playing — no restart needed. While the authorization is pending, YouTube stations
stay silent (MP3 stations keep playing); once you complete the code, the waiting
stream resumes on its own. This happens **once per client**; the server never
needs a token. If a code expires before you finish, a fresh one is issued
automatically.

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
> re-resolves the `watch?v=` URL and continues automatically. On networks where
> YouTube bot-checks anonymous requests, the client uses its one-time OAuth flow
> instead (see *YouTube authorization* above).

Station URLs are resolved on the client, so any mod can add stations; the server only
forwards the playlist.

## Troubleshooting

**No sound?** The stream volume follows the **Record** and **Master** sliders (Sound
Settings). If either is muted (e.g. `soundCategory_music: 0.0` — the mod does *not* use
the Music slider), you'll hear silence. Check the chat log for "Starting Cozy Radio
stream…" and `latest.log` for `(cozyradio-mod)` entries.

**A station goes silent or fails to load** — radio relay URLs change occasionally. Update
the station's `url` in `config/cozyradio-mod/playlist.json` and restart the server.

**A YouTube station stays silent** — check the in-game chat for the
`Cozy Radio: YouTube authorization required` message and complete the one-time
authorization (see *YouTube authorization* above); the stream resumes on its own.

**A YouTube station stops** — live-stream IDs die whenever the 24/7 broadcast restarts.
Remove the station and `/cozyradio add` the current URL. If YouTube bot-checks your
network, the client authorizes via its one-time OAuth flow automatically (see *YouTube
authorization* above).

## Development

### Setup & commands

```
./gradlew build          # compile + package → build/libs/cozyradio-mod-fabric-1.21.1-1.1.0.jar
./gradlew runClient      # launch a dev client
./gradlew runServer      # launch a dev server
./gradlew runGametest    # server-side GameTests (jukebox play/stop tracking)
```

Needs JDK 21+ and internet access on the first build.

### Architecture

`src/main/java` holds the shared/server logic; `src/client/java` the client-only code.

| Package | Responsibility |
| ------- | -------------- |
| `com.cozyradio` | Entry point + registries (`CozyRadioMod`) |
| `com.cozyradio.item` | `CozyRadioDiscItem` — the disc item |
| `com.cozyradio.mixin` | `JukeboxSongPlayerMixin` — vanilla jukebox integration |
| `com.cozyradio.radio` | `ServerRadioManager`, `YoutubeUrl` — server-side radio logic |
| `com.cozyradio.config` | `PlaylistConfig`, `PersonalStationStore` — config read/write + migration |
| `com.cozyradio.network` | `ModNetworking` + `StationStart/StopPayload` — server → client messages |
| `com.cozyradio.client.audio` | `ClientRadioPlayer`, `LavaRadioPlayer`, `CozyRadioAudioDevice`, `LavaPlayerFactory` — client playback |
| `com.cozyradio.client.hud` | `RadioToast` — HUD status card |
| `com.cozyradio.test` | Fabric GameTests |

How it works: the **server** owns the rotation and broadcasts station state to every
client near a playing jukebox; the **client** resolves the station URL and streams it
with Lavaplayer (MP3 natively, YouTube via youtube-source). Servers only forward the
playlist — station URLs always resolve on the client.

### Contributing

- Report bugs via [issues](https://github.com/RenderHam/cozyradio-mod/issues)
- Keep changes in the existing package layout; run `./gradlew build` and `runGametest` before opening a PR
- To suggest a default station, open an issue with a stable MP3 relay URL (ideally one that is online 24/7).

## License

Mod code is CC0-1.0. Bundled via jar-in-jar:

- Lavaplayer (`dev.arbjerg:lavaplayer`, Apache-2.0), lava-common (Apache-2.0) and
  youtube-source (`dev.lavalink.youtube:v2`) for all playback (MP3 + YouTube)
- Lavaplayer natives (LGPL — FFmpeg-based `libconnector`) for Opus/MP3 decode

The mod jar is ~34 MB; the extra size is the Lavaplayer natives for all platforms.
