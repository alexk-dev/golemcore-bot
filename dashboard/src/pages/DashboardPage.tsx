import { Card, Row, Col, Spinner } from 'react-bootstrap';
import { useUsageStats } from '../hooks/useUsage';

export default function DashboardPage() {
  const { data: stats, isLoading } = useUsageStats('24h');

  if (isLoading) return <Spinner />;

  return (
    <div>
      <h4 className="mb-4">Dashboard</h4>
      <Row className="g-3">
        <Col md={3}>
          <Card className="stat-card">
            <Card.Body>
              <div className="text-muted small">Total Requests (24h)</div>
              <h3>{stats?.totalRequests ?? 0}</h3>
            </Card.Body>
          </Card>
        </Col>
        <Col md={3}>
          <Card className="stat-card">
            <Card.Body>
              <div className="text-muted small">Input Tokens</div>
              <h3>{(stats?.totalInputTokens ?? 0).toLocaleString()}</h3>
            </Card.Body>
          </Card>
        </Col>
        <Col md={3}>
          <Card className="stat-card">
            <Card.Body>
              <div className="text-muted small">Output Tokens</div>
              <h3>{(stats?.totalOutputTokens ?? 0).toLocaleString()}</h3>
            </Card.Body>
          </Card>
        </Col>
        <Col md={3}>
          <Card className="stat-card">
            <Card.Body>
              <div className="text-muted small">Avg Latency</div>
              <h3>{stats?.avgLatencyMs ?? 0}ms</h3>
            </Card.Body>
          </Card>
        </Col>
      </Row>
    </div>
  );
}
