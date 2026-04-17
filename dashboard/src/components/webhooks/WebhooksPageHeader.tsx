import type { ReactElement } from 'react';
import { Badge } from '../ui/tailwind-components';
import { FiGlobe } from 'react-icons/fi';

import { PageDocsLinks } from '../common/PageDocsLinks';
import { getDocLinks } from '../../lib/docsLinks';

export interface WebhookSummary {
  total: number;
  agent: number;
  hmac: number;
}

interface WebhooksPageHeaderProps {
  enabled: boolean;
  summary: WebhookSummary;
}

const WEBHOOK_DOCS = getDocLinks(['webhooks', 'dashboard']);

export function WebhooksPageHeader({ enabled, summary }: WebhooksPageHeaderProps): ReactElement {
  return (
    <div className="page-header d-flex flex-wrap align-items-start justify-content-between gap-3">
      <div>
        <h4 className="mb-1 d-flex align-items-center gap-2">
          <FiGlobe size={18} />
          Webhooks
        </h4>
        <p className="text-body-secondary mb-0">
          Configure inbound HTTP hooks, authentication, templates, and delivery routes.
        </p>
        <PageDocsLinks title="Relevant docs" docs={WEBHOOK_DOCS} className="mt-3" />
      </div>
      <div className="d-flex flex-wrap gap-2 align-content-start">
        <Badge bg={enabled ? 'success' : 'secondary'}>
          {enabled ? 'Enabled' : 'Disabled'}
        </Badge>
        <Badge bg="secondary">{summary.total} hooks</Badge>
        <Badge bg="primary">{summary.agent} agent</Badge>
        <Badge bg="info">{summary.hmac} hmac</Badge>
      </div>
    </div>
  );
}
