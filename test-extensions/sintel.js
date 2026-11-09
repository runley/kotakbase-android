// Test torrent extension: answers every search with Blender's Sintel (CC BY 3.0), so the source picker can be tried
// with legal content. It still fetches over the network and reads headers, to exercise the app's fetch bridge.
const HASH = '08ada5a7a6183aae1e09d831df6748d566095a10'
const MAGNET = `magnet:?xt=urn:btih:${HASH}&dn=Sintel&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337&tr=wss%3A%2F%2Ftracker.openwebtorrent.com&ws=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2F&xs=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2Fsintel.torrent`

async function sintel (query) {
  const res = await fetch('https://webtorrent.io/torrents/Sintel/Sintel.mp4', { method: 'HEAD' })
  const size = Number(res.headers.get('content-length')) || 129241752
  const url = new URL('https://example.org/search')
  url.searchParams.set('q', query.titles?.[0] ?? '')
  return [{
    title: `[Blender] Sintel - ${String(query.episode ?? 1).padStart(2, '0')} [1080p] (searched ${url.searchParams.get('q')})`,
    link: MAGNET,
    hash: HASH,
    seeders: 30,
    leechers: 2,
    downloads: 1000,
    size,
    date: new Date('2010-09-27'),
    accuracy: 'high'
  }]
}

export default {
  async test () { return true },
  single: sintel,
  batch: async () => [],
  movie: sintel
}
