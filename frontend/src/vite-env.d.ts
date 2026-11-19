/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Backend base URL in production; empty in dev (Vite proxy). See .env.example. */
  readonly VITE_API_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
