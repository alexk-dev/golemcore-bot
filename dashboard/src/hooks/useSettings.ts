import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getSettings, updatePreferences, getModels } from '../api/settings';

export function useSettings() {
  return useQuery({ queryKey: ['settings'], queryFn: getSettings });
}

export function useModels() {
  return useQuery({ queryKey: ['settings', 'models'], queryFn: getModels });
}

export function useUpdatePreferences() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: updatePreferences,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['settings'] }),
  });
}
