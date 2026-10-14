import { expect, it, vi } from 'vitest'
import { createFrameText } from './frameText'

it('合并同帧片段，完成时只输出一次', () => {
  const publish = vi.fn()
  const schedule = vi.fn(() => 1)
  const cancel = vi.fn()
  const buffer = createFrameText(publish, schedule, cancel)
  buffer.append('你')
  buffer.append('好')
  expect(schedule).toHaveBeenCalledTimes(1)
  expect(publish).not.toHaveBeenCalled()
  buffer.flush()
  buffer.flush()
  expect(publish).toHaveBeenCalledExactlyOnceWith('你好')
})

it('输入变化丢弃过期片段', () => {
  const publish = vi.fn()
  const buffer = createFrameText(publish, () => 1, vi.fn())
  buffer.append('旧结果')
  buffer.clear()
  buffer.flush()
  expect(publish).not.toHaveBeenCalled()
})
