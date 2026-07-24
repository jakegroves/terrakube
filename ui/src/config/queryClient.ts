import { QueryClient } from "@tanstack/react-query";

// Shared server-state cache: dedupes concurrent requests for the same data,
// avoids refetching on every navigation between already-visited pages, and
// is primed directly by router loaders (which run outside the React tree,
// so they need this instance rather than the useQueryClient() hook).
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 10000,
      refetchOnWindowFocus: true,
    },
  },
});
