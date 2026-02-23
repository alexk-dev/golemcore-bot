import { type ReactElement, useState } from 'react';
import { Button, Card, Form, Table } from 'react-bootstrap';
import toast from 'react-hot-toast';
import type { ShellEnvironmentVariable } from '../../api/settings';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';

const SHELL_ENV_VAR_NAME_PATTERN = /^[A-Za-z_][A-Za-z0-9_]*$/;
const RESERVED_SHELL_ENV_VAR_NAMES = new Set(['HOME', 'PWD']);

interface ShellEnvironmentVariablesCardProps {
  variables: ShellEnvironmentVariable[];
  onVariablesChange: (variables: ShellEnvironmentVariable[]) => void;
  isShellEnabled: boolean;
}

interface ShellEnvFormState {
  name: string;
  value: string;
}

interface ShellEnvEditState extends ShellEnvFormState {
  originalName: string;
}

function normalizeShellVariableName(name: string): string {
  return name.trim();
}

function hasShellVariableNameConflict(
  variables: ShellEnvironmentVariable[],
  name: string,
  ignoreName: string | null,
): boolean {
  return variables.some((variable) => variable.name === name && variable.name !== ignoreName);
}

function validateShellVariableName(
  variables: ShellEnvironmentVariable[],
  name: string,
  ignoreName: string | null,
): string | null {
  if (name.length === 0) {
    return 'Variable name is required';
  }
  if (!SHELL_ENV_VAR_NAME_PATTERN.test(name)) {
    return 'Name must match [A-Za-z_][A-Za-z0-9_]*';
  }
  if (RESERVED_SHELL_ENV_VAR_NAMES.has(name)) {
    return `${name} is reserved by the shell runtime`;
  }
  if (hasShellVariableNameConflict(variables, name, ignoreName)) {
    return `Variable ${name} already exists`;
  }
  return null;
}

export function ShellEnvironmentVariablesCard({
  variables,
  onVariablesChange,
  isShellEnabled,
}: ShellEnvironmentVariablesCardProps): ReactElement {
  const [createForm, setCreateForm] = useState<ShellEnvFormState>({ name: '', value: '' });
  const [editForm, setEditForm] = useState<ShellEnvEditState | null>(null);

  const handleCreate = (): void => {
    const normalizedName = normalizeShellVariableName(createForm.name);
    const validationError = validateShellVariableName(variables, normalizedName, null);
    if (validationError != null) {
      toast.error(validationError);
      return;
    }
    onVariablesChange([
      ...variables,
      {
        name: normalizedName,
        value: createForm.value,
      },
    ]);
    setCreateForm({ name: '', value: '' });
    toast.success(`Added ${normalizedName}`);
  };

  const handleDelete = (name: string): void => {
    onVariablesChange(variables.filter((variable) => variable.name !== name));
    if (editForm?.originalName === name) {
      setEditForm(null);
    }
    toast.success(`Deleted ${name}`);
  };

  const handleStartEdit = (variable: ShellEnvironmentVariable): void => {
    setEditForm({
      originalName: variable.name,
      name: variable.name,
      value: variable.value,
    });
  };

  const handleSaveEdit = (): void => {
    if (editForm == null) {
      return;
    }
    const normalizedName = normalizeShellVariableName(editForm.name);
    const validationError = validateShellVariableName(variables, normalizedName, editForm.originalName);
    if (validationError != null) {
      toast.error(validationError);
      return;
    }

    const updatedVariables = variables.map((variable) => {
      if (variable.name !== editForm.originalName) {
        return variable;
      }
      return {
        name: normalizedName,
        value: editForm.value,
      };
    });
    onVariablesChange(updatedVariables);
    setEditForm(null);
    toast.success(`Updated ${normalizedName}`);
  };

  return (
    <Card className="settings-card tools-card mb-3">
      <Card.Body>
        <SettingsCardTitle
          title="Shell Environment Variables"
          tip="Custom environment variables injected into shell command execution runtime."
          className="tools-card-title"
        />
        <div className="small text-body-secondary mb-3">
          Define per-tool variables available to every shell command.
          {!isShellEnabled && ' Shell tool is currently disabled, but variables will be saved for when it is enabled.'}
        </div>

        <Form className="mb-3">
          <div className="row g-2 align-items-end">
            <div className="col-md-4">
              <Form.Group>
                <Form.Label className="small fw-medium">
                  Variable Name <HelpTip text="Use [A-Za-z_][A-Za-z0-9_]* format." />
                </Form.Label>
                <Form.Control
                  size="sm"
                  value={createForm.name}
                  onChange={(event) => setCreateForm((prev) => ({ ...prev, name: event.target.value }))}
                  placeholder="EXAMPLE_API_KEY"
                />
              </Form.Group>
            </div>
            <div className="col-md-6">
              <Form.Group>
                <Form.Label className="small fw-medium">Value</Form.Label>
                <Form.Control
                  size="sm"
                  value={createForm.value}
                  onChange={(event) => setCreateForm((prev) => ({ ...prev, value: event.target.value }))}
                  placeholder="value"
                />
              </Form.Group>
            </div>
            <div className="col-md-2 d-grid">
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={handleCreate}
              >
                Add Variable
              </Button>
            </div>
          </div>
        </Form>

        {variables.length === 0 ? (
          <div className="small text-body-secondary">No custom shell environment variables configured.</div>
        ) : (
          <Table responsive size="sm" className="mb-0 align-middle">
            <thead>
              <tr>
                <th className="w-25">Name</th>
                <th>Value</th>
                <th className="text-nowrap">Actions</th>
              </tr>
            </thead>
            <tbody>
              {variables.map((variable) => {
                const isEditing = editForm?.originalName === variable.name;
                if (isEditing && editForm != null) {
                  return (
                    <tr key={variable.name}>
                      <td>
                        <Form.Control
                          size="sm"
                          value={editForm.name}
                          onChange={(event) => setEditForm({ ...editForm, name: event.target.value })}
                        />
                      </td>
                      <td>
                        <Form.Control
                          size="sm"
                          value={editForm.value}
                          onChange={(event) => setEditForm({ ...editForm, value: event.target.value })}
                        />
                      </td>
                      <td>
                        <div className="d-flex gap-2">
                          <Button type="button" size="sm" variant="primary" onClick={handleSaveEdit}>Save</Button>
                          <Button type="button" size="sm" variant="secondary" onClick={() => setEditForm(null)}>Cancel</Button>
                        </div>
                      </td>
                    </tr>
                  );
                }
                return (
                  <tr key={variable.name}>
                    <td><code>{variable.name}</code></td>
                    <td><span className="font-mono small">{variable.value.length > 0 ? variable.value : '(empty)'}</span></td>
                    <td>
                      <div className="d-flex gap-2">
                        <Button type="button" size="sm" variant="secondary" onClick={() => handleStartEdit(variable)}>Edit</Button>
                        <Button type="button" size="sm" variant="danger" onClick={() => handleDelete(variable.name)}>Delete</Button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </Table>
        )}
      </Card.Body>
    </Card>
  );
}
