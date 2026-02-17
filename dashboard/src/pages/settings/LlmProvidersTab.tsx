import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Badge, Button, Card, Col, Form, InputGroup, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';
import { useUpdateLlm } from '../../hooks/useSettings';
import type { LlmConfig, ModelRouterConfig } from '../../api/settings';

const KNOWN_LLM_PROVIDER_BASE_URLS: Record<string, string> = {
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

const KNOWN_LLM_PROVIDERS: string[] = Object.keys(KNOWN_LLM_PROVIDER_BASE_URLS);

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableString(value: string): string | null {
  return value.length > 0 ? value : null;
}

function toNullableInt(value: string): number | null {
  const parsed = parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

function SaveStateHint({ isDirty }: { isDirty: boolean }): ReactElement {
  return <small className="text-body-secondary">{isDirty ? 'Unsaved changes' : 'All changes saved'}</small>;
}

interface LlmProvidersTabProps {
  config: LlmConfig;
  modelRouter: ModelRouterConfig;
}

export default function LlmProvidersTab({ config, modelRouter }: LlmProvidersTabProps): ReactElement {
  const updateLlm = useUpdateLlm();
  const [form, setForm] = useState<LlmConfig>({ providers: { ...(config.providers ?? {}) } });
  const [newProviderName, setNewProviderName] = useState('');
  const [showKeys, setShowKeys] = useState<Record<string, boolean>>({});
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ providers: { ...(config.providers ?? {}) } });
  }, [config]);

  const providerNames = Object.keys(form.providers ?? {});
  const knownProviderSuggestions = useMemo(() => {
    const combinedProviderNames = [...KNOWN_LLM_PROVIDERS, ...providerNames];
    return Array.from(new Set(combinedProviderNames)).sort();
  }, [providerNames]);

  const addProvider = (): void => {
    const name = newProviderName.trim();
    if (name.length === 0) {
      return;
    }
    const normalizedName = name.toLowerCase();
    if (!/^[a-z0-9][a-z0-9_-]*$/.test(normalizedName)) {
      toast.error('Provider name must match [a-z0-9][a-z0-9_-]*');
      return;
    }
    if (Object.prototype.hasOwnProperty.call(form.providers, normalizedName)) {
      toast.error('Provider already exists');
      return;
    }
    setForm({
      providers: {
        ...form.providers,
        [normalizedName]: {
          apiKey: null,
          apiKeyPresent: false,
          baseUrl: KNOWN_LLM_PROVIDER_BASE_URLS[normalizedName] ?? null,
          requestTimeoutSeconds: 300,
        },
      },
    });
    setNewProviderName('');
  };

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

  const handleSave = async (): Promise<void> => {
    await updateLlm.mutateAsync(form);
    toast.success('LLM provider settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <Card.Title className="h6 mb-3">LLM Providers</Card.Title>
        <div className="small text-body-secondary mb-3">
          Runtime provider list and credentials. No fallback from application properties.
        </div>

        <InputGroup className="mb-3" size="sm">
          <Form.Control
            placeholder="new provider name (e.g. perplexity)"
            list="known-llm-providers"
            value={newProviderName}
            onChange={(e) => setNewProviderName(e.target.value)}
          />
          <Button variant="secondary" onClick={addProvider}>Add provider</Button>
        </InputGroup>
        <datalist id="known-llm-providers">
          {knownProviderSuggestions.map((providerName) => (
            <option key={providerName} value={providerName} />
          ))}
        </datalist>

        <Row className="g-3">
          {providerNames.map((provider) => (
            <Col md={6} key={provider}>
              <Card className="h-100">
                <Card.Body>
                  <div className="d-flex align-items-center justify-content-between mb-3">
                    <div className="d-flex align-items-center gap-2">
                      <Card.Title className="h6 text-capitalize mb-0">{provider}</Card.Title>
                      {form.providers[provider]?.apiKeyPresent === true ? (
                        <Badge bg="success" className="small">Ready</Badge>
                      ) : (
                        <Badge bg="secondary" className="small">Setup needed</Badge>
                      )}
                    </div>
                    <Button
                      variant="secondary"
                      size="sm"
                      disabled={usedProviders.has(provider)}
                      title={usedProviders.has(provider)
                        ? 'Provider is used by model router tiers and cannot be removed'
                        : 'Remove provider'}
                      onClick={() => {
                        const next = { ...form.providers };
                        delete next[provider];
                        setForm({ providers: next });
                      }}
                    >
                      Remove
                    </Button>
                  </div>
                  <Form.Group className="mb-2">
                    <Form.Label className="small fw-medium d-flex align-items-center gap-2">
                      <span>API Key</span>
                      {form.providers[provider]?.apiKeyPresent === true ? (
                        <Badge bg="success-subtle" text="success">Configured</Badge>
                      ) : (
                        <Badge bg="warning-subtle" text="warning">Required</Badge>
                      )}
                      {(form.providers[provider]?.apiKey?.length ?? 0) > 0 && (
                        <Badge bg="info-subtle" text="info">Will update on save</Badge>
                      )}
                    </Form.Label>
                    <InputGroup size="sm">
                      <Form.Control
                        name={`llm-api-key-${provider}`}
                        autoComplete="new-password"
                        autoCorrect="off"
                        autoCapitalize="off"
                        spellCheck={false}
                        data-lpignore="true"
                        placeholder={form.providers[provider]?.apiKeyPresent === true
                          ? 'Secret is configured (hidden)'
                          : ''}
                        type={showKeys[provider] ? 'text' : 'password'}
                        value={form.providers[provider]?.apiKey ?? ''}
                        onChange={(e) => setForm({
                          ...form,
                          providers: {
                            ...form.providers,
                            [provider]: { ...form.providers[provider], apiKey: toNullableString(e.target.value) },
                          },
                        })}
                      />
                      <Button
                        variant="secondary"
                        onClick={() => setShowKeys({ ...showKeys, [provider]: !showKeys[provider] })}
                      >
                        {showKeys[provider] ? 'Hide' : 'Show'}
                      </Button>
                    </InputGroup>
                  </Form.Group>
                  <Form.Group className="mb-2">
                    <Form.Label className="small fw-medium">Base URL</Form.Label>
                    <Form.Control
                      size="sm"
                      value={form.providers[provider]?.baseUrl ?? ''}
                      onChange={(e) => setForm({
                        ...form,
                        providers: {
                          ...form.providers,
                          [provider]: { ...form.providers[provider], baseUrl: toNullableString(e.target.value) },
                        },
                      })}
                    />
                  </Form.Group>
                  <Form.Group>
                    <Form.Label className="small fw-medium">Request Timeout (seconds)</Form.Label>
                    <Form.Control
                      size="sm"
                      type="number"
                      min={1}
                      max={3600}
                      value={form.providers[provider]?.requestTimeoutSeconds ?? 300}
                      onChange={(e) => setForm({
                        ...form,
                        providers: {
                          ...form.providers,
                          [provider]: {
                            ...form.providers[provider],
                            requestTimeoutSeconds: toNullableInt(e.target.value) ?? 300,
                          },
                        },
                      })}
                    />
                  </Form.Group>
                </Card.Body>
              </Card>
            </Col>
          ))}
        </Row>

        <div className="d-flex align-items-center gap-2 mt-3">
          <Button variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updateLlm.isPending}>
            {updateLlm.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}
