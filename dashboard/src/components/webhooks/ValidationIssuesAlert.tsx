import type { ReactElement } from 'react';
import { Alert } from '../ui/tailwind-components';

import type { WebhookValidationResult } from '../../api/webhooks';
import { DocsLinkAnchor } from '../common/DocsLinkAnchor';
import { getDocLink } from '../../lib/docsLinks';

interface ValidationIssuesAlertProps {
  validation: WebhookValidationResult;
}

const WEBHOOKS_DOC = getDocLink('webhooks');

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
      <div className="mt-3">
        <DocsLinkAnchor doc={WEBHOOKS_DOC} appearance="text">
          Review the webhook guide
        </DocsLinkAnchor>
      </div>
    </Alert>
  );
}
