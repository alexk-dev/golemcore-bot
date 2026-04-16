import { type ReactElement, useEffect, useMemo, useState } from 'react';

import type { AvailableModel } from '../../../api/models';
import { modelReferenceFromSpec, modelReferenceToSpec } from '../../../api/settings';
import type { TierFallback } from '../../../api/settingsTypes';
import { toEditorModelIdForProvider } from '../../../lib/providerModelIds';
import { Button, Card, Col, Form, Row } from '../../../components/ui/tailwind-components';
import {
  buildModelProviderOptions,
  buildModelsForProvider,
  resolveTemperatureAfterModelChange,
  toNullableRouterString,
} from '../modelFallbacksEditorSupport';

export const DEFAULT_TEMPERATURE = 0.7;
export const DEFAULT_FALLBACK_WEIGHT = 1;
export const MAX_FALLBACKS = 5;

export interface ModelSelectFieldsProps {
  modelValue: string;
  modelProvider: string;
  reasoningValue: string;
  temperatureValue: number | null;
  providers: Record<string, AvailableModel[]>;
  providerNames: string[];
  onModelChange: (value: string, providerName: string) => void;
  onReasoningChange: (value: string) => void;
  onTemperatureChange: (value: number | null) => void;
}

export function ModelSelectFields({
  modelValue,
  modelProvider,
  reasoningValue,
  temperatureValue,
  providers,
  providerNames,
  onModelChange,
  onReasoningChange,
  onTemperatureChange,
}: ModelSelectFieldsProps): ReactElement {
  const selectedProvider = modelProvider.length > 0 ? modelProvider : (providerNames[0] ?? '');
  const providerOptions = useMemo(
    () => buildModelProviderOptions(providerNames, selectedProvider),
    [providerNames, selectedProvider],
  );
  const [provider, setProvider] = useState(selectedProvider);

  useEffect(() => {
    // Keep the local provider select synchronized with saved router state.
    setProvider(selectedProvider);
  }, [selectedProvider]);

  const modelsForProvider = useMemo(
    () => buildModelsForProvider(providers, provider, modelValue),
    [modelValue, provider, providers],
  );
  const selectedModel = modelsForProvider.find((model) => model.editorId === modelValue);
  const reasoningLevels = selectedModel?.reasoningLevels ?? [];
  const supportsTemperature = selectedModel?.supportsTemperature !== false;

  return (
    <Row className="g-2 align-items-end">
      <Col md={3}>
        <Form.Group>
          <Form.Label className="small fw-medium mb-1">Provider</Form.Label>
          <Form.Select
            size="sm"
            value={provider}
            onChange={(event) => {
              const nextProvider = event.target.value;
              setProvider(nextProvider);
              onModelChange('', nextProvider);
            }}
          >
            {providerOptions.length === 0 && <option value="">No providers</option>}
            {providerOptions.map((providerName) => (
              <option key={providerName} value={providerName}>
                {providerName}
                {!providerNames.includes(providerName) ? ' (unavailable)' : ''}
              </option>
            ))}
          </Form.Select>
        </Form.Group>
      </Col>
      <Col md={4}>
        <Form.Group>
          <Form.Label className="small fw-medium mb-1">Model</Form.Label>
          <Form.Select size="sm" value={modelValue} onChange={(event) => onModelChange(event.target.value, provider)}>
            <option value="">Not configured</option>
            {modelsForProvider.map((model) => <option key={`${provider}-${model.id}`} value={model.editorId}>{model.displayLabel}</option>)}
          </Form.Select>
        </Form.Group>
      </Col>
      <Col md={2}>
        <Form.Group>
          <Form.Label className="small fw-medium mb-1">Reasoning</Form.Label>
          <Form.Select
            size="sm"
            value={reasoningValue}
            disabled={reasoningLevels.length === 0}
            onChange={(event) => onReasoningChange(event.target.value)}
          >
            <option value="">Default</option>
            {reasoningLevels.map((level) => <option key={level} value={level}>{level}</option>)}
          </Form.Select>
        </Form.Group>
      </Col>
      {supportsTemperature && (
        <Col md={3}>
          <Form.Group>
            <Form.Label className="small fw-medium mb-1">
              Temperature: {(temperatureValue ?? DEFAULT_TEMPERATURE).toFixed(1)}
            </Form.Label>
            <Form.Range
              min={0}
              max={2}
              step={0.1}
              value={temperatureValue ?? DEFAULT_TEMPERATURE}
              onChange={(event) => onTemperatureChange(parseFloat(event.target.value))}
            />
          </Form.Group>
        </Col>
      )}
    </Row>
  );
}

export interface FallbackRowProps {
  fallback: TierFallback;
  fallbackIndex: number;
  providers: Record<string, AvailableModel[]>;
  providerNames: string[];
  isWeightedMode: boolean;
  onChange: (fallback: TierFallback) => void;
  onRemove: () => void;
}

function parseFallbackWeight(value: string): number {
  const parsed = Number.parseFloat(value);
  if (!Number.isFinite(parsed) || parsed < 0) {
    return DEFAULT_FALLBACK_WEIGHT;
  }
  return parsed;
}

export function FallbackRow({
  fallback,
  fallbackIndex,
  providers,
  providerNames,
  isWeightedMode,
  onChange,
  onRemove,
}: FallbackRowProps): ReactElement {
  return (
    <Card className="settings-card bg-body-tertiary">
      <Card.Body className="p-3">
        <div className="d-flex justify-content-between align-items-center mb-2">
          <span className="small fw-medium">Fallback {fallbackIndex + 1}</span>
          <Button type="button" variant="secondary" size="sm" onClick={onRemove}>Remove</Button>
        </div>
        <ModelSelectFields
          modelProvider={fallback.model?.provider ?? ''}
          modelValue={toEditorModelIdForProvider(modelReferenceToSpec(fallback.model), fallback.model?.provider)}
          reasoningValue={fallback.reasoning ?? ''}
          temperatureValue={fallback.temperature}
          providers={providers}
          providerNames={providerNames}
          onModelChange={(value, providerName) => onChange({
            ...fallback,
            model: modelReferenceFromSpec(value, providerName),
            reasoning: null,
            temperature: resolveTemperatureAfterModelChange(fallback.temperature, value, providerName, providers),
            weight: isWeightedMode ? fallback.weight ?? DEFAULT_FALLBACK_WEIGHT : fallback.weight,
          })}
          onReasoningChange={(value) => onChange({ ...fallback, reasoning: toNullableRouterString(value) })}
          onTemperatureChange={(value) => onChange({ ...fallback, temperature: value })}
        />
        {isWeightedMode && (
          <Form.Group className="mt-2">
            <Form.Label className="small fw-medium mb-1">Weight</Form.Label>
            <Form.Control
              size="sm"
              type="number"
              min={0}
              step={0.1}
              value={fallback.weight ?? DEFAULT_FALLBACK_WEIGHT}
              onChange={(event) => onChange({ ...fallback, weight: parseFallbackWeight(event.target.value) })}
            />
            <Form.Text className="text-body-secondary">
              Higher weight gives this fallback more share in weighted selection.
            </Form.Text>
          </Form.Group>
        )}
      </Card.Body>
    </Card>
  );
}
