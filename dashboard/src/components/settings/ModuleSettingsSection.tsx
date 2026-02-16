import { Button, Card, Form, InputGroup } from 'react-bootstrap';
import toast from 'react-hot-toast';
import { useEffect, useState } from 'react';
import { useRuntimeModules, useUpdateRuntimeModules } from '../../hooks/useSettings';

export type ModuleFieldType = 'text' | 'password' | 'switch';

export interface ModuleFieldDef {
  key: string;
  label: string;
  type: ModuleFieldType;
  placeholder?: string;
  help?: string;
}

export interface ModuleDefinition {
  id: string;
  title: string;
  description?: string;
  fields: ModuleFieldDef[];
}

function asBool(v: unknown): boolean {
  return typeof v === 'boolean' ? v : false;
}

function asText(v: unknown): string {
  if (typeof v === 'string') return v;
  return '';
}

export default function ModuleSettingsSection({ def }: { def: ModuleDefinition }) {
  const { data: modules = {} } = useRuntimeModules();
  const updateModules = useUpdateRuntimeModules();

  const [form, setForm] = useState<Record<string, unknown>>({});
  const [showSecrets, setShowSecrets] = useState<Record<string, boolean>>({});

  useEffect(() => {
    const current = (modules?.[def.id] as Record<string, unknown> | undefined) ?? {};
    const next: Record<string, unknown> = {};
    def.fields.forEach((f) => {
      next[f.key] = current[f.key] ?? (f.type === 'switch' ? false : '');
    });
    setForm(next);
  }, [modules, def]);

  const handleSave = async () => {
    await updateModules.mutateAsync({ [def.id]: form });
    toast.success(`${def.title} settings saved`);
  };

  return (
    <Card className="settings-card mb-3">
      <Card.Body>
        <Card.Title className="h6 mb-2">{def.title}</Card.Title>
        {def.description && <Card.Text className="text-muted small">{def.description}</Card.Text>}

        {def.fields.map((field) => (
          <Form.Group className="mb-3" key={field.key}>
            <Form.Label className="small fw-medium">{field.label}</Form.Label>

            {field.type === 'switch' ? (
              <Form.Check
                type="switch"
                checked={asBool(form[field.key])}
                onChange={(e) => setForm({ ...form, [field.key]: e.target.checked })}
              />
            ) : field.type === 'password' ? (
              <InputGroup size="sm">
                <Form.Control
                  type={showSecrets[field.key] ? 'text' : 'password'}
                  value={asText(form[field.key])}
                  onChange={(e) => setForm({ ...form, [field.key]: e.target.value })}
                  placeholder={field.placeholder}
                />
                <Button
                  variant="outline-secondary"
                  onClick={() => setShowSecrets({ ...showSecrets, [field.key]: !showSecrets[field.key] })}
                >
                  {showSecrets[field.key] ? 'Hide' : 'Show'}
                </Button>
              </InputGroup>
            ) : (
              <Form.Control
                size="sm"
                type="text"
                value={asText(form[field.key])}
                onChange={(e) => setForm({ ...form, [field.key]: e.target.value })}
                placeholder={field.placeholder}
              />
            )}

            {field.help && <Form.Text className="text-muted">{field.help}</Form.Text>}
          </Form.Group>
        ))}

        <Button variant="primary" size="sm" onClick={handleSave}>Save {def.title}</Button>
      </Card.Body>
    </Card>
  );
}
