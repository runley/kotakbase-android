// What the phone asks of the desktop's shared modules (copied as they are by scripts/sync-desktop.mjs), in one call
// each, so the source picker ranks and Auto Selects exactly like desktop's.
import { parseRelease, releaseTags, audioLanguages, subLanguages, rankByLanguage, pickAuto, forThisEntry, otherEntryWords, entryNumbers } from './release.js'
import { LANGS, fromMpv, pickTrack, pickSubTrack } from './lang.js'
import { pickFile, findEpisode, markedEpisode, VIDEO, RISKY } from './episode.js'
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

// ---- AniList sync: the desktop's rules (anilist-sync.js, unchanged); its loop (network, queue, pacing) is AniSync.kt ----
import { localEntry, toLocal, merge, pushTarget, saveVars, same } from './anilist-sync.js'
const norm = status => status === 'PAUSED' || status === 'REPEATING' ? 'CURRENT' : status
const view = r => r ? { status: norm(r.status), progress: r.progress ?? 0, score: r.score ?? 0 } : null

// Kotakbase's entry for every show it has state for (desktop createSync locals()); shows: id → AniList details, if known
export function syncLocals (progress, list, scores, shows) {
  const ids = new Set([...Object.keys(progress), ...Object.keys(scores)].map(Number).concat(list))
  return Object.fromEntries([...ids].map(id => [id, localEntry(progress[id], list.includes(id), scores[id] ?? 0, shows[id])]))
}

// A pull (desktop createSync pull()): local entries, AniList's (as fromApi), the base and the waiting outbox →
// what to take in here and which shows to send
export function syncPull (local, remote, old, box) {
  const imports = []
  const sends = []
  for (const id of new Set([...Object.keys(local), ...Object.keys(remote)])) {
    if (box[id]) continue // a change here is still waiting to be sent: it's newer
    const L = local[id] ?? null
    const T = merge(L, remote[id], old[id])
    if (!T) continue
    if (!same(L, T)) imports.push([Number(id), T])
    if (!same(view(remote[id]), T)) sends.push(Number(id))
  }
  return { imports, sends }
}

// Takes AniList's entries in (desktop createSync apply()): the new progress, list and scores; raw: AniList's list
// entries by id (episode counts, updatedAt)
export function syncApply (imports, raw, progress, list, scores, shows) {
  for (const [id, T] of imports) {
    const L = localEntry(progress[id], list.includes(id), scores[id] ?? 0, shows[id])
    if (T.status === 'PLANNING') { if (!list.includes(id)) list.push(id) } else if (progress[id]?.rewatch && T.status === 'COMPLETED') {
      // rewatching here: AniList still saying completed doesn't take the rewatch's progress back
    } else if (!L || L.status !== T.status || L.progress !== T.progress) {
      const e = raw[id]
      progress[id] = { ...toLocal(T, progress[id], e?.media?.episodes ?? e?.media?.chapters ?? shows[id]?.episodes ?? shows[id]?.chapters), at: Math.max(progress[id]?.at ?? 0, (e?.updatedAt ?? 0) * 1000) || Date.now() }
    }
    if (T.score) scores[id] = T.score
    else delete scores[id]
  }
  return { progress, list, scores }
}

// Sending one show: pushTarget's target ('delete', an entry, or null) and SaveMediaListEntry's variables
export function syncPush (mediaId, L, old, unwatch, live) {
  const target = pushTarget(L, old, unwatch, live)
  return { target, vars: target && target !== 'delete' ? saveVars(mediaId, target, live) : null }
}

// ---- downloads ----
// Which files of a torrent to download (desktop downloads.js chooseFiles, which also needs Node's fs, so it's repeated
// here): one file asked for by index, or each episode found by its number; for one episode, a torrent's only video
// counts too unless its name marks another episode. files: [{ name, index }]; episodes: [{ episode, numbering }].
// Returns [{ index, episode }], [] when nothing matches.
export function chooseFiles (files, fileIndex, episodes) {
  if (fileIndex != null) return files[fileIndex] && VIDEO.test(files[fileIndex].name) ? [{ index: fileIndex, episode: episodes[0]?.episode ?? null }] : []
  const videos = files.filter(f => VIDEO.test(f.name))
  const lone = e => episodes.length === 1 && videos.length === 1 && [null, e.episode].includes(markedEpisode(videos[0].name)) ? videos[0] : null
  const found = episodes.map(e => ({ file: findEpisode(files, e.episode, e.numbering ?? {}) ?? lone(e), episode: e.episode }))
  const seen = new Set()
  return found.filter(f => f.file && VIDEO.test(f.file.name) && !seen.has(f.file) && seen.add(f.file)).map(f => ({ index: f.file.index, episode: f.episode }))
}
