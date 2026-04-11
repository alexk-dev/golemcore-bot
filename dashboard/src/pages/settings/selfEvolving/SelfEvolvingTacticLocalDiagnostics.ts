import type { SelfEvolvingTacticSearchStatus } from '../../../api/selfEvolving';

export interface LocalEmbeddingDiagnostics {
  variant: 'success' | 'warning' | 'danger' | 'secondary';
  title: string;
  summary: string;
  details: string[];
  installAvailable: boolean;
  installHint: string | null;
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

function isMissingLocalRuntime(status: SelfEvolvingTacticSearchStatus | null | undefined): boolean {
  const runtimeState = status?.runtimeState?.toLowerCase() ?? null;
  const reason = status?.reason?.toLowerCase() ?? '';
  return runtimeState === 'degraded_missing_binary' || reason.includes('not installed on this machine');
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

export function buildLocalEmbeddingDiagnostics(
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
  if (status?.runtimeHealthy === true && status.model === model && status.modelAvailable === false) {
    return buildMissingModelDiagnostics(model, baseUrl, version);
  }
  if (status?.runtimeHealthy === true && status.modelAvailable === true && status.model === model) {
    return buildReadyDiagnostics(model, baseUrl, version);
  }
  return {
    variant: 'secondary',
    title: 'Local embedding diagnostics will appear here after the next status refresh.',
    summary: 'Checking the selected model in the local runtime.',
    details: [`Expected endpoint: ${baseUrl}`, `Selected model: ${model}`],
    installAvailable: false,
    installHint: 'Install becomes available after the current diagnostics check completes.',
  };
}
