import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': process.env.VITE_API_TARGET || 'http://localhost:8080',
      '/ws': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8080',
        ws: true
      }
    }
  },
  define: {
    global: 'globalThis'
  },
  test: {
    exclude: ['**/node_modules/**', '**/dist/**', '**/e2e/**']
  }
})
