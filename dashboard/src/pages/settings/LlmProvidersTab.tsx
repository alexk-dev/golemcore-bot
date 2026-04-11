import { type ReactElement, useMemo, useState } from 'react';
import { Badge, Button, Card, InputGroup, Table } from 'react-bootstrap';
import toast from 'react-hot-toast';
import ConfirmModal from '../../components/common/ConfirmModal';
import { ProviderNameCombobox } from '../../components/common/ProviderNameCombobox';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import type { LlmConfig, LlmProviderConfig, ModelRouterConfig } from '../../api/settingsTypes';
import { useAddLlmProvider, useRemoveLlmProvider, useUpdateLlmProvider } from '../../hooks/useSettings';
import { listConfiguredModelSpecs } from '../../lib/modelRouter';
import { extractErrorMessage } from '../../utils/extractErrorMessage';
import { LlmProviderEditorCard } from './LlmProviderEditorCard';
import {
  API_TYPE_DETAILS,
  buildDefaultProviderConfig,
  KNOWN_PROVIDERS,
  normalizeApiType,
  PROVIDER_NAME_PATTERN,
} from './llmProvidersSupport';

export interface LlmProvidersTabProps {
  config: LlmConfig;
  modelRouter: ModelRouterConfig;
}

export default function LlmProvidersTab({ config, modelRouter }: LlmProvidersTabProps): ReactElement {
  const addProvider = useAddLlmProvider();
  const updateProvider = useUpdateLlmProvider();
  const removeProvider = useRemoveLlmProvider();

  const [editingName, setEditingName] = useState<string | null>(null);
  const [editForm, setEditForm] = useState<LlmProviderConfig | null>(null);
  const [isNewProvider, setIsNewProvider] = useState(false);
  const [showKey, setShowKey] = useState(false);
  const [newProviderName, setNewProviderName] = useState('');
  const [deleteProvider, setDeleteProvider] = useState<string | null>(null);

  const providerNames = Object.keys(config.providers ?? {});
  const knownSuggestions = useMemo(() => {
    const combined = [...KNOWN_PROVIDERS, ...providerNames];
    return Array.from(new Set(combined)).sort();
  }, [providerNames]);

  const usedProviders = useMemo(() => {
    const used = new Set<string>();
    listConfiguredModelSpecs(modelRouter).forEach((modelName) => {
      const idx = modelName.indexOf('/');
      if (idx > 0) {
        used.add(modelName.substring(0, idx));
      }
    });
    return used;
  }, [modelRouter]);

  const isSaving = addProvider.isPending || updateProvider.isPending;
  const normalizedNewProviderName = newProviderName.trim().toLowerCase();
  const isProviderNameInvalid = normalizedNewProviderName.length > 0 && !PROVIDER_NAME_PATTERN.test(normalizedNewProviderName);
  const providerAlreadyExists = normalizedNewProviderName.length > 0
    && Object.prototype.hasOwnProperty.call(config.providers, normalizedNewProviderName);
  const canStartAdd = normalizedNewProviderName.length > 0
    && !isProviderNameInvalid
    && !providerAlreadyExists
    && !isSaving
    && editingName == null;

  const handleStartAdd = (): void => {
    if (normalizedNewProviderName.length === 0) {
      return;
    }
    if (isProviderNameInvalid) {
      toast.error('Provider name must match [a-z0-9][a-z0-9_-]*');
      return;
    }
    if (providerAlreadyExists) {
      toast.error('Provider already exists');
      return;
    }
    const name = normalizedNewProviderName;
    setEditingName(name);
    setEditForm(buildDefaultProviderConfig(name));
    setIsNewProvider(true);
    setShowKey(false);
    setNewProviderName('');
  };

  const handleStartEdit = (name: string): void => {
    const provider = config.providers[name];
    if (provider == null) {
      return;
    }
    setEditingName(name);
    setEditForm({ ...provider, apiKey: null, apiType: normalizeApiType(provider.apiType), legacyApi: provider.legacyApi ?? null });
    setIsNewProvider(false);
    setShowKey(false);
  };

  const handleCancelEdit = (): void => {
    setEditingName(null);
    setEditForm(null);
    setIsNewProvider(false);
    setShowKey(false);
  };

  const handleSave = async (): Promise<void> => {
    if (editingName == null || editForm == null) {
      return;
    }
    try {
      if (isNewProvider) {
        await addProvider.mutateAsync({ name: editingName, config: editForm });
        toast.success(`Provider "${editingName}" added`);
      } else {
        await updateProvider.mutateAsync({ name: editingName, config: editForm });
        toast.success(`Provider "${editingName}" updated`);
      }
      handleCancelEdit();
    } catch (error) {
      toast.error(`Failed to save: ${extractErrorMessage(error)}`);
    }
  };

  const handleConfirmDelete = async (): Promise<void> => {
    if (deleteProvider == null) {
      return;
    }
    try {
      await removeProvider.mutateAsync(deleteProvider);
      toast.success(`Provider "${deleteProvider}" removed`);
      if (editingName === deleteProvider) {
        handleCancelEdit();
      }
    } catch (error) {
      toast.error(`Failed to remove: ${extractErrorMessage(error)}`);
    } finally {
      setDeleteProvider(null);
    }
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title="LLM Providers" />
        <div className="small text-body-secondary mb-3">
          Manage provider credentials and API protocol. API type controls which wire protocol is used for each provider.
        </div>

        <InputGroup className="mb-3" size="sm">
          <div className="flex-grow-1">
            <ProviderNameCombobox
              value={newProviderName}
              suggestions={knownSuggestions}
              placeholder="Provider name (e.g. openai)"
              disabled={editingName != null || isSaving}
              hasError={isProviderNameInvalid || providerAlreadyExists}
              onValueChange={(value) => setNewProviderName(value.toLowerCase())}
              onSubmit={handleStartAdd}
            />
          </div>
          <Button type="button" variant="primary" onClick={handleStartAdd} disabled={!canStartAdd}>Add Provider</Button>
        </InputGroup>
        <div className={`small mb-3 ${isProviderNameInvalid || providerAlreadyExists ? 'text-danger' : 'text-body-secondary'}`}>
          {isProviderNameInvalid
            ? 'Name format: [a-z0-9][a-z0-9_-]*'
            : providerAlreadyExists
              ? 'Provider already exists.'
              : 'Use lowercase provider IDs, for example: openai, anthropic, deepseek. You can also enter a custom provider ID not present in the suggestion list.'}
        </div>

        {providerNames.length > 0 ? (
          <Table size="sm" hover responsive className="mb-3 dashboard-table responsive-table providers-table">
            <thead>
              <tr>
                <th scope="col">Provider</th>
                <th scope="col">API Type</th>
                <th scope="col">Base URL</th>
                <th scope="col">Status</th>
                <th scope="col" className="text-end">Actions</th>
              </tr>
            </thead>
            <tbody>
              {providerNames.map((name) => {
                const provider = config.providers[name];
                const isReady = provider?.apiKeyPresent === true;
                const apiType = normalizeApiType(provider?.apiType);
                return (
                  <tr key={name}>
                    <td data-label="Provider" className="text-capitalize fw-medium">{name}</td>
                    <td data-label="API Type" className="small text-body-secondary">
                      <Badge bg={API_TYPE_DETAILS[apiType].badgeBg} text={API_TYPE_DETAILS[apiType].badgeText}>
                        {API_TYPE_DETAILS[apiType].label}
                      </Badge>
                    </td>
                    <td data-label="Base URL" className="small text-body-secondary provider-url-cell">
                      {provider?.baseUrl ?? <em>default</em>}
                    </td>
                    <td data-label="Status">
                      {isReady ? (
                        <Badge bg="success">Ready</Badge>
                      ) : (
                        <Badge bg="secondary">Setup needed</Badge>
                      )}
                    </td>
                    <td data-label="Actions" className="text-end text-nowrap">
                      <div className="d-flex flex-wrap gap-1 providers-actions">
                        <Button
                          type="button"
                          size="sm"
                          variant="secondary"
                          className="provider-action-btn"
                          disabled={isSaving}
                          onClick={() => {
                            if (editingName === name && !isNewProvider) {
                              handleCancelEdit();
                            } else {
                              handleStartEdit(name);
                            }
                          }}
                        >
                          {editingName === name && !isNewProvider ? 'Close' : 'Edit'}
                        </Button>
                        <Button
                          type="button"
                          size="sm"
                          variant="danger"
                          className="provider-action-btn"
                          disabled={usedProviders.has(name) || removeProvider.isPending}
                          title={usedProviders.has(name) ? 'In use by model router' : removeProvider.isPending ? 'Deletion in progress' : 'Remove provider'}
                          onClick={() => setDeleteProvider(name)}
                        >
                          Delete
                        </Button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </Table>
        ) : (
          <p className="text-body-secondary small mb-3">No providers configured. Add one above to get started.</p>
        )}

        {editingName != null && editForm != null && (
          <LlmProviderEditorCard
            name={editingName}
            form={editForm}
            isNew={isNewProvider}
            showKey={showKey}
            isSaving={isSaving}
            onFormChange={setEditForm}
            onToggleShowKey={() => setShowKey(!showKey)}
            onSave={() => { void handleSave(); }}
            onCancel={handleCancelEdit}
          />
        )}
      </Card.Body>

      <ConfirmModal
        show={deleteProvider !== null}
        title="Delete Provider"
        message={`Remove "${deleteProvider ?? ''}" and its credentials? This cannot be undone.`}
        confirmLabel="Delete"
        confirmVariant="danger"
        isProcessing={removeProvider.isPending}
        onConfirm={() => { void handleConfirmDelete(); }}
        onCancel={() => setDeleteProvider(null)}
      />
    </Card>
  );
}
