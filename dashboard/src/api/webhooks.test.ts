import { describe, expect, it } from 'vitest';
import {
  createDefaultWebhookConfig,
  createEmptyWebhookMapping,
  JSON_SCHEMA_DRAFT_2020_12_DOCS_URL,
  type HookMapping,
  validateWebhookConfig,
  type WebhookConfig,
} from './webhooks';

function createConfig(mapping: Partial<HookMapping>): WebhookConfig {
  return {
    ...createDefaultWebhookConfig(),
    mappings: [{
      ...createEmptyWebhookMapping(),
      name: 'sync-hook',
      action: 'agent',
      authMode: 'bearer',
      syncResponse: true,
      ...mapping,
    }],
  };
}

describe('webhook response JSON Schema validation', () => {
  it('points users to the official Draft 2020-12 documentation', () => {
    expect(JSON_SCHEMA_DRAFT_2020_12_DOCS_URL).toBe('https://json-schema.org/draft/2020-12');
  });

  it('accepts a valid synchronous response schema', () => {
    const config = createConfig({
      responseJsonSchema: JSON.stringify({
        $schema: 'https://json-schema.org/draft/2020-12/schema',
        type: 'object',
        required: ['version'],
        additionalProperties: false,
        properties: {
          version: { type: 'string', const: '1.0' },
          response: true,
        },
        allOf: [
          { type: 'object' },
        ],
        $defs: {
          localizedText: { type: 'string' },
        },
      }),
    });

    expect(validateWebhookConfig(config)).toEqual({ valid: true, issues: [] });
  });

  it('rejects response schema when synchronous response is disabled', () => {
    const result = validateWebhookConfig(createConfig({
      syncResponse: false,
      responseJsonSchema: '{"type":"object"}',
    }));

    expect(result.valid).toBe(false);
    expect(result.issues).toContain('Mapping #1: synchronous response is required when response JSON Schema is configured.');
  });

  it('rejects malformed response schema JSON', () => {
    const result = validateWebhookConfig(createConfig({
      responseJsonSchema: '{"type":',
    }));

    expect(result.valid).toBe(false);
    expect(result.issues).toContain('Mapping #1: response JSON Schema must be valid JSON.');
  });

  it('rejects non-object and invalid schema type values', () => {
    const nonObject = validateWebhookConfig(createConfig({
      responseJsonSchema: 'true',
    }));
    const invalidType = validateWebhookConfig(createConfig({
      responseJsonSchema: '{"type":"invalid"}',
    }));

    expect(nonObject.issues).toContain('Mapping #1: response JSON Schema must be a JSON object.');
    expect(invalidType.valid).toBe(false);
    expect(invalidType.issues.some((issue) => issue.includes('Draft 2020-12'))).toBe(true);
    expect(invalidType.issues.some((issue) => issue.includes('/type'))).toBe(true);
  });

  it('rejects invalid nested schema shapes before saving', () => {
    const result = validateWebhookConfig(createConfig({
      responseJsonSchema: JSON.stringify({
        type: 'object',
        required: ['response', 42],
        properties: {
          response: { type: 'invalid' },
        },
        items: 'wrong',
        oneOf: { type: 'object' },
      }),
    }));

    expect(result.valid).toBe(false);
    expect(result.issues.some((issue) => issue.includes('/required'))).toBe(true);
    expect(result.issues.some((issue) => issue.includes('/properties/response/type'))).toBe(true);
    expect(result.issues.some((issue) => issue.includes('/items'))).toBe(true);
    expect(result.issues.some((issue) => issue.includes('/oneOf'))).toBe(true);
  });

  it('rejects invalid Draft 2020-12 keyword values before saving', () => {
    const result = validateWebhookConfig(createConfig({
      responseJsonSchema: JSON.stringify({
        type: 'string',
        minLength: 'bad',
      }),
    }));

    expect(result.valid).toBe(false);
    expect(result.issues.some((issue) => issue.includes('minLength'))).toBe(true);
    expect(result.issues.some((issue) => issue.includes('Draft 2020-12'))).toBe(true);
  });
});
