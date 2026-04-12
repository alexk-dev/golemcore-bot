import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it } from 'vitest';

import type { PluginSettingsField } from '../../api/plugins';
import { PluginSettingsFieldRenderer } from './PluginSettingsPanelParts';

describe('PluginSettingsPanelParts', () => {
  afterEach(() => {
    delete (globalThis as { window?: unknown }).window;
  });

  it('renders masked string fields with hidden value by default', () => {
    const field: PluginSettingsField = {
      key: 'webhookSecretToken',
      type: 'text',
      label: 'Webhook Secret Token',
      masked: true,
    };

    const html = renderToStaticMarkup(
      <PluginSettingsFieldRenderer
        field={field}
        value="safe-string-token"
        isSecretRevealed={false}
        onChange={() => {}}
        onToggleSecret={() => {}}
      />,
    );

    expect(html).toContain('Webhook Secret Token');
    expect(html).toContain('type="password"');
    expect(html).toContain('>Show<');
  });

  it('renders copyable readonly webhook url with browser origin', () => {
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: {
        location: {
          origin: 'https://bot.example.com',
        },
      },
    });

    const field: PluginSettingsField = {
      key: 'webhookUrl',
      type: 'url',
      label: 'Webhook URL',
      readOnly: true,
      copyable: true,
    };

    const html = renderToStaticMarkup(
      <PluginSettingsFieldRenderer
        field={field}
        value="/api/telegram/webhook"
        isSecretRevealed={false}
        onChange={() => {}}
        onToggleSecret={() => {}}
      />,
    );

    expect(html).toContain('value="https://bot.example.com/api/telegram/webhook"');
    expect(html).toContain('>Copy<');
  });
});
