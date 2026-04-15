import type { ReactElement } from 'react';
import { JSON_SCHEMA_DRAFT_2020_12_DOCS_URL, type HookMappingDraft } from '../../api/webhooks';
import { getExplicitModelTierOptions } from '../../lib/modelTiers';
import { Select, Textarea } from '../ui/field';
import { HookMappingFieldHeading } from './HookMappingFieldHeading';
import { controlClassName, fieldHelpClassName, surfaceClassName, toNullableString } from './HookMappingFormUtils';

/**
 * Renders synchronous response schema controls for webhook mappings.
 *
 * The repair tier is intentionally described as repair-only: the main webhook
 * answer still uses the mapping model tier, while this tier is used only if the
 * raw answer fails JSON Schema validation.
 */
export interface WebhookResponseSchemaSectionProps {
  mapping: HookMappingDraft;
  onChange: (nextMapping: HookMappingDraft) => void;
}

export function WebhookResponseSchemaSection({
  mapping,
  onChange,
}: WebhookResponseSchemaSectionProps): ReactElement {
  const hasResponseJsonSchema = mapping.responseJsonSchema != null && mapping.responseJsonSchema.trim().length > 0;

  return (
    <div className="mt-5 grid gap-4 lg:grid-cols-[minmax(12rem,16rem)_minmax(0,1fr)]">
      <div className={surfaceClassName}>
        <HookMappingFieldHeading
          label="Schema Repair Tier"
          help="Optional tier used only for JSON Schema repair attempts. The main webhook answer uses the hook model tier."
        />
        <Select
          value={mapping.responseValidationModelTier ?? ''}
          disabled={!mapping.syncResponse || !hasResponseJsonSchema}
          onChange={(event) => onChange({
            ...mapping,
            responseValidationModelTier: toNullableString(event.target.value),
          })}
          className={controlClassName}
        >
          <option value="">Default</option>
          {getExplicitModelTierOptions().map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </Select>
        <p className={fieldHelpClassName}>
          Leave empty to repair with the hook model tier, or balanced when no hook model is set.
        </p>
      </div>
      <div className={surfaceClassName}>
        <HookMappingFieldHeading
          label="Response JSON Schema"
          help="Optional Draft 2020-12 schema for the synchronous HTTP response body. The final agent output is validated and repaired up to three times."
        />
        <Textarea
          rows={7}
          value={mapping.responseJsonSchema ?? ''}
          disabled={!mapping.syncResponse}
          onChange={(event) => onChange(nextMappingWithResponseJsonSchema(mapping, event.target.value))}
          placeholder={'{\n  "type": "object",\n  "required": ["version", "response"],\n  "properties": {\n    "version": { "const": "1.0" },\n    "response": { "type": "object" }\n  }\n}'}
          className="min-h-[12rem] rounded-2xl border-border/80 bg-background/80 font-mono text-sm shadow-none"
        />
        <p className={fieldHelpClassName}>
          Reference:{' '}
          <a href={JSON_SCHEMA_DRAFT_2020_12_DOCS_URL} target="_blank" rel="noreferrer" className="font-semibold text-primary underline-offset-4 hover:underline">JSON Schema Draft 2020-12</a>
          .
        </p>
      </div>
    </div>
  );
}

/**
 * Keeps responseValidationModelTier coupled to an actual schema so empty schema
 * editors cannot leave stale repair-tier settings in the saved mapping.
 */
function nextMappingWithResponseJsonSchema(mapping: HookMappingDraft, value: string): HookMappingDraft {
  const responseJsonSchema = toNullableString(value);
  if (responseJsonSchema != null) {
    return { ...mapping, responseJsonSchema };
  }
  return {
    ...mapping,
    responseJsonSchema: null,
    responseValidationModelTier: null,
  };
}
