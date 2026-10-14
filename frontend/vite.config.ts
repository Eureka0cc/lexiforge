import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({
  plugins: [vue()],
  cacheDir: '../../tmp/vite-cache',
  build: { outDir: '../../tmp/frontend-dist', emptyOutDir: true },
  server: { port: 5173, proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } } },
})
