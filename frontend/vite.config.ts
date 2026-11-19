import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
//
// The built app (dist/) is served by the Spring Boot backend from the same origin as the API
// (root Dockerfile copies it to the backend's static resources). The code always calls relative
// /api/... paths, so there is no API URL to configure at build time.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    // Dev only: the browser talks to http://localhost:5173 for everything, and Vite forwards
    // /api/* to the backend. One origin for the browser, exactly like production: the refresh
    // cookie (SameSite=Strict, Path=/api/v1/auth) works and no CORS is needed.
    // changeOrigin: false keeps the Host header (localhost:5173) equal to the browser's Origin, so
    // the backend also sees a same-origin request. Its refresh Origin check expects APP_URL, which
    // defaults to http://localhost:5173 in the dev profile.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: false,
      },
    },
  },
})
