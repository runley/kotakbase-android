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

0. **Spike** (this commit): torrent → local HTTP → libmpv, fold/unfold mid-playback. ✅ on emulator.
1. Browse: AniList/MAL, home, show page, lists, settings, languages (anime and manga modes).
2. Extensions in QuickJS: torrent search + Auto Select, manga search/chapters/pages.
3. Player (gestures, chapters, PiP, Fold layouts) and manga reader.
4. Sync, downloads (foreground service), Watch Together against desktop.
5. Signed release APK on GitHub + in-app update check.

## Spike findings

- Plays Sintel from its torrent (web seed) within ~2 s of start; seeking works over HTTP ranges.
- Folding/unfolding keeps playing without recreating the activity (`configChanges` in the manifest).
- Hardware decoding (`mediacodec`, `mediacodec-copy`) deadlocks on the emulator when folding swaps the surface;
  software decoding doesn't. Retry on the real Fold.
- With software decoding the emulator draws a grid of thin lines over the video. It's probably the emulator's software
  GPU (SwiftShader); check on the Fold or with `emulator -gpu host`.
- Memory while playing: ~149 MB PSS after capping mpv's demuxer cache (default was ~296 MB).
- libass found no fontconfig and uses a fallback font: anime subs need a bundled fallback (incl. CJK) plus
  the fonts embedded in MKVs.
- Debug APK is ~88 MB (two ABIs, uncompressed native libs); release builds should split per ABI.

## Build

JDK 17 and the Android SDK (`ANDROID_HOME`), then:

```bash
./gradlew installDebug
```

Spike with another source: `adb shell am start -n app.kotakbase/.MainActivity --es source "magnet:?..."`
