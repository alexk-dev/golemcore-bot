import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Row, Spinner } from 'react-bootstrap';
import toast from 'react-hot-toast';
import ConfirmModal from '../components/common/ConfirmModal';
import { SaveStateHint, SettingsSaveBar } from '../components/common/SettingsSaveBar';
import { HookExampleCards } from '../components/webhooks/HookMappingsTable';
import { HookMappingsCard } from '../components/webhooks/HookMappingsCard';
import { ValidationIssuesAlert } from '../components/webhooks/ValidationIssuesAlert';
import { WebhookDeliveriesPanel } from '../components/webhooks/WebhookDeliveriesPanel';
import { WebhookRuntimeCard } from '../components/webhooks/WebhookRuntimeCard';
import { WebhooksPageHeader } from '../components/webhooks/WebhooksPageHeader';
import {
  appendEmptyMapping,
  buildWebhookSummary,
  createAbsoluteHookUrl,
  hasWebhookConfigChanges,
  removeMappingAtIndex,
  replaceMappingAtIndex,
  shiftEditIndexAfterDelete,
} from '../components/webhooks/webhookConfigUtils';
import {
  createDefaultWebhookConfig,
  createEmptyWebhookMapping,
  type HookMapping,
  type WebhookConfig,
  validateWebhookConfig,
} from '../api/webhooks';
import { useUpdateWebhookConfig, useWebhookConfig } from '../hooks/useWebhooks';
import { copyTextToClipboard } from '../utils/clipboard';
import { extractErrorMessage } from '../utils/extractErrorMessage';

const DEFAULT_CONFIG = createDefaultWebhookConfig();

function LoadingState(): ReactElement {
  return (
    <div className="dashboard-main">
      <div className="d-flex align-items-center gap-2 text-body-secondary">
        <Spinner size="sm" />
        <span>Loading webhooks...</span>
      </div>
    </div>
  );
}

interface ErrorStateProps {
  onRetry: () => void;
}

function ErrorState({ onRetry }: ErrorStateProps): ReactElement {
  return (
    <div className="dashboard-main">
      <Card className="text-center py-4">
        <Card.Body>
          <p className="text-danger mb-3">Failed to load webhook settings.</p>
          <Button type="button" variant="secondary" size="sm" onClick={onRetry}>
            Retry
          </Button>
        </Card.Body>
      </Card>
    </div>
  );
}

export default function WebhooksPage(): ReactElement {
  const webhookConfigQuery = useWebhookConfig();
  const updateWebhookConfigMutation = useUpdateWebhookConfig();

  const [form, setForm] = useState<WebhookConfig>(DEFAULT_CONFIG);
  const [activeEditIndex, setActiveEditIndex] = useState<number | null>(null);
  const [deleteMappingIndex, setDeleteMappingIndex] = useState<number | null>(null);

  const config = webhookConfigQuery.data ?? DEFAULT_CONFIG;

  // Sync editable form state with backend snapshot on initial load/refetch.
  useEffect(() => {
    setForm(config);
  }, [config]);

  const validation = useMemo(() => validateWebhookConfig(form), [form]);
  const summary = useMemo(() => buildWebhookSummary(form), [form]);
  const isDirty = useMemo(() => hasWebhookConfigChanges(form, config), [form, config]);

  const saveDisabled = !isDirty || updateWebhookConfigMutation.isPending || !validation.valid;

  const handleCopyHookUrl = async (name: string): Promise<void> => {
    const copied = await copyTextToClipboard(createAbsoluteHookUrl(name));
    if (!copied) {
      toast.error('Failed to copy URL');
      return;
    }
    toast.success('Webhook URL copied');
  };

  const handleAddMapping = (): void => {
    setForm((current) => appendEmptyMapping(current, createEmptyWebhookMapping()));
    setActiveEditIndex(form.mappings.length);
  };

  const handleUpdateMapping = (index: number, nextMapping: HookMapping): void => {
    setForm((current) => replaceMappingAtIndex(current, index, nextMapping));
  };

  const handleDeleteMapping = (index: number): void => {
    setForm((current) => removeMappingAtIndex(current, index));
    setActiveEditIndex((currentEditIndex) => shiftEditIndexAfterDelete(currentEditIndex, index));
  };

  const handleSave = async (): Promise<void> => {
    const validationResult = validateWebhookConfig(form);
    if (!validationResult.valid) {
      toast.error(validationResult.issues[0] ?? 'Invalid webhook configuration');
      return;
    }

    try {
      await updateWebhookConfigMutation.mutateAsync(form);
      toast.success('Webhook settings saved');
    } catch (error: unknown) {
      toast.error(`Failed to save webhook settings: ${extractErrorMessage(error)}`);
    }
  };

  if (webhookConfigQuery.isLoading) {
    return <LoadingState />;
  }

  if (webhookConfigQuery.isError) {
    return <ErrorState onRetry={() => { void webhookConfigQuery.refetch(); }} />;
  }

  return (
    <div>
      <WebhooksPageHeader enabled={form.enabled} summary={summary} />

      <Row className="g-3 mb-3">
        <Col lg={8}>
          <WebhookRuntimeCard form={form} onChange={setForm} />
        </Col>

        <Col lg={4}>
          <HookExampleCards bearerToken={form.token} />
        </Col>
      </Row>

      <HookMappingsCard
        mappings={form.mappings}
        activeEditIndex={activeEditIndex}
        onToggleEdit={(index) => setActiveEditIndex(activeEditIndex === index ? null : index)}
        onDelete={setDeleteMappingIndex}
        onAdd={handleAddMapping}
        onUpdate={handleUpdateMapping}
        onCopyEndpoint={(name) => { void handleCopyHookUrl(name); }}
      />

      <ValidationIssuesAlert validation={validation} />

      <SettingsSaveBar>
        <Button type="button" size="sm" variant="primary" onClick={() => { void handleSave(); }} disabled={saveDisabled}>
          {updateWebhookConfigMutation.isPending ? 'Saving...' : 'Save'}
        </Button>
        <SaveStateHint isDirty={isDirty} />
      </SettingsSaveBar>

      <WebhookDeliveriesPanel />

      <ConfirmModal
        show={deleteMappingIndex != null}
        title="Delete Hook Mapping"
        message="This hook mapping will be removed permanently from runtime settings."
        confirmLabel="Delete"
        confirmVariant="danger"
        onConfirm={() => {
          if (deleteMappingIndex != null) {
            handleDeleteMapping(deleteMappingIndex);
            setDeleteMappingIndex(null);
          }
        }}
        onCancel={() => setDeleteMappingIndex(null)}
      />
    </div>
  );
}
