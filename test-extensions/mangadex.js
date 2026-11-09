// Test manga extension on MangaDex's official public API (api.mangadex.org), to try the chapter list and reader.
const API = 'https://api.mangadex.org'

const json = async url => {
  const res = await fetch(url)
  if (!res.ok) throw new Error(`MangaDex: ${res.status}`)
  return res.json()
}

export default {
  async test () { return (await fetch(`${API}/ping`)).ok },

  async search ({ titles, anilistId }) {
    const url = new URL('/manga', API)
    url.searchParams.set('title', titles[0] ?? '')
    url.searchParams.set('limit', '10')
    for (const r of ['safe', 'suggestive']) url.searchParams.append('contentRating[]', r)
    const { data } = await json(url)
    return data.map(m => ({
      id: m.id,
      title: m.attributes.title.en ?? Object.values(m.attributes.title)[0],
      anilistId: m.attributes.links?.al ? Number(m.attributes.links.al) : undefined
    }))
  },

  async chapters ({ id, langs }) {
    const out = []
    for (let offset = 0; offset < 1000; offset += 100) {
      const url = new URL(`/manga/${id}/feed`, API)
      url.searchParams.set('limit', '100')
      url.searchParams.set('offset', String(offset))
      url.searchParams.set('order[chapter]', 'asc')
      for (const l of langs) url.searchParams.append('translatedLanguage[]', l)
      const page = await json(url)
      for (const c of page.data) {
        if (c.attributes.externalUrl || !c.attributes.pages) continue
        out.push({ id: c.id, number: c.attributes.chapter ? Number(c.attributes.chapter) : null, title: c.attributes.title ?? '', group: '', lang: c.attributes.translatedLanguage, date: new Date(c.attributes.publishAt) })
      }
      if (offset + 100 >= page.total) break
    }
    return out
  },

  async pages ({ id }) {
    const at = await json(`${API}/at-home/server/${id}`)
    return at.chapter.data.map(f => `${at.baseUrl}/data/${at.chapter.hash}/${f}`)
  }
}
