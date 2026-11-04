import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [vue()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: { '/api': { target: env.SMARTDRIVE_GATEWAY_URL || 'http://127.0.0.1:8080', changeOrigin: true } },
    },
  }
})
