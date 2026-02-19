import type { ReactElement } from 'react';
import { Button, Card, Col, Form, InputGroup, Row } from 'react-bootstrap';
import { LOG_LEVELS } from './logConstants';
import { levelVariant } from './logUtils';
import type { LogLevelFilter, LogLevel } from './logTypes';

export interface LogsFiltersProps {
  searchText: string;
  loggerFilter: string;
  enabledLevels: LogLevelFilter;
  onSearchChange: (value: string) => void;
  onLoggerChange: (value: string) => void;
  onToggleLevel: (level: LogLevel) => void;
}

export function LogsFilters(props: LogsFiltersProps): ReactElement {
  const { searchText, loggerFilter, enabledLevels, onSearchChange, onLoggerChange, onToggleLevel } = props;

  return (
    <Card className="mb-3">
      <Card.Body className="pb-2">
        <Row className="g-2 align-items-center">
          <Col lg={5}>
            <InputGroup size="sm">
              <InputGroup.Text>Search</InputGroup.Text>
              <Form.Control
                value={searchText}
                onChange={(event) => onSearchChange(event.target.value)}
                placeholder="message / exception / logger"
              />
            </InputGroup>
          </Col>
          <Col lg={4}>
            <InputGroup size="sm">
              <InputGroup.Text>Logger</InputGroup.Text>
              <Form.Control
                value={loggerFilter}
                onChange={(event) => onLoggerChange(event.target.value)}
                placeholder="contains..."
              />
            </InputGroup>
          </Col>
          <Col lg={3}>
            <div className="d-flex flex-wrap gap-1 justify-content-lg-end">
              {LOG_LEVELS.map((level) => (
                <Button
                  key={level}
                  size="sm"
                  variant={enabledLevels[level] ? levelVariant(level) : 'outline-secondary'}
                  onClick={() => onToggleLevel(level)}
                >
                  {level}
                </Button>
              ))}
            </div>
          </Col>
        </Row>
      </Card.Body>
    </Card>
  );
}
