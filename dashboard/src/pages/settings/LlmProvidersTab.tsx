import { type ReactElement, useMemo, useState } from 'react';
import { Badge, Button, Card, Col, Form, InputGroup, Row, Table } from 'react-bootstrap';
import toast from 'react-hot-toast';
import { extractErrorMessage } from '../../utils/extractErrorMessage';
import ConfirmModal from '../../components/common/ConfirmModal';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useAddLlmProvider, useUpdateLlmProvider, useRemoveLlmProvider } from '../../hooks/useSettings';
import type { LlmConfig, LlmProviderConfig, ModelRouterConfig } from '../../api/settings';

const KNOWN_BASE_URLS: Record<string, string> = {
  openai: 'https://api.openai.com/v1',
  openrouter: 'https://openrouter.ai/api/v1',
  anthropic: 'https://api.anthropic.com',
  google: 'https://generativelanguage.googleapis.com/v1beta/openai',
  kimi: 'https://api.moonshot.ai/v1',
  groq: 'https://api.groq.com/openai/v1',
  together: 'https://api.together.xyz/v1',
  fireworks: 'https://api.fireworks.ai/inference/v1',
  deepseek: 'https://api.deepseek.com/v1',
  mistral: 'https://api.mistral.ai/v1',
  xai: 'https://api.x.ai/v1',
  perplexity: 'https://api.perplexity.ai',
  zhipu: 'https://open.bigmodel.cn/api/paas/v4',
  qwen: 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1',
  cerebras: 'https://api.cerebras.ai/v1',
  deepinfra: 'https://api.deepinfra.com/v1/openai',
};

const KNOWN_PROVIDERS: string[] = Object.keys(KNOWN_BASE_URLS);

function toNullableString(value: string): string | null {
  return value.length > 0 ? value : null;
}

function toNullableInt(value: string): number | null {
  const parsed = parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

interface ProviderEditorProps {
  name: string;
  form: LlmProviderConfig;
  isNew: boolean;
  showKey: boolean;
  isSaving: boolean;
  onFormChange: (form: LlmProviderConfig) => void;
  onToggleShowKey: () => void;
  onSave: () => void;
  onCancel: () => void;
}

function ProviderEditor({
  name, form, isNew, showKey, isSaving,
  onFormChange, onToggleShowKey, onSave, onCancel,
}: ProviderEditorProps): ReactElement {
  return (
    <Card className="mb-3 border provider-editor-card">
      <Card.Body className="p-3">
        <h6 className="text-capitalize mb-3">{isNew ? `New provider: ${name}` : name}</h6>
        <Row className="g-2">
          <Col md={12}>
            <Form.Group className="mb-2">
              <Form.Label className="small fw-medium d-flex align-items-center gap-2">
                <span>API Key</span>
                {!isNew && form.apiKeyPresent === true && (
                  <Badge bg="success-subtle" text="success">Configured</Badge>
                )}
                {(form.apiKey?.length ?? 0) > 0 && (
                  <Badge bg="info-subtle" text="info">Will update on save</Badge>
                )}
              </Form.Label>
              <InputGroup size="sm">
                <Form.Control
                  name={`llm-api-key-${name}`}
                  autoComplete="new-password"
                  autoCorrect="off"
                  autoCapitalize="off"
                  spellCheck={false}
                  data-lpignore="true"
                  placeholder={form.apiKeyPresent === true ? 'Secret is configured (hidden)' : 'Enter API key'}
                  type={showKey ? 'text' : 'password'}
                  value={form.apiKey ?? ''}
                  onChange={(e) => onFormChange({ ...form, apiKey: toNullableString(e.target.value) })}
                />
                <Button type="button" variant="secondary" onClick={onToggleShowKey}>
                  {showKey ? 'Hide' : 'Show'}
                </Button>
              </InputGroup>
            </Form.Group>
          </Col>
          <Col md={8}>
            <Form.Group className="mb-2">
              <Form.Label className="small fw-medium">Base URL</Form.Label>
              <Form.Control
                size="sm"
                value={form.baseUrl ?? ''}
                onChange={(e) => onFormChange({ ...form, baseUrl: toNullableString(e.target.value) })}
              />
            </Form.Group>
          </Col>
          <Col md={4}>
            <Form.Group className="mb-2">
              <Form.Label className="small fw-medium">Timeout (s)</Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                max={3600}
                value={form.requestTimeoutSeconds ?? 300}
                onChange={(e) => onFormChange({
                  ...form,
                  requestTimeoutSeconds: toNullableInt(e.target.value) ?? 300,
                })}
              />
            </Form.Group>
          </Col>
        </Row>
        <div className="d-flex gap-2 mt-2">
          <Button type="button" variant="primary" size="sm" onClick={onSave} disabled={isSaving}>
            {isSaving ? 'Saving...' : 'Save'}
          </Button>
          <Button type="button" variant="secondary" size="sm" onClick={onCancel} disabled={isSaving}>
            Cancel
          </Button>
        </div>
      </Card.Body>
    </Card>
  );
}

interface LlmProvidersTabProps {
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
    const models = [
      modelRouter.routingModel,
      modelRouter.balancedModel,
      modelRouter.smartModel,
      modelRouter.codingModel,
      modelRouter.deepModel,
    ].filter(Boolean) as string[];
    models.forEach((modelName) => {
      const idx = modelName.indexOf('/');
      if (idx > 0) {
        used.add(modelName.substring(0, idx));
      }
    });
    return used;
  }, [modelRouter]);

  const isSaving = addProvider.isPending || updateProvider.isPending;

  const handleStartAdd = (): void => {
    const name = newProviderName.trim().toLowerCase();
    if (name.length === 0) {
      return;
    }
    if (!/^[a-z0-9][a-z0-9_-]*$/.test(name)) {
      toast.error('Provider name must match [a-z0-9][a-z0-9_-]*');
      return;
    }
    if (Object.prototype.hasOwnProperty.call(config.providers, name)) {
      toast.error('Provider already exists');
      return;
    }
    setEditingName(name);
    setEditForm({
      apiKey: null,
      apiKeyPresent: false,
      baseUrl: KNOWN_BASE_URLS[name] ?? null,
      requestTimeoutSeconds: 300,
    });
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
    setEditForm({ ...provider, apiKey: null });
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
    } catch (err) {
      toast.error(`Failed to save: ${extractErrorMessage(err)}`);
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
    } catch (err) {
      toast.error(`Failed to remove: ${extractErrorMessage(err)}`);
    } finally {
      setDeleteProvider(null);
    }
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title="LLM Providers" />
        <div className="small text-body-secondary mb-3">
          Manage API provider credentials. Each provider can be assigned to model tiers on the Models page.
        </div>

        <InputGroup className="mb-3" size="sm">
          <Form.Control
            placeholder="Provider name (e.g. openai)"
            list="known-llm-providers"
            value={newProviderName}
            onChange={(e) => setNewProviderName(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { handleStartAdd(); } }}
          />
          <Button type="button" variant="primary" onClick={handleStartAdd}>Add Provider</Button>
        </InputGroup>
        <datalist id="known-llm-providers">
          {knownSuggestions.map((name) => (
            <option key={name} value={name} />
          ))}
        </datalist>

        {providerNames.length > 0 ? (
          <Table size="sm" hover responsive className="mb-3 dashboard-table responsive-table providers-table">
            <thead>
              <tr>
                <th scope="col">Provider</th>
                <th scope="col">Base URL</th>
                <th scope="col">Status</th>
                <th scope="col" className="text-end">Actions</th>
              </tr>
            </thead>
            <tbody>
              {providerNames.map((name) => {
                const provider = config.providers[name];
                const isReady = provider?.apiKeyPresent === true;
                return (
                  <tr key={name}>
                    <td data-label="Provider" className="text-capitalize fw-medium">{name}</td>
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
                        <Button type="button"
                          size="sm"
                          variant="secondary"
                          className="provider-action-btn"
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
                        <Button type="button"
                          size="sm"
                          variant="danger"
                          className="provider-action-btn"
                          disabled={usedProviders.has(name) || removeProvider.isPending}
                          title={usedProviders.has(name) ? 'In use by model router' : 'Remove provider'}
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
          <ProviderEditor
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
