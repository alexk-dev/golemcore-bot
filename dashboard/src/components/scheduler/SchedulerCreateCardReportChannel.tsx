import type { ReactElement } from 'react';
import { Form } from 'react-bootstrap';


export interface ReportChannelOption {
  type: string;
  label: string;
}

interface ReportChannelFieldProps {
  featureEnabled: boolean;
  reportChannelType: string;
  reportChatId: string;
  reportWebhookUrl: string;
  reportWebhookSecret: string;
  channelOptions: ReportChannelOption[];
  onChange: (reportChannelType: string) => void;
  onChatIdChange: (chatId: string) => void;
  onWebhookUrlChange: (url: string) => void;
  onWebhookSecretChange: (secret: string) => void;
}

function isWebhookChannel(channelType: string): boolean {
  return channelType === 'webhook';
}

function isChannelSelected(channelType: string): boolean {
  return channelType.length > 0 && !isWebhookChannel(channelType);
}

export function ReportChannelField({
  featureEnabled,
  reportChannelType,
  reportChatId,
  reportWebhookUrl,
  reportWebhookSecret,
  channelOptions,
  onChange,
  onChatIdChange,
  onWebhookUrlChange,
  onWebhookSecretChange,
}: ReportChannelFieldProps): ReactElement {
  return (
    <Form.Group className="mb-3">
      <Form.Label>Report channel</Form.Label>
      <Form.Select
        size="sm"
        value={reportChannelType}
        onChange={(event) => onChange(event.target.value)}
        disabled={!featureEnabled || channelOptions.length === 0}
      >
        <option value="">None (no report)</option>
        {channelOptions.map((channel) => (
          <option key={channel.type} value={channel.type}>{channel.label}</option>
        ))}
      </Form.Select>
      <Form.Text className="text-body-secondary">
        Send a summary to this channel after each scheduled run completes.
      </Form.Text>

      {isChannelSelected(reportChannelType) && (
        <Form.Control
          size="sm"
          className="mt-2"
          value={reportChatId}
          onChange={(event) => onChatIdChange(event.target.value)}
          disabled={!featureEnabled}
          placeholder="Chat ID (auto-resolved if empty)"
        />
      )}

      {isWebhookChannel(reportChannelType) && (
        <>
          <Form.Control
            size="sm"
            className="mt-2"
            value={reportWebhookUrl}
            onChange={(event) => onWebhookUrlChange(event.target.value)}
            disabled={!featureEnabled}
            placeholder="https://example.com/webhook"
          />
          <Form.Control
            size="sm"
            className="mt-2"
            type="password"
            value={reportWebhookSecret}
            onChange={(event) => onWebhookSecretChange(event.target.value)}
            disabled={!featureEnabled}
            placeholder="Bearer token (optional)"
          />
        </>
      )}
    </Form.Group>
  );
}
