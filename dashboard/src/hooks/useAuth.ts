import { type UseQueryResult, useQuery } from '@tanstack/react-query';
import { getMe } from '../api/auth';

export function useMe(): UseQueryResult<Awaited<ReturnType<typeof getMe>>, unknown> {
  return useQuery({
    queryKey: ['auth', 'me'],
    queryFn: getMe,
    retry: false,
  });
}
