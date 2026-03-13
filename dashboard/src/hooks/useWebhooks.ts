import {
  type UseMutationResult,
  type UseQueryResult,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import {
  createDefaultWebhookConfig,
  type WebhookConfig,
  getWebhookConfig,
  updateWebhookConfig,
} from '../api/webhooks';

export const WEBHOOKS_QUERY_KEY = ['settings', 'webhooks'] as const;

export function useWebhookConfig(): UseQueryResult<WebhookConfig, unknown> {
  return useQuery({
    queryKey: WEBHOOKS_QUERY_KEY,
    queryFn: getWebhookConfig,
    placeholderData: createDefaultWebhookConfig(),
  });
}

export function useUpdateWebhookConfig(): UseMutationResult<void, unknown, WebhookConfig> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (config: WebhookConfig) => updateWebhookConfig(config),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: WEBHOOKS_QUERY_KEY });
      await queryClient.invalidateQueries({ queryKey: ['settings'] });
      await queryClient.invalidateQueries({ queryKey: ['runtime-config'] });
    },
  });
}
