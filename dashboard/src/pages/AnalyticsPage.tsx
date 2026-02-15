import { useState } from 'react';
import { Card, Row, Col, ButtonGroup, Button, Spinner } from 'react-bootstrap';
import { AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer, PieChart, Pie, Cell } from 'recharts';
import { useUsageStats, useUsageByModel } from '../hooks/useUsage';

const COLORS = ['#6366f1', '#06b6d4', '#f59e0b', '#ef4444', '#10b981', '#8b5cf6'];

export default function AnalyticsPage() {
  const [period, setPeriod] = useState('24h');
  const { data: stats, isLoading: statsLoading } = useUsageStats(period);
  const { data: byModel, isLoading: modelLoading } = useUsageByModel(period);

  const modelData = byModel
    ? Object.entries(byModel).map(([name, val]: [string, any]) => ({
        name,
        tokens: val.totalTokens,
        requests: val.requests,
      }))
    : [];

  return (
    <div>
      <div className="d-flex align-items-center justify-content-between mb-4">
        <h4 className="mb-0">Analytics</h4>
        <ButtonGroup size="sm">
          {['24h', '7d', '30d'].map((p) => (
            <Button key={p} variant={period === p ? 'primary' : 'outline-primary'} onClick={() => setPeriod(p)}>
              {p}
            </Button>
          ))}
        </ButtonGroup>
      </div>

      {statsLoading ? (
        <Spinner />
      ) : (
        <Row className="g-3 mb-4">
          <Col md={3}>
            <Card className="stat-card">
              <Card.Body>
                <div className="text-muted small">Requests</div>
                <h3>{stats?.totalRequests ?? 0}</h3>
              </Card.Body>
            </Card>
          </Col>
          <Col md={3}>
            <Card className="stat-card">
              <Card.Body>
                <div className="text-muted small">Total Tokens</div>
                <h3>{(stats?.totalTokens ?? 0).toLocaleString()}</h3>
              </Card.Body>
            </Card>
          </Col>
          <Col md={3}>
            <Card className="stat-card">
              <Card.Body>
                <div className="text-muted small">Input / Output</div>
                <h5>
                  {(stats?.totalInputTokens ?? 0).toLocaleString()} /{' '}
                  {(stats?.totalOutputTokens ?? 0).toLocaleString()}
                </h5>
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
      )}

      {!modelLoading && modelData.length > 0 && (
        <Row className="g-3">
          <Col md={8}>
            <Card>
              <Card.Body>
                <Card.Title>Tokens by Model</Card.Title>
                <ResponsiveContainer width="100%" height={300}>
                  <AreaChart data={modelData}>
                    <XAxis dataKey="name" />
                    <YAxis />
                    <Tooltip />
                    <Area type="monotone" dataKey="tokens" fill="#6366f1" stroke="#6366f1" />
                  </AreaChart>
                </ResponsiveContainer>
              </Card.Body>
            </Card>
          </Col>
          <Col md={4}>
            <Card>
              <Card.Body>
                <Card.Title>Distribution</Card.Title>
                <ResponsiveContainer width="100%" height={300}>
                  <PieChart>
                    <Pie data={modelData} dataKey="tokens" nameKey="name" cx="50%" cy="50%" outerRadius={100}>
                      {modelData.map((_, i) => (
                        <Cell key={i} fill={COLORS[i % COLORS.length]} />
                      ))}
                    </Pie>
                    <Tooltip />
                  </PieChart>
                </ResponsiveContainer>
              </Card.Body>
            </Card>
          </Col>
        </Row>
      )}
    </div>
  );
}
