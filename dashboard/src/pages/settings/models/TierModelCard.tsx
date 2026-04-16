import { type ReactElement, useEffect, useMemo, useState } from 'react';

import type { AvailableModel } from '../../../api/models';
import { toEditorModelIdForProvider } from '../../../lib/providerModelIds';
import { buildModelsForProvider } from '../modelFallbacksEditorSupport';
import { Badge, Button, Card, Form } from '../../../components/ui/tailwind-components';
import { MAX_FALLBACKS } from './FallbackRow';

export interface TierModelCardProps {
  label: string;
  color: string;
  providers: Record<string, AvailableModel[]>;
  providerNames: string[];
  modelProvider: string;
  modelValue: string;
  reasoningValue: string;
  temperatureValue: number | null;
  fallbackCount: number;
  allowEmptyModel: boolean;
  onModelChange: (value: string, providerName: string) => void;
  onReasoningChange: (value: string) => void;
  onTemperatureChange: (value: number | null) => void;
  onConfigureFallbacks: () => void;
}

export function TierModelCard({
  label,
  color,
  providers,
  providerNames,
  modelProvider,
  modelValue,
  reasoningValue,
  temperatureValue,
  fallbackCount,
  allowEmptyModel,
  onModelChange,
  onReasoningChange,
  onTemperatureChange,
  onConfigureFallbacks,
}: TierModelCardProps): ReactElement {
  const configuredProvider = useMemo(() => {
    if (modelValue.length === 0) {
      return '';
    }
    for (const [providerName, models] of Object.entries(providers)) {
      if (models.some((model) => toEditorModelIdForProvider(model.id, providerName) === modelValue)) {
        return providerName;
      }
    }
    if (modelProvider.length > 0) {
      return modelProvider;
    }
    const delimiterIndex = modelValue.indexOf('/');
    return delimiterIndex > 0 ? modelValue.slice(0, delimiterIndex) : '';
  }, [modelProvider, modelValue, providers]);

  const providerOptionValues = useMemo(() => {
    if (configuredProvider.length === 0 || providerNames.includes(configuredProvider)) {
      return providerNames;
    }
    return [configuredProvider, ...providerNames];
  }, [configuredProvider, providerNames]);

  const selectedProvider = useMemo(() => {
    if (modelValue.length === 0) {
      return allowEmptyModel ? '' : (providerNames[0] ?? '');
    }
    return configuredProvider.length > 0 ? configuredProvider : (providerNames[0] ?? '');
  }, [allowEmptyModel, configuredProvider, modelValue, providerNames]);

  const [provider, setProvider] = useState(selectedProvider);

  useEffect(() => {
    // Keep the local provider select synchronized with the parent-derived selection.
    setProvider(selectedProvider);
  }, [selectedProvider]);

  const modelsForProvider = useMemo(
    () => buildModelsForProvider(providers, provider, modelValue),
    [modelValue, provider, providers],
  );
  const selectedModel = modelsForProvider.find((model) => model.editorId === modelValue);
  const reasoningLevels = selectedModel?.reasoningLevels ?? [];
  const supportsTemperature = selectedModel?.supportsTemperature !== false;
  const hasProviders = providerOptionValues.length > 0;
  const providerUnavailable = configuredProvider.length > 0 && !providerNames.includes(configuredProvider);

  useEffect(() => {
    // Keep optional special tiers unconfigured until the user explicitly selects a model.
    if (!allowEmptyModel && modelValue.length === 0 && modelsForProvider.length > 0) {
      onModelChange(modelsForProvider[modelsForProvider.length - 1].id, provider);
    }
  }, [allowEmptyModel, modelValue, modelsForProvider, onModelChange, provider]);

  return (
    <Card className="tier-card h-100">
      <Card.Body className="p-3">
        <Badge bg={color} className="mb-2">{label}</Badge>

        <Form.Group className="mb-2">
          <Form.Label className="small fw-medium mb-1">Provider</Form.Label>
          <Form.Select
            size="sm"
            value={provider}
            disabled={!hasProviders}
            onChange={(e) => {
              const nextProvider = e.target.value;
              setProvider(nextProvider);
              onModelChange('', nextProvider);
            }}
          >
            {allowEmptyModel && <option value="">Select provider</option>}
            {!hasProviders && <option value="">No providers</option>}
            {providerOptionValues.map((providerName) => (
              <option key={providerName || 'empty'} value={providerName}>
                {providerName}
                {providerUnavailable && providerName === configuredProvider ? ' (unavailable)' : ''}
              </option>
            ))}
          </Form.Select>
        </Form.Group>

        <Form.Group className="mb-2">
          <Form.Label className="small fw-medium mb-1">Model</Form.Label>
          <Form.Select
            size="sm"
            value={modelValue}
            disabled={!hasProviders && modelsForProvider.length === 0}
            onChange={(e) => onModelChange(e.target.value, provider)}
          >
            {allowEmptyModel && <option value="">Not configured</option>}
            {modelsForProvider.map((model) => (
              <option key={`${provider}-${model.id}`} value={model.editorId}>{model.displayLabel}</option>
            ))}
          </Form.Select>
        </Form.Group>

        {reasoningLevels.length > 0 && (
          <Form.Group className="mb-2">
            <Form.Label className="small fw-medium mb-1">Reasoning</Form.Label>
            <Form.Select size="sm" value={reasoningValue} onChange={(e) => onReasoningChange(e.target.value)}>
              <option value="">Default</option>
              {reasoningLevels.map((level) => (
                <option key={level} value={level}>{level}</option>
              ))}
            </Form.Select>
          </Form.Group>
        )}

        {supportsTemperature && (
          <Form.Group className="mb-2">
            <Form.Label className="small fw-medium mb-1">
              Temperature: {(temperatureValue ?? 0.7).toFixed(1)}
            </Form.Label>
            <Form.Range
              min={0}
              max={2}
              step={0.1}
              value={temperatureValue ?? 0.7}
              onChange={(e) => onTemperatureChange(parseFloat(e.target.value))}
            />
          </Form.Group>
        )}

        <Button
          type="button"
          variant="outline-secondary"
          size="sm"
          className="w-100"
          onClick={onConfigureFallbacks}
        >
          Configure fallbacks ({fallbackCount}/{MAX_FALLBACKS})
        </Button>
      </Card.Body>
    </Card>
  );
}
