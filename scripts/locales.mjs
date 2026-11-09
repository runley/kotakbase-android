// Copies the desktop app's translations into this app: node scripts/locales.mjs [path to the desktop repo]
// Keys are the English text, as on desktop; plural entries stay objects ({ one, few, many, other }).
import { mkdirSync, readdirSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const desktop = resolve(process.argv[2] ?? '../Kotakbase')
const out = 'app/src/main/assets/locales'
mkdirSync(out, { recursive: true })

const { EN } = await import(pathToFileURL(join(desktop, 'ui/i18n.js')))
writeFileSync(join(out, 'en.json'), JSON.stringify(EN))
for (const file of readdirSync(join(desktop, 'ui/locales')).filter(f => f.endsWith('.js'))) {
  const table = (await import(pathToFileURL(join(desktop, 'ui/locales', file)))).default
  writeFileSync(join(out, file.replace('.js', '.json')), JSON.stringify(table))
  console.log(file, Object.keys(table).length)
}
