import type { ReactElement } from 'react';
import { Alert, Badge, Button, Card, Col, Row, Spinner, Table } from 'react-bootstrap';
import { useNavigate } from 'react-router-dom';
import type { SystemUpdateStatusResponse } from '../api/system';
import { useSystemDiagnostics, useSystemUpdateStatus } from '../hooks/useSystem';
import {
  formatUpdateTimestamp,
  getUpdateStateLabel,
  getUpdateStateVariant,
} from '../utils/systemUpdateUi';

function ValueCell({ value }: { value: string | null }): ReactElement {
  if (value == null || value.trim() === '') {
    return <span className="text-body-secondary">not set</span>;
  }
  return <code>{value}</code>;
}

interface UpdateSummaryProps {
  status: SystemUpdateStatusResponse | undefined;
  isLoading: boolean;
  isError: boolean;
}

function UpdateSummary({ status, isLoading, isError }: UpdateSummaryProps): ReactElement {
  if (isLoading) {
    return <div className="small text-body-secondary">Loading update status...</div>;
  }

  if (isError || status == null) {
    return <div className="small text-body-secondary">Update endpoint unavailable.</div>;
  }

  return (
    <>
      <div className="d-flex align-items-center gap-2 mb-1">
        <span className="small text-body-secondary">State:</span>
        <Badge bg={getUpdateStateVariant(status.state)}>{getUpdateStateLabel(status.state)}</Badge>
      </div>
      <div className="small text-body-secondary">
        Current: <span className="text-body">{status.current?.version ?? 'N/A'}</span>
        {' \u00b7 '}
        Staged: <span className="text-body">{status.staged?.version ?? 'None'}</span>
        {' \u00b7 '}
        Available: <span className="text-body">{status.available?.version ?? 'None'}</span>
      </div>
      <div className="small text-body-secondary">
        Last check: <span className="text-body">{formatUpdateTimestamp(status.lastCheckAt)}</span>
      </div>
    </>
  );
}

export default function DiagnosticsPage(): ReactElement {
  const navigate = useNavigate();
  const { data, isLoading, isError, refetch, isFetching } = useSystemDiagnostics();
  const updateStatusQuery = useSystemUpdateStatus();

  if (isLoading) {
    return <Spinner />;
  }

  if (isError || data == null) {
    return <Alert variant="danger">Failed to load diagnostics data.</Alert>;
  }

  return (
    <div>
      <div className="section-header d-flex align-items-center justify-content-between">
        <h4 className="mb-0">Diagnostics</h4>
        <Button type="button" size="sm" variant="secondary" onClick={() => { void refetch(); }} disabled={isFetching}>
          {isFetching ? 'Refreshing...' : 'Refresh'}
        </Button>
      </div>

      <Alert variant="info" className="mb-3">
        This page shows the effective runtime paths/env used by backend APIs.
      </Alert>

      <Row className="g-3 mb-3">
        <Col md={6}>
          <Card className="h-100">
            <Card.Body>
              <Card.Title className="h6">Storage</Card.Title>
              <div className="small text-body-secondary mb-1">Configured</div>
              <div className="mb-2"><code>{data.storage.configuredBasePath}</code></div>
              <div className="small text-body-secondary mb-1">Resolved</div>
              <div className="mb-3"><code>{data.storage.resolvedBasePath}</code></div>
              <div className="d-flex gap-2">
                <Badge bg="secondary">sessions files: {data.storage.sessionsFiles}</Badge>
                <Badge bg="secondary">usage files: {data.storage.usageFiles}</Badge>
              </div>
            </Card.Body>
          </Card>
        </Col>
        <Col md={6}>
          <Card className="h-100">
            <Card.Body>
              <Card.Title className="h6">Runtime</Card.Title>
              <div className="small text-body-secondary mb-1">user.dir</div>
              <div className="mb-2"><code>{data.runtime.userDir}</code></div>
              <div className="small text-body-secondary mb-1">user.home</div>
              <div><code>{data.runtime.userHome}</code></div>
            </Card.Body>
          </Card>
        </Col>
      </Row>

      <Card className="mb-3">
        <Card.Body className="d-flex flex-column flex-md-row align-items-md-center justify-content-between gap-3">
          <div>
            <Card.Title className="h6 mb-2">Updates</Card.Title>
            <UpdateSummary
              status={updateStatusQuery.data}
              isLoading={updateStatusQuery.isLoading}
              isError={updateStatusQuery.isError}
            />
          </div>
          <div className="d-flex align-items-center gap-2">
            <Button
              type="button"
              size="sm"
              variant="secondary"
              onClick={() => navigate('/settings/updates')}
            >
              Open Updates
            </Button>
          </div>
        </Card.Body>
      </Card>

      <Card>
        <Card.Body>
          <Card.Title className="h6">Environment</Card.Title>
          <Table size="sm" responsive className="mb-0 dashboard-table responsive-table diagnostics-table">
            <thead>
              <tr>
                <th scope="col">Variable</th>
                <th scope="col">Value</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td data-label="Variable"><code>STORAGE_PATH</code></td>
                <td data-label="Value"><ValueCell value={data.environment.STORAGE_PATH} /></td>
              </tr>
              <tr>
                <td data-label="Variable"><code>TOOLS_WORKSPACE</code></td>
                <td data-label="Value"><ValueCell value={data.environment.TOOLS_WORKSPACE} /></td>
              </tr>
              <tr>
                <td data-label="Variable"><code>SPRING_PROFILES_ACTIVE</code></td>
                <td data-label="Value"><ValueCell value={data.environment.SPRING_PROFILES_ACTIVE} /></td>
              </tr>
            </tbody>
          </Table>
        </Card.Body>
      </Card>
    </div>
  );
}
