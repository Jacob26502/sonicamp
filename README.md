# Subsonic for Poweramp

An Android app that makes a Subsonic / OpenSubsonic server (developed against **Navidrome**) appear in
[Poweramp](https://powerampapp.com/) as a library, using Poweramp's track-provider API.

## Status

| Phase | What | State |
|---|---|---|
| 1 | Subsonic client, login screen, encrypted credentials | done |
| 2 | Local library database, scheduled sync | done |
| 3 | Poweramp track provider (browse + stream) | next |
| 4 | Lyrics, playlists in Poweramp, scrobbling | planned |
| 5 | Offline cache, transcoding with seeking | planned |

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

## Project layout

```
app/src/main/java/dev/subsonic/poweramp/
  subsonic/   Subsonic API client (no Android dependencies; unit-tested on the JVM)
  sync/       Sync logic (pure Kotlin) plus WorkManager worker, status and settings
  library/    SQLite library database (albums and artists are views over tracks)
  settings/   Saved server and encrypted credential storage
  ui/         Compose screens
Navidrome/    Docker compose for a local test server, plus a rescan script
```

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

## Local Navidrome test server

```bash
docker compose -f Navidrome/docker-compose.yml up -d
```

Open http://localhost:4533 to create the admin account, and put music in `Navidrome/music/`
(`Artist/Album/01 - Title.flac`; tags matter more than folder names). From the Android emulator the
server is at `http://10.0.2.2:4533`.

Docker on Windows does not pass file-change events into the container, so Navidrome won't notice new
music on its own. Rescan after adding files:

```powershell
.\Navidrome\rescan.ps1        # quick scan
.\Navidrome\rescan.ps1 -Full  # re-read every file
```

## Debugging

Debug builds log every HTTP request under the `SubsonicHttp` tag with credentials masked
(`u=***&t=***&s=***`):

```bash
adb logcat -s SubsonicHttp SyncWorker
```
