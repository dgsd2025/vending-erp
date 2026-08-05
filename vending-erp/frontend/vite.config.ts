import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import UnoCSS from 'unocss/vite'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue(), UnoCSS()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5174,
    proxy: {
      // /api → 后端 8081(后端 context-path 就是 /api,前缀原样转发)
      // 多人并行起服时可用 VITE_PROXY_TARGET 覆盖,如 VITE_PROXY_TARGET=http://127.0.0.1:8084 pnpm dev
      '/api': {
        target: process.env.VITE_PROXY_TARGET || 'http://127.0.0.1:8081',
        changeOrigin: true,
      },
    },
  },
})
