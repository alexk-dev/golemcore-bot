import { type Dispatch, type ReactElement, type SetStateAction, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Form, Nav, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';

import type { SelfEvolvingConfig } from '../../api/settings';
import type { SelfEvolvingTacticSearchStatus } from '../../api/selfEvolving';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';
import { SelfEvolvingEmbeddingStatusCard } from './selfEvolving/SelfEvolvingEmbeddingStatusCard';
import { SelfEvolvingJudgeTierSettings } from './selfEvolving/SelfEvolvingJudgeTierSettings';
import { SelfEvolvingTacticSearchEmbeddingsSettings } from './selfEvolving/SelfEvolvingTacticSearchEmbeddingsSettings';

const CAPTURE_LEVEL_OPTIONS = [
  { value: 'full', label: 'Full payload' },
  { value: 'meta_only', label: 'Metadata only' },
  { value: 'off', label: 'Disabled' },
];

const PROMOTION_MODE_OPTIONS = [
  { value: 'approval_gate', label: 'Approval gate' },
  { value: 'auto_accept', label: 'Auto accept' },
];

const SELF_EVOLVING_TABS = [
  { key: 'general', label: 'General' },
  { key: 'judge', label: 'Judge' },
  { key: 'tactics', label: 'Tactics' },
  { key: 'promotion', label: 'Promotion' },
] as const;

type SelfEvolvingTabKey = (typeof SELF_EVOLVING_TABS)[number]['key'];

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

interface SelfEvolvingTabProps {
  config: SelfEvolvingConfig;
  tacticSearchStatus?: SelfEvolvingTacticSearchStatus | null;
  onInstallTacticEmbedding?: () => Promise<void>;
  isInstallingTacticEmbedding?: boolean;
  onSave: (config: SelfEvolvingConfig) => Promise<void>;
  isSaving?: boolean;
}

interface SelfEvolvingFormSectionProps {
  form: SelfEvolvingConfig;
  setForm: Dispatch<SetStateAction<SelfEvolvingConfig>>;
}

interface SelfEvolvingSectionProps {
  activeTab: SelfEvolvingTabKey;
  tabKey: SelfEvolvingTabKey;
  children: ReactElement | ReactElement[];
}

function SelfEvolvingSection({
  activeTab,
  tabKey,
  children,
}: SelfEvolvingSectionProps): ReactElement {
  return (
    <div
      role="tabpanel"
      aria-hidden={activeTab !== tabKey}
      className={activeTab === tabKey ? '' : 'd-none'}
    >
      {children}
    </div>
  );
}

function SelfEvolvingToggles({ form, setForm }: SelfEvolvingFormSectionProps): ReactElement {
  return (
    <>
      <Form.Check
        type="switch"
        label={<>Enable Self-Evolving <HelpTip text="Turns on run judging, candidate generation, and promotion workflow capture." /></>}
        checked={form.enabled ?? false}
        onChange={(event) => setForm((current) => ({ ...current, enabled: event.target.checked }))}
        className="mb-3"
      />

      <Form.Check
        type="switch"
        label={<>Trace payload override <HelpTip text="When enabled, Self-Evolving forces payload capture depth for replay and evidence anchoring while still honoring redaction." /></>}
        checked={form.tracePayloadOverride ?? true}
        onChange={(event) => setForm((current) => ({ ...current, tracePayloadOverride: event.target.checked }))}
        className="mb-4"
      />
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
            onChange={(event) => setForm((current) => ({
              ...current,
              capture: { ...current.capture, llm: event.target.value },
            }))}
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
            onChange={(event) => setForm((current) => ({
              ...current,
              capture: { ...current.capture, tool: event.target.value },
            }))}
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
            onChange={(event) => setForm((current) => ({
              ...current,
              capture: { ...current.capture, infra: event.target.value },
            }))}
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
              onChange={(event) => setForm((current) => ({
                ...current,
                promotion: { ...current.promotion, mode: event.target.value as 'approval_gate' | 'auto_accept' },
              }))}
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
            onChange={(event) => setForm((current) => ({
              ...current,
              promotion: { ...current.promotion, hiveApprovalPreferred: event.target.checked },
            }))}
          />
        </Col>
      </Row>

      <div className="d-flex flex-column gap-2 mb-4">
        <Form.Check
          type="switch"
          label="Allow auto accept"
          checked={form.promotion.allowAutoAccept ?? true}
          onChange={(event) => setForm((current) => ({
            ...current,
            promotion: { ...current.promotion, allowAutoAccept: event.target.checked },
          }))}
        />
        <Form.Check
          type="switch"
          label="Require shadow before promote"
          checked={form.promotion.shadowRequired ?? true}
          onChange={(event) => setForm((current) => ({
            ...current,
            promotion: { ...current.promotion, shadowRequired: event.target.checked },
          }))}
        />
        <Form.Check
          type="switch"
          label="Require canary before active"
          checked={form.promotion.canaryRequired ?? true}
          onChange={(event) => setForm((current) => ({
            ...current,
            promotion: { ...current.promotion, canaryRequired: event.target.checked },
          }))}
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
        onChange={(event) => setForm((current) => ({
          ...current,
          benchmark: { ...current.benchmark, enabled: event.target.checked },
        }))}
      />
      <Form.Check
        type="switch"
        label="Harvest production runs"
        checked={form.benchmark.harvestProductionRuns ?? true}
        onChange={(event) => setForm((current) => ({
          ...current,
          benchmark: { ...current.benchmark, harvestProductionRuns: event.target.checked },
        }))}
      />
      <Form.Check
        type="switch"
        label="Publish Hive inspection projection"
        checked={form.hive.publishInspectionProjection ?? true}
        onChange={(event) => setForm((current) => ({
          ...current,
          hive: { ...current.hive, publishInspectionProjection: event.target.checked },
        }))}
      />
    </div>
  );
}

export default function SelfEvolvingTab({
  config,
  tacticSearchStatus = null,
  onInstallTacticEmbedding,
  isInstallingTacticEmbedding = false,
  onSave,
  isSaving = false,
}: SelfEvolvingTabProps): ReactElement {
  const [form, setForm] = useState<SelfEvolvingConfig>({ ...config });
  const [activeTab, setActiveTab] = useState<SelfEvolvingTabKey>('general');
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    // Keep the tab aligned with refreshed runtime-config payloads after saves or background refreshes.
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
    await onSave(form);
    toast.success('Self-Evolving settings saved');
  };

  const handleInstallTacticEmbedding = async (): Promise<void> => {
    if (onInstallTacticEmbedding == null) {
      return;
    }
    await onInstallTacticEmbedding();
    toast.success('Self-Evolving embedding model installed');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle
          title="Self-Evolving"
          tip="Configure run judging, promotion gating, benchmark harvesting, and Hive inspection for the Self-Evolving control plane."
        />

        <Nav className="nav-tabs mb-4">
          {SELF_EVOLVING_TABS.map((tab) => (
            <button
              key={tab.key}
              type="button"
              className={`nav-link${activeTab === tab.key ? ' active' : ''}`}
              onClick={() => setActiveTab(tab.key)}
            >
              {tab.label}
            </button>
          ))}
        </Nav>

        <SelfEvolvingSection activeTab={activeTab} tabKey="general">
          <SelfEvolvingToggles form={form} setForm={setForm} />
        </SelfEvolvingSection>

        <SelfEvolvingSection activeTab={activeTab} tabKey="judge">
          <SelfEvolvingJudgeTierSettings form={form} setForm={setForm} />
          <CaptureSettings form={form} setForm={setForm} />
        </SelfEvolvingSection>

        <SelfEvolvingSection activeTab={activeTab} tabKey="tactics">
          <SelfEvolvingEmbeddingStatusCard
            status={tacticSearchStatus}
            isInstalling={isInstallingTacticEmbedding}
            onInstall={onInstallTacticEmbedding == null ? undefined : () => { void handleInstallTacticEmbedding(); }}
          />
          <SelfEvolvingTacticSearchEmbeddingsSettings form={form} setForm={setForm} />
        </SelfEvolvingSection>

        <SelfEvolvingSection activeTab={activeTab} tabKey="promotion">
          <PromotionSettings form={form} setForm={setForm} />
          <BenchmarkAndHiveSettings form={form} setForm={setForm} />
        </SelfEvolvingSection>

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
