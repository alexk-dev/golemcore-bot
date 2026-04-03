import { type Dispatch, type ReactElement, type SetStateAction, useCallback, useEffect, useState } from 'react';
import { Alert, Col, Form, Row } from 'react-bootstrap';

import type { SelfEvolvingConfig } from '../../../api/settings';
import type { SelfEvolvingTacticSearchStatus } from '../../../api/selfEvolving';
import HelpTip from '../../../components/common/HelpTip';
import {
  type EmbeddingsConfig,
  type OllamaEmbeddingPreset,
  EmbeddingDimensionSettings,
  EmbeddingProviderSelector,
  LocalEmbeddingModelSection,
  RemoteEmbeddingFields,
  SearchModeSettings,
  TacticRetrievalToggles,
} from './SelfEvolvingTacticSearchEmbeddingsSections';

const DEFAULT_EMBEDDING_DIMENSIONS = 1024;
const DEFAULT_EMBEDDING_BATCH_SIZE = 32;
const OLLAMA_EMBEDDING_PRESETS: OllamaEmbeddingPreset[] = [
  { value: 'qwen3-embedding:0.6b', label: 'Qwen3 Embedding 0.6B', dimensions: 1024, batchSize: 32 },
  { value: 'nomic-embed-text', label: 'Nomic Embed Text', dimensions: 768, batchSize: 32 },
  { value: 'mxbai-embed-large', label: 'MXBAI Embed Large', dimensions: 1024, batchSize: 16 },
  { value: 'bge-m3', label: 'BGE-M3', dimensions: 1024, batchSize: 16 },
];

interface SelfEvolvingTacticSearchEmbeddingsSettingsProps {
  form: SelfEvolvingConfig;
  setForm: Dispatch<SetStateAction<SelfEvolvingConfig>>;
  status?: SelfEvolvingTacticSearchStatus | null;
  isInstalling?: boolean;
  onInstall?: (model: string) => void;
}

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

function buildOllamaOptions(embeddings: EmbeddingsConfig): OllamaEmbeddingPreset[] {
  const configuredPreset = findOllamaEmbeddingPreset(embeddings.model);
  if (configuredPreset != null || embeddings.model == null || embeddings.model.length === 0) {
    return OLLAMA_EMBEDDING_PRESETS;
  }
  return [
    {
      value: embeddings.model,
      label: `Custom (${embeddings.model})`,
      dimensions: embeddings.dimensions ?? DEFAULT_EMBEDDING_DIMENSIONS,
      batchSize: embeddings.batchSize ?? DEFAULT_EMBEDDING_BATCH_SIZE,
    },
    ...OLLAMA_EMBEDDING_PRESETS,
  ];
}

function modelStatusLabel(status: SelfEvolvingTacticSearchStatus | null | undefined, model: string): string {
  if (status?.runtimeHealthy === true && status.model !== model) {
    return 'Not installed';
  }
  if (status?.model == null || status.model !== model) {
    return 'Status unknown';
  }
  return status.modelAvailable === true ? 'Installed' : 'Missing';
}

interface LocalEmbeddingDiagnostics {
  variant: 'success' | 'warning' | 'danger' | 'secondary';
  title: string;
  summary: string;
  details: string[];
  installAvailable: boolean;
  installHint: string | null;
}

interface LocalDiagnosticsAlertProps {
  diagnostics: LocalEmbeddingDiagnostics | null;
}

interface EmbeddingProviderFieldsProps {
  usesLocalRuntime: boolean;
  embeddings: EmbeddingsConfig;
  showApiKey: boolean;
  setShowApiKey: Dispatch<SetStateAction<boolean>>;
  resolvedOllamaModel: string;
  resolvedOllamaOptions: OllamaEmbeddingPreset[];
  currentModelStatus: string;
  localDiagnostics: LocalEmbeddingDiagnostics | null;
  isInstalling: boolean;
  onInstall?: (model: string) => void;
  updateEmbeddings: (updater: (current: EmbeddingsConfig) => EmbeddingsConfig) => void;
}

function buildMissingRuntimeDiagnostics(baseUrl: string): LocalEmbeddingDiagnostics {
  return {
    variant: 'danger',
    title: 'Ollama is not installed on this machine.',
    summary: 'Install Ollama locally or use the latest base image that already bundles it.',
    details: [
      'Recommended local install: brew install ollama && brew services start ollama',
      `Expected endpoint: ${baseUrl}`,
      'If you run in Docker, rebuild or pull the latest golemcore-bot-base image before starting the bot.',
    ],
    installAvailable: false,
    installHint: 'Model install becomes available after Ollama is installed and running.',
  };
}

function buildStoppedRuntimeDiagnostics(baseUrl: string, version: string | null | undefined): LocalEmbeddingDiagnostics {
  return {
    variant: 'warning',
    title: 'Ollama is installed but not running.',
    summary: `Start Ollama so Self-Evolving can index tactics and execute hybrid retrieval at ${baseUrl}.`,
    details: [
      version != null && version.length > 0 ? `Detected version: ${version}` : 'Detected local Ollama binary on PATH.',
      'Start command: brew services start ollama',
      'Foreground alternative: ollama serve',
    ],
    installAvailable: false,
    installHint: 'Model install becomes available after Ollama is running.',
  };
}

function buildMissingModelDiagnostics(model: string, baseUrl: string, version: string | null | undefined): LocalEmbeddingDiagnostics {
  return {
    variant: 'warning',
    title: 'Ollama is ready, but the selected embedding model is not installed yet.',
    summary: 'Install the selected model to enable local tactic indexing and hybrid retrieval.',
    details: [
      version != null && version.length > 0 ? `Runtime version: ${version}` : `Runtime endpoint: ${baseUrl}`,
      `Selected model: ${model}`,
    ],
    installAvailable: true,
    installHint: null,
  };
}

function buildReadyDiagnostics(model: string, baseUrl: string, version: string | null | undefined): LocalEmbeddingDiagnostics {
  return {
    variant: 'success',
    title: 'Local embeddings are ready.',
    summary: 'Ollama and the selected embedding model are available for tactic indexing and hybrid retrieval.',
    details: [
      version != null && version.length > 0 ? `Runtime version: ${version}` : `Runtime endpoint: ${baseUrl}`,
      `Active model: ${model}`,
    ],
    installAvailable: false,
    installHint: 'This model is already installed.',
  };
}

function buildPendingDiagnostics(model: string, baseUrl: string): LocalEmbeddingDiagnostics {
  return {
    variant: 'secondary',
    title: 'Local embedding diagnostics will appear here after the next status refresh.',
    summary: 'Save or refresh settings if you recently changed the provider or model. You can still install the selected model now.',
    details: [
      `Expected endpoint: ${baseUrl}`,
      `Selected model: ${model}`,
    ],
    installAvailable: true,
    installHint: null,
  };
}

function isMissingLocalRuntime(status: SelfEvolvingTacticSearchStatus | null | undefined): boolean {
  const runtimeState = status?.runtimeState?.toLowerCase() ?? null;
  const reason = status?.reason?.toLowerCase() ?? '';
  return runtimeState === 'degraded_missing_binary'
    || reason.includes('not installed on this machine');
}

function isStoppedLocalRuntime(status: SelfEvolvingTacticSearchStatus | null | undefined): boolean {
  const runtimeState = status?.runtimeState?.toLowerCase() ?? null;
  if (runtimeState != null) {
    return runtimeState === 'owned_starting'
      || runtimeState === 'degraded_start_timeout'
      || runtimeState === 'degraded_crashed'
      || runtimeState === 'degraded_restart_backoff'
      || runtimeState === 'degraded_external_lost'
      || runtimeState === 'degraded_outdated'
      || runtimeState === 'stopping';
  }
  return status?.runtimeInstalled === true && status.runtimeHealthy === false;
}

function isMissingSelectedModel(
  status: SelfEvolvingTacticSearchStatus | null | undefined,
  model: string,
): boolean {
  return status?.runtimeHealthy === true && (status.modelAvailable === false || status.model !== model);
}

function isReadySelectedModel(
  status: SelfEvolvingTacticSearchStatus | null | undefined,
  model: string,
): boolean {
  return status?.runtimeHealthy === true && status.modelAvailable === true && status.model === model;
}

function buildLocalEmbeddingDiagnostics(
  status: SelfEvolvingTacticSearchStatus | null | undefined,
  model: string,
): LocalEmbeddingDiagnostics {
  const baseUrl = status?.baseUrl ?? 'http://127.0.0.1:11434';
  const version = status?.runtimeVersion;
  if (isMissingLocalRuntime(status)) {
    return buildMissingRuntimeDiagnostics(baseUrl);
  }
  if (isStoppedLocalRuntime(status)) {
    return buildStoppedRuntimeDiagnostics(baseUrl, version);
  }
  if (isMissingSelectedModel(status, model)) {
    return buildMissingModelDiagnostics(model, baseUrl, version);
  }
  if (isReadySelectedModel(status, model)) {
    return buildReadyDiagnostics(model, baseUrl, version);
  }
  return buildPendingDiagnostics(model, baseUrl);
}

function LocalDiagnosticsAlert({
  diagnostics,
}: LocalDiagnosticsAlertProps): ReactElement | null {
  if (diagnostics == null) {
    return null;
  }
  return (
    <Alert variant={diagnostics.variant} className="mb-4">
      <div className="fw-semibold mb-1">{diagnostics.title}</div>
      <div className="small mb-2">{diagnostics.summary}</div>
      <ul className="small mb-0 ps-3">
        {diagnostics.details.map((detail) => (
          <li key={detail}>{detail}</li>
        ))}
      </ul>
    </Alert>
  );
}

function EmbeddingProviderFields({
  usesLocalRuntime,
  embeddings,
  showApiKey,
  setShowApiKey,
  resolvedOllamaModel,
  resolvedOllamaOptions,
  currentModelStatus,
  localDiagnostics,
  isInstalling,
  onInstall,
  updateEmbeddings,
}: EmbeddingProviderFieldsProps): ReactElement {
  if (usesLocalRuntime) {
    return (
      <LocalEmbeddingModelSection
        resolvedOllamaModel={resolvedOllamaModel}
        resolvedOllamaOptions={resolvedOllamaOptions}
        currentModelStatus={currentModelStatus}
        canInstallModel={localDiagnostics?.installAvailable ?? false}
        installHint={localDiagnostics?.installHint ?? null}
        isInstalling={isInstalling}
        onInstall={onInstall}
        updateEmbeddings={updateEmbeddings}
      />
    );
  }
  return (
    <RemoteEmbeddingFields
      embeddings={embeddings}
      showApiKey={showApiKey}
      setShowApiKey={setShowApiKey}
      updateEmbeddings={updateEmbeddings}
    />
  );
}

function useLocalEmbeddingDiagnostics(
  usesLocalRuntime: boolean,
  status: SelfEvolvingTacticSearchStatus | null | undefined,
  resolvedOllamaModel: string,
): LocalEmbeddingDiagnostics | null {
  if (!usesLocalRuntime) {
    return null;
  }
  return buildLocalEmbeddingDiagnostics(status, resolvedOllamaModel);
}

function useMaterializeLocalEmbeddingPreset(
  usesLocalRuntime: boolean,
  embeddings: EmbeddingsConfig,
  effectiveOllamaPreset: OllamaEmbeddingPreset,
  updateEmbeddings: (updater: (current: EmbeddingsConfig) => EmbeddingsConfig) => void,
): void {
  // Keep the selected Ollama preset materialized in saved config so search and install use the same model.
  useEffect(() => {
    if (!usesLocalRuntime) {
      return;
    }
    if (embeddings.model != null && embeddings.dimensions != null && embeddings.batchSize != null) {
      return;
    }
    updateEmbeddings((current) => ({
      ...current,
      model: current.model ?? effectiveOllamaPreset.value,
      dimensions: current.dimensions ?? effectiveOllamaPreset.dimensions,
      batchSize: current.batchSize ?? effectiveOllamaPreset.batchSize,
    }));
  }, [
    effectiveOllamaPreset.batchSize,
    effectiveOllamaPreset.dimensions,
    effectiveOllamaPreset.value,
    embeddings.batchSize,
    embeddings.dimensions,
    embeddings.model,
    updateEmbeddings,
    usesLocalRuntime,
  ]);
}

export function SelfEvolvingTacticSearchEmbeddingsSettings({
  form,
  setForm,
  status = null,
  isInstalling = false,
  onInstall,
}: SelfEvolvingTacticSearchEmbeddingsSettingsProps): ReactElement {
  const [showApiKey, setShowApiKey] = useState(false);
  const embeddings = form.tactics.search.embeddings;
  const usesLocalRuntime = (embeddings.provider ?? 'ollama') === 'ollama';
  const effectiveOllamaPreset = findOllamaEmbeddingPreset(embeddings.model) ?? OLLAMA_EMBEDDING_PRESETS[0];
  const resolvedOllamaModel = embeddings.model ?? effectiveOllamaPreset.value;
  const resolvedOllamaOptions = buildOllamaOptions(embeddings);
  const resolvedDimensions = embeddings.dimensions
    ?? (usesLocalRuntime ? effectiveOllamaPreset.dimensions : DEFAULT_EMBEDDING_DIMENSIONS);
  const resolvedBatchSize = embeddings.batchSize
    ?? (usesLocalRuntime ? effectiveOllamaPreset.batchSize : DEFAULT_EMBEDDING_BATCH_SIZE);
  const currentModelStatus = modelStatusLabel(status, resolvedOllamaModel);

  const updateEmbeddings = useCallback((updater: (current: EmbeddingsConfig) => EmbeddingsConfig): void => {
    setForm((current) => ({
      ...current,
      tactics: {
        ...current.tactics,
        search: {
          ...current.tactics.search,
          embeddings: updater(current.tactics.search.embeddings),
        },
      },
    }));
  }, [setForm]);
  const localDiagnostics = useLocalEmbeddingDiagnostics(usesLocalRuntime, status, resolvedOllamaModel);
  useMaterializeLocalEmbeddingPreset(usesLocalRuntime, embeddings, effectiveOllamaPreset, updateEmbeddings);

  const providerFields = (
    <EmbeddingProviderFields
      usesLocalRuntime={usesLocalRuntime}
      embeddings={embeddings}
      showApiKey={showApiKey}
      setShowApiKey={setShowApiKey}
      resolvedOllamaModel={resolvedOllamaModel}
      resolvedOllamaOptions={resolvedOllamaOptions}
      currentModelStatus={currentModelStatus}
      localDiagnostics={localDiagnostics}
      isInstalling={isInstalling}
      onInstall={onInstall}
      updateEmbeddings={updateEmbeddings}
    />
  );

  return (
    <>
      <div className="mb-3">
        <h6 className="mb-2">Tactic search embeddings</h6>
        <p className="text-body-secondary small mb-0">
          Configure the embedding provider used by Self-Evolving tactic retrieval. These runtime-owned settings can
          still be overridden by optional <code>bot.self-evolving.bootstrap.*</code> properties at startup.
        </p>
      </div>

      <div className="rounded-3 border border-border/70 bg-card/40 p-3 mb-4">
        <Row className="g-3 align-items-end">
          <Col md={usesLocalRuntime ? 4 : 3}>
            <EmbeddingProviderSelector
              embeddings={embeddings}
              presets={OLLAMA_EMBEDDING_PRESETS}
              updateEmbeddings={updateEmbeddings}
            />
          </Col>
          {providerFields}
        </Row>
      </div>

      <LocalDiagnosticsAlert diagnostics={usesLocalRuntime ? localDiagnostics : null} />

      <TacticRetrievalToggles
        tacticsEnabled={form.tactics.enabled ?? false}
        embeddingsEnabled={embeddings.enabled ?? false}
        setForm={setForm}
        updateEmbeddings={updateEmbeddings}
      />

      <SearchModeSettings
        mode={form.tactics.search.mode}
        timeoutMs={embeddings.timeoutMs}
        setForm={setForm}
        updateEmbeddings={updateEmbeddings}
        toNullableInt={toNullableInt}
      />

      <EmbeddingDimensionSettings
        resolvedDimensions={resolvedDimensions}
        resolvedBatchSize={resolvedBatchSize}
        updateEmbeddings={updateEmbeddings}
        toNullableInt={toNullableInt}
      />

      <div className="d-flex flex-column gap-2 mb-3">
        <Form.Check
          type="switch"
          label={<>BM25 fallback <HelpTip text="If embeddings are unavailable, keep tactic retrieval alive by falling back to lexical-only mode." /></>}
          checked={embeddings.autoFallbackToBm25 ?? true}
          onChange={(event) => updateEmbeddings((current) => ({ ...current, autoFallbackToBm25: event.target.checked }))}
        />
      </div>
    </>
  );
}
