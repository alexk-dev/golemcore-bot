import { useState } from 'react';
import { Card, ListGroup, Row, Col, Button, Form, Badge, Spinner } from 'react-bootstrap';
import { usePrompts, useUpdatePrompt, useCreatePrompt, useDeletePrompt } from '../hooks/usePrompts';
import { previewPrompt } from '../api/prompts';
import toast from 'react-hot-toast';

export default function PromptsPage() {
  const { data: sections, isLoading } = usePrompts();
  const updateMutation = useUpdatePrompt();
  const createMutation = useCreatePrompt();
  const deleteMutation = useDeletePrompt();

  const [selected, setSelected] = useState<string | null>(null);
  const [editContent, setEditContent] = useState('');
  const [editDesc, setEditDesc] = useState('');
  const [editOrder, setEditOrder] = useState(100);
  const [preview, setPreview] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [newName, setNewName] = useState('');

  if (isLoading) return <Spinner />;

  const current = sections?.find((s) => s.name === selected);

  const handleSelect = (name: string) => {
    const section = sections?.find((s) => s.name === name);
    if (section) {
      setSelected(name);
      setEditContent(section.content);
      setEditDesc(section.description);
      setEditOrder(section.order);
      setPreview('');
    }
  };

  const handleSave = async () => {
    if (!selected) return;
    await updateMutation.mutateAsync({
      name: selected,
      section: { content: editContent, description: editDesc, order: editOrder, enabled: true },
    });
    toast.success('Saved');
  };

  const handlePreview = async () => {
    if (!selected) return;
    const result = await previewPrompt(selected);
    setPreview(result.rendered);
  };

  const handleCreate = async () => {
    if (!newName) return;
    await createMutation.mutateAsync({
      name: newName,
      description: '',
      order: 100,
      enabled: true,
      content: '',
    });
    setShowCreate(false);
    setNewName('');
    toast.success('Created');
  };

  const handleDelete = async () => {
    if (!selected) return;
    await deleteMutation.mutateAsync(selected);
    setSelected(null);
    toast.success('Deleted');
  };

  return (
    <div>
      <div className="section-header d-flex align-items-center justify-content-between">
        <h4 className="mb-0">Prompts</h4>
        <Button size="sm" variant="primary" onClick={() => setShowCreate(!showCreate)}>
          + New Section
        </Button>
      </div>

      {showCreate && (
        <Card className="mb-3">
          <Card.Body>
            <Form.Group className="d-flex gap-2">
              <Form.Control
                size="sm"
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                placeholder="Section name"
              />
              <Button size="sm" onClick={handleCreate}>
                Create
              </Button>
            </Form.Group>
          </Card.Body>
        </Card>
      )}

      <Row className="g-3">
        <Col md={4}>
          <ListGroup>
            {sections?.map((s) => (
              <ListGroup.Item
                key={s.name}
                active={selected === s.name}
                action
                onClick={() => handleSelect(s.name)}
                className="d-flex justify-content-between align-items-center"
              >
                {s.name}
                <Badge bg="secondary">{s.order}</Badge>
              </ListGroup.Item>
            ))}
          </ListGroup>
        </Col>
        <Col md={8}>
          {current ? (
            <Card>
              <Card.Body>
                <Form.Group className="mb-2">
                  <Form.Label className="small">Description</Form.Label>
                  <Form.Control
                    size="sm"
                    value={editDesc}
                    onChange={(e) => setEditDesc(e.target.value)}
                  />
                </Form.Group>
                <Form.Group className="mb-2">
                  <Form.Label className="small">Order</Form.Label>
                  <Form.Control
                    size="sm"
                    type="number"
                    value={editOrder}
                    onChange={(e) => setEditOrder(Number(e.target.value))}
                  />
                </Form.Group>
                <Form.Group className="mb-3">
                  <Form.Label className="small">Content</Form.Label>
                  <Form.Control
                    as="textarea"
                    rows={12}
                    value={editContent}
                    onChange={(e) => setEditContent(e.target.value)}
                    style={{ fontFamily: 'monospace', fontSize: '0.85rem' }}
                  />
                </Form.Group>
                <div className="d-flex gap-2">
                  <Button size="sm" onClick={handleSave}>Save</Button>
                  <Button size="sm" variant="secondary" onClick={handlePreview}>Preview</Button>
                  <Button size="sm" variant="danger" onClick={handleDelete}>Delete</Button>
                </div>
                {preview && (
                  <Card className="mt-3 bg-body-tertiary">
                    <Card.Body>
                      <pre className="mb-0" style={{ whiteSpace: 'pre-wrap', fontSize: '0.85rem' }}>
                        {preview}
                      </pre>
                    </Card.Body>
                  </Card>
                )}
              </Card.Body>
            </Card>
          ) : (
            <Card className="text-center text-muted py-5">
              <Card.Body>Select a section to edit</Card.Body>
            </Card>
          )}
        </Col>
      </Row>
    </div>
  );
}
