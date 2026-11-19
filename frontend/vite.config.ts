import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    // Dev only: the browser talks to http://localhost:5173 for everything, and Vite forwards
    // /api/* to the backend. One origin for the browser means the refresh cookie (SameSite=Strict,
    // Path=/api/v1/auth) is stored and sent normally, and there are no CORS preflights.
    // The browser's Origin header (http://localhost:5173) is passed through unchanged, which is
    // what the backend's refresh Origin check and CORS config expect (FRONTEND_URL).
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
