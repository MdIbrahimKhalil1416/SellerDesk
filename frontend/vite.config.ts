import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// https://vite.dev/config/
export default defineConfig(({ command, mode }) => {
  // VITE_API_URL is read at BUILD time and baked into the JavaScript (see src/lib/env.ts).
  // Without it a production build calls /api/... on the static site itself, which has no API.
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  if (command === 'build' && mode === 'production' && !env.VITE_API_URL) {
    console.warn(
      '\n⚠  VITE_API_URL is not set: this build will call /api on its own origin.\n' +
        '   Fine for CI; for Render set VITE_API_URL to the backend URL (docs/DEPLOYMENT.md).\n',
    )
  }

  return {
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
  }
})
