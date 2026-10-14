/** 按浏览器动画帧合并网络片段；完成与停止时可立即刷新。 */
export function createFrameText(
  publish: (text: string) => void,
  schedule: (callback: FrameRequestCallback) => number = requestAnimationFrame,
  cancel: (id: number) => void = cancelAnimationFrame,
) {
  let pending = ''
  let frame: number | null = null
  function flush() {
    if (frame !== null) cancel(frame)
    frame = null
    const text = pending
    pending = ''
    if (text) publish(text)
  }
  return {
    append(text: string) {
      pending += text
      if (frame === null) frame = schedule(flush)
    },
    flush,
    clear() {
      if (frame !== null) cancel(frame)
      frame = null
      pending = ''
    },
  }
}
