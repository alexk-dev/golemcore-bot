import type { ReactElement } from 'react';
import { Alert, Badge, Button, Card, Spinner } from 'react-bootstrap';
import { useNavigate } from 'react-router-dom';
import { useRuntimeConfig } from '../hooks/useSettings';
import { extractErrorMessage } from '../utils/extractErrorMessage';
import {
  getReadyLlmProviders,
  hasCompatibleModelRouting,
  isStartupSetupComplete,
} from '../utils/startupSetup';

interface SetupStepCardProps {
  step: number;
  title: string;
  description: string;
  isDone: boolean;
  actionLabel: string;
  onAction: () => void;
}

function SetupStepCard({
  step,
  title,
  description,
  isDone,
  actionLabel,
  onAction,
}: SetupStepCardProps): ReactElement {
  return (
    <Card className="settings-card mb-3">
      <Card.Body className="d-flex flex-column flex-lg-row align-items-lg-center gap-3">
        <div className="flex-grow-1">
          <div className="d-flex align-items-center gap-2 mb-2">
            <Badge bg="secondary">Step {step}</Badge>
            <h5 className="mb-0">{title}</h5>
            <Badge bg={isDone ? 'success' : 'warning'}>{isDone ? 'Done' : 'Pending'}</Badge>
          </div>
          <p className="text-body-secondary mb-0">{description}</p>
        </div>
        <div className="d-flex align-items-center">
          <Button type="button" variant="secondary" size="sm" onClick={onAction}>
            {actionLabel}
          </Button>
        </div>
      </Card.Body>
    </Card>
  );
}

function SetupLoadingState(): ReactElement {
  return (
    <div className="d-flex justify-content-center py-5">
      <Spinner animation="border" role="status">
        <span className="visually-hidden">Loading startup setup...</span>
      </Spinner>
    </div>
  );
}

function getErrorMessage(error: unknown, fallback: string): string {
  const message = extractErrorMessage(error);
  return message === 'Unknown error' ? fallback : message;
}

export default function SetupPage(): ReactElement {
  const navigate = useNavigate();
  const runtimeConfigQuery = useRuntimeConfig();
  const runtimeConfig = runtimeConfigQuery.data;

  if (runtimeConfigQuery.isLoading) {
    return <SetupLoadingState />;
  }

  if (runtimeConfig == null) {
    return (
      <Alert variant="danger" className="mb-0">
        {getErrorMessage(runtimeConfigQuery.error, 'Failed to load runtime configuration.')}
      </Alert>
    );
  }

  const readyProviders = getReadyLlmProviders(runtimeConfig);
  const providerReady = readyProviders.length > 0;
  const routingReady = hasCompatibleModelRouting(runtimeConfig);
  const setupComplete = isStartupSetupComplete(runtimeConfig);

  return (
    <div>
      <div className="page-header">
        <h4>Startup Setup Wizard</h4>
        <p className="text-body-secondary mb-0">
          Finish recommended startup configuration for reliable model routing and responses.
        </p>
      </div>

      {runtimeConfigQuery.error != null && (
        <Alert variant="warning">
          {getErrorMessage(runtimeConfigQuery.error, 'Some data may be outdated.')}
        </Alert>
      )}

      <SetupStepCard
        step={1}
        title="Configure LLM Provider"
        description={providerReady
          ? `Ready providers: ${readyProviders.join(', ')}`
          : 'Add at least one provider and set its API key.'}
        isDone={providerReady}
        actionLabel="Open LLM Providers"
        onAction={() => navigate('/settings/llm-providers')}
      />

      <SetupStepCard
        step={2}
        title="Configure Model Routing"
        description={routingReady
          ? 'Routing models are compatible with configured providers.'
          : 'Select routing/tier models that use your configured provider(s).'}
        isDone={routingReady}
        actionLabel="Open Models"
        onAction={() => navigate('/settings/models')}
      />

      <Card className="settings-card">
        <Card.Body className="d-flex flex-column flex-lg-row align-items-lg-center gap-3">
          <div className="flex-grow-1">
            <h5 className="mb-1">Start Chat</h5>
            <p className="text-body-secondary mb-0">
              {setupComplete
                ? 'Setup is complete. You can start chatting now.'
                : 'Chat is available, but setup is recommended before active usage.'}
            </p>
          </div>
          <Button type="button" variant="primary" onClick={() => navigate('/chat')}>
            Open Chat
          </Button>
        </Card.Body>
      </Card>
    </div>
  );
}
