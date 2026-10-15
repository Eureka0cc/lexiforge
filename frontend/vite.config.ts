import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'VITE_')
  return {
  plugins: [vue()],
  cacheDir: '../../tmp/vite-cache',
  build: { outDir: '../../tmp/frontend-dist', emptyOutDir: true },
  server: { host: '127.0.0.1', port: 5173, proxy: { '/api': { target: env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:8080', changeOrigin: true } } },
  }
})
