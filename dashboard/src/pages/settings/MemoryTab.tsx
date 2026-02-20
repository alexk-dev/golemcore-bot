import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Form } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateMemory } from '../../hooks/useSettings';
import type { MemoryConfig } from '../../api/settings';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

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

interface MemoryTabProps {
  config: MemoryConfig;
}

export default function MemoryTab({ config }: MemoryTabProps): ReactElement {
  const updateMemory = useUpdateMemory();
  const [form, setForm] = useState<MemoryConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  // Keep the editable form in sync when server config changes (reload/navigation).
  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
    await updateMemory.mutateAsync(form);
    toast.success('Memory settings saved');
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
        <Form.Group className="mb-3">
          <Form.Label className="small fw-medium">
            Recent Days <HelpTip text="How many previous daily memory files to include in context fallback." />
          </Form.Label>
          <Form.Control
            size="sm"
            type="number"
            min={1}
            max={90}
            value={withNumberFallback(form.recentDays, 7)}
            onChange={(e) => setIntField('recentDays', e.target.value)}
          />
        </Form.Group>
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
            onChange={(e) => setIntField('softPromptBudgetTokens', e.target.value)}
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
            onChange={(e) => setIntField('maxPromptBudgetTokens', e.target.value)}
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
            onChange={(e) => setIntField('workingTopK', e.target.value)}
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
            onChange={(e) => setIntField('episodicTopK', e.target.value)}
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
            onChange={(e) => setIntField('semanticTopK', e.target.value)}
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
            onChange={(e) => setIntField('proceduralTopK', e.target.value)}
          />
        </Form.Group>
        <Form.Check
          type="switch"
          label={<>Promotion Enabled <HelpTip text="Promote high-confidence episodic items to semantic/procedural stores." /></>}
          checked={withBooleanFallback(form.promotionEnabled, true)}
          onChange={(e) => setBoolField('promotionEnabled', e.target.checked)}
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
            onChange={(e) => setFloatField('promotionMinConfidence', e.target.value)}
          />
        </Form.Group>
        <Form.Check
          type="switch"
          label={<>Decay Enabled <HelpTip text="Drop stale memory items older than decay window." /></>}
          checked={withBooleanFallback(form.decayEnabled, true)}
          onChange={(e) => setBoolField('decayEnabled', e.target.checked)}
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
            onChange={(e) => setIntField('decayDays', e.target.value)}
          />
        </Form.Group>
        <Form.Check
          type="switch"
          label={<>Code-aware Extraction <HelpTip text="Extract code-specific facts (errors, tests, files) into memory items." /></>}
          checked={withBooleanFallback(form.codeAwareExtractionEnabled, true)}
          onChange={(e) => setBoolField('codeAwareExtractionEnabled', e.target.checked)}
          className="mb-3"
        />
        <Form.Check
          type="switch"
          label={<>Legacy Daily Notes <HelpTip text="Keep writing and injecting legacy daily notes alongside structured memory." /></>}
          checked={withBooleanFallback(form.legacyDailyNotesEnabled, true)}
          onChange={(e) => setBoolField('legacyDailyNotesEnabled', e.target.checked)}
          className="mb-3"
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
