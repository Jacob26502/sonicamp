# Sonic-Amp

A mostly Vibecoded app that allows you to connect a subsonic server to Poweramp (the best android audio player). Uses proxy files to provide data to poweramp, caches data and songs over time. Seems to work pretty well. Everything else in the readme is AI generated... Tx.

An Android app that makes a Subsonic / OpenSubsonic server (developed against **Navidrome**) appear in
[Poweramp](https://powerampapp.com/) as a library, using Poweramp's track-provider API.

## Version 0.1.0

The first baseline. Tested against Navidrome 0.64.2 and Poweramp build 1035.

- Login with password (token auth) or API key; credentials encrypted on the device
- Library synced on a schedule and shown in Poweramp as Library → Album artist → Album → Tracks
- Tracks stream through the app as seekable files, with an offline cache
- Optional MP3 transcoding per network type, with upgrades to original quality in the background
- Lyrics (synced and plain), server playlists in Poweramp, scrobbling with an offline queue

Why tracks are files and not stream URLs: Poweramp's URL tracks can't seek (it never asks the server
for another position), show as an empty "Stream" in Info/Tags with the login token in the URL, and
can't be matched by playlist entries.

## How it works

- **Login:** token auth (`u`/`t`/`s`, password stored encrypted with an Android Keystore key) or an
  OpenSubsonic API key where the server supports it. Navidrome 0.64 does not support API keys yet.
- **Sync:** a full fetch of every song through paged `search3` (empty query), about one request per
  500 songs. Results are compared with the local database by a per-track content hash, because
  Navidrome has no per-song "modified" time and its `lastScan`/`lastModified` values change on every
  scan. Playlists are only re-fetched when their `changed` time differs.
- **Safety:** sync waits while the server is scanning. It refuses to replace a non-empty library with
  an empty result. Switching server or account wipes the old library.
- **Schedule:** WorkManager runs the sync every 6, 12 or 24 hours (optionally Wi-Fi only), plus right
  after login and on "Sync now". If the server rejects the login, scheduled syncs pause and a
  notification asks you to sign in again.
- **Poweramp provider:** a Storage Access Framework documents provider showing
  Library → Album artist → Album → Tracks, answered from the local database. Tracks carry full tags;
  multi-disc albums are numbered disc × 1000 + track so discs sort in order. Covers are fetched once
  per album and cached. To add it: Poweramp → Settings → Library → Music Folders → + → Sonic-Amp.
- **Keeping Poweramp current:** after a sync that changed something the app asks Poweramp to rescan.
  If Poweramp ignores that (it only accepts scan requests while it or the app is in the foreground),
  the request is retried when Poweramp starts playing. Rescan in Poweramp also triggers a server sync
  if the last one is over 10 minutes old.
- **Lyrics:** fetched when Poweramp's Lyrics view asks for a track (`getLyricsBySongId`, or classic
  `getLyrics` on servers without it). Synced lyrics are converted to LRC. Answers, including "no
  lyrics", are cached on disk for 30 days (3 days for "none").
- **File names:** built from the tags (`01. Title.flac`). Navidrome's `path` is normally made up
  (`Artist/Album/01-01 - Title.flac`), so it's only a fallback for tracks without a title. Poweramp
  only re-reads folders whose last-modified time changed, so a future change to how existing tracks
  are presented (names, columns) must also change those times, or users must rescan in full.
- **Playback:** tracks are files to Poweramp. `openDocument` returns a seekable descriptor
  (`StorageManager.openProxyFileDescriptor`) whose reads are served from a cache file, filled from
  the server with byte-range requests. One background download per track reads ahead from the play
  position (the whole track on unmetered networks, 8 MB ahead on metered ones); reads elsewhere (tag
  peeks near the end, the first read after a seek) fetch one 256 KB block, and the download follows
  if playback continues there. Dropped connections are retried; a read gives up after 20 s rather
  than hanging Poweramp. Opening needs no network: the size comes from the library.
- **Audio cache:** played tracks stay in `cache/audio` (the file size and which byte ranges are
  present are stored beside each file), so replays and fully downloaded tracks play without the
  server. The app shows usage and fully downloaded tracks, has a size limit (1/2/5/10 GB, default 2,
  least recently used removed first, never the track playing), "Clear cache", and an option to
  download whole tracks on mobile data too. A ".meta" file beside each cache file records which
  track and format it holds.
- **Transcoding:** separate quality settings for Wi-Fi and mobile data: Original, or constant-bitrate
  MP3 at 320/256/192/128 kbps (MP3 because its size is predictable: Navidrome's output measured within
  0.8% of duration × bitrate). Servers don't support range requests on transcodes, so the MP3 is
  downloaded once from the start into the cache. Poweramp is given a slightly generous size,
  (duration + 1 s) × bitrate + 96 KB; the gap after the real end is zero-padded (MP3 decoders skip
  zeros), and the last 64 KB is always zeros so Poweramp's tag peeks at the end of the file are
  answered at once. Navidrome transcodes ~50× real time, so a forward seek waits only briefly. Never
  uses Navidrome's `estimateContentLength`: it cuts the stream off at the estimate. Poweramp plays
  the MP3 although the track was scanned as the original format. A fully cached original always
  wins, and a track whose original bitrate is no higher than the target isn't transcoded.
- **Upgrading:** once a track's original is fully cached its transcoded copies are deleted. A
  background job (unmetered network, charging, battery not low) downloads originals for tracks
  cached only as transcodes, and stops rather than evict other music when the cache is full.
- **Playlists:** server playlists appear as `.m3u8` files in a "Playlists" folder in the first
  library's root. Entries are Poweramp's own path for each track,
  `@dev.subsonic.poweramp.provider/lib%3A1/track%3A<id>`: probing on device showed Poweramp keeps
  provider files under the root folder by document id, so name-based entries (`Album/file.flac`)
  never match and document URIs play as outside files. One way: changes made in Poweramp are not
  sent back. Entries only resolve when the whole library root was added in Music Folders.
- **Scrobbling:** Poweramp's track/status broadcasts tell the app what plays. For our tracks it sends
  "now playing" (OpenSubsonic `reportPlayback`, or `scrobble` with `submission=false`) and, once a
  track has played for half its length or 4 minutes (tracks under 30 s never count), queues a
  scrobble with the start time. Queued plays are sent by WorkManager when there's a network, so plays
  made offline are sent later. Can be turned off in the app.

## Project layout

```
app/src/main/java/dev/subsonic/poweramp/
  server/     Server-neutral MusicServer interface, credentials, errors, and the factory that picks an implementation
  subsonic/   Subsonic/OpenSubsonic implementation of MusicServer, on a low-level API client (JVM-tested)
  sync/       Sync logic (pure Kotlin) plus WorkManager worker, status and settings
  library/    SQLite library database (albums and artists are views over tracks)
  provider/   Poweramp track provider (DocumentsProvider), document ids, cover cache, playlist files
  stream/     Streaming tracks into the audio cache with range requests (session logic unit-tested on the JVM)
  lyrics/     Lyrics fetching, LRC conversion and cache
  scrobble/   Playback tracking from Poweramp broadcasts, scrobble queue and worker
  poweramp/   Poweramp API constants, rescan requests, broadcast receiver
  settings/   Saved server and encrypted credential storage
  ui/         Compose screens
app/src/main/java/com/maxmpz/poweramp/player/TrackProviderConsts.java
              copied from github.com/maxmpz/powerampapi (licence permits use by Poweramp plugins)
```

### Server abstraction

Everything except `subsonic/` talks to the server through `server/MusicServer`: connect, scan
status, libraries, paged tracks, playlists, stream and cover URLs, lyrics, now-playing reports and
scrobbles, using server-neutral records (`TrackRecord`, `PlaylistRecord`, `LibraryRecord`),
credentials (`ServerAuth`) and errors (`ServerException` with a `Kind`). `MusicServers.create`
picks the implementation from the saved `ServerType`; Subsonic is the only one so far. Adding another
server type (for example Jellyfin) means a new `MusicServer` implementation, a `ServerType` value,
a case in `MusicServers.create`, and a server-type choice on the login screen. Its library ids must be
mapped to stable Ints, and a transcode must be constant-bitrate MP3 served as `audio/mpeg`.

## Building and testing

Requires JDK 17+ and the Android SDK (compileSdk 36). The Gradle wrapper downloads Gradle itself.

```bash
./gradlew assembleDebug             # build the APK
./gradlew testDebugUnitTest         # JVM unit tests
./gradlew connectedDebugAndroidTest # database tests on a running emulator/device
```

`connectedDebugAndroidTest` uninstalls the app when it finishes, which deletes the saved login and
library on that device.

### Live server tests

Some unit tests run against a real server when these environment variables are set, and are skipped
otherwise:

```bash
SUBSONIC_TEST_URL=http://localhost:4533 SUBSONIC_TEST_USER=<user> SUBSONIC_TEST_PASSWORD=<password> ./gradlew testDebugUnitTest
```

## Test server

Development used a local Navidrome in Docker (`navidrome/navidrome`, port 4533). From the Android
emulator it's at `http://10.0.2.2:4533`; a USB-connected phone reaches it at `http://127.0.0.1:4533`
after `adb reverse tcp:4533 tcp:4533`.

Notes for Docker Desktop on Windows:

- Containers get no file-change events, so Navidrome won't notice new music on its own: rescan with
  `docker compose exec navidrome /app/navidrome scan`, or set `ND_SCANNER_SCHEDULE`.
- Set `ND_TRANSCODINGCACHESIZE: "0"`. Reading Navidrome's transcoding cache back through a
  Windows-mounted data folder fails with "no data available", which truncates MP3/AAC transcodes to a
  few KB.

## Debugging

Debug builds log every HTTP request under the `SubsonicHttp` tag with credentials masked
(`u=***&t=***&s=***`):

```bash
adb logcat -s SubsonicHttp SyncWorker SubsonicProvider SubsonicStream SubsonicUpgrade PowerampRescans Scrobbler ScrobbleWorker SubsonicLyrics
```

`SubsonicProvider` logs each distinct set of columns Poweramp requests, scan starts, track opens and
cover fetches. `SubsonicStream` logs seeks and, when a track's session closes, how many requests it
took and how much is cached. Logs never contain stream URLs or tokens, only track ids.

### Testing on a phone over USB

`adb reverse tcp:4533 tcp:4533` lets a USB-connected phone reach the dev server at
`http://127.0.0.1:4533`. Install with `adb -s <serial> install --user 0 -r app/build/outputs/apk/debug/app-debug.apk`
rather than `gradlew installDebug`, which installs into every user profile on the device (including a
work profile). Likewise, set `ANDROID_SERIAL` before `connectedDebugAndroidTest`, which otherwise runs
on every connected device.

## Licence

Sonic-Amp is free software, licensed under the [GNU General Public License v3.0](LICENSE): you can
use, study, share and modify it, and versions you distribute must stay under the same licence with
their source available.

`app/src/main/java/com/maxmpz/poweramp/player/TrackProviderConsts.java` is © Maksim Petrov and keeps
its own licence (in the file header), which permits redistribution by software that communicates
with Poweramp.
