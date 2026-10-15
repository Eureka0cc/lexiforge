// Local production-bundle host for full application rollback verification.
import http from 'node:http'
import { createReadStream, readFileSync, existsSync, statSync, realpathSync } from 'node:fs'
import path from 'node:path'
import { createHash } from 'node:crypto'
const root = realpathSync(process.argv[2])
const backend = new URL(process.argv[3])
const port = Number(process.argv[4])
if (backend.hostname !== '127.0.0.1' || backend.protocol !== 'http:' || !Number.isInteger(port)) throw new Error('loopback fixture only')
const index = path.join(root, 'index.html')
const indexHash = createHash('sha256').update(readFileSync(index)).digest('hex')
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2' }
http.createServer((req, res) => {
  if (req.url === '/__fixture-health') { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ ready: true, indexHash })); return }
  if (req.url.startsWith('/api/')) {
    const upstream = http.request(new URL(req.url, backend), { method: req.method, headers: { ...req.headers, host: backend.host } }, reply => {
      res.writeHead(reply.statusCode, reply.headers); reply.pipe(res)
    })
    upstream.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end('local backend unavailable') })
    req.pipe(upstream); res.on('close', () => upstream.destroy()); return
  }
  let pathname
  try { pathname = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname) } catch { res.writeHead(400); res.end(); return }
  const candidate = path.resolve(root, '.' + pathname)
  if (candidate !== root && !candidate.startsWith(root + path.sep)) { res.writeHead(403); res.end(); return }
  const file = existsSync(candidate) && statSync(candidate).isFile() ? candidate : index
  res.writeHead(200, { 'Content-Type': mime[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' })
  createReadStream(file).pipe(res)
}).listen(port, '127.0.0.1', () => console.log('READY'))
