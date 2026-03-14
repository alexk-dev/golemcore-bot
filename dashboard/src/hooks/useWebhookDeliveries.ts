import {
  type UseMutationResult,
  type UseQueryResult,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import {
  getWebhookDeliveries,
  getWebhookDelivery,
  retryWebhookDelivery,
  sendWebhookTestDelivery,
  type TestWebhookDeliveryRequest,
  type WebhookDeliveriesResponse,
  type WebhookDeliveryDetail,
  type WebhookDeliveryFilters,
} from '../api/webhookDeliveries';

export const WEBHOOK_DELIVERIES_QUERY_KEY = ['webhooks', 'deliveries'] as const;

export function useWebhookDeliveries(
  filters: WebhookDeliveryFilters,
): UseQueryResult<WebhookDeliveriesResponse, unknown> {
  return useQuery({
    queryKey: [...WEBHOOK_DELIVERIES_QUERY_KEY, filters.status ?? 'ALL', filters.limit ?? 50],
    queryFn: () => getWebhookDeliveries(filters),
    refetchInterval: 15000,
  });
}

export function useWebhookDelivery(
  deliveryId: string | null,
): UseQueryResult<WebhookDeliveryDetail, unknown> {
  return useQuery({
    queryKey: [...WEBHOOK_DELIVERIES_QUERY_KEY, 'detail', deliveryId],
    queryFn: () => getWebhookDelivery(deliveryId ?? ''),
    enabled: deliveryId != null && deliveryId.length > 0,
    refetchInterval: 15000,
  });
}

export function useRetryWebhookDelivery(): UseMutationResult<WebhookDeliveryDetail, unknown, string> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (deliveryId: string) => retryWebhookDelivery(deliveryId),
    onSuccess: async (delivery) => {
      await queryClient.invalidateQueries({ queryKey: WEBHOOK_DELIVERIES_QUERY_KEY });
      await queryClient.invalidateQueries({
        queryKey: [...WEBHOOK_DELIVERIES_QUERY_KEY, 'detail', delivery.deliveryId],
      });
    },
  });
}

export function useSendWebhookTestDelivery(): UseMutationResult<WebhookDeliveryDetail, unknown, TestWebhookDeliveryRequest> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: TestWebhookDeliveryRequest) => sendWebhookTestDelivery(request),
    onSuccess: async (delivery) => {
      await queryClient.invalidateQueries({ queryKey: WEBHOOK_DELIVERIES_QUERY_KEY });
      await queryClient.invalidateQueries({
        queryKey: [...WEBHOOK_DELIVERIES_QUERY_KEY, 'detail', delivery.deliveryId],
      });
    },
  });
}
