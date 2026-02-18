import { type UseMutationResult, type UseQueryResult, useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listSkills, getSkill, getMcpStatus, createSkill, updateSkill, deleteSkill } from '../api/skills';

export function useSkills(): UseQueryResult<Awaited<ReturnType<typeof listSkills>>, unknown> {
  return useQuery({ queryKey: ['skills'], queryFn: listSkills });
}

export function useSkill(name: string): UseQueryResult<Awaited<ReturnType<typeof getSkill>>, unknown> {
  return useQuery({
    queryKey: ['skills', name],
    queryFn: () => getSkill(name),
    enabled: name.length > 0,
  });
}

export function useMcpStatus(name: string): UseQueryResult<Awaited<ReturnType<typeof getMcpStatus>>, unknown> {
  return useQuery({
    queryKey: ['skills', name, 'mcp'],
    queryFn: () => getMcpStatus(name),
    enabled: name.length > 0,
  });
}

export function useCreateSkill(): UseMutationResult<Awaited<ReturnType<typeof createSkill>>, unknown, { name: string; content: string }> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ name, content }: { name: string; content: string }) => createSkill(name, content),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['skills'] }),
  });
}

export function useUpdateSkill(): UseMutationResult<Awaited<ReturnType<typeof updateSkill>>, unknown, { name: string; content: string }> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ name, content }: { name: string; content: string }) => updateSkill(name, content),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['skills'] }),
  });
}

export function useDeleteSkill(): UseMutationResult<Awaited<ReturnType<typeof deleteSkill>>, unknown, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => deleteSkill(name),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['skills'] }),
  });
}
