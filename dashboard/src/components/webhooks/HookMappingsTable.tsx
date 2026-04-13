import { Badge, Button, Card } from 'react-bootstrap';
import type { HookMapping } from '../../api/webhooks';
import { createAbsoluteHookUrl } from './webhookConfigUtils';
import { HOOK_TEMPLATE_EXAMPLES, HOOK_TEMPLATE_PATH_HINT } from './hookTemplateExamples';

interface HookMappingsTableProps {
  mappings: HookMapping[];
  activeEditIndex: number | null;
  onToggleEdit: (index: number) => void;
  onDelete: (index: number) => void;
}

function formatActionLabel(action: string): string {
  return action === 'agent' ? 'Agent' : 'Wake';
}

function formatAuthLabel(authMode: string): string {
  return authMode === 'hmac' ? 'HMAC' : 'Bearer';
}

export function HookMappingsTable({
  mappings,
  activeEditIndex,
  onToggleEdit,
  onDelete,
}: HookMappingsTableProps) {
  if (mappings.length === 0) {
    return <p className="text-body-secondary small mb-0">No webhook mappings configured yet.</p>;
  }

  return (
    <div className="table-responsive">
      <table className="table table-sm align-middle dashboard-table responsive-table webhooks-table mb-0">
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Action</th>
            <th scope="col">Auth</th>
            <th scope="col">Delivery</th>
            <th scope="col">Actions</th>
          </tr>
        </thead>
        <tbody>
          {mappings.map((mapping, index) => (
            <tr key={`${mapping.name}-${index}`}>
              <td data-label="Name">
                {mapping.name.length > 0 ? (
                  <code>{mapping.name}</code>
                ) : (
                  <em className="text-body-secondary">unnamed</em>
                )}
              </td>
              <td data-label="Action">
                <Badge bg={mapping.action === 'agent' ? 'primary' : 'secondary'}>
                  {formatActionLabel(mapping.action)}
                </Badge>
              </td>
              <td data-label="Auth" className="small">
                {formatAuthLabel(mapping.authMode)}
              </td>
              <td data-label="Delivery" className="small">
                {mapping.action === 'agent' && mapping.deliver ? (
                  <Badge bg="info">{mapping.channel ?? 'channel'} → {mapping.to ?? 'target'}</Badge>
                ) : (
                  <span className="text-body-secondary">—</span>
                )}
              </td>
              <td data-label="Actions" className="text-end">
                <div className="d-flex flex-wrap gap-1 webhook-actions">
                  <Button
                    type="button"
                    size="sm"
                    variant="secondary"
                    className="webhook-action-btn"
                    onClick={() => onToggleEdit(index)}
                  >
                    {activeEditIndex === index ? 'Close' : 'Edit'}
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant="danger"
                    className="webhook-action-btn"
                    onClick={() => onDelete(index)}
                  >
                    Delete
                  </Button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

interface HookExampleCardsProps {
  bearerToken: string | null;
  showBearerToken: boolean;
  activeMapping?: HookMapping | null;
}

interface HookExamplePreview {
  description: string;
  command: string;
}

function buildHookDescription(activeMapping?: HookMapping | null): string {
  return activeMapping != null
    ? `Test the hook currently open in the editor with ${activeMapping.authMode === 'hmac' ? 'HMAC' : 'Bearer'} auth.`
    : 'Open a hook mapping to preview its exact endpoint and auth mode.';
}

function buildHmacExample(
  endpoint: string,
  sampleBody: string,
  activeMapping?: HookMapping | null,
): HookExamplePreview {
  const hmacHeader = activeMapping?.hmacHeader?.trim() || 'x-signature';
  const hmacPrefix = activeMapping?.hmacPrefix ?? '';
  return {
    description: buildHookDescription(activeMapping),
    command: `curl -X POST ${endpoint} \\
  -H "Content-Type: application/json" \\
  -H "${hmacHeader}: ${hmacPrefix}<YOUR_HMAC_SHA256>" \\
  -d '${sampleBody}'`,
  };
}

function buildBearerExample(
  endpoint: string,
  sampleBody: string,
  tokenPreview: string,
  activeMapping?: HookMapping | null,
): HookExamplePreview {
  return {
    description: buildHookDescription(activeMapping),
    command: `curl -X POST ${endpoint} \\
  -H "Authorization: Bearer ${tokenPreview}" \\
  -H "Content-Type: application/json" \\
  -d '${sampleBody}'`,
  };
}

function buildHookExamplePreview(
  bearerToken: string | null,
  showBearerToken: boolean,
  activeMapping?: HookMapping | null,
): HookExamplePreview {
  const hasBearerToken = bearerToken != null && bearerToken.length > 0;
  const tokenPreview = hasBearerToken
    ? (showBearerToken ? bearerToken : '<YOUR_TOKEN>')
    : '<YOUR_TOKEN>';
  const activeHookName = activeMapping?.name?.trim() ?? '';
  const endpoint = createAbsoluteHookUrl(activeHookName);
  const sampleBody = '{"event":"Deploy finished","chatId":"webhook:ci"}';
  return activeMapping?.authMode === 'hmac'
    ? buildHmacExample(endpoint, sampleBody, activeMapping)
    : buildBearerExample(endpoint, sampleBody, tokenPreview, activeMapping);
}

export function HookExampleCards({ bearerToken, showBearerToken, activeMapping }: HookExampleCardsProps) {
  const preview = buildHookExamplePreview(bearerToken, showBearerToken, activeMapping);

  return (
    <Card className="webhook-quickstart-card border">
      <Card.Body className="p-3">
        <h3 className="h6 mb-2">Quick Test</h3>
        <div className="small text-body-secondary mb-2">
          {preview.description}
        </div>
        <pre className="mb-0 webhook-code-block"><code>{preview.command}</code></pre>

        <div className="mt-3 pt-3 border-top">
          <h3 className="h6 mb-2">Template Paths</h3>
          <p className="small text-body-secondary mb-3">
            {HOOK_TEMPLATE_PATH_HINT}
          </p>
          <div className="d-grid gap-2">
            {HOOK_TEMPLATE_EXAMPLES.map((example) => (
              <div key={example.path} className="rounded border px-2 py-2 bg-body-tertiary">
                <code className="d-block small">{example.path}</code>
                <div className="small text-body-secondary mt-1">{example.description}</div>
              </div>
            ))}
          </div>
        </div>
      </Card.Body>
    </Card>
  );
}
