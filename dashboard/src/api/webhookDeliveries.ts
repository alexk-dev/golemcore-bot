import client from './client';

export type WebhookDeliveryStatus = 'PENDING' | 'IN_PROGRESS' | 'SUCCESS' | 'FAILED';
export type WebhookDeliverySource = string;

export interface WebhookDeliverySummary {
  deliveryId: string;
  runId: string | null;
  chatId: string | null;
  source: WebhookDeliverySource;
  callbackUrl: string | null;
  model: string | null;
  status: WebhookDeliveryStatus;
  attempts: number;
  lastError: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface WebhookDeliveryPayload {
  status: string | null;
  response: string | null;
  model: string | null;
  durationMs: number;
  error: string | null;
}

export interface WebhookDeliveryEvent {
  sequence: number;
  type: string;
  status: string;
  timestamp: string;
  attempt: number | null;
  message: string | null;
}

export interface WebhookDeliveryDetail extends WebhookDeliverySummary {
  payload: WebhookDeliveryPayload;
  events: WebhookDeliveryEvent[];
}

export interface WebhookDeliveriesResponse {
  deliveries: WebhookDeliverySummary[];
}

export interface TestWebhookDeliveryRequest {
  callbackUrl: string;
  runId: string | null;
  chatId: string | null;
  model: string | null;
  payloadStatus: 'completed' | 'failed';
  response: string | null;
  durationMs: number;
  errorMessage: string | null;
}

export interface WebhookDeliveryFilters {
  status?: WebhookDeliveryStatus;
  limit?: number;
}

export async function getWebhookDeliveries(filters?: WebhookDeliveryFilters): Promise<WebhookDeliveriesResponse> {
  const params = {
    status: filters?.status,
    limit: filters?.limit,
  };
  const { data } = await client.get<WebhookDeliveriesResponse>('/webhooks/deliveries', { params });
  return data;
}

export async function getWebhookDelivery(deliveryId: string): Promise<WebhookDeliveryDetail> {
  const { data } = await client.get<WebhookDeliveryDetail>(`/webhooks/deliveries/${encodeURIComponent(deliveryId)}`);
  return data;
}

export async function retryWebhookDelivery(deliveryId: string): Promise<WebhookDeliveryDetail> {
  const { data } = await client.post<WebhookDeliveryDetail>(`/webhooks/deliveries/${encodeURIComponent(deliveryId)}/retry`);
  return data;
}

export async function sendWebhookTestDelivery(request: TestWebhookDeliveryRequest): Promise<WebhookDeliveryDetail> {
  const { data } = await client.post<WebhookDeliveryDetail>('/webhooks/deliveries/test', request);
  return data;
}
