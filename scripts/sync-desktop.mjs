// Copies what this app shares with the desktop app: node scripts/sync-desktop.mjs [path to the desktop repo]
// - translations (ui/locales) as JSON; keys are the English text, plural entries stay objects
// - the pure JS modules the phone runs as they are in QuickJS: release names and Auto Select, languages, manga matching
import { copyFileSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const desktop = resolve(process.argv[2] ?? '../Kotakbase')

const locales = 'app/src/main/assets/locales'
mkdirSync(locales, { recursive: true })
const { EN } = await import(pathToFileURL(join(desktop, 'ui/i18n.js')))
writeFileSync(join(locales, 'en.json'), JSON.stringify(EN))
for (const file of readdirSync(join(desktop, 'ui/locales')).filter(f => f.endsWith('.js'))) {
  const table = (await import(pathToFileURL(join(desktop, 'ui/locales', file)))).default
  writeFileSync(join(locales, file.replace('.js', '.json')), JSON.stringify(table))
  console.log(file, Object.keys(table).length)
}

const js = 'app/src/main/assets/js'
mkdirSync(js, { recursive: true })
for (const file of ['release.js', 'lang.js', 'manga.js']) {
  copyFileSync(join(desktop, 'ui', file), join(js, file))
  console.log(file)
}
// which file of a torrent is an episode (the desktop's main process uses it; it has no imports, so it runs here too)
copyFileSync(join(desktop, 'episode.js'), join(js, 'episode.js'))
console.log('episode.js')
// AniList sync's pure rules (merge, pushTarget, …); the loop around them is Kotlin (AniSync.kt). Its i18n import is
// assets/js/i18n.js, a stand-in (the desktop's needs Intl, which QuickJS lacks)
for (const file of ['anilist-sync.js']) {
  copyFileSync(join(desktop, 'ui', file), join(js, file))
  console.log(file)
}
// the API client ids (AniList sign-in, MyAnimeList), from the desktop's secrets file, kept out of git like there
const { readFileSync, existsSync } = await import('node:fs')
const secrets = join(desktop, 'secrets.local.json')
if (existsSync(secrets)) {
  const s = JSON.parse(readFileSync(secrets, 'utf8'))
  writeFileSync('secrets.properties', `anilistClientId=${s.anilistClientId ?? ''}\nmalClientId=${s.malClientId ?? ''}\n`)
  console.log('secrets.properties')
}
// Watch Together, unchanged: it runs in an invisible WebView (assets/js/room.html), which has the WebRTC it needs
for (const file of ['room.js', 'room-net.js']) {
  copyFileSync(join(desktop, 'ui', file), join(js, file))
  console.log(file)
}
