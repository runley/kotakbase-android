// What the phone asks of the desktop's shared modules (copied as they are by scripts/sync-desktop.mjs), in one call
// each, so the source picker ranks and Auto Selects exactly like desktop's.
import { parseRelease, releaseTags, audioLanguages, subLanguages, rankByLanguage, pickAuto, forThisEntry, otherEntryWords, entryNumbers } from './release.js'
import { LANGS, fromMpv, pickTrack, pickSubTrack } from './lang.js'
import { pickFile, findEpisode, VIDEO, RISKY } from './episode.js'
import { pickSeries, chapterRows, titlesOf } from './manga.js'

// Same title list Hayase builds: all titles + synonyms, plus "S2" variants of "Season 2" / "2nd Season" (app.js titles())
export function titles (m) {
  const out = []
  for (const t of new Set([...Object.values(m.title), ...(m.synonyms ?? [])].filter(t => t?.length > 3))) {
    for (const v of t.includes('-') ? [t, t.replaceAll('-', '')] : [t]) {
      out.push(v)
      const s = v.match(/Season (\d)/i) ?? v.match(/(\d)(?:nd|rd|th) Season/i)
      if (s) out.push(v.replace(s[0], `S${s[1]}`))
    }
  }
  return out
}

// Audio languages as short tags (JP, EN, CN, …), as on desktop's source cards
const SHORT = { jpn: 'JP', chi: 'CN', kor: 'KR', ukr: 'UA', kaz: 'KZ' }
const short = k => SHORT[k] ?? LANGS[k]?.[1]?.toUpperCase() ?? k

/**
 * The source list as desktop's picker shows it (app.js sources()): releases naming another season or entry of the
 * franchise dropped; once the search is done, ones with no seeders hidden; your languages first, then the release
 * group used last time. ctx: { titles, tvdbSeason, related: other entries' names, prefs: { audio, subs, first },
 * last: group, dub: ids of dub-only extensions, done }. Returns { results, auto: index Auto Select takes, dead }.
 */
export function arrange (raw, ctx) {
  const others = otherEntryWords(ctx.titles, ctx.related ?? [])
  const seasons = [entryNumbers(ctx.titles).season, ctx.tvdbSeason]
  const matching = raw.filter(r => forThisEntry(r.titles, { seasons, others }))
  const found = ctx.done ? matching.filter(r => r.seeders > 0) : matching
  const audioOf = r => audioLanguages(r.titles, r.extensions.some(id => (ctx.dub ?? []).includes(id)))
  // a release that names no subtitle language is taken as English-subbed, the norm for the sources searched
  const subsOf = r => { const s = subLanguages(r.titles); return s.size ? s : new Set(['eng']) }
  const multi = r => releaseTags(r.titles).some(tag => tag.label === 'Multi Subs')
  const group = r => r.titles.map(name => parseRelease(name).group).find(Boolean)
  const ordered = ctx.last ? [...found.filter(r => group(r) === ctx.last), ...found.filter(r => group(r) !== ctx.last)] : found
  const ranked = rankByLanguage(ordered, ctx.prefs, audioOf, subsOf, multi)
  const auto = ranked.length ? ranked.indexOf(pickAuto(ranked)) : -1
  const results = ranked.map(r => ({
    ...r,
    group: group(r) ?? '',
    audio: [...audioOf(r)].map(short),
    subs: multi(r) ? [] : [...subLanguages(r.titles)].map(short),
    tags: [...new Set(releaseTags(r.titles).map(tag => tag.label))]
  }))
  return { results, auto, dead: matching.length - found.length }
}

// ---- manga ----
export const mangaTitles = m => titlesOf(m)
export const series = (m, results) => pickSeries(m, results)
export const chapters = (list, langs) => chapterRows(list, langs)
// Settings' languages (ISO 639-2 keys) as the 2-letter codes manga extensions take, best first
export const readLangs = keys => [...new Set(keys.map(k => LANGS[k]?.[1]).filter(Boolean))]

// ---- the player ----
// files: [{ name, index }] of the torrent; ids: { abs, tvdbSeason, tvdbSeasonEpisode } for continued seasons
const indexOf = f => f ? f.index : -1
export const fileFor = (files, episode, ids) => indexOf(pickFile(files, episode, ids ?? {}))
// the next episode's file in the same torrent (a season pack), or -1: only when its name says so
export const nextFile = (files, episode, ids, current) => { const f = findEpisode(files, episode, ids ?? {}); return f && f.index !== current ? f.index : -1 }
// checked on the file list alone, before anything downloads (desktop main.js startPlay)
export const refuse = files => files.some(f => RISKY.test(f.name)) ? 'risky' : files.some(f => VIDEO.test(f.name)) ? null : 'novideo'
// mpv's track-list → the audio and subtitle tracks to turn on by Settings' language lists (null: leave / off)
export function tracks (list, audio, subs) {
  const a = fromMpv(list, 'audio')
  const s = fromMpv(list, 'sub')
  const ai = pickTrack(a, audio)
  const sub = pickSubTrack(s, subs)
  return { aid: ai >= 0 ? a[ai].number : null, sid: sub ? sub.number : null }
}
