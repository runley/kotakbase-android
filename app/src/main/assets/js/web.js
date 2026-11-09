// The browser APIs Hayase extensions use, for QuickJS (which has none): fetch, Headers, Response, URL,
// URLSearchParams, TextEncoder/TextDecoder, atob/btoa, setTimeout, console. Network and URL parsing are done by
// the app (__fetch, __url); the rest is plain JS. Extensions written for a browser Worker run unchanged on this.
(() => {
  const g = globalThis
  g.self = g
  g.console = { log: (...a) => __log(a.map(String).join(' ')), warn: (...a) => __log(a.map(String).join(' ')), error: (...a) => __log(a.map(String).join(' ')), info: (...a) => __log(a.map(String).join(' ')), debug: () => {} }

  // ---- timers ----
  let nextTimer = 0
  const cancelled = new Set()
  g.setTimeout = (fn, ms = 0, ...args) => { const id = ++nextTimer; __sleep(Number(ms) || 0).then(() => { if (!cancelled.delete(id)) fn(...args) }); return id }
  g.clearTimeout = id => { cancelled.add(id) }
  g.setInterval = (fn, ms = 0, ...args) => { const id = ++nextTimer; const tick = () => __sleep(Number(ms) || 0).then(() => { if (cancelled.has(id)) return cancelled.delete(id); fn(...args); tick() }); tick(); return id }
  g.clearInterval = g.clearTimeout
  g.queueMicrotask ??= fn => Promise.resolve().then(fn)

  // ---- text ----
  g.TextEncoder = class { get encoding () { return 'utf-8' } encode (s = '') { const u = unescape(encodeURIComponent(String(s))); return Uint8Array.from(u, c => c.charCodeAt(0)) } }
  g.TextDecoder = class { constructor (enc = 'utf-8') { this.encoding = enc } decode (b) { if (!b) return ''; const a = b instanceof Uint8Array ? b : new Uint8Array(b.buffer ?? b); let s = ''; for (const x of a) s += String.fromCharCode(x); try { return decodeURIComponent(escape(s)) } catch { return s } } }
  const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
  g.btoa = s => { s = String(s); let out = ''; for (let i = 0; i < s.length; i += 3) { const [a, b, c] = [s.charCodeAt(i), s.charCodeAt(i + 1), s.charCodeAt(i + 2)]; const n = (a << 16) | ((b || 0) << 8) | (c || 0); out += B64[n >> 18 & 63] + B64[n >> 12 & 63] + (isNaN(b) ? '=' : B64[n >> 6 & 63]) + (isNaN(c) ? '=' : B64[n & 63]) } return out }
  g.atob = s => { s = String(s).replace(/[^A-Za-z0-9+/]/g, ''); let out = ''; for (let i = 0; i < s.length; i += 4) { const n = [0, 1, 2, 3].reduce((v, k) => (v << 6) | Math.max(0, B64.indexOf(s[i + k] ?? 'A')), 0); out += String.fromCharCode(n >> 16 & 255); if (s[i + 2]) out += String.fromCharCode(n >> 8 & 255); if (s[i + 3]) out += String.fromCharCode(n & 255) } return out }

  // ---- URLSearchParams / URL ----
  const enc = s => encodeURIComponent(s).replace(/%20/g, '+')
  const dec = s => { try { return decodeURIComponent(s.replace(/\+/g, ' ')) } catch { return s } }
  class URLSearchParams {
    constructor (init = '') {
      this._ = []
      if (typeof init === 'string') { for (const p of init.replace(/^\?/, '').split('&').filter(Boolean)) { const i = p.indexOf('='); this._.push(i < 0 ? [dec(p), ''] : [dec(p.slice(0, i)), dec(p.slice(i + 1))]) } } else if (init instanceof URLSearchParams) this._ = init._.map(x => [...x])
      else if (Array.isArray(init) || init?.[Symbol.iterator]) for (const [k, v] of init) this._.push([String(k), String(v)])
      else if (init) for (const k of Object.keys(init)) this._.push([k, String(init[k])])
    }
    _changed () { this._url?._setSearch(this.toString()) }
    append (k, v) { this._.push([String(k), String(v)]); this._changed() }
    set (k, v) { const i = this._.findIndex(x => x[0] === k); if (i < 0) this._.push([String(k), String(v)]); else { this._[i][1] = String(v); this._ = this._.filter((x, j) => j <= i || x[0] !== k) } this._changed() }
    get (k) { return this._.find(x => x[0] === k)?.[1] ?? null }
    getAll (k) { return this._.filter(x => x[0] === k).map(x => x[1]) }
    has (k) { return this._.some(x => x[0] === k) }
    delete (k) { this._ = this._.filter(x => x[0] !== k); this._changed() }
    sort () { this._.sort((a, b) => a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0); this._changed() }
    forEach (fn) { for (const [k, v] of this._) fn(v, k, this) }
    keys () { return this._.map(x => x[0])[Symbol.iterator]() }
    values () { return this._.map(x => x[1])[Symbol.iterator]() }
    entries () { return this._.map(x => [...x])[Symbol.iterator]() }
    [Symbol.iterator] () { return this.entries() }
    get size () { return this._.length }
    toString () { return this._.map(([k, v]) => enc(k) + '=' + enc(v)).join('&') }
  }
  g.URLSearchParams = URLSearchParams

  class URL {
    constructor (href, base) {
      const p = JSON.parse(__url(String(href), base === undefined ? '' : String(base)))
      if (p.error) throw new TypeError(`Invalid URL: ${href}`)
      Object.assign(this, { protocol: p.protocol, username: p.username, password: p.password, hostname: p.hostname, port: p.port, pathname: p.pathname, hash: p.hash })
      this._search = p.search
      this.searchParams = new URLSearchParams(p.search)
      this.searchParams._url = this
    }
    _setSearch (s) { this._search = s ? '?' + s : '' }
    get search () { return this._search }
    set search (s) { s = String(s); this._search = s && !s.startsWith('?') ? '?' + s : s; const sp = new URLSearchParams(this._search); this.searchParams._ = sp._ }
    get host () { return this.hostname + (this.port ? ':' + this.port : '') }
    get origin () { return this.protocol + '//' + this.host }
    get href () { const auth = this.username ? this.username + (this.password ? ':' + this.password : '') + '@' : ''; return this.protocol + '//' + auth + this.host + this.pathname + this.search + this.hash }
    set href (v) { Object.assign(this, new URL(v)) }
    toString () { return this.href }
    toJSON () { return this.href }
    static canParse (u, b) { try { new URL(u, b); return true } catch { return false } }
  }
  g.URL = URL

  // ---- fetch ----
  class Headers {
    constructor (init) { this._ = new Map(); if (init instanceof Headers) init.forEach((v, k) => this.append(k, v)); else if (Array.isArray(init)) for (const [k, v] of init) this.append(k, v); else if (init) for (const k of Object.keys(init)) this.append(k, init[k]) }
    append (k, v) { k = k.toLowerCase(); this._.set(k, this._.has(k) ? this._.get(k) + ', ' + v : String(v)) }
    set (k, v) { this._.set(k.toLowerCase(), String(v)) }
    get (k) { return this._.get(k.toLowerCase()) ?? null }
    has (k) { return this._.has(k.toLowerCase()) }
    delete (k) { this._.delete(k.toLowerCase()) }
    forEach (fn) { for (const [k, v] of this._) fn(v, k, this) }
    entries () { return this._.entries() }
    keys () { return this._.keys() }
    values () { return this._.values() }
    [Symbol.iterator] () { return this._.entries() }
  }
  g.Headers = Headers

  class Response {
    constructor (body = '', init = {}) { this._body = String(body ?? ''); this.status = init.status ?? 200; this.statusText = init.statusText ?? ''; this.headers = new Headers(init.headers); this.url = init.url ?? ''; this.ok = this.status >= 200 && this.status < 300; this.bodyUsed = false; this.redirected = false; this.type = 'basic' }
    async text () { this.bodyUsed = true; return this._body }
    async json () { this.bodyUsed = true; return JSON.parse(this._body) }
    // shortcut: the body crosses from the app as text, so binary bodies (images, .torrent files) come out mangled;
    // pass bytes through __fetch if an extension needs them
    async arrayBuffer () { this.bodyUsed = true; return new TextEncoder().encode(this._body).buffer }
    clone () { return new Response(this._body, this) }
  }
  g.Response = Response
  g.Request = class Request { constructor (url, init = {}) { this.url = String(url?.url ?? url); this.method = init.method ?? 'GET'; this.headers = new Headers(init.headers); this.body = init.body } }

  g.AbortController = class AbortController { constructor () { this.signal = { aborted: false, addEventListener () {}, removeEventListener () {} } } abort () { this.signal.aborted = true } }
  g.AbortSignal = { timeout: () => ({ aborted: false, addEventListener () {}, removeEventListener () {} }) }

  g.fetch = async (input, init = {}) => {
    const url = String(input?.url ?? input)
    const headers = {}
    new Headers(init.headers ?? input?.headers).forEach((v, k) => { headers[k] = v })
    let body = init.body ?? null
    if (body instanceof URLSearchParams) { body = body.toString(); headers['content-type'] ??= 'application/x-www-form-urlencoded;charset=UTF-8' }
    if (body !== null && typeof body !== 'string') body = JSON.stringify(body)
    const r = JSON.parse(await __fetch(url, String(init.method ?? 'GET').toUpperCase(), JSON.stringify(headers), body))
    if (r.error) throw new TypeError(`fetch failed: ${r.error}`)
    return new Response(r.body, { status: r.status, statusText: r.statusText, headers: r.headers, url: r.url })
  }
})()
