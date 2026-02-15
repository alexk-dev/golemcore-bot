import { useQuery } from '@tanstack/react-query';
import { listSkills, getSkill, getMcpStatus } from '../api/skills';

export function useSkills() {
  return useQuery({ queryKey: ['skills'], queryFn: listSkills });
}

export function useSkill(name: string) {
  return useQuery({
    queryKey: ['skills', name],
    queryFn: () => getSkill(name),
    enabled: !!name,
  });
}

export function useMcpStatus(name: string) {
  return useQuery({
    queryKey: ['skills', name, 'mcp'],
    queryFn: () => getMcpStatus(name),
    enabled: !!name,
  });
}
