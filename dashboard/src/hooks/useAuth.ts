import { useQuery } from '@tanstack/react-query';
import { getMe } from '../api/auth';

export function useMe() {
  return useQuery({
    queryKey: ['auth', 'me'],
    queryFn: getMe,
    retry: false,
  });
}
