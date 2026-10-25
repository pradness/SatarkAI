import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/auth': 'http://127.0.0.1:8080',
      '/investigation': 'http://127.0.0.1:8080',
      '/admin': 'http://127.0.0.1:8080',
      '/calls': 'http://127.0.0.1:8080',
      '/transcripts': 'http://127.0.0.1:8080',
      '/feedback': 'http://127.0.0.1:8080',
      '/health': 'http://127.0.0.1:8080',
    }
  }
})
