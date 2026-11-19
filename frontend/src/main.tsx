import { QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { AuthProvider } from './features/auth/AuthProvider'
import './index.css'
import { queryClient } from './lib/queryClient'
import { AppRoutes } from './routes/AppRoutes'

const root = document.getElementById('root')
if (!root) {
  throw new Error('Missing <div id="root"> in index.html')
}

// Order matters: AuthProvider uses the router (navigate) and the query client (clear on logout).
createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <AppRoutes />
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
