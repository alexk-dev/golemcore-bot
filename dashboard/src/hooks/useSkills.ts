import { type UseMutationResult, type UseQueryResult, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createSkill,
  deleteSkill,
  getMcpStatus,
  getSkill,
  getSkillMarketplace,
  installSkillFromMarketplace,
  listSkills,
  type SkillInstallResult,
  type SkillMarketplaceCatalogResponse,
  type SkillInfo,
  updateSkill,
} from '../api/skills';

function invalidateSkillsQueries(queryClient: ReturnType<typeof useQueryClient>): Promise<unknown[]> {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: ['skills'] }),
    queryClient.invalidateQueries({ queryKey: ['skill-marketplace'] }),
  ]);
}

export function useSkills(): UseQueryResult<SkillInfo[], unknown> {
  return useQuery({ queryKey: ['skills'], queryFn: listSkills });
}

export function useSkill(name: string): UseQueryResult<SkillInfo, unknown> {
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

export function useSkillMarketplace(): UseQueryResult<SkillMarketplaceCatalogResponse, unknown> {
  return useQuery({
    queryKey: ['skill-marketplace'],
    queryFn: getSkillMarketplace,
  });
}

export function useInstallSkillFromMarketplace(): UseMutationResult<
  SkillInstallResult,
  unknown,
  { skillId: string }
> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ skillId }: { skillId: string }) => installSkillFromMarketplace(skillId),
    onSuccess: () => invalidateSkillsQueries(queryClient),
  });
}

export function useCreateSkill(): UseMutationResult<SkillInfo, unknown, { name: string; content: string }> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ name, content }: { name: string; content: string }) => createSkill(name, content),
    onSuccess: () => invalidateSkillsQueries(queryClient),
  });
}

export function useUpdateSkill(): UseMutationResult<SkillInfo, unknown, { name: string; content: string }> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ name, content }: { name: string; content: string }) => updateSkill(name, content),
    onSuccess: () => invalidateSkillsQueries(queryClient),
  });
}

export function useDeleteSkill(): UseMutationResult<void, unknown, string> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => deleteSkill(name),
    onSuccess: () => invalidateSkillsQueries(queryClient),
  });
}
