import { Badge, Button, Card } from 'react-bootstrap';
import type { HookMapping } from '../../api/webhooks';

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
}

export function HookExampleCards({ bearerToken }: HookExampleCardsProps) {
  const tokenPreview = bearerToken != null && bearerToken.length > 0 ? bearerToken : '<YOUR_TOKEN>';

  return (
    <Card className="webhook-quickstart-card border">
      <Card.Body className="p-3">
        <h3 className="h6 mb-2">Quick Test</h3>
        <div className="small text-body-secondary mb-2">
          Test your endpoint from terminal with Bearer auth.
        </div>
        <pre className="mb-0 webhook-code-block"><code>{`curl -X POST http://localhost:8080/api/hooks/wake \\
  -H "Authorization: Bearer ${tokenPreview}" \\
  -H "Content-Type: application/json" \\
  -d '{"text":"Deploy finished","chatId":"webhook:ci"}'`}</code></pre>
      </Card.Body>
    </Card>
  );
}
