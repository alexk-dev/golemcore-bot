import { type ReactElement, useState } from 'react';
import { Badge, Button, Card, Form, InputGroup, Table } from '../../components/ui/tailwind-components';
import toast from 'react-hot-toast';
import { extractErrorMessage } from '../../utils/extractErrorMessage';
import ConfirmModal from '../../components/common/ConfirmModal';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import {
  useAddMcpCatalogEntry, useUpdateMcpCatalogEntry, useRemoveMcpCatalogEntry,
} from '../../hooks/useSettings';
import type { McpCatalogEntry } from '../../api/settingsTypes';
import { McpCatalogEntryEditor } from './McpCatalogEntryEditor';

const ENTRY_NAME_PATTERN = /^[a-z0-9][a-z0-9_-]*$/;

function buildDefaultEntry(name: string): McpCatalogEntry {
  return {
    name,
    description: null,
    command: '',
    env: {},
    startupTimeoutSeconds: 30,
    idleTimeoutMinutes: 5,
    enabled: true,
  };
}

function getNameHint(isNameInvalid: boolean, entryExists: boolean): string {
  if (isNameInvalid) {
    return 'Name format: [a-z0-9][a-z0-9_-]*';
  }
  if (entryExists) {
    return 'Server with this name already exists.';
  }
  return 'Lowercase identifier for the MCP server (e.g. github, filesystem, brave-search).';
}

interface CatalogRowProps {
  entry: McpCatalogEntry;
  isEditing: boolean;
  isSaving: boolean;
  isDeleting: boolean;
  onEdit: () => void;
  onClose: () => void;
  onDelete: () => void;
}

function CatalogRow({ entry, isEditing, isSaving, isDeleting, onEdit, onClose, onDelete }: CatalogRowProps): ReactElement {
  return (
    <tr>
      <td data-label="Name" className="fw-medium">{entry.name}</td>
      <td data-label="Command" className="small text-body-secondary text-truncate" style={{ maxWidth: '300px' }}>
        {entry.command}
      </td>
      <td data-label="Status">
        {entry.enabled !== false
          ? <Badge bg="success">Enabled</Badge>
          : <Badge bg="secondary">Disabled</Badge>}
      </td>
      <td data-label="Actions" className="text-end text-nowrap">
        <div className="d-flex flex-wrap gap-1 justify-content-end">
          <Button type="button" size="sm" variant="secondary" disabled={isSaving} onClick={isEditing ? onClose : onEdit}>
            {isEditing ? 'Close' : 'Edit'}
          </Button>
          <Button type="button" size="sm" variant="danger" disabled={isDeleting} onClick={onDelete}>
            Delete
          </Button>
        </div>
      </td>
    </tr>
  );
}

export interface McpCatalogSectionProps {
  catalog: McpCatalogEntry[];
}

export function McpCatalogSection({ catalog }: McpCatalogSectionProps): ReactElement {
  const addEntry = useAddMcpCatalogEntry();
  const updateEntry = useUpdateMcpCatalogEntry();
  const removeEntry = useRemoveMcpCatalogEntry();

  const [editingName, setEditingName] = useState<string | null>(null);
  const [editForm, setEditForm] = useState<McpCatalogEntry | null>(null);
  const [isNewEntry, setIsNewEntry] = useState(false);
  const [newEntryName, setNewEntryName] = useState('');
  const [deleteEntryName, setDeleteEntryName] = useState<string | null>(null);

  const isSaving = addEntry.isPending || updateEntry.isPending;
  const normalizedNewName = newEntryName.trim().toLowerCase();
  const isNameInvalid = normalizedNewName.length > 0 && !ENTRY_NAME_PATTERN.test(normalizedNewName);
  const entryExists = normalizedNewName.length > 0 && catalog.some((e) => e.name === normalizedNewName);
  const canStartAdd = normalizedNewName.length > 0
    && !isNameInvalid && !entryExists && !isSaving && editingName == null;

  const handleStartAdd = (): void => {
    if (!canStartAdd) {
      return;
    }
    setEditingName(normalizedNewName);
    setEditForm(buildDefaultEntry(normalizedNewName));
    setIsNewEntry(true);
    setNewEntryName('');
  };

  const handleStartEdit = (name: string): void => {
    const entry = catalog.find((e) => e.name === name);
    if (entry == null) {
      return;
    }
    setEditingName(name);
    setEditForm({ ...entry });
    setIsNewEntry(false);
  };

  const handleCancelEdit = (): void => {
    setEditingName(null);
    setEditForm(null);
    setIsNewEntry(false);
  };

  const handleSave = async (): Promise<void> => {
    if (editingName == null || editForm == null) {
      return;
    }
    try {
      if (isNewEntry) {
        await addEntry.mutateAsync(editForm);
        toast.success(`MCP server "${editingName}" added`);
      } else {
        await updateEntry.mutateAsync({ name: editingName, entry: editForm });
        toast.success(`MCP server "${editingName}" updated`);
      }
      handleCancelEdit();
    } catch (err) {
      toast.error(`Failed to save: ${extractErrorMessage(err)}`);
    }
  };

  const handleConfirmDelete = async (): Promise<void> => {
    if (deleteEntryName == null) {
      return;
    }
    try {
      await removeEntry.mutateAsync(deleteEntryName);
      toast.success(`MCP server "${deleteEntryName}" removed`);
      if (editingName === deleteEntryName) {
        handleCancelEdit();
      }
    } catch (err) {
      toast.error(`Failed to remove: ${extractErrorMessage(err)}`);
    } finally {
      setDeleteEntryName(null);
    }
  };

  const nameHintClass = isNameInvalid || entryExists ? 'text-danger' : 'text-body-secondary';

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title="MCP Server Catalog" />
        <div className="small text-body-secondary mb-3">
          Register MCP servers that skills can use. Each entry defines a server command, environment variables, and timeout settings.
        </div>

        <InputGroup className="mb-3" size="sm">
          <Form.Control
            placeholder="Server name (e.g. github, slack, postgres)"
            value={newEntryName}
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
            aria-invalid={isNameInvalid || entryExists}
            onChange={(e) => setNewEntryName(e.target.value.toLowerCase())}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                if (canStartAdd) {
                  handleStartAdd();
                }
              }
            }}
          />
          <Button type="button" variant="primary" onClick={handleStartAdd} disabled={!canStartAdd}>
            Add Server
          </Button>
        </InputGroup>
        <div className={`small mb-3 ${nameHintClass}`}>
          {getNameHint(isNameInvalid, entryExists)}
        </div>

        {catalog.length > 0 ? (
          <Table size="sm" hover responsive className="mb-3 dashboard-table responsive-table">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Command</th>
                <th scope="col">Status</th>
                <th scope="col" className="text-end">Actions</th>
              </tr>
            </thead>
            <tbody>
              {catalog.map((entry) => (
                <CatalogRow
                  key={entry.name}
                  entry={entry}
                  isEditing={editingName === entry.name && !isNewEntry}
                  isSaving={isSaving}
                  isDeleting={removeEntry.isPending}
                  onEdit={() => handleStartEdit(entry.name)}
                  onClose={handleCancelEdit}
                  onDelete={() => setDeleteEntryName(entry.name)}
                />
              ))}
            </tbody>
          </Table>
        ) : (
          <p className="text-body-secondary small mb-3">No MCP servers registered. Add one above to get started.</p>
        )}

        {editingName != null && editForm != null && (
          <McpCatalogEntryEditor
            name={editingName}
            form={editForm}
            isNew={isNewEntry}
            isSaving={isSaving}
            onFormChange={setEditForm}
            onSave={() => { void handleSave(); }}
            onCancel={handleCancelEdit}
          />
        )}
      </Card.Body>

      <ConfirmModal
        show={deleteEntryName !== null}
        title="Delete MCP Server"
        message={`Remove "${deleteEntryName ?? ''}" from the catalog? This cannot be undone.`}
        confirmLabel="Delete"
        confirmVariant="danger"
        isProcessing={removeEntry.isPending}
        onConfirm={() => { void handleConfirmDelete(); }}
        onCancel={() => setDeleteEntryName(null)}
      />
    </Card>
  );
}
