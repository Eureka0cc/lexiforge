import { defineConfig } from 'vitest/config'
export default defineConfig({ cacheDir: '../../tmp/vitest-cache', test: { include: ['src/**/*.test.ts'] } })
