import { QueryClient } from '@tanstack/react-query'
import axios from 'axios'

/** Shared TanStack Query client: caches server data and refetches it when needed. */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      // Retrying 4xx errors (404, 403, validation) never helps; retry only network/5xx once.
      retry: (failureCount, error) => {
        const status = axios.isAxiosError(error) ? error.response?.status : undefined
        return status !== undefined && status < 500 ? false : failureCount < 1
      },
      refetchOnWindowFocus: false,
    },
  },
})
