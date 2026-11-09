// Serves these test extensions on this PC only: node test-extensions/serve.mjs
// Then `adb reverse tcp:8099 tcp:8099` and add http://localhost:8099/index.json in the app's Settings → Extensions.
import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { join, basename } from 'node:path'

const dir = import.meta.dirname
createServer(async (req, res) => {
  const file = basename(new URL(req.url, 'http://x').pathname) // no paths out of this folder
  try {
    const body = await readFile(join(dir, file))
    res.writeHead(200, { 'content-type': file.endsWith('.json') ? 'application/json' : 'text/javascript' }).end(body)
  } catch { res.writeHead(404).end() }
}).listen(8099, '127.0.0.1', () => console.log('http://localhost:8099/index.json'))
