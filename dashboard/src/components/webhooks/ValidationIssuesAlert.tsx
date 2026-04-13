import type { ReactElement } from 'react';
import { Alert } from '../ui/tailwind-components';
import type { WebhookValidationResult } from '../../api/webhooks';

interface ValidationIssuesAlertProps {
  validation: WebhookValidationResult;
}

export function ValidationIssuesAlert({ validation }: ValidationIssuesAlertProps): ReactElement | null {
  if (validation.valid) {
    return null;
  }

  return (
    <Alert variant="warning" className="mb-3">
      <div className="fw-semibold mb-1">Validation issues</div>
      <ul className="mb-0 ps-3">
        {validation.issues.map((issue) => (
          <li key={issue}>{issue}</li>
        ))}
      </ul>
    </Alert>
  );
}
