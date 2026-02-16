import { useState } from 'react';
import { Card, ListGroup, Row, Col, Button, Form, Badge, Spinner, Modal } from 'react-bootstrap';
import { useSkills, useSkill, useCreateSkill, useUpdateSkill, useDeleteSkill } from '../hooks/useSkills';
import toast from 'react-hot-toast';

const SKILL_TEMPLATE = `---
description: ""
available: true
model_tier: balanced
---

`;

export default function SkillsPage() {
  const { data: skills, isLoading } = useSkills();
  const createMutation = useCreateSkill();
  const updateMutation = useUpdateSkill();
  const deleteMutation = useDeleteSkill();

  const [selected, setSelected] = useState<string | null>(null);
  const [editContent, setEditContent] = useState('');
  const [search, setSearch] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [newName, setNewName] = useState('');

  const { data: detail } = useSkill(selected || '');

  if (isLoading) return <Spinner />;

  const filtered = skills?.filter((s) =>
    s.name.toLowerCase().includes(search.toLowerCase())
  );

  const handleSelect = (name: string) => {
    setSelected(name);
  };

  // Sync editor content when detail loads
  const currentContent = selected === detail?.name ? detail?.content || '' : '';
  const editorContent = selected === detail?.name && editContent === '' ? currentContent : editContent;

  const handleSelectAndLoad = (name: string) => {
    handleSelect(name);
    setEditContent('');
  };

  const handleSave = async () => {
    if (!selected) return;
    try {
      await updateMutation.mutateAsync({ name: selected, content: editContent || currentContent });
      toast.success('Skill saved');
    } catch {
      toast.error('Failed to save skill');
    }
  };

  const handleDelete = async () => {
    if (!selected) return;
    if (!window.confirm(`Delete skill "${selected}"?`)) return;
    try {
      await deleteMutation.mutateAsync(selected);
      setSelected(null);
      setEditContent('');
      toast.success('Skill deleted');
    } catch {
      toast.error('Failed to delete skill');
    }
  };

  const handleCreate = async () => {
    if (!newName.trim()) return;
    try {
      await createMutation.mutateAsync({ name: newName.trim(), content: SKILL_TEMPLATE });
      setShowCreate(false);
      setNewName('');
      setSelected(newName.trim());
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
        <Button size="sm" variant="primary" onClick={() => setShowCreate(true)}>
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
                  {s.modelTier && s.modelTier !== 'balanced' && (
                    <Badge bg="warning" text="dark">{s.modelTier}</Badge>
                  )}
                </div>
              </ListGroup.Item>
            ))}
            {filtered?.length === 0 && (
              <ListGroup.Item className="text-muted text-center">No skills found</ListGroup.Item>
            )}
          </ListGroup>
        </Col>
        <Col md={8}>
          {selected && detail ? (
            <Card>
              <Card.Header className="d-flex justify-content-between align-items-center">
                <span className="fw-semibold">{selected}</span>
                <div className="d-flex gap-1">
                  {detail.hasMcp && <Badge bg="info">MCP</Badge>}
                  {detail.modelTier && <Badge bg="secondary">{detail.modelTier}</Badge>}
                </div>
              </Card.Header>
              <Card.Body>
                <Form.Group className="mb-3">
                  <Form.Label className="small text-muted">SKILL.md Content</Form.Label>
                  <Form.Control
                    as="textarea"
                    rows={18}
                    value={editorContent}
                    onChange={(e) => setEditContent(e.target.value)}
                    style={{ fontFamily: 'monospace', fontSize: '0.85rem' }}
                  />
                </Form.Group>
                <div className="d-flex gap-2">
                  <Button size="sm" onClick={handleSave} disabled={updateMutation.isPending}>
                    {updateMutation.isPending ? 'Saving...' : 'Save'}
                  </Button>
                  <Button
                    size="sm"
                    variant="danger"
                    onClick={handleDelete}
                    disabled={deleteMutation.isPending}
                  >
                    Delete
                  </Button>
                </div>
              </Card.Body>
            </Card>
          ) : (
            <Card className="text-center text-muted py-5">
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
            <Form.Text className="text-muted">
              Alphanumeric and hyphens only (e.g. my-skill-name)
            </Form.Text>
          </Form.Group>
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" size="sm" onClick={() => setShowCreate(false)}>Cancel</Button>
          <Button size="sm" onClick={handleCreate} disabled={createMutation.isPending}>
            {createMutation.isPending ? 'Creating...' : 'Create'}
          </Button>
        </Modal.Footer>
      </Modal>
    </div>
  );
}
