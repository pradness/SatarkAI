import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const BACKEND_URL = process.env.VITE_BACKEND_URL || 'http://127.0.0.1:8000'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  base: './',
  server: {
    port: 5173,
    proxy: {
      '/auth': BACKEND_URL,
      '/investigation': BACKEND_URL,
      '/admin': BACKEND_URL,
      '/calls': BACKEND_URL,
      '/transcripts': BACKEND_URL,
      '/feedback': BACKEND_URL,
      '/health': BACKEND_URL,
      '/contacts': BACKEND_URL,
      '/predict': BACKEND_URL,
    }
  }
})

