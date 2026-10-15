// Fault-injection fixture only. It never calls an external model or logs request content.
import http from 'node:http'
import { writeFileSync } from 'node:fs'
const port = Number(process.argv[2])
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('invalid fixture port')
http.createServer((req, res) => {
  if (req.url === '/health') { res.writeHead(200); res.end('READY'); return }
  if (req.method !== 'POST' || req.url !== '/v1/chat/completions') { res.writeHead(404); res.end(); return }
  req.resume()
  req.on('end', () => {
    console.log('REQUEST_RECEIVED')
    if (process.argv[3]) writeFileSync(process.argv[3], 'received')
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' })
    res.write(`data: ${JSON.stringify({ id: 'fault-fixture', object: 'chat.completion.chunk', created: 1, model: 'fault-fixture', choices: [{ index: 0, delta: { role: 'assistant', content: '{"translatedText":"' } }] })}\n\n`)
    const heartbeat = setInterval(() => res.write(': fixture waiting\n\n'), 5000)
    const timer = setTimeout(() => res.end(), 240000)
    res.on('close', () => { clearInterval(heartbeat); clearTimeout(timer) })
  })
}).listen(port, '127.0.0.1', () => console.log('READY'))
