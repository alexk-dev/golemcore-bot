import { type ReactElement, useMemo, useState } from 'react';
import { Card, Row, Col, ButtonGroup, Button, Spinner, Placeholder } from 'react-bootstrap';
import { AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer, PieChart, Pie, Cell } from 'recharts';
import { useUsageStats, useUsageByModel } from '../hooks/useUsage';
import type { UsageByModelEntry } from '../api/usage';

interface ChartPalette {
  axisColor: string;
  tooltipBg: string;
  tooltipBorder: string;
  tooltipText: string;
  primary: string;
  fills: string[];
}

export default function AnalyticsPage(): ReactElement {
  const [period, setPeriod] = useState('24h');
  const { data: stats, isLoading: statsLoading } = useUsageStats(period);
  const { data: byModel, isLoading: modelLoading } = useUsageByModel(period);

  const modelData = byModel != null
    ? Object.entries(byModel).map(([name, val]: [string, UsageByModelEntry]) => ({
        name,
        tokens: val.totalTokens,
        requests: val.requests,
      }))
    : [];

  const chartPalette = useMemo<ChartPalette>(() => {
    if (typeof window === 'undefined') {
      return {
        axisColor: '#6c757d',
        tooltipBg: '#ffffff',
        tooltipBorder: '#dee2e6',
        tooltipText: '#212529',
        primary: '#0d6efd',
        fills: ['#0d6efd', '#198754', '#ffc107', '#dc3545', '#0dcaf0', '#6c757d'],
      };
    }

    const css = window.getComputedStyle(document.documentElement);
    const readVar = (name: string, fallback: string): string => {
      const cssValue = css.getPropertyValue(name).trim();
      return cssValue.length > 0 ? cssValue : fallback;
    };

      return {
        axisColor: readVar('--bs-secondary-color', '#6c757d'),
        tooltipBg: readVar('--bs-body-bg', '#ffffff'),
        tooltipBorder: readVar('--bs-border-color', '#dee2e6'),
        tooltipText: readVar('--bs-body-color', '#212529'),
        primary: readVar('--bs-primary', '#0d6efd'),
        fills: [
        readVar('--bs-primary', '#0d6efd'),
        readVar('--bs-success', '#198754'),
        readVar('--bs-warning', '#ffc107'),
        readVar('--bs-danger', '#dc3545'),
        readVar('--bs-info', '#0dcaf0'),
        readVar('--bs-secondary', '#6c757d'),
      ],
    };
  }, []);

  return (
    <div>
      <div className="section-header d-flex align-items-center justify-content-between">
        <h4 className="mb-0">Analytics</h4>
        <ButtonGroup size="sm" className="period-toggle-group">
          {['24h', '7d', '30d'].map((p) => (
            <Button key={p} variant={period === p ? 'primary' : 'secondary'} onClick={() => setPeriod(p)}>
              {p}
            </Button>
          ))}
        </ButtonGroup>
      </div>

      {statsLoading ? (
        <Row className="g-3 mb-4">
          {[0, 1, 2, 3].map((idx) => (
            <Col sm={6} md={3} key={idx}>
              <Card className="stat-card">
                <Card.Body>
                  <Placeholder as="div" animation="glow" className="mb-2"><Placeholder xs={6} /></Placeholder>
                  <Placeholder as="div" animation="glow"><Placeholder xs={8} /></Placeholder>
                </Card.Body>
              </Card>
            </Col>
          ))}
          <div className="d-flex justify-content-center pt-1">
            <Spinner size="sm" />
          </div>
        </Row>
      ) : (
        <Row className="g-3 mb-4">
          <Col sm={6} md={3}>
            <Card className="stat-card">
              <Card.Body>
                <div className="text-body-secondary small">Requests</div>
                <h3>{stats?.totalRequests ?? 0}</h3>
              </Card.Body>
            </Card>
          </Col>
          <Col sm={6} md={3}>
            <Card className="stat-card">
              <Card.Body>
                <div className="text-body-secondary small">Total Tokens</div>
                <h3>{(stats?.totalTokens ?? 0).toLocaleString()}</h3>
              </Card.Body>
            </Card>
          </Col>
          <Col sm={6} md={3}>
            <Card className="stat-card">
              <Card.Body>
                <div className="text-body-secondary small">Input / Output</div>
                <h5>
                  {(stats?.totalInputTokens ?? 0).toLocaleString()} /{' '}
                  {(stats?.totalOutputTokens ?? 0).toLocaleString()}
                </h5>
              </Card.Body>
            </Card>
          </Col>
          <Col sm={6} md={3}>
            <Card className="stat-card">
              <Card.Body>
                <div className="text-body-secondary small">Avg Latency</div>
                <h3>{stats?.avgLatencyMs ?? 0}ms</h3>
              </Card.Body>
            </Card>
          </Col>
        </Row>
      )}

      {!modelLoading && modelData.length > 0 && (
        <Row className="g-3">
          <Col lg={8}>
            <Card>
              <Card.Body>
                <Card.Title>Tokens by Model</Card.Title>
                <ResponsiveContainer width="100%" height={300}>
                  <AreaChart data={modelData}>
                    <XAxis dataKey="name" tick={{ fill: chartPalette.axisColor }} angle={-30} textAnchor="end" height={60} />
                    <YAxis tick={{ fill: chartPalette.axisColor }} />
                    <Tooltip
                      contentStyle={{
                        backgroundColor: chartPalette.tooltipBg,
                        borderColor: chartPalette.tooltipBorder,
                        color: chartPalette.tooltipText,
                      }}
                      itemStyle={{ color: chartPalette.tooltipText }}
                      labelStyle={{ color: chartPalette.tooltipText }}
                    />
                    <Area type="monotone" dataKey="tokens" fill={chartPalette.primary} stroke={chartPalette.primary} fillOpacity={0.2} />
                  </AreaChart>
                </ResponsiveContainer>
              </Card.Body>
            </Card>
          </Col>
          <Col lg={4}>
            <Card>
              <Card.Body>
                <Card.Title>Distribution</Card.Title>
                <ResponsiveContainer width="100%" height={300}>
                  <PieChart>
                    <Pie data={modelData} dataKey="tokens" nameKey="name" cx="50%" cy="50%" outerRadius={80}>
                      {modelData.map((_, i) => (
                        <Cell key={i} fill={chartPalette.fills[i % chartPalette.fills.length]} />
                      ))}
                    </Pie>
                    <Tooltip
                      contentStyle={{
                        backgroundColor: chartPalette.tooltipBg,
                        borderColor: chartPalette.tooltipBorder,
                        color: chartPalette.tooltipText,
                      }}
                      itemStyle={{ color: chartPalette.tooltipText }}
                      labelStyle={{ color: chartPalette.tooltipText }}
                    />
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
