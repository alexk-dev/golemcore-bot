import { type Dispatch, type ReactElement, type SetStateAction, useCallback, useEffect, useState } from 'react';
import { Alert, Col, Row } from 'react-bootstrap';

import type { SelfEvolvingConfig } from '../../../api/settings';
import type { SelfEvolvingTacticSearchStatus } from '../../../api/selfEvolving';
import { hasOverriddenPath, hasOverriddenPathPrefix } from './selfEvolvingOverridePaths';
import { buildLocalEmbeddingDiagnostics, type LocalEmbeddingDiagnostics } from './SelfEvolvingTacticLocalDiagnostics';
import {
  type EmbeddingsConfig,
  type OllamaEmbeddingPreset,
  EmbeddingProviderSelector,
  LocalEmbeddingModelSection,
  RemoteEmbeddingFields,
  SearchModeSettings,
} from './SelfEvolvingTacticSearchEmbeddingsSections';
import { AdvisoryCountSettings } from './SelfEvolvingTacticAdvisoryCountSettings';
import { EmbeddingDimensionSettings } from './SelfEvolvingTacticEmbeddingDimensionSettings';
import { QueryExpansionSettings } from './SelfEvolvingTacticQueryExpansionSettings';
import { RemoteEmbeddingProbeSection } from './SelfEvolvingRemoteEmbeddingProbeSection';

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
  overriddenPaths?: string[];
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
  if (status == null || status.runtimeState === 'disabled' || status.model == null || status.model !== model) {
    return 'Checking...';
  }
  return status.modelAvailable === true ? 'Installed' : 'Missing';
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
  disabled?: boolean;
  onInstall?: (model: string) => void;
  updateEmbeddings: (updater: (current: EmbeddingsConfig) => EmbeddingsConfig) => void;
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
  disabled = false,
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
        disabled={disabled}
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
      disabled={disabled}
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
  overriddenPaths = [],
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
  const searchModeManaged = hasOverriddenPath(overriddenPaths, 'tactics.search.mode');
  const embeddingFieldsManaged = hasOverriddenPathPrefix(overriddenPaths, 'tactics.search.embeddings');

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
      disabled={embeddingFieldsManaged}
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
              disabled={embeddingFieldsManaged}
              updateEmbeddings={updateEmbeddings}
            />
          </Col>
          {providerFields}
        </Row>
      </div>

      <LocalDiagnosticsAlert diagnostics={usesLocalRuntime ? localDiagnostics : null} />

      {!usesLocalRuntime ? (
        <RemoteEmbeddingProbeSection embeddings={embeddings} disabled={embeddingFieldsManaged} />
      ) : null}

      <SearchModeSettings
        mode={form.tactics.search.mode}
        timeoutMs={embeddings.timeoutMs}
        modeDisabled={searchModeManaged}
        timeoutDisabled={embeddingFieldsManaged}
        setForm={setForm}
        updateEmbeddings={updateEmbeddings}
        toNullableInt={toNullableInt}
      />

      <EmbeddingDimensionSettings
        resolvedDimensions={resolvedDimensions}
        resolvedBatchSize={resolvedBatchSize}
        disabled={embeddingFieldsManaged}
        updateEmbeddings={updateEmbeddings}
        toNullableInt={toNullableInt}
      />

      <hr className="my-4" />

      <QueryExpansionSettings
        queryExpansion={form.tactics.search.queryExpansion}
        disabled={hasOverriddenPathPrefix(overriddenPaths, 'tactics.search.queryExpansion')}
        setForm={setForm}
      />

      <hr className="my-4" />

      <AdvisoryCountSettings
        advisoryCount={form.tactics.search.advisoryCount}
        disabled={hasOverriddenPath(overriddenPaths, 'tactics.search.advisoryCount')}
        setForm={setForm}
      />
    </>
  );
}
