# Kotakbase for Android

The Android version of [Kotakbase](https://github.com/runley/kotakbase): native Kotlin, built for low memory, tested on a
Pixel Fold (folded and unfolded). Shipped as an APK on GitHub releases; not on the Play Store.

## Stack

| Part | Desktop | Android |
|---|---|---|
| UI | HTML/JS | Jetpack Compose, adaptive layouts for folded/unfolded |
| Torrents | WebTorrent | libtorrent4j, sequential, served to the player over 127.0.0.1 |
| Player | external mpv.exe | libmpv (dev.jdtech.mpv), embedded |
| Extensions (anime + manga) | Web Worker | QuickJS, `fetch` bridged to OkHttp |
| Watch Together | WebRTC + WSS trackers | stream-webrtc-android + OkHttp WebSocket, same protocol as desktop |
| Manga reader | `ui/reader.js` | Compose: LazyColumn (webtoon strip) / HorizontalPager (RTL, LTR) + Coil |

## Features

**Same as desktop**: home, search, show pages, Your List, schedule, history, stats, recommendations, AniList + MAL
sync, downloads, Watch Together, the 9 languages, manga mode.

**Manga mode** (desktop 1.3.0): AniList's manga with its own list/progress/history, manga extensions
(`search`, `chapters`, `pages`), a chapter list with a source picker, and a reader (vertical, RTL, LTR; reaching a
chapter's end marks it read). All of it ports:
- Extensions run in the same QuickJS sandbox as torrent extensions; results go through the same cleaning rules as
  `ui/ext-clean.js` (https-only page URLs).
- The reader is simpler natively: Coil decodes pages downsampled to the screen, so long webtoon strips don't blow RAM.
  Very tall strips need tiled decoding (subsampling) — check against a real webtoon.
- MangaDex's User-Agent workaround is just an OkHttp header here.
- Fold extras: unfolded shows two pages side by side in RTL/LTR mode; folded shows one.
- Gestures replace keys: tap zones to turn pages, hold-to-scroll becomes plain fling.

**Changed**: tray → a notification while downloading; Electron updater → GitHub release check + APK install;
download folder → Android's folder picker; `kotakbase://` → intent filter (AniList sign-in, invites); fullscreen →
immersive mode; keyboard shortcuts → gestures.

**Dropped**: Discord Rich Presence (Android Discord has no API for it), close-to-tray.

**New**: picture-in-picture, tabletop mode (half-folded: video top, controls/chat bottom).

## Phases

0. **Spike** ✅: torrent → local HTTP → libmpv, fold/unfold mid-playback (replaced by the player in phase 3).
1. **Browse** ✅: AniList home rows (cached like desktop), View More, search with filters, show and manga pages
   (your list, progress, "already watched", ratings, episodes, Related, Similar), Your List, Settings → Language,
   anime ⇄ manga switch, all 9 languages from the desktop's translations (`node scripts/sync-desktop.mjs` to refresh).
   Unfolded, a show opens beside the page; folding moves it to its own page. ~128 MB while browsing.
   Left for later phases: Recommended/"Because you watched" rows, the home feature box and Airing today, schedule,
   history, stats, episode thumbnails (ani.zip), MyAnimeList fallback, and the settings that belong to later features.
2. **Extensions** ✅: Hayase extensions added by manifest link (Settings → Extensions; same link rules, settings keys
   and calls as desktop), each in its own QuickJS sandbox with `fetch`, `URL`, `URLSearchParams`, `TextEncoder`,
   `atob`, `setTimeout` (assets/js/web.js). The source picker asks every torrent extension with Hayase's query
   (titles, episode, ani.zip ids), and ranks and Auto Selects with the desktop's own `release.js`/`lang.js`, run
   unchanged (copied by `scripts/sync-desktop.mjs`). Manga: series match (desktop `manga.js`), chapter list, a vertical
   reader that marks a chapter read at its end, "Read chapter n". Seeder counts for results sent without them come
   from Nyaa's tracker (scrape), as on desktop.
   Left for later: binary `fetch` bodies, `DOMParser`, extension options UI, the audio/subtitle language settings
   (defaults: Japanese audio, English subtitles).
   Test extensions (legal content: Blender's Sintel, MangaDex's API): `node test-extensions/serve.mjs`,
   `adb reverse tcp:8099 tcp:8099`, add `http://localhost:8099/index.json` (debug builds allow that plain-HTTP link).
3. **Player and reader** ✅ (`PlayerActivity`, `Mpv`, `Torrent`, `Reader`):
   - Torrents: one at a time, only the episode's file (desktop `episode.js` picks it, run unchanged), its start and end
     first (MP4/MKV indexes), stale waits dropped on seek; unsafe torrents (programs, no video) refused as on desktop;
     the stream's files are deleted when the player closes.
   - Player: libmpv with hardware decoding on the phone (software on the emulator), mpv's `fast` profile with 8-bit
     buffers (GPU memory 227 → 142 MB on the Fold); controls, seek bar, double-tap ±10 s, audio/subtitle menus and
     tracks picked by Settings' languages (desktop `lang.js`), Skip Intro/Credits from chapters or AniSkip, resume,
     progress and history saved like desktop (Continue Watching), next episode from the same season pack or else
     the source picker, picture-in-picture (auto on Home), tabletop layout half-folded, pause in the background.
   - Reader: vertical, right-to-left or left-to-right (remembered per manga; default by country like desktop), tap
     zones, two pages side by side unfolded, page counter, next chapter.
   Not yet: brightness/volume swipes, zoom in the reader, the episode list inside the player, subtitles from separate
   files in the torrent, a CJK fallback font (Roboto only; MKVs bring their own fonts), the two-page spread checked on
   a real multi-page manga (the test title's chapters are single pages). Memory on the Fold while playing: ~362 MB.
4. **Sync, downloads, Watch Together** ✅:
   - AniList: sign-in through AniList's page in the app (its redirect with the token caught, as on desktop; client id
     from `secrets.properties`, which `scripts/sync-desktop.mjs` copies from the desktop's git-ignored secrets). The sync
     runs the desktop's own rules (`anilist-sync.js`: merge, pushTarget, localEntry, toLocal, saveVars) unchanged; the
     loop around them (pull, persisted outbox paced like desktop, the first-sync question) is `AniSync.kt`. Anime and
     manga lists. Not tested with a real account (needs your password). The token is in the app's private storage
     (desktop encrypts it with the OS; shortcut: use the Android Keystore if that matters).
   - Downloads: one entry per torrent with the episodes picked (desktop `chooseFiles`), a foreground service with a
     notification, pause/resume/delete, resumed at start, and downloaded episodes play from the phone (Watch, Next).
     They're kept in the app's internal storage: libtorrent's memory-mapped files crashed Android's shared-storage
     service under Android/data. Not yet: a folder picker.
   - Watch Together: the desktop's `room.js` and `room-net.js` run unchanged in an invisible WebView (`room.html`,
     served from the app's assets on a secure origin for WebRTC), bridged to the native player. Tested between the
     emulator and the Fold: joining, chat, the host starting an episode (the other opens it, in step within a second),
     a pause on the guest pausing the host. Invite links `kotakbase://join/CODE`. Not yet: saved rooms with a PIN, ban,
     chat over the player; not tried against the desktop app itself (same code, so it should).
5. **Release and updates** ✅: `./gradlew assembleRelease` makes a signed, R8-shrunk, arm64-only APK (~44 MB). Settings →
   Updates (desktop wording) and a check at start (every 6 h at most, release builds only) read this repo's latest
   GitHub release; a newer `versionName` downloads its `.apk` and offers "Restart to update", which opens Android's
   installer (it asks, and only accepts the same signing key). Tested on the emulator: browsing, the update check,
   Sintel playing through the torrent and mpv.

## Spike findings

- Plays Sintel from its torrent (web seed) within ~2 s of start; seeking works over HTTP ranges.
- Folding/unfolding keeps playing without recreating the activity (`configChanges` in the manifest).
- Hardware decoding (`mediacodec`) deadlocks on the emulator when folding swaps the surface, and software decoding
  draws a grid of lines there. Both are emulator-only: on the Pixel 10 Pro Fold, `mediacodec` plays cleanly and
  survives closed → open (`--es hwdec mediacodec`; the spike still defaults to `no`).
- When the screen goes away (phone locks on fold), mpv falls back to software decoding and keeps decoding in the
  background. The player must pause video (or `vid=no`) when the surface is gone and re-enable `hwdec` when it's back.
- Real swarm: 32 peers, ~4.6 MB/s, but **31 s to first frame**. Sintel's index (`moov`) is at the end of the file, so mpv
  waits for the last pieces while sequential download fetches from the start. The real stream should fetch the file's
  first and last pieces first (and cancel stale waits on seek).
- Memory on the Fold with hardware decoding: ~280 MB PSS, of which ~150 MB is GPU buffers (mpv's `vo=gpu` at the
  inner screen's resolution). Try `vo=gpu-next` / lower FBO precision before settling.
- Memory while playing: ~149 MB PSS after capping mpv's demuxer cache (default was ~296 MB).
- libass found no fontconfig and uses a fallback font: anime subs need a bundled fallback (incl. CJK) plus
  the fonts embedded in MKVs.
- Debug APK is ~88 MB (two ABIs, uncompressed native libs); release builds should split per ABI.

## Build

JDK 17 and the Android SDK (`ANDROID_HOME`), then:

```bash
./gradlew installDebug
```

### Releasing

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`.
2. `./gradlew assembleRelease` (needs `keystore.properties`, not in git: `storeFile`, `storePassword`, `keyAlias`,
   `keyPassword`). Keep the key file and its password backed up: without them no update can install over the app.
3. Create a GitHub release tagged `v<versionName>` with `app/build/outputs/apk/release/app-release.apk` attached
   (`gh release create v0.1.0 app/build/outputs/apk/release/app-release.apk#kotakbase-0.1.0.apk`). The repo must be
   public for the app's update check to see it.

A debug build is signed with another key: uninstall it (which drops its data) before installing a release.

Spike with another source: `adb shell am start -n app.kotakbase/.SpikeActivity --es source "magnet:?..."`
