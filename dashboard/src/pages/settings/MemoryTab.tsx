import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Form } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useMemoryPresets, useUpdateMemory } from '../../hooks/useSettings';
import type {
  MemoryConfig,
  MemoryDiagnosticsConfig,
  MemoryDiagnosticsVerbosity,
  MemoryDisclosureConfig,
  MemoryDisclosureMode,
  MemoryPreset,
  MemoryPromptStyle,
} from '../../api/settings';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';
import { MemoryDisclosureFields } from './MemoryDisclosureFields';
import { MemoryPresetPicker } from './MemoryPresetPicker';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableInt(value: string): number | null {
  const parsed = parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

function toNullableFloat(value: string): number | null {
  const parsed = parseFloat(value);
  return Number.isNaN(parsed) ? null : parsed;
}

function withNumberFallback(value: number | null | undefined, fallback: number): number {
  return value == null ? fallback : value;
}

function withBooleanFallback(value: boolean | null | undefined, fallback: boolean): boolean {
  return value == null ? fallback : value;
}

const DEFAULT_MEMORY_DISCLOSURE: MemoryDisclosureConfig = {
  mode: 'summary',
  promptStyle: 'balanced',
  toolExpansionEnabled: true,
  disclosureHintsEnabled: true,
  detailMinScore: 0.8,
};

const DEFAULT_MEMORY_DIAGNOSTICS: MemoryDiagnosticsConfig = {
  verbosity: 'basic',
};

const DEFAULT_MEMORY_DISCLOSURE_MODE: MemoryDisclosureMode = 'summary';
const DEFAULT_MEMORY_PROMPT_STYLE: MemoryPromptStyle = 'balanced';
const DEFAULT_MEMORY_DIAGNOSTICS_VERBOSITY: MemoryDiagnosticsVerbosity = 'basic';

interface MemoryTabProps {
  config: MemoryConfig;
}

interface MemoryFieldsEditorProps {
  form: MemoryConfig;
  onIntFieldChange: (key: keyof MemoryConfig, value: string) => void;
  onFloatFieldChange: (key: keyof MemoryConfig, value: string) => void;
  onBoolFieldChange: (key: keyof MemoryConfig, value: boolean) => void;
}

const DEFAULT_MEMORY_PRESET_ID = 'coding_balanced';

function normalizeMemoryConfig(config: MemoryConfig): MemoryConfig {
  const disclosure = config.disclosure;
  const diagnostics = config.diagnostics;
  return {
    ...config,
    disclosure: {
      mode: disclosure?.mode ?? DEFAULT_MEMORY_DISCLOSURE_MODE,
      promptStyle: disclosure?.promptStyle ?? DEFAULT_MEMORY_PROMPT_STYLE,
      toolExpansionEnabled: disclosure?.toolExpansionEnabled ?? DEFAULT_MEMORY_DISCLOSURE.toolExpansionEnabled,
      disclosureHintsEnabled: disclosure?.disclosureHintsEnabled ?? DEFAULT_MEMORY_DISCLOSURE.disclosureHintsEnabled,
      detailMinScore: disclosure?.detailMinScore ?? DEFAULT_MEMORY_DISCLOSURE.detailMinScore,
    },
    diagnostics: {
      verbosity: diagnostics?.verbosity ?? DEFAULT_MEMORY_DIAGNOSTICS_VERBOSITY,
    },
  };
}

function MemoryFieldsEditor({
  form,
  onIntFieldChange,
  onFloatFieldChange,
  onBoolFieldChange,
}: MemoryFieldsEditorProps): ReactElement {
  return (
    <>
      <Form.Group className="mb-3">
        <Form.Label className="small fw-medium">
          Soft Prompt Budget <HelpTip text="Target token budget for memory pack." />
        </Form.Label>
        <Form.Control
          size="sm"
          type="number"
          min={200}
          max={10000}
          value={withNumberFallback(form.softPromptBudgetTokens, 1800)}
          onChange={(e) => onIntFieldChange('softPromptBudgetTokens', e.target.value)}
        />
      </Form.Group>
      <Form.Group className="mb-3">
        <Form.Label className="small fw-medium">
          Max Prompt Budget <HelpTip text="Hard cap token budget for memory pack." />
        </Form.Label>
        <Form.Control
          size="sm"
          type="number"
          min={200}
          max={12000}
          value={withNumberFallback(form.maxPromptBudgetTokens, 3500)}
          onChange={(e) => onIntFieldChange('maxPromptBudgetTokens', e.target.value)}
        />
      </Form.Group>
      <Form.Group className="mb-3">
        <Form.Label className="small fw-medium">
          Working Top-K <HelpTip text="Max selected working-memory items." />
        </Form.Label>
        <Form.Control
          size="sm"
          type="number"
          min={1}
          max={30}
          value={withNumberFallback(form.workingTopK, 6)}
          onChange={(e) => onIntFieldChange('workingTopK', e.target.value)}
        />
      </Form.Group>
      <Form.Group className="mb-3">
        <Form.Label className="small fw-medium">
          Episodic Top-K <HelpTip text="Max selected episodic-memory items." />
        </Form.Label>
        <Form.Control
          size="sm"
          type="number"
          min={1}
          max={30}
          value={withNumberFallback(form.episodicTopK, 8)}
          onChange={(e) => onIntFieldChange('episodicTopK', e.target.value)}
        />
      </Form.Group>
      <Form.Group className="mb-3">
        <Form.Label className="small fw-medium">
          Semantic Top-K <HelpTip text="Max selected semantic-memory items." />
        </Form.Label>
        <Form.Control
          size="sm"
          type="number"
          min={1}
          max={30}
          value={withNumberFallback(form.semanticTopK, 6)}
          onChange={(e) => onIntFieldChange('semanticTopK', e.target.value)}
        />
      </Form.Group>
      <Form.Group className="mb-3">
        <Form.Label className="small fw-medium">
          Procedural Top-K <HelpTip text="Max selected procedural-memory items." />
        </Form.Label>
        <Form.Control
          size="sm"
          type="number"
          min={1}
          max={30}
          value={withNumberFallback(form.proceduralTopK, 4)}
          onChange={(e) => onIntFieldChange('proceduralTopK', e.target.value)}
        />
      </Form.Group>
      <Form.Check
        type="switch"
        label={<>Promotion Enabled <HelpTip text="Promote high-confidence episodic items to semantic/procedural stores." /></>}
        checked={withBooleanFallback(form.promotionEnabled, true)}
        onChange={(e) => onBoolFieldChange('promotionEnabled', e.target.checked)}
        className="mb-3"
      />
      <Form.Group className="mb-3">
        <Form.Label className="small fw-medium">
          Promotion Min Confidence <HelpTip text="Minimum confidence (0..1) required for promotion." />
        </Form.Label>
        <Form.Control
          size="sm"
          type="number"
          min={0}
          max={1}
          step={0.01}
          value={withNumberFallback(form.promotionMinConfidence, 0.75)}
          onChange={(e) => onFloatFieldChange('promotionMinConfidence', e.target.value)}
        />
      </Form.Group>
      <Form.Check
        type="switch"
        label={<>Decay Enabled <HelpTip text="Drop stale memory items older than decay window." /></>}
        checked={withBooleanFallback(form.decayEnabled, true)}
        onChange={(e) => onBoolFieldChange('decayEnabled', e.target.checked)}
        className="mb-3"
      />
      <Form.Group className="mb-3">
        <Form.Label className="small fw-medium">
          Decay Days <HelpTip text="Retention window for stale memory pruning." />
        </Form.Label>
        <Form.Control
          size="sm"
          type="number"
          min={1}
          max={3650}
          value={withNumberFallback(form.decayDays, 30)}
          onChange={(e) => onIntFieldChange('decayDays', e.target.value)}
        />
      </Form.Group>
      <Form.Group className="mb-3">
        <Form.Label className="small fw-medium">
          Retrieval Lookback Days <HelpTip text="How many recent episodic day-files to scan on each retrieval (independent from decay)." />
        </Form.Label>
        <Form.Control
          size="sm"
          type="number"
          min={1}
          max={90}
          value={withNumberFallback(form.retrievalLookbackDays, 21)}
          onChange={(e) => onIntFieldChange('retrievalLookbackDays', e.target.value)}
        />
      </Form.Group>
      <Form.Check
        type="switch"
        label={<>Code-aware Extraction <HelpTip text="Extract code-specific facts (errors, tests, files) into memory items." /></>}
        checked={withBooleanFallback(form.codeAwareExtractionEnabled, true)}
        onChange={(e) => onBoolFieldChange('codeAwareExtractionEnabled', e.target.checked)}
        className="mb-3"
      />
    </>
  );
}

export default function MemoryTab({ config }: MemoryTabProps): ReactElement {
  const updateMemory = useUpdateMemory();
  const { data: presets = [], isLoading: presetsLoading } = useMemoryPresets();
  const normalizedConfig = useMemo(() => normalizeMemoryConfig(config), [config]);
  const [form, setForm] = useState<MemoryConfig>(normalizedConfig);
  const [selectedPresetId, setSelectedPresetId] = useState<string>(DEFAULT_MEMORY_PRESET_ID);
  const isDirty = useMemo(() => hasDiff(form, normalizedConfig), [form, normalizedConfig]);
  const selectedPreset = useMemo<MemoryPreset | null>(() => {
    if (presets.length === 0) {
      return null;
    }
    const explicit = presets.find((preset) => preset.id === selectedPresetId);
    return explicit ?? presets[0];
  }, [presets, selectedPresetId]);

  // Keep the editable form in sync when server config changes (reload/navigation).
  useEffect(() => {
    setForm(normalizedConfig);
  }, [normalizedConfig]);

  const handleSave = async (): Promise<void> => {
    await updateMemory.mutateAsync(form);
    toast.success('Memory settings saved');
  };

  const applySelectedPreset = (): void => {
    if (selectedPreset == null) {
      toast.error('Memory presets are unavailable');
      return;
    }
    setForm(normalizeMemoryConfig(selectedPreset.memory));
    toast.success(`Preset "${selectedPreset.label}" applied. Click Save to persist.`);
  };

  const setIntField = (key: keyof MemoryConfig, value: string): void => {
    setForm({ ...form, [key]: toNullableInt(value) });
  };

  const setFloatField = (key: keyof MemoryConfig, value: string): void => {
    setForm({ ...form, [key]: toNullableFloat(value) });
  };

  const setBoolField = (key: keyof MemoryConfig, value: boolean): void => {
    setForm({ ...form, [key]: value });
  };

  const setDisclosureField = (
    key: keyof MemoryDisclosureConfig,
    value: MemoryDisclosureMode | MemoryPromptStyle | boolean | number | null,
  ): void => {
    setForm({
      ...form,
      disclosure: {
        ...(form.disclosure ?? DEFAULT_MEMORY_DISCLOSURE),
        [key]: value,
      },
    });
  };

  const setDiagnosticsVerbosity = (value: MemoryDiagnosticsVerbosity): void => {
    setForm({
      ...form,
      diagnostics: {
        ...(form.diagnostics ?? DEFAULT_MEMORY_DIAGNOSTICS),
        verbosity: value,
      },
    });
  };

  const disclosure = form.disclosure ?? DEFAULT_MEMORY_DISCLOSURE;
  const diagnostics = form.diagnostics ?? DEFAULT_MEMORY_DIAGNOSTICS;

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title="Memory" />
        <Form.Check
          type="switch"
          label={<>Enable Memory <HelpTip text="Persist user/assistant exchanges into long-term notes and include memory context in prompts." /></>}
          checked={withBooleanFallback(form.enabled, true)}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
          className="mb-3"
        />
        <MemoryPresetPicker
          presets={presets}
          presetsLoading={presetsLoading}
          selectedPreset={selectedPreset}
          onSelectPreset={setSelectedPresetId}
          onApplyPreset={applySelectedPreset}
        />
        <MemoryFieldsEditor
          form={form}
          onIntFieldChange={setIntField}
          onFloatFieldChange={setFloatField}
          onBoolFieldChange={setBoolField}
        />
        <MemoryDisclosureFields
          disclosureMode={disclosure.mode ?? DEFAULT_MEMORY_DISCLOSURE_MODE}
          promptStyle={disclosure.promptStyle ?? DEFAULT_MEMORY_PROMPT_STYLE}
          toolExpansionEnabled={withBooleanFallback(disclosure.toolExpansionEnabled, true)}
          disclosureHintsEnabled={withBooleanFallback(disclosure.disclosureHintsEnabled, true)}
          detailMinScore={withNumberFallback(disclosure.detailMinScore, 0.8)}
          diagnosticsVerbosity={diagnostics.verbosity ?? DEFAULT_MEMORY_DIAGNOSTICS_VERBOSITY}
          onDisclosureModeChange={(value) => setDisclosureField('mode', value)}
          onPromptStyleChange={(value) => setDisclosureField('promptStyle', value)}
          onToolExpansionChange={(value) => setDisclosureField('toolExpansionEnabled', value)}
          onDisclosureHintsChange={(value) => setDisclosureField('disclosureHintsEnabled', value)}
          onDetailMinScoreChange={(value) => setDisclosureField('detailMinScore', toNullableFloat(value))}
          onDiagnosticsVerbosityChange={setDiagnosticsVerbosity}
        />
        <SettingsSaveBar>
          <Button
            type="button"
            variant="primary"
            size="sm"
            onClick={() => { void handleSave(); }}
            disabled={!isDirty || updateMemory.isPending}
          >
            {updateMemory.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
