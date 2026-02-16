import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listSkills, getSkill, getMcpStatus, createSkill, updateSkill, deleteSkill } from '../api/skills';

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

export function useCreateSkill() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ name, content }: { name: string; content: string }) => createSkill(name, content),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['skills'] }),
  });
}

export function useUpdateSkill() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ name, content }: { name: string; content: string }) => updateSkill(name, content),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['skills'] }),
  });
}

export function useDeleteSkill() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => deleteSkill(name),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['skills'] }),
  });
}
