import { describe, expect, it } from 'vitest'
import { readSseStream, type SseMessage } from './sse'

function stream(chunks: Uint8Array[]) {
  return new ReadableStream<Uint8Array>({ start(controller) {
    chunks.forEach(chunk => controller.enqueue(chunk))
    controller.close()
  } })
}

describe('流式传输', () => {
  it('任意字节切分仍能解析中文、Emoji 与 CRLF', async () => {
    const bytes = new TextEncoder().encode('event: delta\r\ndata: {"text":"你好🚀"}\r\n\r\nevent: completed\r\ndata: {}\r\n\r\n')
    for (let split = 0; split <= bytes.length; split++) {
      const messages: SseMessage[] = []
      await readSseStream(stream([bytes.slice(0, split), bytes.slice(split)]), item => messages.push(item))
      expect(messages).toEqual([{ event: 'delta', data: { text: '你好🚀' } }, { event: 'completed', data: {} }])
    }
  })
  it('支持心跳与多个 data 行', async () => {
    const messages: SseMessage[] = []
    await readSseStream(stream([new TextEncoder().encode(': heartbeat\n\nevent: delta\ndata: {\ndata: "text":"ok"}\n\n')]), item => messages.push(item))
    expect(messages).toEqual([{ event: 'delta', data: { text: 'ok' } }])
  })
  it('拒绝截断数据', async () => {
    await expect(readSseStream(stream([new TextEncoder().encode('event: delta\ndata: {"text":')]), () => undefined))
      .rejects.toThrow('未完整结束')
  })
  it('消费者失败时取消读取，避免继续生成', async () => {
    let cancelled = false
    const body = new ReadableStream<Uint8Array>({
      start(controller) { controller.enqueue(new TextEncoder().encode('event: error\ndata: {}\n\n')) },
      cancel() { cancelled = true },
    })
    await expect(readSseStream(body, () => { throw new Error('业务失败') })).rejects.toThrow('业务失败')
    expect(cancelled).toBe(true)
  })
})
