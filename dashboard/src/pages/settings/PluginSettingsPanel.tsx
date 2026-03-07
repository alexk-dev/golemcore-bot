import { type ReactElement, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Form,
  InputGroup,
  Spinner,
  Table,
} from 'react-bootstrap';
import toast from 'react-hot-toast';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';
import {
  type PluginSettingsAction,
  type PluginSettingsBlock,
  type PluginSettingsField,
  type PluginSettingsTableRow,
} from '../../api/plugins';
import { useExecutePluginSettingsAction, usePluginSettingsSection, useSavePluginSettingsSection } from '../../hooks/usePlugins';
import { extractErrorMessage } from '../../utils/extractErrorMessage';

interface PluginSettingsPanelProps {
  routeKey: string;
}

type PluginFormState = Record<string, unknown>;

function hasDiff(current: PluginFormState, initial: PluginFormState): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function buttonVariant(variant: string | null | undefined): string {
  return variant != null && variant.trim().length > 0 ? variant : 'secondary';
}

function fieldValueAsString(value: unknown): string {
  if (value == null) {
    return '';
  }
  return String(value);
}

function renderCellValue(value: unknown): string {
  if (value == null) {
    return '';
  }
  if (typeof value === 'boolean') {
    return value ? 'Yes' : 'No';
  }
  return String(value);
}

export default function PluginSettingsPanel({ routeKey }: PluginSettingsPanelProps): ReactElement {
  const { data: section, isLoading } = usePluginSettingsSection(routeKey);
  const saveSection = useSavePluginSettingsSection(routeKey);
  const executeAction = useExecutePluginSettingsAction(routeKey);
  const [form, setForm] = useState<PluginFormState>({});
  const [revealedSecrets, setRevealedSecrets] = useState<Record<string, boolean>>({});

  useEffect(() => {
    setForm(section?.values ?? {});
    setRevealedSecrets({});
  }, [section]);

  const isDirty = useMemo(() => hasDiff(form, section?.values ?? {}), [form, section]);
  const hasFields = (section?.fields?.length ?? 0) > 0;

  const handleSave = async (): Promise<void> => {
    try {
      await saveSection.mutateAsync(form);
      toast.success('Plugin settings saved');
    } catch (error: unknown) {
      toast.error(`Failed to save plugin settings: ${extractErrorMessage(error)}`);
    }
  };

  const handleAction = async (action: PluginSettingsAction, payload: Record<string, unknown> = {}): Promise<void> => {
    if (action.confirmationMessage != null && action.confirmationMessage.length > 0) {
      const confirmed = window.confirm(action.confirmationMessage);
      if (!confirmed) {
        return;
      }
    }
    try {
      const result = await executeAction.mutateAsync({ actionId: action.actionId, payload });
      if (result.status === 'ok') {
        toast.success(result.message ?? 'Action completed');
      } else {
        toast.error(result.message ?? 'Action failed');
      }
    } catch (error: unknown) {
      toast.error(`Action failed: ${extractErrorMessage(error)}`);
    }
  };

  const renderField = (field: PluginSettingsField): ReactElement => {
    const value = form[field.key];
    const description = field.description != null && field.description.length > 0
      ? <Form.Text className="text-muted d-block">{field.description}</Form.Text>
      : null;

    if (field.type === 'boolean') {
      return (
        <Form.Group key={field.key} className="mb-3">
          <Form.Check
            type="switch"
            label={field.label}
            checked={Boolean(value)}
            disabled={field.readOnly === true}
            onChange={(event) => setForm((prev) => ({ ...prev, [field.key]: event.target.checked }))}
          />
          {description}
        </Form.Group>
      );
    }

    if (field.type === 'select') {
      return (
        <Form.Group key={field.key} className="mb-3">
          <Form.Label className="small fw-medium">{field.label}</Form.Label>
          <Form.Select
            size="sm"
            value={fieldValueAsString(value)}
            disabled={field.readOnly === true}
            onChange={(event) => setForm((prev) => ({ ...prev, [field.key]: event.target.value }))}
          >
            {(field.options ?? []).map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </Form.Select>
          {description}
        </Form.Group>
      );
    }

    if (field.type === 'secret') {
      const revealed = revealedSecrets[field.key] === true;
      return (
        <Form.Group key={field.key} className="mb-3">
          <Form.Label className="small fw-medium">{field.label}</Form.Label>
          <InputGroup size="sm">
            <Form.Control
              type={revealed ? 'text' : 'password'}
              value={fieldValueAsString(value)}
              readOnly={field.readOnly === true}
              placeholder={field.placeholder ?? undefined}
              autoComplete="new-password"
              autoCapitalize="off"
              autoCorrect="off"
              spellCheck={false}
              onChange={(event) => setForm((prev) => ({ ...prev, [field.key]: event.target.value }))}
            />
            <Button
              type="button"
              variant="secondary"
              onClick={() => setRevealedSecrets((prev) => ({ ...prev, [field.key]: !revealed }))}
            >
              {revealed ? 'Hide' : 'Show'}
            </Button>
          </InputGroup>
          {description}
        </Form.Group>
      );
    }

    const inputType = field.type === 'number'
      ? 'number'
      : field.type === 'url'
        ? 'url'
        : 'text';

    return (
      <Form.Group key={field.key} className="mb-3">
        <Form.Label className="small fw-medium">{field.label}</Form.Label>
        <Form.Control
          size="sm"
          type={inputType}
          inputMode={field.type === 'number' ? 'decimal' : undefined}
          value={fieldValueAsString(value)}
          readOnly={field.readOnly === true}
          placeholder={field.placeholder ?? undefined}
          min={field.min ?? undefined}
          max={field.max ?? undefined}
          step={field.step ?? undefined}
          autoCapitalize={field.type === 'url' ? 'off' : undefined}
          autoCorrect={field.type === 'url' ? 'off' : undefined}
          spellCheck={field.type === 'url' ? false : undefined}
          onChange={(event) => {
            const nextValue = field.type === 'number'
              ? event.target.value
              : event.target.value;
            setForm((prev) => ({ ...prev, [field.key]: nextValue }));
          }}
        />
        {description}
      </Form.Group>
    );
  };

  const renderTable = (block: PluginSettingsBlock): ReactElement => {
    const columns = block.columns ?? [];
    const rows = block.rows ?? [];
    const hasActions = rows.some((row) => (row.actions?.length ?? 0) > 0);

    return (
      <div key={block.key} className="mb-3">
        {block.title != null && <h3 className="h6 mb-1">{block.title}</h3>}
        {block.description != null && <p className="text-body-secondary small mb-2">{block.description}</p>}
        <Table size="sm" hover responsive className="mb-0 dashboard-table responsive-table">
          <thead>
            <tr>
              {columns.map((column) => (
                <th key={column.key} scope="col">{column.label}</th>
              ))}
              {hasActions && <th scope="col" className="text-end">Actions</th>}
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={columns.length + (hasActions ? 1 : 0)} className="text-body-secondary small">
                  No data
                </td>
              </tr>
            )}
            {rows.map((row: PluginSettingsTableRow) => (
              <tr key={row.id}>
                {columns.map((column) => (
                  <td key={column.key} data-label={column.label}>
                    {renderCellValue(row.cells?.[column.key])}
                  </td>
                ))}
                {hasActions && (
                  <td className="text-end">
                    <div className="d-flex justify-content-end flex-wrap gap-1">
                      {(row.actions ?? []).map((action) => (
                        <Button
                          key={`${row.id}-${action.actionId}`}
                          type="button"
                          size="sm"
                          variant={buttonVariant(action.variant)}
                          onClick={() => { void handleAction(action, { rowId: row.id, cells: row.cells }); }}
                        >
                          {action.label}
                        </Button>
                      ))}
                    </div>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </Table>
      </div>
    );
  };

  if (isLoading || section == null) {
    return (
      <Card className="settings-card">
        <Card.Body className="d-flex justify-content-center py-4">
          <Spinner animation="border" size="sm" />
        </Card.Body>
      </Card>
    );
  }

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title={section.title} />
        {section.description != null && (
          <Form.Text className="text-muted d-block mb-3">{section.description}</Form.Text>
        )}

        {(section.actions ?? []).length > 0 && (
          <div className="d-flex flex-wrap gap-2 mb-3">
            {section.actions.map((action) => (
              <Button
                key={action.actionId}
                type="button"
                size="sm"
                variant={buttonVariant(action.variant)}
                onClick={() => { void handleAction(action); }}
              >
                {action.label}
              </Button>
            ))}
          </div>
        )}

        <Form>
          {(section.fields ?? []).map(renderField)}
        </Form>

        {(section.blocks ?? []).map((block) => {
          if (block.type === 'notice') {
            return (
              <Alert key={block.key} variant={buttonVariant(block.variant)} className="mb-3">
                {block.title != null && <div className="fw-medium mb-1">{block.title}</div>}
                {block.text}
              </Alert>
            );
          }
          if (block.type === 'table') {
            return renderTable(block);
          }
          return null;
        })}

        {hasFields && (
          <SettingsSaveBar className="mt-3">
            <Button
              type="button"
              variant="primary"
              size="sm"
              onClick={() => { void handleSave(); }}
              disabled={!isDirty || saveSection.isPending}
            >
              {saveSection.isPending ? 'Saving...' : 'Save'}
            </Button>
            <SaveStateHint isDirty={isDirty} />
          </SettingsSaveBar>
        )}
      </Card.Body>
    </Card>
  );
}
