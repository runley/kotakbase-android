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
