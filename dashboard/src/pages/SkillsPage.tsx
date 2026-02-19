import { type ReactElement, useState } from 'react';
import { Badge, Card, ListGroup, Row, Col, Button, Form, Spinner, Modal, Placeholder } from 'react-bootstrap';
import { useSkills, useSkill, useCreateSkill, useUpdateSkill, useDeleteSkill } from '../hooks/useSkills';
import toast from 'react-hot-toast';
import ConfirmModal from '../components/common/ConfirmModal';

const SKILL_TEMPLATE = `---
description: ""
available: true
model_tier: balanced
---

`;

export default function SkillsPage(): ReactElement {
  const { data: skills, isLoading } = useSkills();
  const createMutation = useCreateSkill();
  const updateMutation = useUpdateSkill();
  const deleteMutation = useDeleteSkill();

  const [selected, setSelected] = useState<string | null>(null);
  const [editContent, setEditContent] = useState('');
  const [search, setSearch] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [newName, setNewName] = useState('');

  const { data: detail } = useSkill(selected ?? '');

  if (isLoading) {
    return (
      <div>
        <div className="section-header d-flex align-items-center justify-content-between">
          <h4 className="mb-0">Skills</h4>
        </div>
        <Row className="g-3">
          <Col md={4}>
            <Card>
              <Card.Body>
                <Placeholder as="div" animation="glow" className="mb-2"><Placeholder xs={12} /></Placeholder>
                <Placeholder as="div" animation="glow" className="mb-2"><Placeholder xs={12} /></Placeholder>
                <Placeholder as="div" animation="glow"><Placeholder xs={10} /></Placeholder>
              </Card.Body>
            </Card>
          </Col>
          <Col md={8}>
            <Card>
              <Card.Body>
                <Placeholder as="div" animation="glow" className="mb-2"><Placeholder xs={6} /></Placeholder>
                <Placeholder as="div" animation="glow" className="mb-2"><Placeholder xs={12} /></Placeholder>
                <Placeholder as="div" animation="glow" className="mb-2"><Placeholder xs={12} /></Placeholder>
                <div className="d-flex justify-content-center pt-2">
                  <Spinner size="sm" />
                </div>
              </Card.Body>
            </Card>
          </Col>
        </Row>
      </div>
    );
  }

  const filtered = skills?.filter((s) =>
    s.name.toLowerCase().includes(search.toLowerCase())
  );

  const handleSelect = (name: string): void => {
    setSelected(name);
  };

  // Sync editor content when detail loads
  const currentContent = selected === detail?.name ? (detail?.content ?? '') : '';
  const editorContent = selected === detail?.name && editContent === '' ? currentContent : editContent;

  const handleSelectAndLoad = (name: string): void => {
    handleSelect(name);
    setEditContent('');
  };

  const handleSave = async (): Promise<void> => {
    if (selected == null || selected.length === 0) {
      return;
    }
    try {
      await updateMutation.mutateAsync({ name: selected, content: editContent.length > 0 ? editContent : currentContent });
      toast.success('Skill saved');
    } catch {
      toast.error('Failed to save skill');
    }
  };

  const handleDelete = async (): Promise<void> => {
    if (selected == null || selected.length === 0) {
      return;
    }
    try {
      await deleteMutation.mutateAsync(selected);
      setSelected(null);
      setEditContent('');
      setShowDeleteConfirm(false);
      toast.success('Skill deleted');
    } catch {
      toast.error('Failed to delete skill');
    }
  };

  const handleCreate = async (): Promise<void> => {
    const trimmedName = newName.trim();
    if (trimmedName.length === 0) {
      return;
    }
    try {
      await createMutation.mutateAsync({ name: trimmedName, content: SKILL_TEMPLATE });
      setShowCreate(false);
      setNewName('');
      setSelected(trimmedName);
      setEditContent('');
      toast.success('Skill created');
    } catch (err: unknown) {
      const status = (err as { response?: { status?: number } })?.response?.status;
      if (status === 409) {
        toast.error('Skill already exists');
      } else {
        toast.error('Failed to create skill');
      }
    }
  };

  return (
    <div>
      <div className="section-header d-flex align-items-center justify-content-between">
        <h4 className="mb-0">Skills</h4>
        <Button type="button" size="sm" variant="primary" onClick={() => setShowCreate(true)}>
          + New Skill
        </Button>
      </div>

      <Row className="g-3">
        <Col md={4}>
          <Form.Control
            size="sm"
            placeholder="Search skills..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="mb-2"
          />
          <ListGroup>
            {filtered?.map((s) => (
              <ListGroup.Item
                key={s.name}
                active={selected === s.name}
                action
                onClick={() => handleSelectAndLoad(s.name)}
                className="d-flex justify-content-between align-items-center"
              >
                <span className="text-truncate me-2">{s.name}</span>
                <div className="d-flex gap-1 flex-shrink-0">
                  <Badge bg={s.available ? 'success' : 'secondary'} className="small">
                    {s.available ? 'on' : 'off'}
                  </Badge>
                  {s.hasMcp && <Badge bg="info">MCP</Badge>}
                  {s.modelTier != null && s.modelTier.length > 0 && s.modelTier !== 'balanced' && (
                    <Badge className="text-bg-warning">{s.modelTier}</Badge>
                  )}
                </div>
              </ListGroup.Item>
            ))}
            {filtered?.length === 0 && (
              <ListGroup.Item className="text-body-secondary text-center">No skills found</ListGroup.Item>
            )}
          </ListGroup>
        </Col>
        <Col md={8}>
          {selected != null && selected.length > 0 && detail != null ? (
            <Card>
              <Card.Header className="d-flex justify-content-between align-items-center">
                <span className="fw-semibold">{selected}</span>
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
                    onChange={(e) => setEditContent(e.target.value)}
                    className="code-text"
                  />
                </Form.Group>
                <div className="d-flex gap-2">
                  <Button type="button" size="sm" onClick={() => { void handleSave(); }} disabled={updateMutation.isPending}>
                    {updateMutation.isPending ? 'Saving...' : 'Save'}
                  </Button>
                  <Button type="button"
                    size="sm"
                    variant="danger"
                    onClick={() => setShowDeleteConfirm(true)}
                    disabled={deleteMutation.isPending}
                  >
                    Delete
                  </Button>
                </div>
              </Card.Body>
            </Card>
          ) : (
            <Card className="text-center text-body-secondary py-5">
              <Card.Body>Select a skill to edit</Card.Body>
            </Card>
          )}
        </Col>
      </Row>

      <Modal show={showCreate} onHide={() => setShowCreate(false)}>
        <Modal.Header closeButton>
          <Modal.Title>New Skill</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <Form.Group>
            <Form.Label>Skill name</Form.Label>
            <Form.Control
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              placeholder="my-skill"
              autoFocus
            />
            <Form.Text className="text-body-secondary">
              Alphanumeric and hyphens only (e.g. my-skill-name)
            </Form.Text>
          </Form.Group>
        </Modal.Body>
        <Modal.Footer>
          <Button type="button" variant="secondary" size="sm" onClick={() => setShowCreate(false)}>Cancel</Button>
          <Button type="button" size="sm" onClick={() => { void handleCreate(); }} disabled={createMutation.isPending}>
            {createMutation.isPending ? 'Creating...' : 'Create'}
          </Button>
        </Modal.Footer>
      </Modal>

      <ConfirmModal
        show={showDeleteConfirm}
        title="Delete Skill"
        message={selected != null && selected.length > 0 ? `Skill "${selected}" will be permanently deleted. This action cannot be undone.` : ''}
        confirmLabel="Delete"
        confirmVariant="danger"
        isProcessing={deleteMutation.isPending}
        onConfirm={() => { void handleDelete(); }}
        onCancel={() => setShowDeleteConfirm(false)}
      />
    </div>
  );
}
