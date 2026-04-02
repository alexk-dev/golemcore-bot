import { type Dispatch, type ReactElement, type SetStateAction, useCallback, useEffect, useState } from 'react';
import { Button, Col, Form, InputGroup, Row } from 'react-bootstrap';

import type { SelfEvolvingConfig } from '../../../api/settings';
import HelpTip from '../../../components/common/HelpTip';

const TACTIC_SEARCH_MODE_OPTIONS = [
  { value: 'bm25', label: 'BM25 only' },
  { value: 'hybrid', label: 'Hybrid (BM25 + embeddings)' },
];

const EMBEDDING_PROVIDER_OPTIONS = [
  { value: 'ollama', label: 'Ollama (local runtime)' },
  { value: 'openai_compatible', label: 'OpenAI-compatible API' },
];

interface OllamaEmbeddingPreset {
  value: string;
  label: string;
  dimensions: number;
  batchSize: number;
}

const DEFAULT_OLLAMA_BASE_URL = 'http://127.0.0.1:11434';
const DEFAULT_REMOTE_BASE_URL = 'https://api.openai.com/v1';
const DEFAULT_REMOTE_MODEL = 'text-embedding-3-large';
const DEFAULT_EMBEDDING_DIMENSIONS = 1024;
const DEFAULT_EMBEDDING_BATCH_SIZE = 32;
const OLLAMA_EMBEDDING_PRESETS: OllamaEmbeddingPreset[] = [
  { value: 'qwen3-embedding:0.6b', label: 'Qwen3 Embedding 0.6B', dimensions: 1024, batchSize: 32 },
  { value: 'nomic-embed-text', label: 'Nomic Embed Text', dimensions: 768, batchSize: 32 },
  { value: 'mxbai-embed-large', label: 'MXBAI Embed Large', dimensions: 1024, batchSize: 16 },
  { value: 'bge-m3', label: 'BGE-M3', dimensions: 1024, batchSize: 16 },
];

function toNullableInt(value: string): number | null {
  const parsed = Number.parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

function findOllamaEmbeddingPreset(model: string | null | undefined): OllamaEmbeddingPreset | null {
  if (model == null) {
    return null;
  }
  return OLLAMA_EMBEDDING_PRESETS.find((preset) => preset.value === model) ?? null;
}

interface SelfEvolvingTacticSearchEmbeddingsSettingsProps {
  form: SelfEvolvingConfig;
  setForm: Dispatch<SetStateAction<SelfEvolvingConfig>>;
}

export function SelfEvolvingTacticSearchEmbeddingsSettings({
  form,
  setForm,
}: SelfEvolvingTacticSearchEmbeddingsSettingsProps): ReactElement {
  const [showApiKey, setShowApiKey] = useState(false);
  const embeddings = form.tactics.search.embeddings;
  const local = embeddings.local;
  const usesLocalRuntime = (embeddings.provider ?? 'ollama') === 'ollama';
  const configuredOllamaPreset = findOllamaEmbeddingPreset(embeddings.model);
  const fallbackOllamaPreset = OLLAMA_EMBEDDING_PRESETS[0];
  const effectiveOllamaPreset = configuredOllamaPreset ?? fallbackOllamaPreset;
  const resolvedOllamaModel = embeddings.model ?? effectiveOllamaPreset.value;
  const resolvedOllamaOptions = configuredOllamaPreset == null && embeddings.model != null && embeddings.model.length > 0
    ? [
        {
          value: embeddings.model,
          label: `Custom (${embeddings.model})`,
          dimensions: embeddings.dimensions ?? DEFAULT_EMBEDDING_DIMENSIONS,
          batchSize: embeddings.batchSize ?? DEFAULT_EMBEDDING_BATCH_SIZE,
        },
        ...OLLAMA_EMBEDDING_PRESETS,
      ]
    : OLLAMA_EMBEDDING_PRESETS;
  const resolvedDimensions = embeddings.dimensions
    ?? (usesLocalRuntime ? effectiveOllamaPreset.dimensions : DEFAULT_EMBEDDING_DIMENSIONS);
  const resolvedBatchSize = embeddings.batchSize
    ?? (usesLocalRuntime ? effectiveOllamaPreset.batchSize : DEFAULT_EMBEDDING_BATCH_SIZE);
  const updateEmbeddings = useCallback((
    updater: (current: SelfEvolvingConfig['tactics']['search']['embeddings']) => SelfEvolvingConfig['tactics']['search']['embeddings'],
  ): void =>
    setForm((current) => ({
      ...current,
      tactics: {
        ...current.tactics,
        search: {
          ...current.tactics.search,
          embeddings: updater(current.tactics.search.embeddings),
        },
      },
    })), [setForm]);
  const updateLocal = useCallback((updater: (current: typeof local) => typeof local): void =>
    updateEmbeddings((currentEmbeddings) => ({
      ...currentEmbeddings,
      local: updater(currentEmbeddings.local),
    })), [updateEmbeddings]);

  useEffect(() => {
    if (!usesLocalRuntime) {
      return;
    }
    const needsDefaults = embeddings.baseUrl == null
      || embeddings.model == null
      || embeddings.dimensions == null
      || embeddings.batchSize == null;
    if (!needsDefaults) {
      return;
    }
    updateEmbeddings((current) => ({
      ...current,
      baseUrl: current.baseUrl ?? DEFAULT_OLLAMA_BASE_URL,
      model: current.model ?? effectiveOllamaPreset.value,
      dimensions: current.dimensions ?? effectiveOllamaPreset.dimensions,
      batchSize: current.batchSize ?? effectiveOllamaPreset.batchSize,
    }));
  }, [
    effectiveOllamaPreset.batchSize,
    effectiveOllamaPreset.dimensions,
    effectiveOllamaPreset.value,
    embeddings.baseUrl,
    embeddings.batchSize,
    embeddings.dimensions,
    embeddings.model,
    updateEmbeddings,
    usesLocalRuntime,
  ]);

  return (
    <>
      <div className="mb-3">
        <h6 className="mb-2">Tactic search embeddings</h6>
        <p className="text-body-secondary small mb-0">
          Configure the embedding provider used by Self-Evolving tactic retrieval. These runtime-owned settings can
          still be overridden by optional <code>bot.self-evolving.bootstrap.*</code> properties at startup.
        </p>
      </div>

      <div className="d-flex flex-column gap-2 mb-3">
        <Form.Check
          type="switch"
          label={<>Enable tactic retrieval <HelpTip text="Turns on tactic search for advisory retrieval during Self-Evolving runs." /></>}
          checked={form.tactics.enabled ?? false}
          onChange={(event) => setForm((current) => ({
            ...current,
            tactics: { ...current.tactics, enabled: event.target.checked },
          }))}
        />
        <Form.Check
          type="switch"
          label={<>Enable embeddings <HelpTip text="Allows hybrid retrieval with BM25 plus vector search when search mode is set to Hybrid." /></>}
          checked={embeddings.enabled ?? false}
          onChange={(event) => updateEmbeddings((current) => ({ ...current, enabled: event.target.checked }))}
        />
      </div>

      <Row className="g-3 mb-3">
        <Col md={4}>
          <Form.Group controlId="self-evolving-tactic-search-mode">
            <Form.Label className="small fw-medium">
              Search mode <HelpTip text="BM25-only avoids vector calls; Hybrid enables embeddings when they are configured and healthy." />
            </Form.Label>
            <Form.Select
              size="sm"
              value={form.tactics.search.mode ?? 'bm25'}
              onChange={(event) => setForm((current) => ({
                ...current,
                tactics: {
                  ...current.tactics,
                  search: { ...current.tactics.search, mode: event.target.value as 'bm25' | 'hybrid' },
                },
              }))}
            >
              {TACTIC_SEARCH_MODE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </Form.Select>
          </Form.Group>
        </Col>
        <Col md={4}>
          <Form.Group controlId="self-evolving-embedding-provider">
            <Form.Label className="small fw-medium">Embedding provider</Form.Label>
            <Form.Select
              size="sm"
              value={embeddings.provider ?? 'ollama'}
              onChange={(event) => updateEmbeddings((current) => {
                const nextProvider = event.target.value;
                const nextPreset = OLLAMA_EMBEDDING_PRESETS[0];
                if (nextProvider === 'ollama') {
                  return {
                    ...current,
                    provider: nextProvider,
                    baseUrl: current.baseUrl ?? DEFAULT_OLLAMA_BASE_URL,
                    model: current.model ?? nextPreset.value,
                    dimensions: current.dimensions ?? nextPreset.dimensions,
                    batchSize: current.batchSize ?? nextPreset.batchSize,
                  };
                }
                return {
                  ...current,
                  provider: nextProvider,
                  baseUrl: current.baseUrl ?? DEFAULT_REMOTE_BASE_URL,
                  model: current.model ?? DEFAULT_REMOTE_MODEL,
                };
              })}
            >
              {EMBEDDING_PROVIDER_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </Form.Select>
          </Form.Group>
        </Col>
        <Col md={4}>
          <Form.Group controlId="self-evolving-embedding-model">
            <Form.Label className="small fw-medium">
              Embedding model <HelpTip text="For local mode, pick a local Ollama preset that the runtime can probe and install on demand." />
            </Form.Label>
            {usesLocalRuntime ? (
              <Form.Select
                size="sm"
                value={resolvedOllamaModel}
                onChange={(event) => {
                  const selectedPreset = resolvedOllamaOptions.find((preset) => preset.value === event.target.value);
                  updateEmbeddings((current) => ({
                    ...current,
                    baseUrl: current.baseUrl ?? DEFAULT_OLLAMA_BASE_URL,
                    model: event.target.value,
                    dimensions: selectedPreset?.dimensions ?? current.dimensions ?? DEFAULT_EMBEDDING_DIMENSIONS,
                    batchSize: selectedPreset?.batchSize ?? current.batchSize ?? DEFAULT_EMBEDDING_BATCH_SIZE,
                  }));
                }}
              >
                {resolvedOllamaOptions.map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </Form.Select>
            ) : (
              <Form.Control
                size="sm"
                type="text"
                placeholder={DEFAULT_REMOTE_MODEL}
                value={embeddings.model ?? ''}
                onChange={(event) => updateEmbeddings((current) => ({ ...current, model: event.target.value || null }))}
              />
            )}
          </Form.Group>
        </Col>
      </Row>

      <Row className="g-3 mb-3">
        {!usesLocalRuntime && (
          <>
            <Col md={6}>
              <Form.Group controlId="self-evolving-embedding-base-url">
                <Form.Label className="small fw-medium">Base URL</Form.Label>
                <Form.Control
                  size="sm"
                  type="url"
                  placeholder={DEFAULT_REMOTE_BASE_URL}
                  value={embeddings.baseUrl ?? ''}
                  onChange={(event) => updateEmbeddings((current) => ({ ...current, baseUrl: event.target.value || null }))}
                />
              </Form.Group>
            </Col>
            <Col md={6}>
              <Form.Group controlId="self-evolving-embedding-api-key">
                <Form.Label className="small fw-medium">Embedding API key</Form.Label>
                <InputGroup size="sm">
                  <Form.Control
                    type={showApiKey ? 'text' : 'password'}
                    autoComplete="new-password"
                    autoCorrect="off"
                    autoCapitalize="off"
                    spellCheck={false}
                    placeholder="Enter API key"
                    value={embeddings.apiKey ?? ''}
                    onChange={(event) => updateEmbeddings((current) => ({ ...current, apiKey: event.target.value || null }))}
                  />
                  <Button type="button" variant="secondary" onClick={() => setShowApiKey((current) => !current)}>
                    {showApiKey ? 'Hide' : 'Show'}
                  </Button>
                </InputGroup>
              </Form.Group>
            </Col>
          </>
        )}
      </Row>

      <Row className="g-3 mb-4">
        <Col md={4}>
          <Form.Group controlId="self-evolving-embedding-dimensions">
            <Form.Label className="small fw-medium">Dimensions</Form.Label>
            <Form.Control
              size="sm"
              type="number"
              min={1}
              value={resolvedDimensions}
              onChange={(event) => updateEmbeddings((current) => ({ ...current, dimensions: toNullableInt(event.target.value) }))}
            />
          </Form.Group>
        </Col>
        <Col md={4}>
          <Form.Group controlId="self-evolving-embedding-batch-size">
            <Form.Label className="small fw-medium">Batch size</Form.Label>
            <Form.Control
              size="sm"
              type="number"
              min={1}
              value={resolvedBatchSize}
              onChange={(event) => updateEmbeddings((current) => ({ ...current, batchSize: toNullableInt(event.target.value) }))}
            />
          </Form.Group>
        </Col>
        <Col md={4}>
          <Form.Group controlId="self-evolving-embedding-timeout-ms">
            <Form.Label className="small fw-medium">Timeout (ms)</Form.Label>
            <Form.Control
              size="sm"
              type="number"
              min={1}
              value={embeddings.timeoutMs ?? ''}
              onChange={(event) => updateEmbeddings((current) => ({ ...current, timeoutMs: toNullableInt(event.target.value) }))}
            />
          </Form.Group>
        </Col>
      </Row>

      <div className="d-flex flex-column gap-2 mb-3">
        <Form.Check
          type="switch"
          label={<>BM25 fallback <HelpTip text="If embeddings are unavailable, keep tactic retrieval alive by falling back to lexical-only mode." /></>}
          checked={embeddings.autoFallbackToBm25 ?? true}
          onChange={(event) => updateEmbeddings((current) => ({ ...current, autoFallbackToBm25: event.target.checked }))}
        />
      </div>

      <div className="rounded-3 border border-border/70 bg-card/40 p-3 mb-4">
        <div className="d-flex align-items-center justify-content-between gap-2 mb-2">
          <h6 className="mb-0">Local embedding runtime</h6>
          <small className="text-body-secondary">{usesLocalRuntime ? 'Ollama local mode' : 'Inactive for this provider'}</small>
        </div>
        <p className="text-body-secondary small mb-3">
          Use these flags to activate local model installation and runtime health checks on startup. They are applied by
          the Self-Evolving local embedding bootstrap flow.
        </p>
        <div className="d-flex flex-column gap-2">
          <Form.Check
            type="switch"
            label="Auto-install local model"
            checked={local.autoInstall ?? false}
            onChange={(event) => updateLocal((current) => ({ ...current, autoInstall: event.target.checked }))}
          />
          <Form.Check
            type="switch"
            label="Pull model on start"
            checked={local.pullOnStart ?? false}
            onChange={(event) => updateLocal((current) => ({ ...current, pullOnStart: event.target.checked }))}
          />
          <Form.Check
            type="switch"
            label="Require healthy runtime"
            checked={local.requireHealthyRuntime ?? true}
            onChange={(event) => updateLocal((current) => ({ ...current, requireHealthyRuntime: event.target.checked }))}
          />
          <Form.Check
            type="switch"
            label="Fail open to BM25"
            checked={local.failOpen ?? true}
            onChange={(event) => updateLocal((current) => ({ ...current, failOpen: event.target.checked }))}
          />
        </div>
      </div>
    </>
  );
}
