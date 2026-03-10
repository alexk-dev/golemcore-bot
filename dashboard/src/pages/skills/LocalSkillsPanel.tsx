import type { ReactElement } from 'react';
import { Badge, Button, Card, Col, Form, ListGroup, Row, Spinner } from 'react-bootstrap';
import type { SkillInfo } from '../../api/skills';

interface LocalSkillsPanelProps {
  detail: SkillInfo | undefined;
  detailError: boolean;
  detailLoading: boolean;
  editorContent: string;
  filteredSkills: SkillInfo[];
  isSkillDirty: boolean;
  onDelete: () => void;
  onEditorChange: (content: string) => void;
  onOpenMarketplace: () => void;
  onRefetchDetail: () => void;
  onSave: () => void;
  onSearchChange: (query: string) => void;
  onSelectSkill: (name: string) => void;
  searchQuery: string;
  selectedSkillName: string | null;
  updatePending: boolean;
  deletePending: boolean;
}

interface LocalSkillDetailPaneProps {
  detail: SkillInfo | undefined;
  selectedSkillName: string | null;
  detailLoading: boolean;
  detailError: boolean;
  onRefetchDetail: () => void;
  editorContent: string;
  onEditorChange: (content: string) => void;
  isSkillDirty: boolean;
  updatePending: boolean;
  onSave: () => void;
  deletePending: boolean;
  onDelete: () => void;
}

function renderEmptyState(onOpenMarketplace: () => void): ReactElement {
  return (
    <ListGroup.Item className="text-body-secondary text-center">
      No skills found.
      {' '}
      <button
        type="button"
        className="btn btn-link p-0 align-baseline"
        onClick={onOpenMarketplace}
      >
        Open marketplace
      </button>
    </ListGroup.Item>
  );
}

function renderDetailPane({
  detail,
  selectedSkillName,
  detailLoading,
  detailError,
  onRefetchDetail,
  editorContent,
  onEditorChange,
  isSkillDirty,
  updatePending,
  onSave,
  deletePending,
  onDelete,
}: LocalSkillDetailPaneProps): ReactElement {
  if (selectedSkillName != null && selectedSkillName.length > 0 && detailLoading) {
    return (
      <Card className="text-center text-body-secondary py-5">
        <Card.Body>
          <Spinner size="sm" className="me-2" />
          Loading skill...
        </Card.Body>
      </Card>
    );
  }

  if (selectedSkillName != null && selectedSkillName.length > 0 && detailError) {
    return (
      <Card className="text-center py-5">
        <Card.Body>
          <p className="text-danger mb-3">Failed to load selected skill.</p>
          <Button type="button" size="sm" variant="secondary" onClick={onRefetchDetail}>
            Retry
          </Button>
        </Card.Body>
      </Card>
    );
  }

  if (selectedSkillName != null && selectedSkillName.length > 0 && detail != null) {
    return (
      <Card>
        <Card.Header className="d-flex justify-content-between align-items-center">
          <span className="fw-semibold">{selectedSkillName}</span>
          <div className="d-flex gap-1">
            {detail.hasMcp && <Badge bg="info">MCP</Badge>}
            {detail.modelTier != null && detail.modelTier.length > 0 && <Badge bg="secondary">{detail.modelTier}</Badge>}
          </div>
        </Card.Header>
        <Card.Body>
          <Form.Group className="mb-3">
            <Form.Label className="small text-body-secondary">SKILL.md Content</Form.Label>
            <Form.Control
              as="textarea"
              rows={18}
              value={editorContent}
              onChange={(event) => onEditorChange(event.target.value)}
              className="code-text"
            />
          </Form.Group>
          <div className="d-flex gap-2">
            <Button
              type="button"
              size="sm"
              onClick={onSave}
              disabled={!isSkillDirty || updatePending}
            >
              {updatePending ? 'Saving...' : 'Save'}
            </Button>
            <Button
              type="button"
              size="sm"
              variant="danger"
              onClick={onDelete}
              disabled={deletePending}
            >
              Delete
            </Button>
          </div>
        </Card.Body>
      </Card>
    );
  }

  return (
    <Card className="text-center text-body-secondary py-5">
      <Card.Body>Select a skill to edit</Card.Body>
    </Card>
  );
}

export function LocalSkillsPanel({
  detail,
  detailError,
  detailLoading,
  editorContent,
  filteredSkills,
  isSkillDirty,
  onDelete,
  onEditorChange,
  onOpenMarketplace,
  onRefetchDetail,
  onSave,
  onSearchChange,
  onSelectSkill,
  searchQuery,
  selectedSkillName,
  updatePending,
  deletePending,
}: LocalSkillsPanelProps): ReactElement {
  return (
    <Row className="g-3">
      <Col md={4}>
        <Form.Control
          size="sm"
          placeholder="Search skills..."
          value={searchQuery}
          onChange={(event) => onSearchChange(event.target.value)}
          className="mb-2"
        />
        <ListGroup>
          {filteredSkills.map((skill) => (
            <ListGroup.Item
              key={skill.name}
              active={selectedSkillName === skill.name}
              action
              onClick={() => onSelectSkill(skill.name)}
              className="d-flex justify-content-between align-items-center"
            >
              <span className="text-truncate me-2">{skill.name}</span>
              <div className="d-flex gap-1 flex-shrink-0">
                <Badge bg={skill.available ? 'success' : 'secondary'} className="small">
                  {skill.available ? 'on' : 'off'}
                </Badge>
                {skill.hasMcp && <Badge bg="info">MCP</Badge>}
                {skill.modelTier != null && skill.modelTier.length > 0 && skill.modelTier !== 'balanced' && (
                  <Badge className="text-bg-warning">{skill.modelTier}</Badge>
                )}
              </div>
            </ListGroup.Item>
          ))}
          {filteredSkills.length === 0 && renderEmptyState(onOpenMarketplace)}
        </ListGroup>
      </Col>
      <Col md={8}>
        {renderDetailPane({
          detail,
          selectedSkillName,
          detailLoading,
          detailError,
          onRefetchDetail,
          editorContent,
          onEditorChange,
          isSkillDirty,
          updatePending,
          onSave,
          deletePending,
          onDelete,
        })}
      </Col>
    </Row>
  );
}
