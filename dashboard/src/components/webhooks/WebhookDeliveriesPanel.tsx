import { type ReactElement, useMemo, useState } from 'react';
import { Alert, Button, Card, Spinner } from 'react-bootstrap';
import toast from 'react-hot-toast';
import { FiActivity } from 'react-icons/fi';
import type {
  TestWebhookDeliveryRequest,
  WebhookDeliveryStatus,
} from '../../api/webhookDeliveries';
import {
  useRetryWebhookDelivery,
  useSendWebhookTestDelivery,
  useWebhookDeliveries,
  useWebhookDelivery,
} from '../../hooks/useWebhookDeliveries';
import { extractErrorMessage } from '../../utils/extractErrorMessage';
import { DeliveryDetailCard } from './DeliveryDetailCard';
import { DeliveryFilterBar } from './DeliveryFilterBar';
import { DeliveryTable } from './DeliveryTable';
import { DEFAULT_DELIVERY_LIMIT } from './deliveryUtils';
import { TestDeliveryModal } from './TestDeliveryModal';

interface DeliveryPanelState {
  status: 'ALL' | WebhookDeliveryStatus;
  limit: number;
  selectedDeliveryId: string | null;
}

const INITIAL_STATE: DeliveryPanelState = {
  status: 'ALL',
  limit: DEFAULT_DELIVERY_LIMIT,
  selectedDeliveryId: null,
};

export function WebhookDeliveriesPanel(): ReactElement {
  const [panelState, setPanelState] = useState<DeliveryPanelState>(INITIAL_STATE);
  const [showTestModal, setShowTestModal] = useState(false);

  const filters = useMemo(() => ({
    status: panelState.status === 'ALL' ? undefined : panelState.status,
    limit: panelState.limit,
  }), [panelState.limit, panelState.status]);

  const deliveriesQuery = useWebhookDeliveries(filters);
  const deliveryDetailQuery = useWebhookDelivery(panelState.selectedDeliveryId);
  const retryMutation = useRetryWebhookDelivery();
  const sendTestMutation = useSendWebhookTestDelivery();

  const deliveries = deliveriesQuery.data?.deliveries ?? [];

  return (
    <Card className="settings-card mt-3">
      <Card.Body>
        <div className="d-flex flex-wrap align-items-start justify-content-between gap-2 mb-3">
          <div>
            <h5 className="mb-1 d-flex align-items-center gap-2">
              <FiActivity size={16} />
              Delivery attempts
            </h5>
            <p className="small text-body-secondary mb-0">
              Observe callback attempts, inspect delivery timeline, retry failed webhooks, and send test callbacks.
            </p>
          </div>
          <Button type="button" size="sm" variant="primary" onClick={() => setShowTestModal(true)}>
            Send test callback
          </Button>
        </div>

        <DeliveryFilterBar
          status={panelState.status}
          limit={panelState.limit}
          onStatusChange={(status) => setPanelState((current) => ({ ...current, status }))}
          onLimitChange={(limit) => setPanelState((current) => ({ ...current, limit }))}
          onRefresh={() => { void deliveriesQuery.refetch(); }}
          refreshPending={deliveriesQuery.isFetching}
        />

        {deliveriesQuery.isLoading ? (
          <div className="d-flex align-items-center gap-2 text-body-secondary mb-3">
            <Spinner size="sm" />
            <span>Loading delivery attempts...</span>
          </div>
        ) : (
          <DeliveryTable
            deliveries={deliveries}
            selectedDeliveryId={panelState.selectedDeliveryId}
            onSelect={(selectedDeliveryId) => setPanelState((current) => ({ ...current, selectedDeliveryId }))}
          />
        )}

        {deliveriesQuery.isError && (
          <Alert variant="danger" className="small mb-3">
            Failed to load deliveries: {extractErrorMessage(deliveriesQuery.error)}
          </Alert>
        )}

        <DeliveryDetailCard
          detail={deliveryDetailQuery.data}
          loading={deliveryDetailQuery.isLoading || deliveryDetailQuery.isFetching}
          retryPending={retryMutation.isPending}
          onRetry={() => {
            if (panelState.selectedDeliveryId == null) {
              return;
            }
            retryMutation.mutate(panelState.selectedDeliveryId, {
              onSuccess: () => {
                toast.success('Delivery retry requested');
                void deliveryDetailQuery.refetch();
                void deliveriesQuery.refetch();
              },
              onError: (error: unknown) => {
                toast.error(`Failed to retry delivery: ${extractErrorMessage(error)}`);
              },
            });
          }}
        />

        <TestDeliveryModal
          show={showTestModal}
          saving={sendTestMutation.isPending}
          onHide={() => setShowTestModal(false)}
          onSubmit={async (request: TestWebhookDeliveryRequest) => {
            try {
              const detail = await sendTestMutation.mutateAsync(request);
              setPanelState((current) => ({ ...current, selectedDeliveryId: detail.deliveryId }));
              toast.success('Test callback sent');
            } catch (error: unknown) {
              toast.error(`Failed to send test callback: ${extractErrorMessage(error)}`);
              throw error;
            }
          }}
        />
      </Card.Body>
    </Card>
  );
}
