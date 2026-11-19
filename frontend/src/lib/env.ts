/**
 * Backend base URL. Empty in dev, so requests go to the same origin (/api/...) and the Vite proxy
 * forwards them. In prod it is the backend's URL from VITE_API_URL (set at build time).
 */
export const API_BASE_URL = (import.meta.env.VITE_API_URL ?? '').replace(/\/+$/, '')
