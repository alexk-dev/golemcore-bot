import { useQuery } from '@tanstack/react-query';
import { getCommands } from '../api/commands';

export function useCommands() {
  return useQuery({
    queryKey: ['commands'],
    queryFn: getCommands,
  });
}
