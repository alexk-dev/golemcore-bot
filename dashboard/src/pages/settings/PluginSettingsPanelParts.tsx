import type { ReactElement } from 'react';
import {
  Alert,
  Button,
  Form,
  InputGroup,
  Modal,
  Table,
} from '../../components/ui/tailwind-components';

import type {
  PluginSettingsAction,
  PluginSettingsBlock,
  PluginSettingsField,
  PluginSettingsTableRow,
} from '../../api/plugins';
import { buttonVariant } from './pluginSettingsUi';

export type PluginFormState = Record<string, unknown>;

export interface PluginActionRequest {
  action: PluginSettingsAction;
  payload: Record<string, unknown>;
}

interface PluginActionConfirmModalProps {
  request: PluginActionRequest | null;
  isPending: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

interface PluginSettingsFieldRendererProps {
  field: PluginSettingsField;
  value: unknown;
  isSecretRevealed: boolean;
  onChange: (value: unknown) => void;
  onToggleSecret: () => void;
}

interface PluginSettingsSectionBlockProps {
  block: PluginSettingsBlock;
  onAction: (action: PluginSettingsAction, payload: Record<string, unknown>) => void;
}

interface SecretFieldRenderArgs {
  field: PluginSettingsField;
  value: unknown;
  isSecretRevealed: boolean;
  isReadOnly: boolean;
  description: ReactElement | null;
  onChange: (value: unknown) => void;
  onToggleSecret: () => void;
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

function renderFieldDescription(description: string | null | undefined): ReactElement | null {
  if (description == null || description.length === 0) {
    return null;
  }
  return <Form.Text className="text-muted d-block">{description}</Form.Text>;
}

function resolveFieldDisplayValue(field: PluginSettingsField, value: unknown): string {
  const stringValue = fieldValueAsString(value);
  if (field.type === 'url'
    && stringValue.startsWith('/')
    && typeof window !== 'undefined'
    && window.location?.origin != null) {
    return `${window.location.origin}${stringValue}`;
  }
  return stringValue;
}

export function PluginActionConfirmModal({
  request,
  isPending,
  onCancel,
  onConfirm,
}: PluginActionConfirmModalProps): ReactElement {
  const confirmationMessage = request?.action.confirmationMessage ?? '';

  return (
    <Modal show={request != null} onHide={onCancel} centered>
      <Modal.Header closeButton>
        <Modal.Title>Confirm action</Modal.Title>
      </Modal.Header>
      <Modal.Body>{confirmationMessage}</Modal.Body>
      <Modal.Footer>
        <Button type="button" variant="secondary" onClick={onCancel} disabled={isPending}>
          Cancel
        </Button>
        <Button type="button" variant={buttonVariant(request?.action.variant)} onClick={onConfirm} disabled={isPending}>
          {isPending ? 'Running...' : request?.action.label ?? 'Run action'}
        </Button>
      </Modal.Footer>
    </Modal>
  );
}

export function PluginSettingsFieldRenderer({
  field,
  value,
  isSecretRevealed,
  onChange,
  onToggleSecret,
}: PluginSettingsFieldRendererProps): ReactElement {
  const description = renderFieldDescription(field.description);
  const isReadOnly = field.readOnly ?? false;

  if (field.type === 'boolean') {
    return renderBooleanField(field, value, isReadOnly, description, onChange);
  }

  if (field.type === 'select') {
    return renderSelectField(field, value, isReadOnly, description, onChange);
  }

  if (field.type === 'secret') {
    return renderSecretField({
      field,
      value,
      isSecretRevealed,
      isReadOnly,
      description,
      onChange,
      onToggleSecret,
    });
  }

  if (field.masked === true) {
    return renderSecretField({
      field,
      value,
      isSecretRevealed,
      isReadOnly,
      description,
      onChange,
      onToggleSecret,
    });
  }

  return renderTextField(field, value, isReadOnly, description, onChange);
}

export function PluginSettingsSectionBlock({
  block,
  onAction,
}: PluginSettingsSectionBlockProps): ReactElement | null {
  if (block.type === 'notice') {
    return (
      <Alert key={block.key} variant={buttonVariant(block.variant)} className="mb-3">
        {block.title != null && <div className="fw-medium mb-1">{block.title}</div>}
        {block.text}
      </Alert>
    );
  }

  if (block.type !== 'table') {
    return null;
  }

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
                        onClick={() => onAction(action, { rowId: row.id, cells: row.cells })}
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
}

function renderBooleanField(
  field: PluginSettingsField,
  value: unknown,
  isReadOnly: boolean,
  description: ReactElement | null,
  onChange: (value: unknown) => void,
): ReactElement {
  return (
    <Form.Group key={field.key} className="mb-3">
      <Form.Check
        type="switch"
        label={field.label}
        checked={Boolean(value)}
        disabled={isReadOnly}
        onChange={(event) => onChange(event.target.checked)}
      />
      {description}
    </Form.Group>
  );
}

function renderSelectField(
  field: PluginSettingsField,
  value: unknown,
  isReadOnly: boolean,
  description: ReactElement | null,
  onChange: (value: unknown) => void,
): ReactElement {
  return (
    <Form.Group key={field.key} className="mb-3">
      <Form.Label className="small fw-medium">{field.label}</Form.Label>
      <Form.Select
        size="sm"
        value={fieldValueAsString(value)}
        disabled={isReadOnly}
        onChange={(event) => onChange(event.target.value)}
      >
        {(field.options ?? []).map((option) => (
          <option key={option.value} value={option.value}>{option.label}</option>
        ))}
      </Form.Select>
      {description}
    </Form.Group>
  );
}

function renderSecretField({
  field,
  value,
  isSecretRevealed,
  isReadOnly,
  description,
  onChange,
  onToggleSecret,
}: SecretFieldRenderArgs): ReactElement {
  return (
    <Form.Group key={field.key} className="mb-3">
      <Form.Label className="small fw-medium">{field.label}</Form.Label>
      <InputGroup size="sm">
        <Form.Control
          type={isSecretRevealed ? 'text' : 'password'}
          value={fieldValueAsString(value)}
          readOnly={isReadOnly}
          placeholder={field.placeholder ?? undefined}
          autoComplete="new-password"
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          onChange={(event) => onChange(event.target.value)}
        />
        <Button type="button" variant="secondary" onClick={onToggleSecret}>
          {isSecretRevealed ? 'Hide' : 'Show'}
        </Button>
      </InputGroup>
      {description}
    </Form.Group>
  );
}

function renderTextField(
  field: PluginSettingsField,
  value: unknown,
  isReadOnly: boolean,
  description: ReactElement | null,
  onChange: (value: unknown) => void,
): ReactElement {
  const displayValue = resolveFieldDisplayValue(field, value);
  const inputType = field.type === 'number'
    ? 'number'
    : field.type === 'url'
      ? 'url'
      : 'text';

  const control = (
    <Form.Control
      size="sm"
      type={inputType}
      inputMode={field.type === 'number' ? 'decimal' : undefined}
      value={displayValue}
      readOnly={isReadOnly}
      placeholder={field.placeholder ?? undefined}
      min={field.min ?? undefined}
      max={field.max ?? undefined}
      step={field.step ?? undefined}
      autoCapitalize={field.type === 'url' ? 'off' : undefined}
      autoCorrect={field.type === 'url' ? 'off' : undefined}
      spellCheck={field.type === 'url' ? false : undefined}
      onChange={(event) => onChange(event.target.value)}
    />
  );

  return (
    <Form.Group key={field.key} className="mb-3">
      <Form.Label className="small fw-medium">{field.label}</Form.Label>
      {field.copyable === true ? (
        <InputGroup size="sm">
          {control}
          <Button
            type="button"
            variant="secondary"
            onClick={() => {
              if (typeof navigator !== 'undefined' && navigator.clipboard != null) {
                void navigator.clipboard.writeText(displayValue);
              }
            }}
          >
            Copy
          </Button>
        </InputGroup>
      ) : control}
      {description}
    </Form.Group>
  );
}
