import type { Dispatch, ReactElement, SetStateAction } from 'react';
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

const DEFAULT_REMOTE_BASE_URL = 'https://api.openai.com/v1';
const DEFAULT_REMOTE_MODEL = 'text-embedding-3-large';
const DEFAULT_EMBEDDING_DIMENSIONS = 1024;
const DEFAULT_EMBEDDING_BATCH_SIZE = 32;
const REMOTE_EMBEDDING_MODEL_DATALIST_ID = 'self-evolving-remote-embedding-models';
const REMOTE_EMBEDDING_MODEL_SUGGESTIONS: readonly string[] = [
  'text-embedding-3-large',
  'text-embedding-3-small',
  'text-embedding-ada-002',
];

export interface OllamaEmbeddingPreset {
  value: string;
  label: string;
  dimensions: number;
  batchSize: number;
}

export type EmbeddingsConfig = SelfEvolvingConfig['tactics']['search']['embeddings'];
export type UpdateEmbeddings = (updater: (current: EmbeddingsConfig) => EmbeddingsConfig) => void;

interface EmbeddingProviderSelectorProps {
  embeddings: EmbeddingsConfig;
  presets: OllamaEmbeddingPreset[];
  disabled?: boolean;
  updateEmbeddings: UpdateEmbeddings;
}

interface LocalEmbeddingModelSectionProps {
  resolvedOllamaModel: string;
  resolvedOllamaOptions: OllamaEmbeddingPreset[];
  currentModelStatus: string;
  canInstallModel: boolean;
  installHint: string | null;
  isInstalling: boolean;
  disabled?: boolean;
  onInstall?: (model: string) => void;
  updateEmbeddings: UpdateEmbeddings;
}

interface RemoteEmbeddingFieldsProps {
  embeddings: EmbeddingsConfig;
  showApiKey: boolean;
  setShowApiKey: Dispatch<SetStateAction<boolean>>;
  disabled?: boolean;
  updateEmbeddings: UpdateEmbeddings;
}

interface TacticRetrievalTogglesProps {
  tacticsEnabled: boolean;
  embeddingsEnabled: boolean;
  tacticsDisabled?: boolean;
  embeddingsDisabled?: boolean;
  setForm: Dispatch<SetStateAction<SelfEvolvingConfig>>;
  updateEmbeddings: UpdateEmbeddings;
}

interface SearchModeSettingsProps {
  mode: 'bm25' | 'hybrid' | null;
  timeoutMs: number | null;
  modeDisabled?: boolean;
  timeoutDisabled?: boolean;
  setForm: Dispatch<SetStateAction<SelfEvolvingConfig>>;
  updateEmbeddings: UpdateEmbeddings;
  toNullableInt: (value: string) => number | null;
}


export function EmbeddingProviderSelector({
  embeddings,
  presets,
  disabled = false,
  updateEmbeddings,
}: EmbeddingProviderSelectorProps): ReactElement {
  return (
    <Form.Group controlId="self-evolving-embedding-provider">
      <Form.Label className="small fw-medium">Embedding provider</Form.Label>
      <Form.Select
        size="sm"
        value={embeddings.provider ?? 'ollama'}
        disabled={disabled}
        onChange={(event) => updateEmbeddings((current) => {
          const nextProvider = event.target.value;
          const previousProvider = current.provider ?? 'ollama';
          const nextPreset = presets[0];
          if (nextProvider === 'ollama') {
            return {
              ...current,
              provider: nextProvider,
              baseUrl: null,
              model: current.model ?? nextPreset.value,
              dimensions: current.dimensions ?? nextPreset.dimensions,
              batchSize: current.batchSize ?? nextPreset.batchSize,
            };
          }
          const switchingFromLocal = previousProvider === 'ollama';
          return {
            ...current,
            provider: nextProvider,
            baseUrl: switchingFromLocal || current.baseUrl == null || current.baseUrl.length === 0
              ? DEFAULT_REMOTE_BASE_URL
              : current.baseUrl,
            model: switchingFromLocal || current.model == null || current.model.length === 0
              ? DEFAULT_REMOTE_MODEL
              : current.model,
          };
        })}
      >
        {EMBEDDING_PROVIDER_OPTIONS.map((option) => (
          <option key={option.value} value={option.value}>{option.label}</option>
        ))}
      </Form.Select>
    </Form.Group>
  );
}

export function LocalEmbeddingModelSection({
  resolvedOllamaModel,
  resolvedOllamaOptions,
  currentModelStatus,
  canInstallModel,
  installHint,
  isInstalling,
  disabled = false,
  onInstall,
  updateEmbeddings,
}: LocalEmbeddingModelSectionProps): ReactElement {
  const installLabel = isInstalling ? 'Installing...' : 'Install model';

  return (
    <>
      <Col md={5}>
        <Form.Group controlId="self-evolving-local-embedding-model">
          <div className="small text-body-secondary mb-1">Embedding model</div>
          <Form.Label className="small fw-medium">Local embedding model</Form.Label>
          <div className="text-body-secondary small mb-2">
            Used for tactic indexing and hybrid retrieval.
          </div>
          <Form.Select
            size="sm"
            aria-label="Local embedding model"
            value={resolvedOllamaModel}
            disabled={disabled}
            onChange={(event) => {
              const selectedPreset = resolvedOllamaOptions.find((preset) => preset.value === event.target.value);
              updateEmbeddings((current) => ({
                ...current,
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
        </Form.Group>
      </Col>
      <Col md={3}>
        <div className="small text-body-secondary mb-1">Model status</div>
        <div className="mb-2">{currentModelStatus}</div>
        {canInstallModel ? (
          <Button
            type="button"
            variant="primary"
            onClick={() => onInstall?.(resolvedOllamaModel)}
            disabled={isInstalling || resolvedOllamaModel.length === 0}
            className="w-100"
          >
            {installLabel}
          </Button>
        ) : (
          <div className="text-body-secondary small">
            {installHint ?? 'Model install becomes available after Ollama is installed and running.'}
          </div>
        )}
      </Col>
    </>
  );
}

export function RemoteEmbeddingFields({
  embeddings,
  showApiKey,
  setShowApiKey,
  disabled = false,
  updateEmbeddings,
}: RemoteEmbeddingFieldsProps): ReactElement {
  return (
    <>
      <Col md={3}>
        <Form.Group controlId="self-evolving-embedding-model">
          <Form.Label className="small fw-medium">
            Embedding model <HelpTip text={`Pick a remote embedding model. Recommended: ${DEFAULT_REMOTE_MODEL}.`} />
          </Form.Label>
          <Form.Control
            size="sm"
            type="text"
            list={REMOTE_EMBEDDING_MODEL_DATALIST_ID}
            placeholder={DEFAULT_REMOTE_MODEL}
            value={embeddings.model ?? ''}
            disabled={disabled}
            onChange={(event) => updateEmbeddings((current) => ({ ...current, model: event.target.value || null }))}
          />
          <datalist id={REMOTE_EMBEDDING_MODEL_DATALIST_ID}>
            {REMOTE_EMBEDDING_MODEL_SUGGESTIONS.map((suggestion) => (
              <option key={suggestion} value={suggestion} />
            ))}
          </datalist>
          <div className="text-body-secondary small mt-1">
            Recommended: <code>{DEFAULT_REMOTE_MODEL}</code>
          </div>
        </Form.Group>
      </Col>
      <Col md={3}>
        <Form.Group controlId="self-evolving-embedding-base-url">
          <Form.Label className="small fw-medium">Base URL</Form.Label>
          <Form.Control
            size="sm"
            type="url"
            placeholder={DEFAULT_REMOTE_BASE_URL}
            value={embeddings.baseUrl ?? ''}
            disabled={disabled}
            onChange={(event) => updateEmbeddings((current) => ({ ...current, baseUrl: event.target.value || null }))}
          />
        </Form.Group>
      </Col>
      <Col md={3}>
        <Form.Group controlId="self-evolving-embedding-api-key">
          <Form.Label className="small fw-medium">
            Embedding API key
            {embeddings.apiKeyPresent === true && (embeddings.apiKey == null || embeddings.apiKey.length === 0) ? (
              <span className="badge bg-success ms-2">Saved</span>
            ) : null}
          </Form.Label>
          <InputGroup size="sm">
            <Form.Control
              type={showApiKey ? 'text' : 'password'}
              autoComplete="new-password"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              placeholder={embeddings.apiKeyPresent === true ? 'Key stored — leave blank to keep' : 'Enter API key'}
              value={embeddings.apiKey ?? ''}
              disabled={disabled}
              onChange={(event) => updateEmbeddings((current) => ({ ...current, apiKey: event.target.value || null }))}
            />
            <Button type="button" variant="secondary" disabled={disabled} onClick={() => setShowApiKey((current) => !current)}>
              {showApiKey ? 'Hide' : 'Show'}
            </Button>
          </InputGroup>
        </Form.Group>
      </Col>
    </>
  );
}

export function TacticRetrievalToggles({
  tacticsEnabled,
  embeddingsEnabled,
  tacticsDisabled = false,
  embeddingsDisabled = false,
  setForm,
  updateEmbeddings,
}: TacticRetrievalTogglesProps): ReactElement {
  return (
    <div className="d-flex flex-column gap-2 mb-3">
      <Form.Check
        type="switch"
        label={<>Enable tactic retrieval <HelpTip text="Turns on tactic search for advisory retrieval during Self-Evolving runs." /></>}
        checked={tacticsEnabled}
        disabled={tacticsDisabled}
        onChange={(event) => setForm((current) => ({
          ...current,
          tactics: { ...current.tactics, enabled: event.target.checked },
        }))}
      />
      <Form.Check
        type="switch"
        label={<>Enable embeddings <HelpTip text="Allows hybrid retrieval with BM25 plus vector search when search mode is set to Hybrid." /></>}
        checked={embeddingsEnabled}
        disabled={embeddingsDisabled}
        onChange={(event) => updateEmbeddings((current) => ({ ...current, enabled: event.target.checked }))}
      />
    </div>
  );
}

export function SearchModeSettings({
  mode,
  timeoutMs,
  modeDisabled = false,
  timeoutDisabled = false,
  setForm,
  updateEmbeddings,
  toNullableInt,
}: SearchModeSettingsProps): ReactElement {
  return (
    <Row className="g-3 mb-3">
      <Col md={6}>
        <Form.Group controlId="self-evolving-tactic-search-mode">
          <Form.Label className="small fw-medium">
            Retrieval mode <HelpTip text="BM25-only avoids vector calls; Hybrid enables embeddings when they are configured and healthy." />
          </Form.Label>
          <Form.Select
            size="sm"
            value={mode ?? 'hybrid'}
            disabled={modeDisabled}
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
      <Col md={6}>
        <Form.Group controlId="self-evolving-embedding-timeout-ms">
          <Form.Label className="small fw-medium">Timeout (ms)</Form.Label>
          <Form.Control
            size="sm"
            type="number"
            min={1}
            value={timeoutMs ?? ''}
            disabled={timeoutDisabled}
            onChange={(event) => updateEmbeddings((current) => ({ ...current, timeoutMs: toNullableInt(event.target.value) }))}
          />
        </Form.Group>
      </Col>
    </Row>
  );
}
