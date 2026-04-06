import { useState, type ReactElement } from 'react';
import { Alert, Button, Spinner } from 'react-bootstrap';

import {
  probeSelfEvolvingRemoteEmbedding,
  type SelfEvolvingRemoteEmbeddingProbeResponse,
} from '../../../api/selfEvolving';
import type { EmbeddingsConfig } from './SelfEvolvingTacticSearchEmbeddingsSections';

interface RemoteEmbeddingProbeSectionProps {
  embeddings: EmbeddingsConfig;
  disabled?: boolean;
}

type ProbeState =
  | { kind: 'idle' }
  | { kind: 'running' }
  | { kind: 'success'; result: SelfEvolvingRemoteEmbeddingProbeResponse }
  | { kind: 'failure'; error: string; result: SelfEvolvingRemoteEmbeddingProbeResponse | null };

function extractErrorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  if (typeof error === 'string') {
    return error;
  }
  return 'Unexpected error during embedding check';
}

export function RemoteEmbeddingProbeSection({
  embeddings,
  disabled = false,
}: RemoteEmbeddingProbeSectionProps): ReactElement {
  const [state, setState] = useState<ProbeState>({ kind: 'idle' });
  const isRunning = state.kind === 'running';

  const handleCheck = async (): Promise<void> => {
    setState({ kind: 'running' });
    try {
      const result = await probeSelfEvolvingRemoteEmbedding({
        baseUrl: embeddings.baseUrl ?? null,
        apiKey: embeddings.apiKey ?? null,
        model: embeddings.model ?? null,
        dimensions: embeddings.dimensions ?? null,
        timeoutMs: embeddings.timeoutMs ?? null,
      });
      if (result.ok) {
        setState({ kind: 'success', result });
      } else {
        setState({ kind: 'failure', error: result.error ?? 'Probe failed', result });
      }
    } catch (error) {
      setState({ kind: 'failure', error: extractErrorMessage(error), result: null });
    }
  };

  return (
    <div className="mb-4">
      <div className="d-flex align-items-center gap-2 mb-2">
        <Button
          type="button"
          variant="primary"
          size="sm"
          disabled={disabled || isRunning}
          onClick={() => { void handleCheck(); }}
        >
          {isRunning ? (
            <>
              <Spinner animation="border" size="sm" role="status" aria-hidden="true" className="me-2" />
              Checking...
            </>
          ) : (
            'Check connection'
          )}
        </Button>
        <span className="text-body-secondary small">
          Sends one embedding request with the current base URL, API key, and model.
        </span>
      </div>
      {state.kind === 'success' ? (
        <Alert variant="success" className="mb-0 small">
          <div className="fw-semibold mb-1">Remote embedding endpoint reachable.</div>
          <ul className="mb-0 ps-3">
            <li>Base URL: <code>{state.result.baseUrl ?? '—'}</code></li>
            <li>Model: <code>{state.result.model ?? '—'}</code></li>
            {state.result.vectorLength != null ? (
              <li>Returned vector length: {state.result.vectorLength}</li>
            ) : null}
          </ul>
        </Alert>
      ) : null}
      {state.kind === 'failure' ? (
        <Alert variant="danger" className="mb-0 small">
          <div className="fw-semibold mb-1">Embedding check failed.</div>
          <div className="mb-1">{state.error}</div>
          {state.result?.baseUrl != null ? (
            <div className="text-body-secondary">Base URL: <code>{state.result.baseUrl}</code></div>
          ) : null}
        </Alert>
      ) : null}
    </div>
  );
}
