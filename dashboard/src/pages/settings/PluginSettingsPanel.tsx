import { useEffect, useMemo, useState, type ReactElement } from 'react';
import {
  Button,
  Card,
  Form,
  Spinner,
} from 'react-bootstrap';
import toast from 'react-hot-toast';

import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';
import type { PluginSettingsAction } from '../../api/plugins';
import { useExecutePluginSettingsAction, usePluginSettingsSection, useSavePluginSettingsSection } from '../../hooks/usePlugins';
import { extractErrorMessage } from '../../utils/extractErrorMessage';
import {
  PluginActionConfirmModal,
  PluginSettingsFieldRenderer,
  PluginSettingsSectionBlock,
  type PluginActionRequest,
  type PluginFormState,
} from './PluginSettingsPanelParts';
import { buttonVariant } from './pluginSettingsUi';

interface PluginSettingsPanelProps {
  routeKey: string;
}

function hasDiff(current: PluginFormState, initial: PluginFormState): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

export default function PluginSettingsPanel({ routeKey }: PluginSettingsPanelProps): ReactElement {
  const { data: section, isLoading } = usePluginSettingsSection(routeKey);
  const saveSection = useSavePluginSettingsSection(routeKey);
  const executeAction = useExecutePluginSettingsAction(routeKey);
  const [form, setForm] = useState<PluginFormState>({});
  const [revealedSecrets, setRevealedSecrets] = useState<Record<string, boolean>>({});
  const [actionRequest, setActionRequest] = useState<PluginActionRequest | null>(null);

  // Reset the local editing state whenever the backend section payload changes.
  useEffect(() => {
    setForm(section?.values ?? {});
    setRevealedSecrets({});
  }, [section]);

  const isDirty = useMemo(() => hasDiff(form, section?.values ?? {}), [form, section]);
  const hasFields = (section?.fields?.length ?? 0) > 0;

  const handleSave = async (): Promise<void> => {
    try {
      await saveSection.mutateAsync(form);
      toast.success('Plugin settings saved');
    } catch (error: unknown) {
      toast.error(`Failed to save plugin settings: ${extractErrorMessage(error)}`);
    }
  };

  const executeRequestedAction = async (request: PluginActionRequest): Promise<void> => {
    try {
      const result = await executeAction.mutateAsync({
        actionId: request.action.actionId,
        payload: request.payload,
      });
      if (result.status === 'ok') {
        toast.success(result.message ?? 'Action completed');
      } else {
        toast.error(result.message ?? 'Action failed');
      }
    } catch (error: unknown) {
      toast.error(`Action failed: ${extractErrorMessage(error)}`);
    }
  };

  const handleAction = (action: PluginSettingsAction, payload: Record<string, unknown> = {}): void => {
    const request: PluginActionRequest = { action, payload };
    if (action.confirmationMessage != null && action.confirmationMessage.length > 0) {
      setActionRequest(request);
      return;
    }
    void executeRequestedAction(request);
  };

  const handleConfirmAction = (): void => {
    if (actionRequest == null) {
      return;
    }
    const request = actionRequest;
    setActionRequest(null);
    void executeRequestedAction(request);
  };

  if (isLoading || section == null) {
    return (
      <Card className="settings-card">
        <Card.Body className="d-flex justify-content-center py-4">
          <Spinner animation="border" size="sm" />
        </Card.Body>
      </Card>
    );
  }

  return (
    <>
      <Card className="settings-card">
        <Card.Body>
          <SettingsCardTitle title={section.title} />
          {section.description != null && (
            <Form.Text className="text-muted d-block mb-3">{section.description}</Form.Text>
          )}

          {(section.actions ?? []).length > 0 && (
            <div className="d-flex flex-wrap gap-2 mb-3">
              {section.actions.map((action) => (
                <Button
                  key={action.actionId}
                  type="button"
                  size="sm"
                  variant={buttonVariant(action.variant)}
                  onClick={() => handleAction(action)}
                >
                  {action.label}
                </Button>
              ))}
            </div>
          )}

          <Form>
            {(section.fields ?? []).map((field) => (
              <PluginSettingsFieldRenderer
                key={field.key}
                field={field}
                value={form[field.key]}
                isSecretRevealed={revealedSecrets[field.key] ?? false}
                onChange={(value) => setForm((prev) => ({ ...prev, [field.key]: value }))}
                onToggleSecret={() => setRevealedSecrets((prev) => ({ ...prev, [field.key]: !prev[field.key] }))}
              />
            ))}
          </Form>

          {(section.blocks ?? []).map((block) => (
            <PluginSettingsSectionBlock key={block.key} block={block} onAction={handleAction} />
          ))}

          {hasFields && (
            <SettingsSaveBar className="mt-3">
              <Button
                type="button"
                variant="primary"
                size="sm"
                onClick={() => { void handleSave(); }}
                disabled={!isDirty || saveSection.isPending}
              >
                {saveSection.isPending ? 'Saving...' : 'Save'}
              </Button>
              <SaveStateHint isDirty={isDirty} />
            </SettingsSaveBar>
          )}
        </Card.Body>
      </Card>
      <PluginActionConfirmModal
        request={actionRequest}
        isPending={executeAction.isPending}
        onCancel={() => setActionRequest(null)}
        onConfirm={handleConfirmAction}
      />
    </>
  );
}
