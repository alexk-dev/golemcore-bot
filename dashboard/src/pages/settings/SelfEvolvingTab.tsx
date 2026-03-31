import { type Dispatch, type ReactElement, type SetStateAction, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Form, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';

import type { SelfEvolvingConfig } from '../../api/settings';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';
import { getExplicitModelTierOptions } from '../../lib/modelTiers';

const CAPTURE_LEVEL_OPTIONS = [
  { value: 'full', label: 'Full payload' },
  { value: 'meta_only', label: 'Metadata only' },
  { value: 'off', label: 'Disabled' },
];

const PROMOTION_MODE_OPTIONS = [
  { value: 'approval_gate', label: 'Approval gate' },
  { value: 'auto_accept', label: 'Auto accept' },
];

const JUDGE_TIER_OPTIONS = [
  { value: 'standard', label: 'Standard' },
  { value: 'premium', label: 'Premium' },
  ...getExplicitModelTierOptions(),
];

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableFloat(value: string): number | null {
  const parsed = Number.parseFloat(value);
  return Number.isNaN(parsed) ? null : parsed;
}

function updateJudgeField(
  setForm: Dispatch<SetStateAction<SelfEvolvingConfig>>,
  field: keyof SelfEvolvingConfig['judge'],
  value: boolean | number | string | null,
): void {
  setForm((current) => ({
    ...current,
    judge: { ...current.judge, [field]: value },
  }));
}

function updateCaptureField(
  setForm: Dispatch<SetStateAction<SelfEvolvingConfig>>,
  field: keyof SelfEvolvingConfig['capture'],
  value: string | null,
): void {
  setForm((current) => ({
    ...current,
    capture: { ...current.capture, [field]: value },
  }));
}

function updatePromotionField(
  setForm: Dispatch<SetStateAction<SelfEvolvingConfig>>,
  field: keyof SelfEvolvingConfig['promotion'],
  value: boolean | string | null,
): void {
  setForm((current) => ({
    ...current,
    promotion: { ...current.promotion, [field]: value },
  }));
}

function updateBenchmarkField(
  setForm: Dispatch<SetStateAction<SelfEvolvingConfig>>,
  field: keyof SelfEvolvingConfig['benchmark'],
  value: boolean | null,
): void {
  setForm((current) => ({
    ...current,
    benchmark: { ...current.benchmark, [field]: value },
  }));
}

function updateHiveField(
  setForm: Dispatch<SetStateAction<SelfEvolvingConfig>>,
  field: keyof SelfEvolvingConfig['hive'],
  value: boolean | null,
): void {
  setForm((current) => ({
    ...current,
    hive: { ...current.hive, [field]: value },
  }));
}

interface SelfEvolvingTabProps {
  config: SelfEvolvingConfig;
  onSave: (config: SelfEvolvingConfig) => Promise<void>;
  isSaving?: boolean;
}

interface SelfEvolvingFormSectionProps {
  form: SelfEvolvingConfig;
  setForm: Dispatch<SetStateAction<SelfEvolvingConfig>>;
}

function SelfEvolvingToggles({ form, setForm }: SelfEvolvingFormSectionProps): ReactElement {
  return (
    <>
      <Form.Check
        type="switch"
        label={<>Enable SelfEvolving <HelpTip text="Turns on run judging, candidate generation, and promotion workflow capture." /></>}
        checked={form.enabled ?? false}
        onChange={(event) => setForm((current) => ({ ...current, enabled: event.target.checked }))}
        className="mb-3"
      />

      <Form.Check
        type="switch"
        label={<>Trace payload override <HelpTip text="When enabled, SelfEvolving forces payload capture depth for replay and evidence anchoring while still honoring redaction." /></>}
        checked={form.tracePayloadOverride ?? true}
        onChange={(event) => setForm((current) => ({ ...current, tracePayloadOverride: event.target.checked }))}
        className="mb-4"
      />
    </>
  );
}

function JudgeTierSettings({ form, setForm }: SelfEvolvingFormSectionProps): ReactElement {
  return (
    <>
      <Row className="g-3 mb-4">
        <Col md={4}>
          <Form.Group controlId="self-evolving-primary-tier">
            <Form.Label className="small fw-medium">
              Primary judge tier <HelpTip text="Default tier used for outcome and process judging." />
            </Form.Label>
            <Form.Select
              size="sm"
              value={form.judge.primaryTier ?? 'standard'}
              onChange={(event) => updateJudgeField(setForm, 'primaryTier', event.target.value)}
            >
              {JUDGE_TIER_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </Form.Select>
          </Form.Group>
        </Col>
        <Col md={4}>
          <Form.Group controlId="self-evolving-tiebreaker-tier">
            <Form.Label className="small fw-medium">
              Tiebreaker tier <HelpTip text="Escalation tier used when judges disagree or confidence drops below the configured threshold." />
            </Form.Label>
            <Form.Select
              size="sm"
              value={form.judge.tiebreakerTier ?? 'premium'}
              onChange={(event) => updateJudgeField(setForm, 'tiebreakerTier', event.target.value)}
            >
              {JUDGE_TIER_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </Form.Select>
          </Form.Group>
        </Col>
        <Col md={4}>
          <Form.Group controlId="self-evolving-evolution-tier">
            <Form.Label className="small fw-medium">
              Evolution tier <HelpTip text="Tier used when deriving or tuning candidates after a run completes." />
            </Form.Label>
            <Form.Select
              size="sm"
              value={form.judge.evolutionTier ?? 'premium'}
              onChange={(event) => updateJudgeField(setForm, 'evolutionTier', event.target.value)}
            >
              {JUDGE_TIER_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </Form.Select>
          </Form.Group>
        </Col>
      </Row>

      <Row className="g-3 mb-4">
        <Col md={6}>
          <Form.Group controlId="self-evolving-uncertainty-threshold">
            <Form.Label className="small fw-medium">
              Uncertainty threshold <HelpTip text="Above this threshold, SelfEvolving escalates to the tiebreaker judge." />
            </Form.Label>
            <Form.Control
              size="sm"
              type="number"
              min={0}
              max={1}
              step="0.01"
              value={form.judge.uncertaintyThreshold ?? 0.22}
              onChange={(event) => updateJudgeField(setForm, 'uncertaintyThreshold', toNullableFloat(event.target.value))}
            />
          </Form.Group>
        </Col>
        <Col md={6} className="d-flex align-items-end">
          <Form.Check
            type="switch"
            label={<>Require evidence anchors <HelpTip text="Judge verdicts must cite run evidence before they can influence promotion decisions." /></>}
            checked={form.judge.requireEvidenceAnchors ?? true}
            onChange={(event) => updateJudgeField(setForm, 'requireEvidenceAnchors', event.target.checked)}
          />
        </Col>
      </Row>
    </>
  );
}

function CaptureSettings({ form, setForm }: SelfEvolvingFormSectionProps): ReactElement {
  return (
    <Row className="g-3 mb-4">
      <Col md={4}>
        <Form.Group controlId="self-evolving-capture-llm">
          <Form.Label className="small fw-medium">LLM capture</Form.Label>
          <Form.Select
            size="sm"
            value={form.capture.llm ?? 'full'}
            onChange={(event) => updateCaptureField(setForm, 'llm', event.target.value)}
          >
            {CAPTURE_LEVEL_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </Form.Select>
        </Form.Group>
      </Col>
      <Col md={4}>
        <Form.Group controlId="self-evolving-capture-tool">
          <Form.Label className="small fw-medium">Tool capture</Form.Label>
          <Form.Select
            size="sm"
            value={form.capture.tool ?? 'full'}
            onChange={(event) => updateCaptureField(setForm, 'tool', event.target.value)}
          >
            {CAPTURE_LEVEL_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </Form.Select>
        </Form.Group>
      </Col>
      <Col md={4}>
        <Form.Group controlId="self-evolving-capture-infra">
          <Form.Label className="small fw-medium">Infra capture</Form.Label>
          <Form.Select
            size="sm"
            value={form.capture.infra ?? 'meta_only'}
            onChange={(event) => updateCaptureField(setForm, 'infra', event.target.value)}
          >
            {CAPTURE_LEVEL_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </Form.Select>
        </Form.Group>
      </Col>
    </Row>
  );
}

function PromotionSettings({ form, setForm }: SelfEvolvingFormSectionProps): ReactElement {
  return (
    <>
      <Row className="g-3 mb-3">
        <Col md={6}>
          <Form.Group controlId="self-evolving-promotion-mode">
            <Form.Label className="small fw-medium">
              Promotion mode <HelpTip text="Choose whether promotions stop for approval by default or move forward automatically." />
            </Form.Label>
            <Form.Select
              size="sm"
              value={form.promotion.mode ?? 'approval_gate'}
              onChange={(event) => updatePromotionField(setForm, 'mode', event.target.value)}
            >
              {PROMOTION_MODE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </Form.Select>
          </Form.Group>
        </Col>
        <Col md={6} className="d-flex align-items-end">
          <Form.Check
            type="switch"
            label={<>Prefer Hive approvals <HelpTip text="Route promotion approvals through Hive when the golem is connected." /></>}
            checked={form.promotion.hiveApprovalPreferred ?? true}
            onChange={(event) => updatePromotionField(setForm, 'hiveApprovalPreferred', event.target.checked)}
          />
        </Col>
      </Row>

      <div className="d-flex flex-column gap-2 mb-4">
        <Form.Check
          type="switch"
          label="Allow auto accept"
          checked={form.promotion.allowAutoAccept ?? true}
          onChange={(event) => updatePromotionField(setForm, 'allowAutoAccept', event.target.checked)}
        />
        <Form.Check
          type="switch"
          label="Require shadow before promote"
          checked={form.promotion.shadowRequired ?? true}
          onChange={(event) => updatePromotionField(setForm, 'shadowRequired', event.target.checked)}
        />
        <Form.Check
          type="switch"
          label="Require canary before active"
          checked={form.promotion.canaryRequired ?? true}
          onChange={(event) => updatePromotionField(setForm, 'canaryRequired', event.target.checked)}
        />
      </div>
    </>
  );
}

function BenchmarkAndHiveSettings({ form, setForm }: SelfEvolvingFormSectionProps): ReactElement {
  return (
    <div className="d-flex flex-column gap-2 mb-4">
      <Form.Check
        type="switch"
        label="Enable benchmark lab"
        checked={form.benchmark.enabled ?? true}
        onChange={(event) => updateBenchmarkField(setForm, 'enabled', event.target.checked)}
      />
      <Form.Check
        type="switch"
        label="Harvest production runs"
        checked={form.benchmark.harvestProductionRuns ?? true}
        onChange={(event) => updateBenchmarkField(setForm, 'harvestProductionRuns', event.target.checked)}
      />
      <Form.Check
        type="switch"
        label="Publish Hive inspection projection"
        checked={form.hive.publishInspectionProjection ?? true}
        onChange={(event) => updateHiveField(setForm, 'publishInspectionProjection', event.target.checked)}
      />
    </div>
  );
}

export default function SelfEvolvingTab({
  config,
  onSave,
  isSaving = false,
}: SelfEvolvingTabProps): ReactElement {
  const [form, setForm] = useState<SelfEvolvingConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    // Keep the tab aligned with refreshed runtime-config payloads after saves or background refreshes.
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
    await onSave(form);
    toast.success('SelfEvolving settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle
          title="SelfEvolving"
          tip="Configure run judging, promotion gating, benchmark harvesting, and Hive inspection for the SelfEvolving control plane."
        />

        <SelfEvolvingToggles form={form} setForm={setForm} />
        <JudgeTierSettings form={form} setForm={setForm} />
        <CaptureSettings form={form} setForm={setForm} />
        <PromotionSettings form={form} setForm={setForm} />
        <BenchmarkAndHiveSettings form={form} setForm={setForm} />

        <SettingsSaveBar>
          <Button
            type="button"
            variant="primary"
            size="sm"
            onClick={() => { void handleSave(); }}
            disabled={!isDirty || isSaving}
          >
            {isSaving ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
