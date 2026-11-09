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

0. **Spike**: torrent → local HTTP → libmpv, fold/unfold mid-playback. ✅ emulator + Pixel 10 Pro Fold
   (`adb shell am start -n app.kotakbase/.SpikeActivity`).
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
   reader that marks a chapter read at its end, "Read chapter n". Picking a source plays it in the spike player.
   Left for later: tracker scrape for extensions that send no seeder counts (their results show as 0 seeders and are
   hidden, as on desktop before the scrape), binary `fetch` bodies, `DOMParser`, extension options UI, the audio/
   subtitle language settings (defaults: Japanese audio, English subtitles), paged RTL/LTR reader.
   Test extensions (legal content: Blender's Sintel, MangaDex's API): `node test-extensions/serve.mjs`,
   `adb reverse tcp:8099 tcp:8099`, add `http://localhost:8099/index.json` (debug builds allow that plain-HTTP link).
3. Player (gestures, chapters, PiP, Fold layouts, the right file of a batch) and paged manga reader.
4. Sync, downloads (foreground service), Watch Together against desktop.
5. Signed release APK on GitHub + in-app update check.

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

Spike with another source: `adb shell am start -n app.kotakbase/.SpikeActivity --es source "magnet:?..."`
